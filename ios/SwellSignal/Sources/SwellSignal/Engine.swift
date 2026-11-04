import Foundation
import SwellCore

public enum EngineError: Error, LocalizedError, Equatable {
    case own, used, mismatch, refused, unreachable(String), blocked, noRoute, codeUnavailable
    public var errorDescription: String? {
        switch self {
        case .own: return "This is your own code."
        case .used: return "This code was already used or has expired."
        case .mismatch: return "The code does not match the person who answered. Do not continue."
        case .refused: return "The other phone refused the connection."
        case .unreachable(let s): return "Could not connect: \(s)"
        case .blocked: return "Unblock this contact to send messages."
        case .noRoute: return "This contact is offline and has no mailbox yet."
        case .codeUnavailable: return "Codes cannot be made or opened right now. Please try again later."
        }
    }
}

/// The Swell messaging engine for a mailbox-only peer (docs/IOS-PLAN.md section 2, PROTOCOL.md section 8).
/// It follows the Kotlin `Node` for everything an iPhone does: redeem an Android v2 invite, send TEXT
/// (direct while in the foreground, otherwise into the contact's mailbox), poll its own queues, read
/// STATE / SETTINGS. All state lives in one [KeyValueStore] so a ratchet step and its stored message commit together.
public actor Engine {
    private let kv: KeyValueStore
    private let crypto: LibSignalCrypto
    private let transport: MailboxTransport
    private let auth: OnionAuth
    private let account: Account
    private let now: @Sendable () -> Date
    private let powBits: Int
    private var openChat: String?
    private var onChange: (@Sendable () -> Void)?
    /// Set by `shutdown()`: every later operation does nothing or fails.
    private var stopped = false
    private var redeeming = false
    private var ops: [Int: Task<Void, Never>] = [:]
    private var opSeq = 0
    static let maxMessagesPerContact = 2000
    static let fileNotice = "open this chat in Swell on Android, or update Swell, to see it"

    public var mailboxOnion: String
    /// Delivery/read receipts and typing: off means neither sent nor processed (PROTOCOL.md 5a). Off by default.
    public var receiptsEnabled = false
    public var typingEnabled = false
    public func setReceipts(_ on: Bool) { receiptsEnabled = on }
    public func setTyping(_ on: Bool) { typingEnabled = on }
    /// "Note to Self": a chat that never leaves the phone.
    public static let noteId = "self"
    static let caps: Int32 = 1
    static let editWindowMs: Int64 = 24 * 3600 * 1000 + 5 * 60 * 1000
    private var pendingDelivered: [String: [[UInt8]]] = [:]
    private var prepareTask: Task<Void, Never>?
    // voice notes (FILE / FILE_PART)
    static let partSize = 48 * 1024, bigPartSize = 240 * 1024, maxFile = 5_000_000, maxVoiceMs: Int32 = 180_000
    static let maxOfflineFile = 1_500_000, maxPendingPerContact = 2 * 5_000_000 + 240 * 1024
    private var filesDir: URL?
    public func setFilesDirectory(_ u: URL) { filesDir = u; try? FileManager.default.createDirectory(at: u, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication]) }
    public func fileURL(_ fileId: String) -> URL? { guard let d = filesDir, !fileId.isEmpty, fileId.allSatisfy({ $0.isHexDigit }) else { return nil }; return d.appendingPathComponent(fileId + ".m4a") }
    private func removeFile(_ fileId: String) { if let u = fileURL(fileId) { try? FileManager.default.removeItem(at: u) } }
    private var inboundBuckets: [String: (minute: Int64, n: Int)] = [:]
    public var profileName: String
    /// True while the app is in the foreground: only then are direct connections to a contact's onion tried.
    public var foreground = true

    public static let onionPattern: (String) -> Bool = { s in
        s.utf8.count == 56 && s.utf8.allSatisfy { ($0 >= 97 && $0 <= 122) || ($0 >= 50 && $0 <= 55) }
    }

    public init(account: Account, kv: KeyValueStore, transport: MailboxTransport, auth: OnionAuth, mailboxOnion: String,
                profileName: String, powBits: Int = MailboxProtocol.powBits, now: @escaping @Sendable () -> Date = Date.init) throws {
        self.account = account; self.kv = kv; self.transport = transport; self.auth = auth
        self.mailboxOnion = mailboxOnion; self.profileName = profileName; self.powBits = powBits; self.now = now
        crypto = try LibSignalCrypto(account: account, kv: kv)
    }

    public func setOnChange(_ f: (@Sendable () -> Void)?) { onChange = f }
    public func setForeground(_ on: Bool) { foreground = on }
    public func setProfileName(_ n: String) { profileName = Engine.cleanName(n) }
    public func setOpenChat(_ id: String?) {
        openChat = id
        if let id, var c = loadContact(id), c.unread > 0 { c.unread = 0; kv.put("contact", id, c.encode()); changed() }
    }
    private func changed() { onChange?() }
    private func millis() -> Int64 { Int64(now().timeIntervalSince1970 * 1000) }

    public static func cleanName(_ s: String) -> String {
        let f = s.unicodeScalars.filter { u in
            switch u.properties.generalCategory { case .control, .format, .lineSeparator, .paragraphSeparator: return false; default: return true }
        }
        return String(String.UnicodeScalarView(f)).trimmingCharacters(in: .whitespacesAndNewlines).prefix(64).description
    }

    // MARK: lifetime

    /// Runs `body` as a task the engine can cancel and wait for (`shutdown`). Cancelling the caller cancels it.
    private func track<T: Sendable>(_ body: @escaping @Sendable () async throws -> T) async throws -> T {
        if stopped { throw CancellationError() }
        let id = opSeq; opSeq += 1
        let t = Task { try await body() }
        ops[id] = Task { _ = await t.result }
        defer { ops[id] = nil }
        return try await withTaskCancellationHandler { try await t.value } onCancel: { t.cancel() }
    }

    /// Stops the engine: cancels every send, poll and redeem in flight and waits until each has ended.
    /// Afterwards no operation starts, so nothing can write or ACK. Used before an account is deleted.
    public func shutdown() async {
        stopped = true; onChange = nil
        let running = Array(ops.values)
        running.forEach { $0.cancel() }
        for t in running { await t.value }
    }

    // MARK: storage

    private func loadContact(_ id: String) -> Contact? { kv.get("contact", id).flatMap { try? Contact.decode($0) } }
    private func save(_ c: Contact) { kv.put("contact", c.id, c.encode()) }

    public func contacts() -> [Contact] {
        kv.list("contact").compactMap { try? Contact.decode($0.value) }.sorted { $0.lastAt > $1.lastAt }
    }
    public func contact(_ id: String) -> Contact? { loadContact(id) }

    public func messages(_ contactId: String) -> [ChatMessage] {
        let t = millis()
        return kv.list("msg.\(contactId)").compactMap { try? ChatMessage.decode($0.value) }
            .filter { $0.expiresAt == 0 || $0.expiresAt > t }.sorted { ($0.sentAt, $0.id) < ($1.sentAt, $1.id) }
    }

    private func store(_ m: ChatMessage, into c: inout Contact) {
        let ns = "msg.\(m.contactId)"
        kv.put(ns, m.key, m.encode())
        let keys = kv.list(ns).map { $0.key }
        if keys.count > Engine.maxMessagesPerContact { // per-contact quota: the oldest go first
            for k in keys.sorted().prefix(keys.count - Engine.maxMessagesPerContact) { kv.delete(ns, k) }
        }
        if m.sentAt >= c.lastAt { c.lastAt = m.sentAt; c.lastText = (m.fileId.isEmpty && m.voiceMs == 0) ? String(m.text.prefix(120)) : "Voice note" }
    }

    private func updateStatus(_ m: ChatMessage, _ s: ChatMessage.Status) {
        var n = m; n.status = s
        kv.put("msg.\(m.contactId)", m.key, n.encode())
        changed()
    }

    /// Removes messages whose disappearing timer ran out, refreshes the chat-list preview of the affected
    /// contacts and tells the UI.
    public func purgeExpired() {
        guard !stopped else { return }
        let t = millis()
        var any = false
        for var c in contacts() {
            let ns = "msg.\(c.id)"
            var removed = false
            do {
                try kv.batch {
                    for (k, v) in kv.list(ns) {
                        if let m = try? ChatMessage.decode(v), m.expiresAt != 0, m.expiresAt <= t { removeFile(m.fileId); kv.delete(ns, k); removed = true }
                    }
                    if removed {
                        let last = kv.list(ns).compactMap { try? ChatMessage.decode($0.value) }.max { ($0.sentAt, $0.id) < ($1.sentAt, $1.id) }
                        c.lastText = last.map { String($0.text.prefix(120)) } ?? ""
                        save(c)
                    }
                }
                any = any || removed
            } catch {}
        }
        // Parts or descriptions of a file that never completed are dropped after the mailbox TTL (14 days).
        for c in contacts() {
            for ns in ["fpart.\(c.id)", "fmeta.\(c.id)"] {
                for (k, v) in kv.list(ns) { if let at = try? In(v).i64(), t - at > 14 * 86_400_000 { kv.delete(ns, k) } }
            }
        }
        if any { changed() }
    }

    /// Call once at launch, before any send: a message still "sending" was cut off (app killed, phone off)
    /// and becomes "failed", so the user can retry it.
    public func recoverInterrupted() {
        guard !stopped else { return }
        var any = false
        for c in contacts() {
            let ns = "msg.\(c.id)"
            for (k, v) in kv.list(ns) {
                if var m = try? ChatMessage.decode(v), !m.incoming, m.status == .sending {
                    m.status = .failed; kv.put(ns, k, m.encode()); any = true
                }
            }
        }
        if any { changed() }
    }

    // MARK: inner messages

    private func msgBody(_ o: Out, id: [UInt8], at: Int64, expire: Int32, text: String) { o.raw(id).i64(at).i32(expire).str(text) }

    /// Registers client authorization for every known contact (Tor forgets it at restart).
    public func restoreAuth() async {
        for c in contacts() where !c.onion.isEmpty && c.peerAuth.count == 32 && !stopped {
            try? await auth.setClientAuth(onion: c.onion, privateKey: c.peerAuth)
        }
    }

    private func retrying<T>(_ attempts: Int, _ what: String, _ body: () async throws -> T) async throws -> T {
        var last: Error = SwellError.transport(what)
        for i in 0..<attempts {
            do { return try await body() } catch {
                last = error
                if error is CancellationError || Task.isCancelled { throw CancellationError() }
                if i + 1 < attempts { try? await Task.sleep(nanoseconds: UInt64(2 + i * 3) * 1_000_000_000) }
            }
        }
        throw last
    }

    private func tag() -> String { Codec.hex(Codec.random(8)) }

    /// A mailbox call. In the foreground it rides the warm stream (no new circuit and handshake per call);
    /// in the background each call is a fresh stream.
    private func mb(_ onion: String, _ req: [UInt8]) async throws -> [UInt8] {
        if foreground { return try await transport.warmRoundTrip(onion: onion, request: req, lane: "io", seconds: 60) }
        return try await transport.roundTrip(onion: onion, request: req, isolation: tag())
    }

    /// Opens the warm stream to our mailbox ahead of the first call (app launch / foreground).
    public func prewarm() async { if !mailboxOnion.isEmpty, !stopped { await transport.warmUp(onion: mailboxOnion, lane: "io") } }
    public func dropWarm() async { await transport.dropWarm() }

    // MARK: live receive (WAIT)

    /// False once the server answered a WAIT with a rejection (an older mailbox); then `listen` polls every 12 s.
    public private(set) var waitSupported = true
    public var fallbackPollSeconds = 12.0
    public func setFallbackPoll(_ s: Double) { fallbackPollSeconds = s }
    func hasOpenInvites() -> Bool { !openInvites().isEmpty }
    func hasPendingInvites() -> Bool { openInvites().contains { !$0.ready } }
    func hasPendingHandshakes() -> Bool { contacts().contains { !$0.handshakeDone } }
    func inviteTick() async { await pollInvites(); await retryHandshakes(); await prepareInvites() }
    private func queueSignature() -> String {
        contacts().filter { $0.queueReady }.map { $0.id }.sorted().joined() + "|" + openInvites().map { Codec.hex($0.queueId) }.sorted().joined()
            + "|" + contacts().filter { !$0.handshakeDone }.map { $0.id }.joined()
    }

    /// Receives until cancelled. While the mailbox supports WAIT (0x17) the app keeps long polls open on warm
    /// streams (up to 8 queues each) and a message is handled the moment it is put; otherwise it falls back to
    /// `poll()` every 12 s. A first `poll()` catches up whatever arrived while no wait was open.
    public func listen() async {
        _ = try? await track { await self.listenImpl() }
    }

    private func listenImpl() async {
        Task { await self.prepareInvites() }
        while !Task.isCancelled, !stopped {
            guard !mailboxOnion.isEmpty else { try? await Task.sleep(nanoseconds: 2_000_000_000); continue }
            await pollImpl()
            await retryHandshakes()
            let secrets = contacts().filter { $0.queueReady }.sorted { $0.id < $1.id }.map { $0.myQueueSecret }
            guard waitSupported, !secrets.isEmpty else {
                try? await Task.sleep(nanoseconds: waitSupported ? 3_000_000_000 : UInt64(fallbackPollSeconds * 1_000_000_000)); continue
            }
            let sig = queueSignature()
            let groups = stride(from: 0, to: secrets.count, by: MailboxProtocol.maxSecretsPerGet).map {
                Array(secrets[$0..<min($0 + MailboxProtocol.maxSecretsPerGet, secrets.count)])
            }
            await withTaskGroup(of: Void.self) { g in
                for (i, grp) in groups.enumerated() { g.addTask { await self.waitLoop(grp, lane: "wait\(i)") } }
                g.addTask { // open invite queues always hold our bundle, so WAIT would answer at once: look every 5 s instead
                    while !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 5_000_000_000)
                        let a = await self.hasOpenInvites(), b = await self.hasPendingHandshakes(), c = await self.hasPendingInvites()
                        if a || b || c { await self.inviteTick() }
                    }
                }
                g.addTask { // a new or removed contact changes the set of queues: restart the waits
                    while !Task.isCancelled {
                        try? await Task.sleep(nanoseconds: 2_000_000_000)
                        if await self.queueSignature() != sig { break }
                    }
                }
                await g.next()
                g.cancelAll()
            }
        }
    }

    private func waitLoop(_ secrets: [[UInt8]], lane: String) async {
        while !Task.isCancelled, !stopped, waitSupported {
            let started = Date()
            do {
                let req = try MailboxProtocol.waitRequest(secrets: secrets)
                let r = try await transport.warmRoundTrip(onion: mailboxOnion, request: req, lane: lane, seconds: 45)
                if r.first.map(Int.init) == MailboxProtocol.rReject { waitSupported = false; return }
                let items = try MailboxProtocol.parseList(r)
                var allAcked = true
                for idx in Set(items.map { $0.index }).sorted() where idx >= 0 && idx < secrets.count {
                    var ack: [String] = []
                    let mine = items.filter { $0.index == idx }
                    for it in mine { if handleEnvelope(it.envelope) { ack.append(it.id) } }
                    if ack.count < mine.count { allAcked = false }
                    if !ack.isEmpty {
                        _ = try? await retrying(2, "ack") { try await mb(mailboxOnion, MailboxProtocol.ackRequest(secret: secrets[idx], ids: ack)) }
                    }
                }
                if !items.isEmpty { changed(); await flushReceipts() }
                // Never spin: a stuck envelope, or an answer that came back at once with nothing in it.
                if !allAcked || (items.isEmpty && Date().timeIntervalSince(started) < 1) { try? await Task.sleep(nanoseconds: 3_000_000_000) }
            } catch {
                if Task.isCancelled || stopped { return }
                try? await Task.sleep(nanoseconds: 3_000_000_000)
            }
        }
    }
    private func peerFrame(_ type: Int) -> Out { Out().u8(PeerFrame.protocolVersion).u8(type) }

    // MARK: redeem an invite

    /// Redeems an Android v2 invite (the inviter's onion is reached in the foreground; ours is `""`).
    public func redeem(_ invite: Invite, firstText: String) async throws -> Contact {
        guard !redeeming else { throw EngineError.unreachable("another connection is in progress") }
        redeeming = true; defer { redeeming = false }
        return try await track { try await self.redeemImpl(invite, firstText: firstText) }
    }

    private func redeemImpl(_ invite: Invite, firstText: String) async throws -> Contact {
        guard invite.identityKey != crypto.identityKey else { throw EngineError.own }
        guard !mailboxOnion.isEmpty else { throw EngineError.unreachable("no mailbox is set up") }
        var c = Contact(channelId: Codec.random(16), onion: invite.onion, identityKey: invite.identityKey, name: "",
                        createdAt: millis(), myQueueSecret: Codec.random(32), myAuth: Codec.random(32), lastAt: millis())
        c.peerAuth = invite.authKey
        // Proof of work for our queue runs on a background thread while we talk to the inviter.
        let secret = c.myQueueSecret, bits = powBits
        let nonceTask = Task.detached(priority: .userInitiated) { MailboxProtocol.solvePow(queueId: MailboxProtocol.queueId(secret: secret), bits: bits) }
        do {
            try await auth.setClientAuth(onion: invite.onion, privateKey: invite.authKey)
            let resp = try await retrying(8, "bundle") {
                try await transport.roundTrip(onion: invite.onion, request: peerFrame(PeerFrame.getBundle).raw(invite.secret).done(), isolation: tag())
            }
            let r = In(resp)
            guard try r.u8() == PeerFrame.rBundle else { throw EngineError.used }
            let bundle = try r.bytes(); try r.end()
            guard try Engine.bundleIdentity(bundle) == invite.identityKey else { throw EngineError.mismatch }

            // Our queue on our own mailbox, so the HELLO can carry a working address.
            let nonce = await withTaskCancellationHandler { await nonceTask.value } onCancel: { nonceTask.cancel() }
            if nonce == MailboxProtocol.powCancelled || Task.isCancelled { throw CancellationError() }
            let created = try await retrying(3, "queue") {
                try await mb(mailboxOnion, MailboxProtocol.createRequest(secret: secret, nonce: nonce))
            }
            try MailboxProtocol.parseOK(created)
            c.queueReady = true

            let msgId = Codec.random(16), at = millis()
            let hello = Out().u8(1).str("").str(profileName).str(mailboxOnion).bytes(MailboxProtocol.queueId(secret: secret)).bytes(c.myAuth)
            msgBody(hello, id: msgId, at: at, expire: 0, text: firstText); hello.i32(Engine.caps)
            let ct = try crypto.startSession(channelId: c.channelId, peerBundle: bundle, firstMessage: hello.done())
            let frame = PeerFrame.hello(secret: invite.secret, channelId: c.channelId, preKeyMessage: ct)
            let ans = try await retrying(4, "hello") { try await transport.roundTrip(onion: invite.onion, request: frame, isolation: tag()) }
            let a = In(ans)
            guard try a.u8() == PeerFrame.rOK else { throw EngineError.refused }
            let state = try a.bytes(); try a.end()

            try kv.batch {
                let inner = In(try crypto.decrypt(channelId: c.channelId, type: 2, ciphertext: state))
                guard try inner.u8() == 5 else { throw SwellError.format("bad answer") }
                try applyState(inner, to: &c)
                let m = ChatMessage(id: Codec.hex(msgId), contactId: c.id, text: firstText, incoming: false, sentAt: at, status: .sentDirect)
                store(m, into: &c)
                save(c)
            }
        } catch {
            nonceTask.cancel()
            await rollback(c, invite)
            throw error
        }
        if !c.onion.isEmpty, c.peerAuth.count == 32 { try? await auth.setClientAuth(onion: c.onion, privateKey: c.peerAuth) }
        changed()
        if c.supportsV11, myAvatar() != nil { await sendAvatar(to: c) }
        return c
    }

    /// Undoes a failed redeem. Client authorization for the inviter's onion goes back to what it was: if an
    /// existing contact uses the same onion, its key is restored instead of removed.
    private func rollback(_ c: Contact, _ invite: Invite) async {
        try? kv.batch { kv.delete("sess", "\(c.id).1"); kv.delete("idk", "\(c.id).1"); kv.delete("contact", c.id) }
        let prior = contacts().first { $0.onion == invite.onion && $0.id != c.id && $0.peerAuth.count == 32 }
        try? await auth.setClientAuth(onion: invite.onion, privateKey: prior?.peerAuth)
    }

    /// Identity key (33 bytes) inside a prekey bundle (PROTOCOL.md section 4 layout).
    static func bundleIdentity(_ b: [UInt8]) throws -> [UInt8] {
        let r = In(b)
        _ = try r.i32(); _ = try r.i32(); _ = try r.bytes(max: 64)
        _ = try r.i32(); _ = try r.bytes(max: 64); _ = try r.bytes(max: 128)
        return try r.bytes(max: 64)
    }

    // MARK: mailbox invites (v3)

    static let inviteTTLMs: Int64 = 24 * 3600 * 1000

    /// An invite we made. It is prepared ahead of time, so showing "My code" is instant:
    /// - `ready`: the queue exists on the mailbox and holds the sealed bundle;
    /// - not ready: the code already works as text, the network part (CREATE + PUT) is finished in the background
    ///   and retried until it works (a redeemer's PEEK before that finds nothing and retries).
    /// - `shown`: it was handed to the user, so a fresh one is prepared behind it.
    private struct OpenInvite {
        let queueId: [UInt8]; let secret: [UInt8]; let sealKey: [UInt8]; let createdAt: Int64
        var ready = false, shown = false
        var envelope: [UInt8] = []
        var nonce: Int64 = 0
        func encode() -> [UInt8] {
            Out().raw(secret).raw(sealKey).i64(createdAt).u8((ready ? 1 : 0) | (shown ? 2 : 0)).bytes(envelope).i64(nonce).done()
        }
    }
    private func openInvites() -> [OpenInvite] {
        kv.list("invite").compactMap { (k, v) in
            let r = In(v)
            guard let q = try? Codec.unhex(k), let s = try? r.raw(32), let seal = try? r.raw(32), let at = try? r.i64() else { return nil }
            var i = OpenInvite(queueId: q, secret: s, sealKey: seal, createdAt: at)
            if r.remaining > 0, let f = try? r.u8() { i.ready = f & 1 != 0; i.shown = f & 2 != 0; i.envelope = (try? r.bytes(max: 64 << 10)) ?? []; i.nonce = (try? r.i64()) ?? 0 }
            else { i.ready = true; i.shown = true } // made by an earlier build: it is live
            return i
        }
    }
    private func save(_ i: OpenInvite) { kv.put("invite", Codec.hex(i.queueId), i.encode()) }
    private func code(_ i: OpenInvite) -> String { MailboxInvite(mailbox: mailboxOnion, queueId: i.queueId, sealKey: i.sealKey, identityKey: crypto.identityKey).encode() }
    private func liveInvites() -> [OpenInvite] { openInvites().filter { millis() - $0.createdAt < Engine.inviteTTLMs } }

    /// Whether the mailbox answers PEEK (0x16): nil = not known yet. Remembered across launches.
    public var peekKnown: Bool? { kv.get("acct", "peek").map { $0 == [1] } }

    /// The code for "My code", at once and without the network: an unshown prepared invite, else the newest
    /// one, else a new one made on the spot (a local key generation, milliseconds). Marks it shown and prepares the next.
    /// `unavailable` is true when the mailbox is known not to understand PEEK, so no code could be redeemed.
    public func inviteForDisplay(forceNew: Bool = false) -> (code: String?, unavailable: Bool) {
        guard !mailboxOnion.isEmpty, !stopped else { return (nil, false) }
        if peekKnown == false { return (nil, true) }
        var live = liveInvites()
        var pick: OpenInvite?
        if !forceNew { pick = live.filter { !$0.shown }.min { $0.createdAt < $1.createdAt } ?? live.max { $0.createdAt < $1.createdAt } }
        if pick == nil { pick = try? makeLocalInvite(); if let p = pick { live.append(p) } }
        guard var i = pick else { return (nil, false) }
        i.shown = true
        try? kv.batch { save(i) }
        if live.filter({ !$0.shown && $0.queueId != i.queueId }).isEmpty { Task { await self.prepareInvites() } } // refill behind it
        return (code(i), false)
    }

    /// A new invite, local only: keys, sealed bundle, envelope. No network, no proof of work.
    private func makeLocalInvite() throws -> OpenInvite {
        let secret = Codec.random(32), sealKey = Codec.random(32), qid = MailboxProtocol.queueId(secret: secret)
        let inv = MailboxInvite(mailbox: mailboxOnion, queueId: qid, sealKey: sealKey, identityKey: crypto.identityKey)
        var i = OpenInvite(queueId: qid, secret: secret, sealKey: sealKey, createdAt: millis())
        i.envelope = MailboxInvite.bundleEnvelope(sealed: try inv.seal(bundle: try crypto.makeBundle()))
        try kv.batch { save(i) }
        return i
    }

    /// Background work, never on a tap: keeps one unshown invite ready (at most 3 open at a time) and finishes the
    /// network part (proof of work, CREATE, PUT, one PEEK to learn whether the mailbox supports it) of every invite.
    public func prepareInvites() async {
        if let t = prepareTask { await t.value; return } // one pass at a time; a second caller waits for it
        let t = Task { await self.prepareBody() }
        prepareTask = t
        await t.value
        prepareTask = nil
    }

    private func prepareBody() async {
        guard !mailboxOnion.isEmpty, !stopped else { return }
        let live = liveInvites()
        if peekKnown != false, live.filter({ !$0.shown }).isEmpty, live.count < 3 { _ = try? makeLocalInvite() }
        for i in liveInvites().sorted(by: { !$0.ready && $1.ready }) where !i.ready {
            if Task.isCancelled || stopped { return }
            await completeInvite(i)
        }
    }

    private func completeInvite(_ i0: OpenInvite) async {
        var i = i0
        if i.nonce == 0 {
            let qid = i.queueId, bits = powBits
            let n = await Task.detached(priority: .utility) { MailboxProtocol.solvePow(queueId: qid, bits: bits) }.value
            guard n != MailboxProtocol.powCancelled else { return }
            i.nonce = n; try? kv.batch { save(i) }
        }
        do {
            try MailboxProtocol.parseOK(try await retrying(2, "queue") { try await mb(mailboxOnion, MailboxProtocol.createRequest(secret: i.secret, nonce: i.nonce)) })
            try MailboxProtocol.parseOK(try await retrying(2, "put") { try await mb(mailboxOnion, MailboxProtocol.putRequest(queueId: i.queueId, envelope: i.envelope)) })
        } catch { return } // offline: retried at the next pass
        let probe = try? await retrying(2, "peek") { try await mb(mailboxOnion, MailboxProtocol.peekRequest(queueId: i.queueId)) }
        guard let probe else { return }
        if let items = try? MailboxProtocol.parseList(probe), items.count == 1 {
            i.ready = true; try? kv.batch { save(i); kv.put("acct", "peek", [1]) }
            changed()
        } else if probe.first.map(Int.init) == MailboxProtocol.rReject {
            // An older mailbox: no code can be opened by a friend. Remember it, and clean up.
            try? kv.batch { kv.put("acct", "peek", [0]) }
            await clearQueue(secret: i.secret)
            for o in openInvites() { await clearQueue(secret: o.secret); kv.delete("invite", Codec.hex(o.queueId)) }
        }
    }

    /// Deletes every envelope of a queue we own (GET, then ACK what came back).
    private func clearQueue(secret: [UInt8]) async {
        for _ in 0..<3 {
            guard let items = try? MailboxProtocol.parseList(try await mb(mailboxOnion, MailboxProtocol.getRequest(secrets: [secret]))), !items.isEmpty else { return }
            _ = try? await mb(mailboxOnion, MailboxProtocol.ackRequest(secret: secret, ids: items.map { $0.id }))
        }
    }

    /// Drops invites older than 24 h and removes their queues from the server.
    private func expireInvites() async {
        for i in openInvites() where millis() - i.createdAt >= Engine.inviteTTLMs {
            await clearQueue(secret: i.secret)
            kv.delete("invite", Codec.hex(i.queueId))
        }
    }

    /// Reads our open invite queues.
    private func pollInvites() async {
        await expireInvites()
        for inv in openInvites().filter({ $0.ready }).shuffled() {
            if Task.isCancelled || stopped { return }
            guard let items = try? await retrying(2, "get", {
                try MailboxProtocol.parseList(try await mb(mailboxOnion, MailboxProtocol.getRequest(secrets: [inv.secret])))
            }), !items.isEmpty else { continue }
            await processInvite(inv, items)
        }
    }

    /// A HELLO sealed with the invite's key makes a contact (the first one wins); a HELLO that cannot be used
    /// (wrong key, garbage) is removed so it does not block the queue.
    private func processInvite(_ inv: OpenInvite, _ items: [MailboxProtocol.Item]) async {
        guard let h = items.first(where: { $0.envelope.count > 2 && $0.envelope[1] == UInt8(PeerFrame.hello) }) else { return }
        if let c = try? acceptHello(h.envelope, invite: inv) {
            _ = try? await retrying(2, "ack") { try await mb(mailboxOnion, MailboxProtocol.ackRequest(secret: inv.secret, ids: items.map { $0.id })) }
            changed()
            Task { await self.prepareInvites() } // one was used: refill
            await finishHandshake(c)
        } else {
            _ = try? await retrying(2, "ack") { try await mb(mailboxOnion, MailboxProtocol.ackRequest(secret: inv.secret, ids: [h.id])) }
        }
    }

    /// One HELLO into an invite queue: opens the session, stores the contact and its first message, and forgets
    /// the invite, all in one commit.
    private func acceptHello(_ env: [UInt8], invite inv: OpenInvite) throws -> Contact {
        let r = In(env)
        guard try r.u8() == PeerFrame.protocolVersion, try r.u8() == PeerFrame.hello else { throw SwellError.format("hello") }
        let secret = try r.raw(32), channel = try r.raw(16), ct = try r.bytes(); try r.end()
        guard secret == inv.sealKey, loadContact(Codec.hex(channel)) == nil else { throw SwellError.format("hello") }
        var contact: Contact?
        try kv.batch {
            let inner = In(try crypto.decrypt(channelId: channel, type: 3, ciphertext: ct))
            guard try inner.u8() == 1 else { throw SwellError.format("hello") }
            let onion = try inner.str(max: 64), name = Engine.cleanName(try inner.str(max: 256)), mbx = try inner.str(max: 64)
            let q = try inner.bytes(max: 32), authKey = try inner.bytes(max: 32)
            let msgId = Codec.hex(try inner.raw(16)); let at = try inner.i64(); _ = try inner.i32(); let text = try inner.str()
            let caps: Int32 = inner.remaining >= 4 ? ((try? inner.i32()) ?? 0) : 0
            guard let ik = crypto.remoteIdentity(channelId: channel) else { throw SwellError.format("no identity") }
            var c = Contact(channelId: channel, onion: Engine.onionPattern(onion) ? onion : "", identityKey: ik, name: name, createdAt: millis(),
                            myQueueSecret: Codec.random(32), myAuth: Codec.random(32), lastAt: millis())
            if Engine.onionPattern(mbx), q.count == 32 { c.peerMailbox = mbx; c.peerQueue = q }
            if authKey.count == 32 { c.peerAuth = authKey }
            c.caps = caps; c.fromInvite = true; c.handshakeDone = false
            guard c.onion != "" || c.hasMailbox else { throw SwellError.format("unreachable peer") }
            receive(id: msgId, at: at, text: text, replyTo: "", into: &c)
            save(c)
            kv.delete("invite", Codec.hex(inv.queueId))
            contact = c
        }
        guard let c = contact else { throw SwellError.format("hello") }
        return c
    }

    /// Creates our queue for a contact made from an invite and tells them where to write (STATE). Retried from `poll`
    /// until it works, because the contact is already saved.
    private func finishHandshake(_ c0: Contact) async {
        var c = c0
        let secret = c.myQueueSecret, bits = powBits
        let nonce = await Task.detached(priority: .userInitiated) { MailboxProtocol.solvePow(queueId: MailboxProtocol.queueId(secret: secret), bits: bits) }.value
        if nonce == MailboxProtocol.powCancelled { return }
        do {
            try MailboxProtocol.parseOK(try await retrying(3, "queue") { try await mb(mailboxOnion, MailboxProtocol.createRequest(secret: secret, nonce: nonce)) })
            c.queueReady = true
            try kv.batch { save(c) }
            let state = Out().u8(5).str(profileName).str(mailboxOnion).bytes(MailboxProtocol.queueId(secret: secret)).str("").bytes(c.myAuth).i32(Engine.caps).done()
            try await sendInner(state, to: c)
            c.handshakeDone = true
            try kv.batch { save(c) }
            if myAvatar() != nil, c.supportsV11 { await sendAvatar(to: c) }
            changed()
        } catch {}
    }

    /// Contacts made from an invite whose queue or STATE did not get through yet.
    private func retryHandshakes() async {
        for c in contacts() where !c.handshakeDone && c.fromInvite && c.hasMailbox { if Task.isCancelled || stopped { return }; await finishHandshake(c) }
    }

    /// Redeems a mailbox invite (v3, from an iPhone or an Android that has no onion service to reach).
    public func redeem(_ invite: MailboxInvite, firstText: String) async throws -> Contact {
        guard !redeeming else { throw EngineError.unreachable("another connection is in progress") }
        redeeming = true; defer { redeeming = false }
        return try await track { try await self.redeemMailboxImpl(invite, firstText: firstText) }
    }

    private func redeemMailboxImpl(_ invite: MailboxInvite, firstText: String) async throws -> Contact {
        guard invite.identityKey != crypto.identityKey else { throw EngineError.own }
        guard !mailboxOnion.isEmpty else { throw EngineError.unreachable("no mailbox is set up") }
        let iso = tag()
        let peek = try await retrying(4, "peek") { try await transport.roundTrip(onion: invite.mailbox, request: MailboxProtocol.peekRequest(queueId: invite.queueId), isolation: iso) }
        guard let items = try? MailboxProtocol.parseList(peek) else { throw EngineError.codeUnavailable }
        guard let first = items.first else { throw EngineError.used }
        let bundle = try invite.open(sealed: try MailboxInvite.sealedBundle(fromEnvelope: first.envelope))
        guard try Engine.bundleIdentity(bundle) == invite.identityKey else { throw EngineError.mismatch }
        var c = Contact(channelId: Codec.random(16), onion: "", identityKey: invite.identityKey, name: "", createdAt: millis(),
                        myQueueSecret: Codec.random(32), myAuth: Codec.random(32), lastAt: millis())
        c.fromInvite = true; c.waiting = true
        let secret = c.myQueueSecret, bits = powBits
        let nonceTask = Task.detached(priority: .userInitiated) { MailboxProtocol.solvePow(queueId: MailboxProtocol.queueId(secret: secret), bits: bits) }
        do {
            let nonce = await withTaskCancellationHandler { await nonceTask.value } onCancel: { nonceTask.cancel() }
            if nonce == MailboxProtocol.powCancelled || Task.isCancelled { throw CancellationError() }
            try MailboxProtocol.parseOK(try await retrying(3, "queue") { try await mb(mailboxOnion, MailboxProtocol.createRequest(secret: secret, nonce: nonce)) })
            c.queueReady = true
            let msgId = Codec.random(16), at = millis()
            let hello = Out().u8(1).str("").str(profileName).str(mailboxOnion).bytes(MailboxProtocol.queueId(secret: secret)).bytes(c.myAuth)
            msgBody(hello, id: msgId, at: at, expire: 0, text: firstText); hello.i32(Engine.caps)
            let ct = try crypto.startSession(channelId: c.channelId, peerBundle: bundle, firstMessage: hello.done())
            let frame = PeerFrame.hello(secret: invite.sealKey, channelId: c.channelId, preKeyMessage: ct)
            let put = try await retrying(4, "hello") { try await transport.roundTrip(onion: invite.mailbox, request: MailboxProtocol.putRequest(queueId: invite.queueId, envelope: frame), isolation: iso) }
            do { try MailboxProtocol.parseOK(put) } catch { throw EngineError.refused }
            try kv.batch {
                let m = ChatMessage(id: Codec.hex(msgId), contactId: c.id, text: firstText, incoming: false, sentAt: at, status: .queued)
                store(m, into: &c)
                save(c)
            }
        } catch {
            nonceTask.cancel()
            try? kv.batch { kv.delete("sess", "\(c.id).1"); kv.delete("idk", "\(c.id).1"); kv.delete("contact", c.id) }
            throw error
        }
        changed()
        return c
    }

    // MARK: STATE

    private func applyState(_ r: In, to c: inout Contact) throws {
        let n = Engine.cleanName(try r.str(max: 256))
        let mb = try r.str(max: 64), q = try r.bytes(max: 32)
        let o = try r.str(max: 64), authKey = try r.bytes(max: 32)
        if mb.isEmpty { c.peerMailbox = ""; c.peerQueue = [] }
        else if Engine.onionPattern(mb), q.count == 32 { c.peerMailbox = mb; c.peerQueue = q }
        if !n.isEmpty, c.name.isEmpty { c.name = n }
        if o.isEmpty, !c.peerMailbox.isEmpty { c.onion = "" } // mailbox-only peer
        else if Engine.onionPattern(o), o != c.onion { c.onion = o }
        if authKey.count == 32 { c.peerAuth = authKey }
        if r.remaining >= 4 { c.caps = (try? r.i32()) ?? c.caps }
        c.waiting = false
    }

    // MARK: send

    /// Sends a text. The message is stored first (status `sending`), then delivered; the status is updated.
    /// If the message cannot be saved, nothing is sent and this throws. `replyTo` is the hex id of the quoted message.
    @discardableResult
    public func send(_ text: String, to contactId: String, replyTo: String = "") async throws -> ChatMessage {
        try await track { try await self.sendImpl(text, to: contactId, replyTo: replyTo) }
    }

    private func sendImpl(_ text: String, to contactId: String, replyTo: String) async throws -> ChatMessage {
        let id = Codec.random(16), at = millis()
        let reply = replyTo.count == 32 ? replyTo : ""
        if contactId == Engine.noteId { // Note to Self: stored here, nothing is sent
            var m = ChatMessage(id: Codec.hex(id), contactId: contactId, text: text, incoming: false, sentAt: at, status: .sentDirect)
            m.replyTo = reply
            try kv.batch { kv.put("msg.\(contactId)", m.key, m.encode()) }
            changed()
            return m
        }
        guard var c = loadContact(contactId) else { throw EngineError.noRoute }
        guard !c.blocked else { throw EngineError.blocked }
        var m = ChatMessage(id: Codec.hex(id), contactId: c.id, text: text, incoming: false, sentAt: at, status: .sending,
                            expiresAt: c.expireSec > 0 ? at + Int64(c.expireSec) * 1000 : 0)
        m.replyTo = reply
        try kv.batch { store(m, into: &c); save(c) }
        changed()
        await deliver(&m, id: id)
        return m
    }

    public func retry(messageId: String, contactId: String) async {
        _ = try? await track {
            guard let m0 = await self.messages(contactId).first(where: { $0.id == messageId && !$0.incoming && $0.status == .failed }),
                  let id = try? Codec.unhex(m0.id) else { return }
            var m = m0
            await self.updateStatus(m, .sending); m.status = .sending
            if !m.fileId.isEmpty {
                await self.retryVoice(&m)
            } else {
                await self.deliver(&m, id: id)
            }
        }
    }

    private func retryVoice(_ m: inout ChatMessage) async {
        guard let u = fileURL(m.fileId), let d = try? Data(contentsOf: u), let c = loadContact(m.contactId), let fid = try? Codec.unhex(m.fileId) else {
            updateStatus(m, .failed); m.status = .failed; return
        }
        await deliverVoice(&m, data: [UInt8](d), fileId: fid, contact: c)
    }

    private func deliver(_ m: inout ChatMessage, id: [UInt8]) async {
        guard !stopped, let c = loadContact(m.contactId) else { return }
        do {
            let o = Out().u8(2); msgBody(o, id: id, at: m.sentAt, expire: c.expireSec, text: m.text)
            if m.replyTo.count == 32 { o.bytes((try? Codec.unhex(m.replyTo)) ?? []) } // 1.0 readers stop after the text
            let how = try await sendInner(o.done(), to: c)
            updateStatus(m, how); m.status = how
        } catch {
            updateStatus(m, .failed); m.status = .failed
        }
    }

    /// Encrypts one inner message (the ratchet step is saved before the frame leaves; if saving fails nothing is
    /// sent) and delivers it: direct in the foreground, else into the contact's mailbox. `mailbox: false` is for
    /// typing, which never goes through a mailbox.
    @discardableResult
    private func sendInner(_ inner: [UInt8], to c: Contact, mailbox: Bool = true) async throws -> ChatMessage.Status {
        var type = 0, ct: [UInt8] = []
        try kv.batch { (type, ct) = try crypto.encrypt(channelId: c.channelId, plaintext: inner) }
        let frame = PeerFrame.msg(channelId: c.channelId, ciphertextType: type, ciphertext: ct)
        return try await route(frame, to: c, mailbox: mailbox)
    }

    /// Direct to the contact's onion while in the foreground; otherwise (or on failure) into their mailbox.
    private func route(_ frame: [UInt8], to c: Contact, mailbox: Bool = true) async throws -> ChatMessage.Status {
        var lastError: Error = EngineError.noRoute
        if !c.onion.isEmpty, foreground {
            do {
                if c.peerAuth.count == 32 { try? await auth.setClientAuth(onion: c.onion, privateKey: c.peerAuth) }
                let r = try await transport.roundTrip(onion: c.onion, request: frame, isolation: tag())
                if r.first.map(Int.init) == PeerFrame.rOK { return .sentDirect }
                lastError = EngineError.refused
            } catch { lastError = error }
        }
        guard mailbox, c.hasMailbox else { throw lastError }
        let r = try await retrying(2, "put") {
            try await mb(c.peerMailbox, MailboxProtocol.putRequest(queueId: c.peerQueue, envelope: frame))
        }
        try MailboxProtocol.parseOK(r)
        return .queued
    }

    // MARK: receive

    /// Fetches every queue of ours, one per request, in random order. Returns the number of envelopes handled.
    @discardableResult
    public func poll() async -> Int {
        (try? await track { await self.pollImpl() }) ?? 0
    }

    private func pollImpl() async -> Int {
        guard !mailboxOnion.isEmpty, !stopped else { return 0 }
        var total = 0
        await pollInvites()
        await retryHandshakes()
        for c in contacts().filter({ $0.queueReady }).shuffled() {
            for _ in 0..<10 {
                if Task.isCancelled || stopped { return total }
                guard let items = try? await retrying(2, "get", {
                    try MailboxProtocol.parseList(try await mb(mailboxOnion, MailboxProtocol.getRequest(secrets: [c.myQueueSecret])))
                }), !items.isEmpty else { break }
                var ack: [String] = []
                for it in items { if handleEnvelope(it.envelope) { ack.append(it.id) } }
                if !ack.isEmpty {
                    _ = try? await retrying(2, "ack") {
                        try await mb(mailboxOnion, MailboxProtocol.ackRequest(secret: c.myQueueSecret, ids: ack))
                    }
                }
                total += items.count
                if ack.count < items.count || items.count < MailboxProtocol.maxSecretsPerGet { break }
            }
        }
        if total > 0 { changed() }
        await flushReceipts()
        return total
    }

    /// Decrypts and stores one envelope (a complete MSG frame). Returns true when it may be acknowledged:
    /// the message is saved, or it can never be processed (garbage, blocked, duplicate). It returns false
    /// when saving failed, so the envelope stays in the mailbox and the ratchet is not advanced.
    func handleEnvelope(_ env: [UInt8]) -> Bool {
        if stopped { return false }
        do {
            let r = In(env)
            guard try r.u8() == PeerFrame.protocolVersion, try r.u8() == PeerFrame.msg else { return true }
            let channel = try r.raw(16), type = try r.u8(), body = try r.bytes()
            try r.end()
            guard var c = loadContact(Codec.hex(channel)), !c.blocked else { return true }
            try kv.batch {
                let inner = In(try crypto.decrypt(channelId: channel, type: type, ciphertext: body))
                switch try inner.u8() {
                case 2:
                    let id = Codec.hex(try inner.raw(16)); let at = try inner.i64(); _ = try inner.i32(); let text = try inner.str()
                    let reply = inner.remaining > 0 ? ((try? inner.bytes(max: 16)) ?? []) : []
                    receive(id: id, at: at, text: text, replyTo: reply.count == 16 ? Codec.hex(reply) : "", into: &c)
                case 3: c.expireSec = max(0, min(try inner.i32(), 365 * 86400))
                case 5: try applyState(inner, to: &c)
                case 7:
                    let fid = Codec.hex(try inner.raw(16)); let idx = Int(try inner.i32()); let data = try inner.bytes(max: Engine.bigPartSize)
                    guard idx >= 0, idx < (Engine.maxFile + Engine.partSize - 1) / Engine.partSize else { throw SwellError.format("part index") }
                    let ns = "fpart.\(c.id)"
                    if kv.list(ns).reduce(0, { $0 + $1.value.count }) + data.count <= Engine.maxPendingPerContact {
                        kv.put(ns, "\(fid).\(idx)", Out().i64(millis()).bytes(data).done())
                        completeFile(fid, into: &c)
                    }
                case 8:
                    // FILE: header like a text, then the file description. Audio (voice notes) is received; other files
                    // show a notice in this build.
                    let id = Codec.hex(try inner.raw(16)); let at = try inner.i64(); _ = try inner.i32(); let caption = try inner.str()
                    let fid = Codec.hex(try inner.raw(16))
                    let name = Engine.cleanName(try inner.str(max: 512)); let mime = try inner.str(max: 100)
                    let size = try inner.i64(); let hash = try inner.raw(32); let parts = Int(try inner.i32())
                    var reply: [UInt8] = []; var dur: Int32 = 0
                    if inner.remaining > 0 { reply = (try? inner.bytes(max: 16)) ?? [] }
                    if inner.remaining >= 4 { dur = (try? inner.i32()) ?? 0 }
                    if mime.hasPrefix("audio/"), size > 0, size <= Int64(Engine.maxFile), parts >= 1, parts <= 105, filesDir != nil {
                        let meta = Out().i64(millis()).raw(try Codec.unhex(id)).i64(at).str(caption).i64(size).raw(hash).i32(Int32(parts))
                            .bytes(reply.count == 16 ? reply : []).i32(max(0, min(dur, Engine.maxVoiceMs))).done()
                        kv.put("fmeta.\(c.id)", fid, meta)
                        completeFile(fid, into: &c)
                    } else {
                        for (k, _) in kv.list("fpart.\(c.id)") where k.hasPrefix(fid + ".") { kv.delete("fpart.\(c.id)", k) }
                        receive(id: id, at: at, text: "\(name.isEmpty ? "Photo or file" : name): \(Engine.fileNotice).", replyTo: "", into: &c)
                    }
                case 9:
                    let id = Codec.hex(try inner.raw(16)); let emoji = try inner.str(max: 64); let add = try inner.u8() == 1
                    if allowEphemeral(c.id), Engine.validEmoji(emoji) { applyReaction(id, emoji, add, from: c) }
                case 10:
                    let op = try inner.u8(); let id = Codec.hex(try inner.raw(16)); let text = try inner.str()
                    if allowEphemeral(c.id), op == 1 || op == 2 { applyModify(op, id, text, from: &c) }
                case 11:
                    let kind = try inner.u8(); let n = Int(try inner.i32())
                    guard (0...64).contains(n) else { throw SwellError.format("receipt size") }
                    let ids = try (0..<n).map { _ in Codec.hex(try inner.raw(16)) }
                    if receiptsEnabled, kind == 1 || kind == 2, allowEphemeral(c.id) { applyReceipt(kind, ids, from: c) }
                case 12: break // typing never travels through a mailbox; a copy that arrives here is stale
                case 13:
                    let hash = try inner.raw(32); let jpeg = try inner.bytes(max: Engine.maxAvatar + 1)
                    if jpeg.count <= Engine.maxAvatar, Codec.sha256(jpeg) == hash, allowEphemeral(c.id) {
                        if jpeg.isEmpty { kv.delete("avatar", c.id) } else if jpeg.count > 2, jpeg[0] == 0xFF, jpeg[1] == 0xD8 { kv.put("avatar", c.id, jpeg) }
                    }
                default: break // FILE_PART (7) belongs to a FILE notice; newer types are ignored
                }
                save(c)
            }
            if !c.onion.isEmpty, c.peerAuth.count == 32 { Task { try? await auth.setClientAuth(onion: c.onion, privateKey: c.peerAuth) } }
            return true
        } catch {
            if error is StoreError { return false } // could not be saved: keep it in the mailbox, try again later
            return true // undecryptable or duplicate: acknowledging avoids an endless retry
        }
    }

    static let maxAvatar = 40 * 1024
    static func validEmoji(_ e: String) -> Bool {
        let n = e.utf8.count
        return n >= 1 && n <= 16 && e.unicodeScalars.allSatisfy { $0.properties.generalCategory != .control }
    }

    /// REACTION, MODIFY, RECEIPT and AVATAR from one contact: 90 a minute together, then dropped silently.
    private func allowEphemeral(_ id: String) -> Bool {
        let m = millis() / 60_000
        var b = inboundBuckets[id] ?? (m, 0)
        if b.minute != m { b = (m, 0) }
        b.n += 1; inboundBuckets[id] = b
        return b.n <= 90
    }

    private func receive(id: String, at: Int64, text: String, replyTo: String, into c: inout Contact) {
        if kv.list("msg.\(c.id)").contains(where: { (try? ChatMessage.decode($0.value))?.id == id }) { return }
        let t = millis()
        var m = ChatMessage(id: id, contactId: c.id, text: text, incoming: true, sentAt: min(at, t), status: .received,
                            expiresAt: c.expireSec > 0 ? t + Int64(c.expireSec) * 1000 : 0)
        m.replyTo = replyTo; m.receivedAt = t
        store(m, into: &c)
        if openChat != c.id { c.unread += 1 }
        if receiptsEnabled, c.supportsV11, let raw = try? Codec.unhex(id) { pendingDelivered[c.id, default: []].append(raw) }
    }

    /// Assembles a received voice note once its description and every part are here.
    private func completeFile(_ fid: String, into c: inout Contact) {
        guard let dir = filesDir, let metaB = kv.get("fmeta.\(c.id)", fid) else { return }
        let r = In(metaB)
        guard (try? r.i64()) != nil, let rawId = try? r.raw(16), let at = try? r.i64(), let caption = try? r.str(), let size = try? r.i64(),
              let hash = try? r.raw(32), let parts = try? r.i32(), let rt = try? r.bytes(max: 16), let dur = try? r.i32() else { kv.delete("fmeta.\(c.id)", fid); return }
        var data = [UInt8](); data.reserveCapacity(Int(size))
        let ns = "fpart.\(c.id)"
        for i in 0..<Int(parts) {
            guard let p = kv.get(ns, "\(fid).\(i)"), case let pr = In(p), (try? pr.i64()) != nil, let chunk = try? pr.bytes(max: Engine.bigPartSize) else { return } // not complete yet
            data += chunk
        }
        for i in 0..<Int(parts) { kv.delete(ns, "\(fid).\(i)") }
        kv.delete("fmeta.\(c.id)", fid)
        guard Int64(data.count) == size, Codec.sha256(data) == hash else { return }
        let url = dir.appendingPathComponent(fid + ".m4a")
        guard fid.allSatisfy({ $0.isHexDigit }), (try? Data(data).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])) != nil else { return }
        let id = Codec.hex(rawId)
        if kv.list("msg.\(c.id)").contains(where: { (try? ChatMessage.decode($0.value))?.id == id }) { return }
        let t = millis()
        var m = ChatMessage(id: id, contactId: c.id, text: caption, incoming: true, sentAt: min(at, t), status: .received, expiresAt: c.expireSec > 0 ? t + Int64(c.expireSec) * 1000 : 0)
        m.replyTo = rt.count == 16 ? Codec.hex(rt) : ""; m.receivedAt = t; m.fileId = fid; m.voiceMs = dur
        store(m, into: &c)
        if openChat != c.id { c.unread += 1 }
        if receiptsEnabled, c.supportsV11 { pendingDelivered[c.id, default: []].append(rawId) }
    }

    /// Sends a recorded voice note (AAC in an MP4/M4A container, at most 180 s) as a FILE, parts first.
    @discardableResult
    public func sendVoice(_ data: [UInt8], durationMs: Int, to contactId: String, replyTo: String = "") async throws -> ChatMessage {
        try await track { try await self.sendVoiceImpl(data, durationMs: durationMs, to: contactId, replyTo: replyTo) }
    }

    private func sendVoiceImpl(_ data: [UInt8], durationMs: Int, to contactId: String, replyTo: String) async throws -> ChatMessage {
        guard !data.isEmpty, data.count <= Engine.maxFile, filesDir != nil else { throw SwellError.format("voice note size") }
        let dur = Int32(max(1, min(durationMs, Int(Engine.maxVoiceMs))))
        let fileId = Codec.random(16), id = Codec.random(16), at = millis()
        let fid = Codec.hex(fileId)
        let isNote = contactId == Engine.noteId
        var c = loadContact(contactId)
        if !isNote {
            guard c != nil else { throw EngineError.noRoute }
            guard !c!.blocked else { throw EngineError.blocked }
        }
        guard let url = fileURL(fid) else { throw SwellError.format("files") }
        try Data(data).write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        var m = ChatMessage(id: Codec.hex(id), contactId: contactId, text: "", incoming: false, sentAt: at, status: isNote ? .sentDirect : .sending,
                            expiresAt: (c?.expireSec ?? 0) > 0 ? at + Int64(c!.expireSec) * 1000 : 0)
        m.replyTo = replyTo.count == 32 ? replyTo : ""; m.fileId = fid; m.voiceMs = dur
        try kv.batch { if isNote { kv.put("msg.\(contactId)", m.key, m.encode()) } else { store(m, into: &c!); save(c!) } }
        changed()
        guard !isNote, let contact = c else { return m }
        await deliverVoice(&m, data: data, fileId: fileId, contact: contact)
        return m
    }

    /// Parts first (the first one small enough for a mailbox; if it went direct the rest go in big parts), then the FILE message.
    private func deliverVoice(_ m: inout ChatMessage, data: [UInt8], fileId: [UInt8], contact: Contact) async {
        do {
            var off = 0, i = 0, direct = true
            let total = data.count
            while off < total {
                let size = (i == 0 || !direct) ? Engine.partSize : Engine.bigPartSize
                let chunk = Array(data[off..<min(total, off + size)])
                let how = try await sendInner(Out().u8(7).raw(fileId).i32(Int32(i)).bytes(chunk).done(), to: contact, mailbox: size == Engine.partSize)
                if i == 0 { direct = how == .sentDirect }
                if !direct && total > Engine.maxOfflineFile { throw EngineError.unreachable("The contact is offline. Voice notes this long need them online.") }
                off += chunk.count; i += 1
            }
            let id = try Codec.unhex(m.id)
            let hdr = Out().u8(8); msgBody(hdr, id: id, at: m.sentAt, expire: contact.expireSec, text: "")
            hdr.raw(fileId).str("voice-\(m.sentAt).m4a").str("audio/mp4").i64(Int64(total)).raw(Codec.sha256(data)).i32(Int32(i))
            if contact.supportsV11 { hdr.bytes(m.replyTo.count == 32 ? ((try? Codec.unhex(m.replyTo)) ?? []) : []).i32(m.voiceMs) }
            let how = try await sendInner(hdr.done(), to: contact)
            updateStatus(m, how); m.status = how
        } catch {
            updateStatus(m, .failed); m.status = .failed
        }
    }

    private func find(_ id: String, in contactId: String) -> ChatMessage? {
        kv.list("msg.\(contactId)").lazy.compactMap { try? ChatMessage.decode($0.value) }.first { $0.id == id }
    }
    private func write(_ m: ChatMessage) { kv.put("msg.\(m.contactId)", m.key, m.encode()) }

    /// A reaction from the contact on a message in this chat. Unknown or deleted messages are ignored.
    private func applyReaction(_ id: String, _ emoji: String, _ add: Bool, from c: Contact) {
        guard var m = find(id, in: c.id), !m.deleted else { return }
        if add { m.theirReaction = emoji } else if m.theirReaction == emoji { m.theirReaction = "" } else { return }
        write(m)
    }

    /// The contact edits or deletes one of THEIR messages (never ours), within 24 h of the time we got it.
    private func applyModify(_ op: Int, _ id: String, _ text: String, from c: inout Contact) {
        guard var m = find(id, in: c.id), m.incoming, !m.deleted else { return }
        let base = m.receivedAt > 0 ? m.receivedAt : m.sentAt
        guard millis() - base <= Engine.editWindowMs else { return }
        if op == 1 {
            removeFile(m.fileId); m.deleted = true; m.text = ""; m.myReaction = ""; m.theirReaction = ""; m.voiceMs = 0; m.fileId = ""
        } else {
            guard !text.isEmpty, m.fileId.isEmpty, m.voiceMs == 0 else { return }
            m.text = text; m.edited = true
        }
        write(m)
        refreshPreview(&c)
    }

    private func applyReceipt(_ kind: Int, _ ids: [String], from c: Contact) {
        for id in ids {
            guard var m = find(id, in: c.id), !m.incoming else { continue }
            m.delivered = true; if kind == 2 { m.read = true }
            write(m)
        }
    }

    /// The chat-list preview follows the newest message (after an edit or a delete).
    private func refreshPreview(_ c: inout Contact) {
        let last = kv.list("msg.\(c.id)").compactMap { try? ChatMessage.decode($0.value) }.max { ($0.sentAt, $0.id) < ($1.sentAt, $1.id) }
        c.lastText = last.map { $0.deleted ? "This message was deleted" : ($0.fileId.isEmpty && $0.voiceMs == 0) ? String($0.text.prefix(120)) : "Voice note" } ?? ""
    }

    /// Sends the delivered receipts collected while handling envelopes, and read receipts for the chat on screen.
    private func flushReceipts() async {
        guard receiptsEnabled, !stopped else { pendingDelivered = [:]; return }
        let todo = pendingDelivered; pendingDelivered = [:]
        for (cid, ids) in todo {
            guard let c = loadContact(cid), c.supportsV11, !c.blocked else { continue }
            await sendReceipt(1, ids, to: c)
        }
        if let open = openChat, foreground { await markRead(open) }
    }

    private func sendReceipt(_ kind: Int, _ ids: [[UInt8]], to c: Contact) async {
        for chunk in stride(from: 0, to: ids.count, by: 64).map({ Array(ids[$0..<min($0 + 64, ids.count)]) }) {
            let o = Out().u8(11).u8(kind).i32(Int32(chunk.count)); chunk.forEach { o.raw($0) }
            _ = try? await sendInner(o.done(), to: c)
        }
    }

    /// The chat is on screen: tell the contact which of their messages were read (only with receipts on).
    public func markRead(_ contactId: String) async {
        guard receiptsEnabled, !stopped, let c = loadContact(contactId), c.supportsV11, !c.blocked else { return }
        let unread = kv.list("msg.\(contactId)").compactMap { try? ChatMessage.decode($0.value) }.filter { $0.incoming && !$0.read && !$0.deleted }
        guard !unread.isEmpty else { return }
        for var m in unread { m.read = true; m.delivered = true; write(m) }
        await sendReceipt(2, unread.compactMap { try? Codec.unhex($0.id) }, to: c)
        changed()
    }

    // MARK: contact actions

    private func update(_ id: String, _ f: (inout Contact) -> Void) throws {
        guard var c = loadContact(id) else { return }
        f(&c)
        try kv.batch { save(c) }
        changed()
    }
    public func rename(_ id: String, _ name: String) throws { try update(id) { $0.nickname = Engine.cleanName(name) } }
    public func setBlocked(_ id: String, _ b: Bool) throws { try update(id) { $0.blocked = b } }
    public func setVerified(_ id: String, _ v: Bool) throws { try update(id) { $0.verified = v } }

    // MARK: chat actions (1.1)

    /// Disappearing messages for the whole chat (0 = off). The timer is stored here and announced to the contact
    /// (inner type 3); if the announcement cannot be delivered this throws, and the timer still applies on this phone.
    public func setDisappearing(_ id: String, seconds: Int) async throws {
        guard let c0 = loadContact(id) else { return }
        let secs = Int32(max(0, min(seconds, 365 * 86400)))
        try update(id) { $0.expireSec = secs }
        _ = try await track { try await self.sendInner(Out().u8(3).i32(secs).done(), to: c0) }
    }

    /// Reaction on a message: the same emoji again removes it, another one replaces it.
    public func react(_ messageId: String, in contactId: String, emoji: String) async {
        guard Engine.validEmoji(emoji), var m = find(messageId, in: contactId), !m.deleted else { return }
        let removing = m.myReaction == emoji
        m.myReaction = removing ? "" : emoji
        try? kv.batch { write(m) }
        changed()
        guard contactId != Engine.noteId, let c = loadContact(contactId), c.supportsV11, !c.blocked, let raw = try? Codec.unhex(messageId) else { return }
        _ = try? await track { try await self.sendInner(Out().u8(9).raw(raw).bytes(Array(emoji.utf8)).u8(removing ? 0 : 1).done(), to: c) }
    }

    public func canEdit(_ m: ChatMessage) -> Bool {
        !m.incoming && !m.deleted && m.fileId.isEmpty && m.voiceMs == 0 && m.status != .failed && millis() - m.sentAt <= Engine.editWindowMs - 5 * 60_000
    }

    /// Edit one of our text messages (within 24 h). The contact is told when it supports 1.1.
    public func edit(_ messageId: String, in contactId: String, text: String) async throws {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty, var m = find(messageId, in: contactId), canEdit(m) else { return }
        m.text = t; m.edited = true
        try kv.batch {
            write(m)
            if var c = loadContact(contactId) { refreshPreview(&c); save(c) }
        }
        changed()
        guard contactId != Engine.noteId, let c = loadContact(contactId), c.supportsV11, !c.blocked, let raw = try? Codec.unhex(messageId) else { return }
        _ = try await track { try await self.sendInner(Out().u8(10).u8(2).raw(raw).str(t).done(), to: c) }
    }

    /// Delete for everyone: our message becomes "This message was deleted" here, and (1.1 contacts) there.
    public func deleteForEveryone(_ messageId: String, in contactId: String) async throws {
        guard var m = find(messageId, in: contactId), !m.incoming, !m.deleted else { return }
        removeFile(m.fileId)
        m.deleted = true; m.text = ""; m.myReaction = ""; m.theirReaction = ""; m.voiceMs = 0; m.fileId = ""
        try kv.batch {
            write(m)
            if var c = loadContact(contactId) { refreshPreview(&c); save(c) }
        }
        changed()
        guard contactId != Engine.noteId, let c = loadContact(contactId), c.supportsV11, !c.blocked, let raw = try? Codec.unhex(messageId) else { return }
        _ = try await track { try await self.sendInner(Out().u8(10).u8(1).raw(raw).str("").done(), to: c) }
    }

    /// Removes a message from this phone only.
    public func deleteHere(_ messageId: String, in contactId: String) throws {
        guard let m = find(messageId, in: contactId) else { return }
        removeFile(m.fileId)
        try kv.batch {
            kv.delete("msg.\(contactId)", m.key)
            if var c = loadContact(contactId) { refreshPreview(&c); save(c) }
        }
        changed()
    }

    /// Typing: direct path only, foreground only, never through a mailbox; off by default and needs a 1.1 contact.
    public func sendTyping(_ on: Bool, to contactId: String) async {
        guard typingEnabled, foreground, !stopped, let c = loadContact(contactId), c.supportsV11, !c.blocked, !c.onion.isEmpty else { return }
        _ = try? await sendInner(Out().u8(12).u8(on ? 1 : 0).done(), to: c, mailbox: false)
    }

    /// Search over every chat (text only, case and accent insensitive). Newest first.
    public func search(_ query: String) -> [ChatMessage] {
        let q = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !q.isEmpty else { return [] }
        var ids = contacts().map { $0.id }; ids.append(Engine.noteId)
        let t = millis()
        var out: [ChatMessage] = []
        for id in ids {
            for (_, v) in kv.list("msg.\(id)") {
                guard let m = try? ChatMessage.decode(v), !m.deleted, m.expiresAt == 0 || m.expiresAt > t else { continue }
                if m.text.range(of: q, options: [.caseInsensitive, .diacriticInsensitive]) != nil { out.append(m) }
            }
        }
        return out.sorted { $0.sentAt > $1.sentAt }
    }

    // MARK: profile photo (inner type 13)

    public func myAvatar() -> [UInt8]? { kv.get("acct", "avatar") }
    public func avatar(for contactId: String) -> [UInt8]? { kv.get("avatar", contactId) }

    /// Stores our photo (a JPEG of at most 40 KiB, made by the app) or removes it (`nil`), and sends it to every contact.
    public func setMyAvatar(_ jpeg: [UInt8]?) async throws {
        if let j = jpeg, j.count > Engine.maxAvatar { throw SwellError.format("photo too large") }
        try kv.batch { if let j = jpeg, !j.isEmpty { kv.put("acct", "avatar", j) } else { kv.delete("acct", "avatar") } }
        for c in contacts() where c.supportsV11 && !c.blocked { await sendAvatar(to: c) }
        changed()
    }

    private func sendAvatar(to c: Contact) async {
        let j = myAvatar() ?? []
        _ = try? await sendInner(Out().u8(13).raw(Codec.sha256(j)).bytes(j).done(), to: c)
    }

    public func safetyNumber(_ id: String) -> String? {
        guard let c = loadContact(id) else { return nil }
        return try? crypto.safetyNumber(local: crypto.identityKey, remote: c.identityKey)
    }

    public func delete(_ id: String) async throws {
        guard let c = loadContact(id) else { return }
        for (_, v) in kv.list("msg.\(id)") { if let m = try? ChatMessage.decode(v) { removeFile(m.fileId) } }
        try kv.batch {
            for ns in ["fpart.\(id)", "fmeta.\(id)"] { for (k, _) in kv.list(ns) { kv.delete(ns, k) } }
            kv.delete("avatar", id)
            for (k, _) in kv.list("msg.\(id)") { kv.delete("msg.\(id)", k) }
            kv.delete("sess", "\(id).1"); kv.delete("idk", "\(id).1"); kv.delete("contact", id)
        }
        if !c.onion.isEmpty, !contacts().contains(where: { $0.onion == c.onion }) { try? await auth.setClientAuth(onion: c.onion, privateKey: nil) }
        changed()
    }

    // MARK: report

    /// Sends a sealed report (reason, optional note, the contact's identity key and the contact's last
    /// `includeLast` text messages) to the Jiji Labs report inbox. Same wire as Kotlin `Node.report`.
    public func report(_ contactId: String, reason: String, note: String, includeLast: Int, inbox: ReportInbox) async throws {
        try await track { try await self.reportImpl(contactId, reason: reason, note: note, includeLast: includeLast, inbox: inbox) }
    }

    private func reportImpl(_ contactId: String, reason: String, note: String, includeLast: Int, inbox: ReportInbox) async throws {
        guard let c = loadContact(contactId) else { throw EngineError.noRoute }
        let text = reason + (note.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? "" : " \u{2014} " + note.trimmingCharacters(in: .whitespacesAndNewlines))
        let entries = recentIncoming(contactId, includeLast).map { Report.Entry(sentAt: $0.sentAt, incoming: true, text: $0.text) }
        let body = Report.body(nowMillis: millis(), reason: text, identityKey: c.identityKey, messages: entries)
        let env = try Report.seal(publicKey: inbox.publicKey, plain: try Padding.pad(body))
        let r = try await retrying(3, "report") {
            try await transport.roundTrip(onion: inbox.mailbox, request: MailboxProtocol.putRequest(queueId: inbox.queueId, envelope: env), isolation: tag())
        }
        do { try MailboxProtocol.parseOK(r) } catch { throw EngineError.unreachable("The report inbox did not accept the report. Try again later.") }
    }

    /// The contact's last `n` incoming messages (what a report would include).
    public func recentIncoming(_ contactId: String, _ n: Int) -> [ChatMessage] {
        guard n > 0 else { return [] }
        return Array(messages(contactId).filter { $0.incoming }.suffix(n))
    }
}
