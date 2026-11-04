import XCTest
import SwellCore
@testable import SwellSignal

/// In-memory network: one fake Android inviter (own onion), one fake mailbox.
final class FakeNet: MailboxTransport, @unchecked Sendable {
    let inviterOnion = String(repeating: "a", count: 56), mailbox = String(repeating: "m", count: 56)
    let inviterKv = MemoryKeyValueStore()
    let inviter: LibSignalCrypto
    var inviterOnline = true
    var queues: [String: [(String, [UInt8])]] = [:]   // queueId hex -> (id, envelope)
    var secrets: [String: [UInt8]] = [:]               // secret hex -> queueId
    var received: [String] = []                        // texts the inviter got
    var inners: [[UInt8]] = []                         // every inner message the inviter got, in order
    var peerState: Int32 = 1                           // caps the inviter announces in STATE
    var helloInfo: (channel: [UInt8], mailbox: String, queue: [UInt8], name: String)?
    var bundleServed = false
    var waitSupported = true
    var peekSupported = true
    var waits = 0
    let inviteSecret = Codec.random(32)
    private let lock = NSLock()
    var counter = 0

    init() throws { inviter = try LibSignalCrypto(account: Account(entropy: [UInt8](repeating: 0x4B, count: 16), registrationId: 99), kv: inviterKv) }

    func invite() throws -> Invite {
        Invite(onion: inviterOnion, secret: inviteSecret, identityKey: inviter.identityKey, authKey: Codec.random(32))
    }

    func roundTrip(onion: String, request: [UInt8], isolation: String) async throws -> [UInt8] {
        try lock.withLock { try handle(onion, request) }
    }

    func state() -> [UInt8] {
        Out().u8(5).str("Kotlin Peer").str(mailbox).bytes(Codec.random(32)).str(inviterOnion).bytes(Codec.random(32)).i32(peerState).done()
    }

    func handle(_ onion: String, _ req: [UInt8]) throws -> [UInt8] {
        let r = In(req)
        if onion == inviterOnion {
            guard inviterOnline else { throw SwellError.transport("offline") }
            _ = try r.u8()
            switch try r.u8() {
            case 1:
                _ = try r.raw(32); bundleServed = true
                return Out().u8(0x81).bytes(try inviter.makeBundle()).done()
            case 2:
                _ = try r.raw(32); let ch = try r.raw(16); let ct = try r.bytes()
                let inner = In(try inviter.decrypt(channelId: ch, type: 3, ciphertext: ct))
                XCTAssertEqual(try inner.u8(), 1); XCTAssertEqual(try inner.str(), "")
                let name = try inner.str(), mb = try inner.str(), q = try inner.bytes(), _ = try inner.bytes()
                helloInfo = (ch, mb, q, name)
                let ans = try inviter.encrypt(channelId: ch, plaintext: state())
                XCTAssertEqual(ans.type, 2)
                return Out().u8(0x80).bytes(ans.ciphertext).done()
            case 3:
                let ch = try r.raw(16); let t = try r.u8(); let ct = try r.bytes()
                let plain = try inviter.decrypt(channelId: ch, type: t, ciphertext: ct)
                let probe = In(plain)
                inners.append(plain)
                if try probe.u8() == 2 { _ = try probe.raw(16); _ = try probe.i64(); _ = try probe.i32(); received.append(try probe.str()) }
                return [0x80]
            default: return [0xFF]
            }
        }
        XCTAssertEqual(onion, mailbox)
        XCTAssertEqual(try r.u8(), 2)
        switch try r.u8() {
        case 0x14:
            let s = try r.raw(32), nonce = try r.i64(); let q = MailboxProtocol.queueId(secret: s)
            guard MailboxProtocol.powOK(queueId: q, nonce: nonce, bits: 8) else { return [0xFF] }
            queues[Codec.hex(q)] = []; secrets[Codec.hex(s)] = q; return [0x80]
        case 0x11:
            let q = Codec.hex(try r.raw(32)); let env = try r.bytes()
            guard queues[q] != nil else { return [0xFF] }
            counter += 1; queues[q]!.append(("e\(counter)", env)); return [0x80]
        case 0x12:
            XCTAssertEqual(try r.i32(), 1)
            let s = try r.raw(32); guard let q = secrets[Codec.hex(s)] else { return [0xFF] }
            let items = queues[Codec.hex(q)]!
            let o = Out().u8(0x82).i32(Int32(items.count))
            for (i, it) in items.enumerated() { o.i32(Int32(i)).str(it.0).bytes(it.1) }
            return o.done()
        case 0x13:
            let s = try r.raw(32); let q = Codec.hex(secrets[Codec.hex(s)]!); let n = Int(try r.i32())
            let ids = Set(try (0..<n).map { _ in try r.str() })
            queues[q]!.removeAll { ids.contains($0.0) }; return [0x80]
        case 0x16:
            guard peekSupported else { return [0xFF] }
            let q = Codec.hex(try r.raw(32))
            let o = Out().u8(0x82)
            if let first = queues[q]?.first { o.i32(1).i32(0).str(first.0).bytes(first.1) } else { o.i32(0) }
            return o.done()
        case 0x17:
            guard waitSupported else { return [0xFF] }
            waits += 1
            _ = try r.i32(); let n = Int(try r.i32())
            let o = Out()
            var items: [(Int, String, [UInt8])] = []
            for i in 0..<n {
                let s = try r.raw(32); guard let q = secrets[Codec.hex(s)] else { continue }
                for it in queues[Codec.hex(q)]! { items.append((i, it.0, it.1)) }
            }
            o.u8(0x82).i32(Int32(items.count))
            for it in items { o.i32(Int32(it.0)).str(it.1).bytes(it.2) }
            return o.done()
        default: return [0xFF]
        }
    }

    /// The inviter leaves a TEXT in the iPhone's mailbox queue (what Android does when the phone is not reachable).
    func putText(_ text: String) throws {
        let h = helloInfo!
        let o = Out().u8(2).raw(Codec.random(16)).i64(1_700_000_000_000 + Int64(counter)).i32(0).str(text)
        let e = try inviter.encrypt(channelId: h.channel, plaintext: o.done())
        let frame = PeerFrame.msg(channelId: h.channel, ciphertextType: e.type, ciphertext: e.ciphertext)
        counter += 1
        queues[Codec.hex(h.queue)]!.append(("e\(counter)", frame))
    }
}

final class EngineTests: XCTestCase {
    nonisolated(unsafe) static var tick: Int64 = 0
    var dir: URL!
    let key = [UInt8](repeating: 9, count: 32)
    let acct = Account(entropy: [UInt8](repeating: 0x53, count: 16), registrationId: 5)
    override func setUp() { dir = FileManager.default.temporaryDirectory.appendingPathComponent("eng-\(UUID().uuidString)") }
    override func tearDown() { try? FileManager.default.removeItem(at: dir) }

    func engine(_ net: FakeNet, _ kv: KeyValueStore) throws -> Engine {
        try Engine(account: acct, kv: kv, transport: net, auth: NoOnionAuth(), mailboxOnion: net.mailbox, profileName: "iPhone Tester", powBits: 8)
    }

    func testRedeemSendReceiveAndRelaunch() async throws {
        let net = try FakeNet()
        let url = dir.appendingPathComponent("s.swk")
        var kv = try EncryptedFileStore(url: url, key: key)
        var e = try engine(net, kv)
        let c = try await e.redeem(net.invite(), firstText: "Hi!")
        XCTAssertEqual(c.name, "Kotlin Peer"); XCTAssertTrue(c.queueReady)
        XCTAssertEqual(net.helloInfo?.mailbox, net.mailbox); XCTAssertEqual(net.helloInfo?.name, "iPhone Tester")
        XCTAssertEqual(net.helloInfo?.queue, MailboxProtocol.queueId(secret: c.myQueueSecret))

        // direct while the inviter is online
        let m1 = try await e.send("direct one", to: c.id)
        XCTAssertEqual(m1.status, .sentDirect); XCTAssertEqual(net.received, ["direct one"])
        // inviter offline and no mailbox known for it (state had one: PUT must fail because no queue exists on it)
        net.inviterOnline = false
        let m2 = try await e.send("while offline", to: c.id)
        XCTAssertEqual(m2.status, .failed)   // contact mailbox queue does not exist in this fake

        // receive via mailbox
        try net.putText("hello from mailbox"); try net.putText("second")
        let n = await e.poll()
        XCTAssertEqual(n, 2)
        let msgs = await e.messages(c.id)
        XCTAssertEqual(msgs.filter(\.incoming).map(\.text), ["hello from mailbox", "second"])
        XCTAssertTrue(net.queues[Codec.hex(net.helloInfo!.queue)]!.isEmpty, "acked")
        let unread = await e.contact(c.id)?.unread
        XCTAssertEqual(unread, 2)

        // relaunch: new store + engine on the same file
        kv = try EncryptedFileStore(url: url, key: key)
        e = try engine(net, kv)
        let again = await e.messages(c.id)
        XCTAssertEqual(again.count, 5) // hello text, direct one, failed one, two incoming
        // ratchet continues across relaunch
        try net.putText("after relaunch")
        let n2 = await e.poll()
        XCTAssertEqual(n2, 1)
        net.inviterOnline = true
        let m3 = try await e.send("back online", to: c.id)
        XCTAssertEqual(m3.status, .sentDirect)
    }

    func testListenReceivesThroughWaitAndFallsBackOnOldServer() async throws {
        for supported in [true, false] {
            let net = try FakeNet(); net.waitSupported = supported
            let e = try engine(net, MemoryKeyValueStore())
            let c = try await e.redeem(net.invite(), firstText: "Hi")
            await e.setFallbackPoll(0.5)
            let listener = Task { await e.listen() }
            try await Task.sleep(nanoseconds: 300_000_000)
            try net.putText("live one")
            var got: [ChatMessage] = []
            for _ in 0..<40 {
                got = await e.messages(c.id).filter(\.incoming)
                if !got.isEmpty { break }
                try await Task.sleep(nanoseconds: 250_000_000)
            }
            listener.cancel()
            XCTAssertEqual(got.map(\.text), ["live one"], "supported=\(supported)")
            let ws = await e.waitSupported
            XCTAssertEqual(ws, supported)
            if supported { XCTAssertGreaterThan(net.waits, 0) }
        }
    }

    /// The inviter leaves an arbitrary inner message in the iPhone's mailbox queue.
    func putInner(_ net: FakeNet, _ inner: [UInt8]) throws {
        let h = net.helloInfo!
        let e = try net.inviter.encrypt(channelId: h.channel, plaintext: inner)
        net.counter += 1
        net.queues[Codec.hex(h.queue)]!.append(("e\(net.counter)", PeerFrame.msg(channelId: h.channel, ciphertextType: e.type, ciphertext: e.ciphertext)))
    }
    func textInner(_ id: [UInt8], _ text: String, reply: [UInt8]? = nil) -> [UInt8] {
        Self.tick += 1
        let o = Out().u8(2).raw(id).i64(1_700_000_000_000 + Self.tick).i32(0).str(text)
        if let reply { o.bytes(reply) }
        return o.done()
    }

    func testV11ReplyReactionEditDeleteReceiptsAndSettings() async throws {
        let net = try FakeNet()
        let e = try engine(net, MemoryKeyValueStore())
        await e.setReceipts(true)
        let c = try await e.redeem(net.invite(), firstText: "Hi")
        let st = await e.contact(c.id)
        XCTAssertTrue(st?.supportsV11 ?? false)
        // incoming reply + a message to react to
        let id1 = Codec.random(16), id2 = Codec.random(16)
        try putInner(net, textInner(id1, "first"))
        try putInner(net, textInner(id2, "answer", reply: id1))
        _ = await e.poll()
        var msgs = await e.messages(c.id).filter(\.incoming)
        XCTAssertEqual(msgs.map(\.text), ["first", "answer"]); XCTAssertEqual(msgs[1].replyTo, Codec.hex(id1))
        // delivered receipts went out (direct, inviter online)
        let receipts = net.inners.filter { $0.first == 11 }
        XCTAssertEqual(receipts.count, 1); XCTAssertEqual(receipts[0][1], 1)
        // sending a reply: the quoted id is the trailing field
        let m = try await e.send("my reply", to: c.id, replyTo: Codec.hex(id2))
        XCTAssertEqual(m.replyTo, Codec.hex(id2))
        let sent = In(net.inners.last!); _ = try sent.u8(); _ = try sent.raw(16); _ = try sent.i64(); _ = try sent.i32(); _ = try sent.str()
        XCTAssertEqual(try sent.bytes(max: 16), id2)
        // reactions both ways
        await e.react(Codec.hex(id1), in: c.id, emoji: "👍")
        XCTAssertEqual(net.inners.last![0], 9)
        try putInner(net, Out().u8(9).raw(m.id.isEmpty ? id1 : try Codec.unhex(m.id)).bytes(Array("❤️".utf8)).u8(1).done())
        try putInner(net, Out().u8(9).raw(Codec.random(16)).bytes(Array("😂".utf8)).u8(1).done()) // unknown id: ignored
        _ = await e.poll()
        msgs = await e.messages(c.id)
        XCTAssertEqual(msgs.first { $0.id == Codec.hex(id1) }?.myReaction, "👍")
        XCTAssertEqual(msgs.first { $0.id == m.id }?.theirReaction, "❤️")
        await e.react(Codec.hex(id1), in: c.id, emoji: "👍") // same again removes
        XCTAssertEqual(net.inners.last!.last, 0)
        // the contact edits then deletes their message; our own messages cannot be touched by them
        try putInner(net, Out().u8(10).u8(2).raw(id1).str("first (fixed)").done())
        try putInner(net, Out().u8(10).u8(1).raw(try Codec.unhex(m.id)).str("").done())
        _ = await e.poll()
        msgs = await e.messages(c.id)
        let f = msgs.first { $0.id == Codec.hex(id1) }!
        XCTAssertEqual(f.text, "first (fixed)"); XCTAssertTrue(f.edited)
        XCTAssertFalse(msgs.first { $0.id == m.id }!.deleted, "a contact cannot delete our message")
        try putInner(net, Out().u8(10).u8(1).raw(id2).str("").done())
        _ = await e.poll()
        let d = await e.messages(c.id).first { $0.id == Codec.hex(id2) }!
        XCTAssertTrue(d.deleted); XCTAssertEqual(d.text, "")
        // our edit and delete-for-everyone
        try await e.edit(m.id, in: c.id, text: "my reply, fixed")
        let ed = In(net.inners.last!); XCTAssertEqual(try ed.u8(), 10); XCTAssertEqual(try ed.u8(), 2)
        try await e.deleteForEveryone(m.id, in: c.id)
        XCTAssertEqual(net.inners.last![1], 1)
        let gone = await e.messages(c.id).first { $0.id == m.id }!
        XCTAssertTrue(gone.deleted)
        // read/delivered receipts for our message
        let m2 = try await e.send("receipt me", to: c.id)
        try putInner(net, Out().u8(11).u8(2).i32(1).raw(try Codec.unhex(m2.id)).done())
        _ = await e.poll()
        let r2 = await e.messages(c.id).first { $0.id == m2.id }!
        XCTAssertTrue(r2.read && r2.delivered)
        // receipts off: nothing processed
        await e.setReceipts(false)
        let m3 = try await e.send("no receipt", to: c.id)
        try putInner(net, Out().u8(11).u8(2).i32(1).raw(try Codec.unhex(m3.id)).done())
        _ = await e.poll()
        let r3 = await e.messages(c.id).first { $0.id == m3.id }!
        XCTAssertFalse(r3.read)
        // disappearing messages: announced as inner type 3
        try await e.setDisappearing(c.id, seconds: 3600)
        let sett = In(net.inners.last!); XCTAssertEqual(try sett.u8(), 3); XCTAssertEqual(try sett.i32(), 3600)
        let timed = try await e.send("will vanish", to: c.id)
        XCTAssertGreaterThan(timed.expiresAt, 0)
        // search
        let hits = await e.search("VANISH")
        XCTAssertEqual(hits.map(\.text), ["will vanish"])
        // note to self never touches the network
        let before = net.inners.count
        let note = try await e.send("remember", to: Engine.noteId)
        XCTAssertEqual(note.status, .sentDirect); XCTAssertEqual(net.inners.count, before)
        let notes = await e.messages(Engine.noteId)
        XCTAssertEqual(notes.map(\.text), ["remember"])
    }

    func testVoiceNoteBothWays() async throws {
        let net = try FakeNet()
        let e = try engine(net, MemoryKeyValueStore())
        await e.setFilesDirectory(dir.appendingPathComponent("files"))
        let c = try await e.redeem(net.invite(), firstText: "Hi")
        // send ~120 KB: first part 48 KiB, the rest in big parts because the first went direct
        let audio = (0..<120_000).map { UInt8(truncatingIfNeeded: $0 &* 31) }
        let m = try await e.sendVoice(audio, durationMs: 7_500, to: c.id)
        XCTAssertEqual(m.status, .sentDirect); XCTAssertEqual(m.voiceMs, 7_500)
        let parts = net.inners.filter { $0.first == 7 }, files = net.inners.filter { $0.first == 8 }
        XCTAssertEqual(parts.count, 2); XCTAssertEqual(files.count, 1)
        let fr = In(files[0]); _ = try fr.u8(); _ = try fr.raw(16); _ = try fr.i64(); _ = try fr.i32(); _ = try fr.str()
        _ = try fr.raw(16); XCTAssertTrue(try fr.str().hasPrefix("voice-")); XCTAssertEqual(try fr.str(), "audio/mp4")
        XCTAssertEqual(try fr.i64(), 120_000); XCTAssertEqual(try fr.raw(32), Codec.sha256(audio)); XCTAssertEqual(try fr.i32(), 2)
        XCTAssertEqual(try fr.bytes(max: 16), []); XCTAssertEqual(try fr.i32(), 7_500)
        let preview = await e.contact(c.id)?.lastText
        XCTAssertEqual(preview, "Voice note")
        // receive: three parts then the FILE, out of order (FILE last here)
        let rid = Codec.random(16), fid = Codec.random(16)
        let clip = (0..<100_000).map { UInt8(truncatingIfNeeded: $0 &* 7) }
        let chunks = stride(from: 0, to: clip.count, by: 48 * 1024).map { Array(clip[$0..<min($0 + 48 * 1024, clip.count)]) }
        for (i, ch) in chunks.enumerated() { try putInner(net, Out().u8(7).raw(fid).i32(Int32(i)).bytes(ch).done()) }
        let hdr = Out().u8(8).raw(rid).i64(1_700_000_000_000).i32(0).str("")
        hdr.raw(fid).str("voice-1.m4a").str("audio/mp4").i64(Int64(clip.count)).raw(Codec.sha256(clip)).i32(Int32(chunks.count)).bytes([]).i32(4_200)
        try putInner(net, hdr.done())
        _ = await e.poll()
        let got = await e.messages(c.id).first { $0.id == Codec.hex(rid) }
        XCTAssertEqual(got?.voiceMs, 4_200); XCTAssertEqual(got?.incoming, true)
        let url = await e.fileURL(got!.fileId)!
        XCTAssertEqual([UInt8](try Data(contentsOf: url)), clip)
        // a corrupt file (wrong hash) is dropped, not shown
        let bad = Codec.random(16), bid = Codec.random(16)
        try putInner(net, Out().u8(7).raw(bid).i32(0).bytes([1, 2, 3]).done())
        let h2 = Out().u8(8).raw(bad).i64(1_700_000_000_001).i32(0).str(""); h2.raw(bid).str("x.m4a").str("audio/mp4").i64(3).raw(Codec.random(32)).i32(1).bytes([]).i32(0)
        try putInner(net, h2.done())
        _ = await e.poll()
        let none = await e.messages(c.id).first { $0.id == Codec.hex(bad) }
        XCTAssertNil(none)
        // deleting removes the file
        try await e.deleteHere(got!.id, in: c.id)
        XCTAssertFalse(FileManager.default.fileExists(atPath: url.path))
    }

    func testAvatarRoundTripAndNickname() async throws {
        let net = try FakeNet()
        let e = try engine(net, MemoryKeyValueStore())
        let c = try await e.redeem(net.invite(), firstText: "Hi")
        let jpeg: [UInt8] = [0xFF, 0xD8, 1, 2, 3, 0xFF, 0xD9]
        try putInner(net, Out().u8(13).raw(Codec.sha256(jpeg)).bytes(jpeg).done())
        try putInner(net, Out().u8(13).raw(Codec.random(32)).bytes([1, 2, 3, 4]).done()) // wrong hash: ignored
        _ = await e.poll()
        let got = await e.avatar(for: c.id)
        XCTAssertEqual(got, jpeg)
        try putInner(net, Out().u8(13).raw(Codec.sha256([])).bytes([]).done()) // removal
        _ = await e.poll()
        let none = await e.avatar(for: c.id)
        XCTAssertNil(none)
        try await e.setMyAvatar(jpeg)
        let sent = net.inners.last!
        XCTAssertEqual(sent[0], 13); XCTAssertEqual(Array(sent[1..<33]), Codec.sha256(jpeg))
        try await e.rename(c.id, "Sister")
        let n = await e.contact(c.id)
        XCTAssertEqual(n?.displayName, "Sister"); XCTAssertEqual(n?.name, "Kotlin Peer")
    }

    /// Two engines on one fake mailbox: A makes a code, B redeems it, they chat. Also PEEK missing -> clear error.
    func testMailboxInviteBetweenTwoPhones() async throws {
        let net = try FakeNet()
        net.peekSupported = true
        let kvA = MemoryKeyValueStore(), kvB = MemoryKeyValueStore()
        let a = try engine(net, kvA)
        let b = try Engine(account: Account(entropy: [UInt8](repeating: 0x21, count: 16), registrationId: 9), kv: kvB, transport: net, auth: NoOnionAuth(),
                           mailboxOnion: net.mailbox, profileName: "Phone B", powBits: 8)
        await a.setProfileName("Phone A")
        let shown = await a.inviteForDisplay()
        let code = shown.code!
        XCTAssertFalse(shown.unavailable)
        XCTAssertTrue(net.queues.isEmpty, "the code is shown before any network call")
        await a.prepareInvites() // background work: queue, bundle, probe
        XCTAssertEqual(net.queues.count, 2, "the shown one plus the refill behind it")
        let again = await a.inviteForDisplay()
        XCTAssertNotEqual(again.code, code, "the next tap gets the prepared fresh code")
        guard case .mailbox(let inv) = try InviteCode.parse(code) else { return XCTFail() }
        let cb = try await b.redeem(inv, firstText: "Hello A")
        XCTAssertTrue(cb.waiting)
        // A sees the HELLO, makes the contact, answers with STATE through B's mailbox
        await a.inviteTick()
        let ca = await a.contacts()
        XCTAssertEqual(ca.count, 1); XCTAssertEqual(ca[0].name, "Phone B"); XCTAssertTrue(ca[0].queueReady); XCTAssertTrue(ca[0].handshakeDone)
        let amsgs = await a.messages(ca[0].id)
        XCTAssertEqual(amsgs.map(\.text), ["Hello A"])
        // the used invite is gone; another one is prepared
        await a.prepareInvites()
        // B gets STATE (poll), contact completes
        _ = await b.poll()
        let cb2 = await b.contact(cb.id)
        XCTAssertEqual(cb2?.name, "Phone A"); XCTAssertEqual(cb2?.waiting, false); XCTAssertTrue(cb2?.hasMailbox ?? false)
        // chat both ways through the mailbox
        let m = try await b.send("ping", to: cb.id)
        XCTAssertEqual(m.status, .queued)
        _ = await a.poll()
        let got = await a.messages(ca[0].id).filter(\.incoming).map(\.text)
        XCTAssertEqual(got, ["Hello A", "ping"])
        _ = try await a.send("pong", to: ca[0].id)
        _ = await b.poll()
        let back = await b.messages(cb.id).filter(\.incoming).map(\.text)
        XCTAssertEqual(back, ["pong"])
        // a second redeem of the same code finds nothing
        do { _ = try await Engine(account: Account(entropy: [UInt8](repeating: 0x22, count: 16), registrationId: 3), kv: MemoryKeyValueStore(), transport: net,
                                   auth: NoOnionAuth(), mailboxOnion: net.mailbox, profileName: "C", powBits: 8).redeem(inv, firstText: "me too")
            // the bundle is still in the queue until A's ACK; here A acked everything, so this must be .used
            XCTFail("reused code") } catch { XCTAssertEqual(error as? EngineError, .used) }
        // own code
        let code2 = await a.inviteForDisplay(forceNew: true).code!
        await a.prepareInvites()
        guard case .mailbox(let own) = try InviteCode.parse(code2) else { return XCTFail() }
        do { _ = try await a.redeem(own, firstText: "x"); XCTFail() } catch { XCTAssertEqual(error as? EngineError, .own) }
    }

    func testCodeIsRefusedClearlyWhenServerHasNoPeek() async throws {
        let net = try FakeNet(); net.peekSupported = false
        let a = try engine(net, MemoryKeyValueStore())
        let first = await a.inviteForDisplay()
        XCTAssertNotNil(first.code, "unknown support: the code is shown at once")
        await a.prepareInvites() // the probe learns the mailbox has no PEEK and cleans up
        let second = await a.inviteForDisplay()
        XCTAssertTrue(second.unavailable); XCTAssertNil(second.code)
        XCTAssertTrue(net.queues.values.allSatisfy { $0.isEmpty }, "nothing left on the server")
    }

    func testMailboxPutWhenOffline() async throws {
        let net = try FakeNet()
        let e = try engine(net, MemoryKeyValueStore())
        let c = try await e.redeem(net.invite(), firstText: "Hi")
        // give the inviter a queue on the mailbox and tell the iPhone via STATE through the mailbox
        let inviterQueue = Codec.random(32)
        net.queues[Codec.hex(MailboxProtocol.queueId(secret: inviterQueue))] = []
        let st = Out().u8(5).str("Kotlin Peer").str(net.mailbox).bytes(MailboxProtocol.queueId(secret: inviterQueue)).str(net.inviterOnion).bytes(Codec.random(32)).done()
        let enc = try net.inviter.encrypt(channelId: net.helloInfo!.channel, plaintext: st)
        net.queues[Codec.hex(net.helloInfo!.queue)]!.append(("s1", PeerFrame.msg(channelId: net.helloInfo!.channel, ciphertextType: enc.type, ciphertext: enc.ciphertext)))
        _ = await e.poll()
        net.inviterOnline = false
        let m = try await e.send("left in mailbox", to: c.id)
        XCTAssertEqual(m.status, .queued)
        XCTAssertEqual(net.queues[Codec.hex(MailboxProtocol.queueId(secret: inviterQueue))]!.count, 1)
        // background: no direct attempt even when online
        net.inviterOnline = true
        await e.setForeground(false)
        let m2 = try await e.send("background", to: c.id)
        XCTAssertEqual(m2.status, .queued)
        XCTAssertEqual(net.received, [])
    }

    func testOwnInviteAndWrongIdentityRollBack() async throws {
        let net = try FakeNet()
        let kv = MemoryKeyValueStore()
        let e = try engine(net, kv)
        var bad = try net.invite(); bad = Invite(onion: bad.onion, secret: bad.secret, identityKey: [UInt8](repeating: 5, count: 33), authKey: bad.authKey)
        do { _ = try await e.redeem(bad, firstText: "x"); XCTFail() } catch { XCTAssertEqual(error as? EngineError, .mismatch) }
        let n = await e.contacts().count
        XCTAssertEqual(n, 0)
    }
}
