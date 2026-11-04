import Foundation
import Security
import SwiftUI
import CryptoKit
import SwellCore
import SwellSignal

/// Keychain items, this device only, never in a backup. Readable after the first unlock so a later
/// background wake-up can still work.
enum KeychainStore {
    struct Failure: Error, LocalizedError { let status: OSStatus; var errorDescription: String? { "Keychain error \(status)" } }
    private static let service = "swell.account"

    private static func query(_ account: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
    }

    /// Update-or-add: an existing item is never deleted before the new value is stored. Every status is checked.
    static func save(_ data: Data, account: String = "entropy") throws {
        let q = query(account)
        var st = SecItemUpdate(q as CFDictionary, [kSecValueData as String: data] as CFDictionary)
        if st == errSecItemNotFound {
            var a = q
            a[kSecValueData as String] = data
            a[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
            st = SecItemAdd(a as CFDictionary, nil)
        }
        guard st == errSecSuccess else { throw Failure(status: st) }
        guard try load(account: account) == data else { throw Failure(status: errSecInternalError) }
    }

    /// nil only when the item does not exist; any other failure throws (the caller must not treat it as "missing").
    static func load(account: String = "entropy") throws -> Data? {
        var q = query(account)
        q[kSecReturnData as String] = true
        var out: CFTypeRef?
        let st = SecItemCopyMatching(q as CFDictionary, &out)
        if st == errSecItemNotFound { return nil }
        guard st == errSecSuccess, let d = out as? Data else { throw Failure(status: st == errSecSuccess ? errSecInternalError : st) }
        return d
    }

    static func wipe() throws {
        let st = SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service] as CFDictionary)
        guard st == errSecSuccess || st == errSecItemNotFound else { throw Failure(status: st) }
        guard try load(account: "entropy") == nil, try load(account: "storekey") == nil else { throw Failure(status: errSecInternalError) }
    }
}

struct TorAuth: OnionAuth {
    let tor: TorService
    func setClientAuth(onion: String, privateKey: [UInt8]?) async throws { try await tor.clientAuth(onion: onion, privateKey: privateKey) }
}

@MainActor final class AppModel: ObservableObject {
    enum Stage { case welcome, phrase, restore, name, ready, locked }
    @Published var stage: Stage
    @Published var account: Account?
    @Published var name: String = UserDefaults.standard.string(forKey: "name") ?? ""
    @Published var contacts: [Contact] = []
    @Published var messages: [ChatMessage] = []
    @Published var network = "Not connected"
    @Published var networkOK = false
    @Published var problem: String?
    @Published var adding = false
    /// Profile photos (JPEG) by contact id, and ours.
    @Published var avatars: [String: Data] = [:]
    @Published var myPhoto: Data?
    /// A tapped invite link waiting for the user's confirmation.
    @Published var pendingInvite: InviteCode?
    @Published var theme = SwellTheme.current { didSet { UserDefaults.standard.set(theme.rawValue, forKey: "theme"); themeTick += 1 } }
    @Published var accent = SwellAccent.current { didSet { UserDefaults.standard.set(accent.rawValue, forKey: "accent"); themeTick += 1 } }
    @Published var themeTick = 0
    @Published var appLock = UserDefaults.standard.bool(forKey: "applock") { didSet { UserDefaults.standard.set(appLock, forKey: "applock") } }
    @Published var screenSecurity = UserDefaults.standard.object(forKey: "screensec") as? Bool ?? true { didSet { UserDefaults.standard.set(screenSecurity, forKey: "screensec") } }
    @Published var autoplay = UserDefaults.standard.object(forKey: "autoplay") as? Bool ?? true { didSet { UserDefaults.standard.set(autoplay, forKey: "autoplay") } }
    @Published var phraseHidden = UserDefaults.standard.bool(forKey: "phraseHidden") { didSet { UserDefaults.standard.set(phraseHidden, forKey: "phraseHidden") } }
    /// Chat-list flags (1 pinned, 2 archived, 4 marked unread), kept on this phone only.
    @Published var flags: [String: UInt8] = [:]
    func setFlag(_ id: String, _ f: UInt8, _ on: Bool) { Task { try? await eng()?.setChatFlag(id, f, on); await reload() } }
    @Published var receipts = UserDefaults.standard.bool(forKey: "receipts") {
        didSet { UserDefaults.standard.set(receipts, forKey: "receipts"); let on = receipts; Task { await eng()?.setReceipts(on) } }
    }
    @Published var typing = UserDefaults.standard.bool(forKey: "typing") {
        didSet { UserDefaults.standard.set(typing, forKey: "typing"); let on = typing; Task { await eng()?.setTyping(on) } }
    }
    /// Seconds from launch until Tor was ready (debug measurement).
    var readyAfter: Double?
    let launchedAt = Date()

    let tor = TorService()
    private var engine: Engine?
    /// The engine opens off the main thread; actions wait for it with `eng()`.
    private var opening: Task<Engine?, Never>?
    private var snapshotKey: [UInt8]?
    @Published var messagesLoaded = false
    private var store: EncryptedFileStore?
    private var openChat: String?
    private var loop: Task<Void, Never>?
    private var statusTask: Task<Void, Never>?
    private var expiryTask: Task<Void, Never>?
    private var deleting = false
    private var active = false

    init() {
        stage = .welcome
        start()
        #if DEBUG
        if CommandLine.arguments.contains("-swellWelcome") { stage = .welcome }
        if let i = CommandLine.arguments.firstIndex(of: "-swellTheme"), i + 1 < CommandLine.arguments.count { UserDefaults.standard.set(CommandLine.arguments[i + 1], forKey: "theme"); theme = SwellTheme.current }
        #endif
    }

    private func start() {
        problem = nil
        do {
            if let d = try KeychainStore.load(), d.count == 16 {
                stage = .ready
                loadSnapshot()
                beginOpening(entropy: [UInt8](d))
            } else {
                stage = .welcome
            }
        } catch {
            // Never treat a keychain error as "no account": that could create a second identity over the first.
            stage = .locked
            problem = "Keychain: \(error.localizedDescription)"
        }
    }

    func retryStart() { start(); if active, stage == .ready { startLoop(); startExpiry() } }

    // MARK: identity

    private nonisolated func storeURL() throws -> URL {
        var dir = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
            .appendingPathComponent("swell", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var v = URLResourceValues(); v.isExcludedFromBackup = true
        try dir.setResourceValues(v)
        return dir.appendingPathComponent("state.swk")
    }

    private nonisolated func filesURL() throws -> URL {
        var dir = try storeURL().deletingLastPathComponent().appendingPathComponent("files", isDirectory: true)
        try FileManager.default.createDirectory(at: dir, withIntermediateDirectories: true)
        var v = URLResourceValues(); v.isExcludedFromBackup = true
        try dir.setResourceValues(v)
        return dir
    }
    func fileURL(_ fileId: String) -> URL? {
        guard !fileId.isEmpty, fileId.allSatisfy({ $0.isHexDigit }), let d = try? filesURL() else { return nil }
        let a = d.appendingPathComponent(fileId + ".m4a")
        return FileManager.default.fileExists(atPath: a.path) ? a : d.appendingPathComponent(fileId + ".dat")
    }

    /// Upload progress by message id, for the bubble of a photo or file that is being sent.
    @Published var progress: [String: Int] = [:]

    /// Sends a photo or file. `prepared` photos are already JPEG without EXIF (PhotoPrep).
    func sendFile(_ data: Data, name: String, mime: String, caption: String = "", to id: String, replyTo: String = "") {
        Task {
            guard let engine = await eng() else { return }
            do { try await engine.sendFile([UInt8](data), name: name, mime: mime, caption: caption, to: id, replyTo: replyTo) }
            catch { problem = (error as? EngineError).map { "\($0)" } ?? "That could not be sent. \(error.localizedDescription)" }
            await reload()
        }
    }

    func sendVoice(_ data: Data, ms: Int, to id: String, replyTo: String = "") {
        
        Task {
            guard let engine = await eng() else { return }
            do { try await engine.sendVoice([UInt8](data), durationMs: ms, to: id, replyTo: replyTo) } catch { problem = "The voice note could not be sent. \(error.localizedDescription)" }
            await reload()
        }
    }

    /// The store key lives only in the Keychain. A new key is made only for a new store; if a store file exists
    /// but its key is missing, that is an error (the file cannot be opened), never a reason to make a new key.
    private nonisolated func storeKey() throws -> [UInt8] {
        if let k = try KeychainStore.load(account: "storekey") {
            guard k.count == 32 else { throw StoreError.corrupt }
            return [UInt8](k)
        }
        if FileManager.default.fileExists(atPath: try storeURL().path) { throw StoreError.corrupt }
        let k = Codec.random(32)
        try KeychainStore.save(Data(k), account: "storekey")
        return k
    }

    /// Opens the encrypted store and the engine on a background thread, so the first frame never waits for it.
    private func beginOpening(entropy: [UInt8]) {
        let tor = self.tor, profile = name
        opening = Task.detached(priority: .userInitiated) { [weak self] in
            guard let self else { return nil }
            do {
                let url = try self.storeURL(), key = try self.storeKey()
                let kv = try EncryptedFileStore(url: url, key: key)
                var reg: Int32
                if let b = kv.get("acct", "reg"), b.count == 4, let r = try? In(b).i32() { reg = r }
                else { reg = Int32.random(in: 1...0x3FFF); try kv.batch { kv.put("acct", "reg", Out().i32(reg).done()) } }
                let acct = Account(entropy: entropy, registrationId: reg)
                let e = try Engine(account: acct, kv: kv, transport: torTransport(tor), auth: TorAuth(tor: tor),
                                   mailboxOnion: Config.mailboxOnion, profileName: profile)
                let files = try? self.filesURL()
                await MainActor.run { self.store = kv; self.account = acct; self.engine = e; self.snapshotKey = key }
                if let files { await e.setFilesDirectory(files) }
                let (r, t) = await MainActor.run { (self.receipts, self.typing) }
                await e.setReceipts(r); await e.setTyping(t)
                await e.setOnProgress { [weak self] id, p in Task { @MainActor in if p >= 100 { self?.progress[id] = nil } else { self?.progress[id] = p } } }
                await e.setOnChange { [weak self] in Task { @MainActor in await self?.reload() } }
                await e.recoverInterrupted()
                await e.purgeExpired()
                await self.reload()
                #if DEBUG
                fputs("SWELL engine ready t=\(String(format: "%.3f", ProcessClock.sinceStart()))\n", stderr)
                #endif
                return e
            } catch {
                await MainActor.run { self.problem = "The saved data could not be opened. Restore with your 12 words." }
                return nil
            }
        }
    }

    func eng() async -> Engine? {
        if let e = engine { return e }
        return await opening?.value
    }

    // MARK: first-frame snapshot (chat list), encrypted with the store key

    private nonisolated func snapshotURL() throws -> URL { try storeURL().deletingLastPathComponent().appendingPathComponent("list.swk") }

    private func loadSnapshot() {
        guard let k = try? KeychainStore.load(account: "storekey"), k.count == 32, let url = try? snapshotURL(),
              let file = try? Data(contentsOf: url), file.count > 28,
              let box = try? AES.GCM.SealedBox(combined: file), let plain = try? AES.GCM.open(box, using: SymmetricKey(data: k), authenticating: Data("swell-list-1".utf8)) else { return }
        snapshotKey = [UInt8](k)
        let r = In([UInt8](plain))
        guard let n = try? r.i32(), n >= 0, n < 5000 else { return }
        var cs: [Contact] = [], av: [String: Data] = [:]
        for _ in 0..<Int(n) {
            guard let b = try? r.bytes(max: 1 << 20), let a = try? r.bytes(max: 64 << 10), let c = try? Contact.decode(b) else { return }
            cs.append(c); if !a.isEmpty { av[c.id] = Data(a) }
        }
        contacts = cs; avatars = av
    }

    private func saveSnapshot() {
        guard let k = snapshotKey, let url = try? snapshotURL() else { return }
        let o = Out().i32(Int32(contacts.count))
        for c in contacts { o.bytes(c.encode()).bytes(avatars[c.id].map { [UInt8]($0) } ?? []) }
        let plain = Data(o.done())
        Task.detached(priority: .utility) {
            guard let sealed = try? AES.GCM.seal(plain, using: SymmetricKey(data: k), authenticating: Data("swell-list-1".utf8)).combined else { return }
            try? sealed.write(to: url, options: [.atomic, .completeFileProtectionUntilFirstUserAuthentication])
        }
    }

    func createAccount() {
        let a = Account.create()
        do { try KeychainStore.save(Data(a.entropy)) } catch { problem = "Could not save your key in the iPhone keychain. Try again."; return }
        account = a
        stage = .phrase
    }

    func restore(words: String) throws {
        let a = try Account.restore(phrase: words.split(separator: " ").map(String.init))
        do { try KeychainStore.save(Data(a.entropy)) } catch { throw SwellError.format("Could not save your key in the iPhone keychain. Try again.") }
        account = a
        stage = .name
    }

    func saveName(_ n: String) {
        name = Engine.cleanName(n)
        UserDefaults.standard.set(name, forKey: "name")
        if let a = account { beginOpening(entropy: a.entropy) }
        stage = .ready
        if active { startLoop(); startExpiry() }
    }

    /// Disappearing messages: checked every few seconds while the app is open (independent of Tor), so an
    /// expired message leaves the open chat and the chat-list preview when its time is up.
    private func startExpiry() {
        guard expiryTask == nil else { return }
        expiryTask = Task { [weak self] in
            guard let engine = await self?.eng() else { return }
            while !Task.isCancelled {
                await engine.purgeExpired()
                try? await Task.sleep(nanoseconds: 3_000_000_000)
            }
        }
    }

    /// Deletes the account from this iPhone (App Store 5.1.1(v)): stops the loop, stops and awaits every engine
    /// operation, makes the store refuse writes, removes the file and the Keychain items, checks that they are
    /// gone, and only then returns to onboarding. Returns false (and keeps the screen) if something is left.
    @discardableResult
    func deleteAccount() async -> Bool {
        guard !deleting else { return false }
        deleting = true; defer { deleting = false }
        loop?.cancel(); statusTask?.cancel(); expiryTask?.cancel()
        loop = nil; statusTask = nil; expiryTask = nil
        store?.invalidate()
        await eng()?.shutdown()
        if let u = try? snapshotURL() { try? FileManager.default.removeItem(at: u) }
        var gone = store?.destroy() ?? true
        if store == nil, let u = try? storeURL() { // engine was never opened: still remove a stale file
            try? FileManager.default.removeItem(at: u); gone = !FileManager.default.fileExists(atPath: u.path)
        }
        do { try KeychainStore.wipe() } catch { gone = false }
        guard gone else {
            problem = "Some data could not be removed. Close Swell and try again."
            return false
        }
        store = nil; engine = nil; opening = nil; snapshotKey = nil
        UserDefaults.standard.removeObject(forKey: "name"); for k in ["phraseHidden", "applock", "theme", "accent"] { UserDefaults.standard.removeObject(forKey: k) }
        account = nil; contacts = []; messages = []; name = ""; openChat = nil; problem = nil
        stage = .welcome
        return true
    }

    // MARK: data

    func reload() async {
        #if DEBUG
        if CommandLine.arguments.contains("-swellDemo") {
            contacts = CommandLine.arguments.contains("-swellEmpty") ? [] : DemoData.contacts()
            if let id = openChat { messages = DemoData.messages(id) }
            return
        }
        #endif
        guard let engine = await eng() else { return }
        contacts = await engine.contacts()
        flags = await engine.chatFlags()
        #if DEBUG
        await debugDump(engine)
        #endif
        var av: [String: Data] = [:]
        for c in contacts { if let a = await engine.avatar(for: c.id) { av[c.id] = Data(a) } }
        avatars = av
        myPhoto = await engine.myAvatar().map { Data($0) }
        if let id = openChat { messages = await engine.messages(id); messagesLoaded = true }
        saveSnapshot()
    }

    func open(_ id: String) {
        openChat = id
        #if DEBUG
        if CommandLine.arguments.contains("-swellDemo") {
            messages = DemoData.messages(id)
            if let u = fileURL("bb"), !FileManager.default.fileExists(atPath: u.path) { // a drawn sea scene stands in for a photo
                let r = UIGraphicsImageRenderer(size: CGSize(width: 800, height: 600)).image { c in
                    let g = CGGradient(colorsSpace: nil, colors: [UIColor(red: 0.98, green: 0.72, blue: 0.45, alpha: 1).cgColor, UIColor(red: 0.2, green: 0.45, blue: 0.75, alpha: 1).cgColor] as CFArray, locations: [0, 0.55])!
                    c.cgContext.drawLinearGradient(g, start: .zero, end: CGPoint(x: 0, y: 600), options: [])
                    UIColor(red: 0.05, green: 0.2, blue: 0.4, alpha: 1).setFill(); c.fill(CGRect(x: 0, y: 330, width: 800, height: 270))
                }
                try? r.jpegData(compressionQuality: 0.85)?.write(to: u)
            }
            return
        }
        #endif
        messagesLoaded = false
        Task { let e = await eng(); await e?.setOpenChat(id); messages = await e?.messages(id) ?? []; messagesLoaded = true; await reload() }
    }
    func close(_ id: String) {
        if openChat == id { openChat = nil; messages = []; messagesLoaded = false }
        Task { await eng()?.setOpenChat(nil) }
    }

    func send(_ text: String, to id: String, replyTo: String = "") {
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !t.isEmpty else { return }
        Task {
            guard let engine = await eng() else { return }
            do { try await engine.send(t, to: id, replyTo: replyTo) } catch { problem = error.localizedDescription }
            await reload()
        }
    }

    func react(_ m: ChatMessage, _ emoji: String) { Task { await eng()?.react(m.id, in: m.contactId, emoji: emoji); await reload() } }
    func edit(_ m: ChatMessage, _ text: String) { act { try await $0.edit(m.id, in: m.contactId, text: text) } }
    func deleteForEveryone(_ m: ChatMessage) { act { try await $0.deleteForEveryone(m.id, in: m.contactId) } }
    func deleteHere(_ m: ChatMessage) { act { try await $0.deleteHere(m.id, in: m.contactId) } }
    func canEdit(_ m: ChatMessage) async -> Bool { await eng()?.canEdit(m) ?? false }
    func typing(_ on: Bool, in id: String) { Task { await eng()?.sendTyping(on, to: id) } }
    func search(_ q: String) async -> [ChatMessage] { await eng()?.search(q) ?? [] }

    /// Disappearing messages for a chat (0 = off). The timer applies here at once; if the other phone cannot be told
    /// right now, the user sees a short notice.
    func setDisappearing(_ id: String, seconds: Int) {
        
        Task {
            guard let engine = await eng() else { return }
            do { try await engine.setDisappearing(id, seconds: seconds) }
            catch { problem = "The timer is set on this iPhone. Your contact will get it when they are reachable." }
            await reload()
        }
    }

    func setName(_ n: String) {
        name = Engine.cleanName(n)
        UserDefaults.standard.set(name, forKey: "name")
        let v = name
        Task { await eng()?.setProfileName(v) }
    }

    /// Profile photo: a square 256x256 JPEG under 40 KiB (EXIF is dropped by re-encoding), sent to every contact.
    func setPhoto(_ image: UIImage?) {
        
        var jpeg: [UInt8]?
        if let image {
            let side: CGFloat = 256
            let fmt = UIGraphicsImageRendererFormat(); fmt.scale = 1; fmt.opaque = true
            let r = UIGraphicsImageRenderer(size: CGSize(width: side, height: side), format: fmt)
            let sq = min(image.size.width, image.size.height)
            let crop = CGRect(x: (image.size.width - sq) / 2, y: (image.size.height - sq) / 2, width: sq, height: sq)
            let img = r.image { _ in
                if let cg = image.cgImage?.cropping(to: CGRect(x: crop.minX * image.scale, y: crop.minY * image.scale, width: crop.width * image.scale, height: crop.height * image.scale)) {
                    UIImage(cgImage: cg, scale: 1, orientation: image.imageOrientation).draw(in: CGRect(x: 0, y: 0, width: side, height: side))
                } else { image.draw(in: CGRect(x: 0, y: 0, width: side, height: side)) }
            }
            var q: CGFloat = 0.8
            var d = img.jpegData(compressionQuality: q)
            while let x = d, x.count > 40 * 1024, q > 0.2 { q -= 0.1; d = img.jpegData(compressionQuality: q) }
            guard let d, d.count <= 40 * 1024 else { problem = "That photo could not be used."; return }
            jpeg = [UInt8](d)
        }
        Task {
            guard let engine = await eng() else { return }
            do { try await engine.setMyAvatar(jpeg) } catch { problem = "Could not save the photo." }
            await reload()
        }
    }

    /// "My code": instant. The invite was prepared in the background (keys, queue, sealed bundle); nothing here waits for the network.
    func myCode(new: Bool = false) async -> (code: String?, error: String?) {
        #if DEBUG
        if CommandLine.arguments.contains("-swellCodeFixture") { // screenshot fixture: a well-formed but unusable code
            return (MailboxInvite(mailbox: String(repeating: "a", count: 56), queueId: Codec.random(32), sealKey: Codec.random(32), identityKey: [UInt8](repeating: 5, count: 33)).encode(), nil)
        }
        #endif
        guard let engine = await eng() else { return (nil, "Not ready.") }
        let r = await engine.inviteForDisplay(forceNew: new)
        if r.unavailable { return (nil, "Codes cannot be made right now. Please try again later, or add a friend with their code.") }
        return (r.code, r.code == nil ? "Could not make a code. Please try again." : nil)
    }

    /// A tapped link or a scanned code: show the confirmation, add only after the user agrees.
    func handleLink(_ url: URL) {
        guard let code = try? InviteCode.parse(url.absoluteString) else { problem = "That link is not a Swell code."; return }
        pendingInvite = code
    }

    func retry(_ m: ChatMessage) { Task { await eng()?.retry(messageId: m.id, contactId: m.contactId); await reload() } }

    func add(code: String) async -> String? {
        guard let engine = await eng() else { return "Not ready." }
        adding = true; defer { adding = false }
        do {
            let parsed = try InviteCode.parse(code)
            await engine.setForeground(true)
            await engine.setProfileName(name)
            switch parsed {
            case .direct(let invite): _ = try await engine.redeem(invite, firstText: "Hi!")
            case .mailbox(let invite): _ = try await engine.redeem(invite, firstText: "Hi!")
            }
            await reload()
            return nil
        } catch SwellError.format { return "That is not a Swell code." }
        catch { return error.localizedDescription }
    }

    func safetyNumber(_ id: String) async -> String? { await eng()?.safetyNumber(id) }
    func setVerified(_ id: String, _ v: Bool) { act { try await $0.setVerified(id, v) } }
    func setBlocked(_ id: String, _ b: Bool) { act { try await $0.setBlocked(id, b) } }
    func rename(_ id: String, _ n: String) { act { try await $0.rename(id, n) } }
    func delete(_ id: String) { act { try await $0.delete(id) } }

    private func act(_ f: @escaping (Engine) async throws -> Void) {
        
        Task {
            guard let engine = await eng() else { return }
            do { try await f(engine) } catch { problem = "Could not save the change. Is the iPhone storage full?" }; await reload() }
    }

    /// Reports a contact to Jiji Labs (sealed, over Tor); optionally blocks them afterwards.
    func report(_ id: String, reason: String, note: String, includeLast: Int, block: Bool) async -> String? {
        guard let engine = await eng() else { return "Not ready." }
        guard let inbox = Config.reportInbox else { return "Reporting is not available in this build." }
        do {
            try await engine.report(id, reason: reason, note: note, includeLast: includeLast, inbox: inbox)
            if block { try await engine.setBlocked(id, true) }
            await reload()
            return nil
        } catch { return error.localizedDescription }
    }

    func recentIncoming(_ id: String, _ n: Int) async -> [ChatMessage] { await eng()?.recentIncoming(id, n) ?? [] }

    // MARK: foreground / network

    func scene(_ phase: ScenePhase) {
        #if DEBUG
        fputs("SWELL scene \(phase) t=\(String(format: "%.3f", ProcessClock.sinceStart())) args=\(CommandLine.arguments.dropFirst())\n", stderr)
        #endif
        switch phase {
        case .active:
            active = true
            if stage == .ready { startLoop(); startExpiry() }
        case .inactive:
            break // transient (control centre, system alert, launch); the network stays on until the app is backgrounded
        default:
            active = false
            loop?.cancel(); loop = nil; statusTask?.cancel(); statusTask = nil; expiryTask?.cancel(); expiryTask = nil
            Task { let e = await eng(); await e?.setForeground(false); await e?.dropWarm(); try? await tor.setForeground(false) }
            network = "Not connected"; networkOK = false
        }
    }

    /// Brings the network up quietly in the background. Nothing here shows in the main screens.
    private func startLoop() {
        guard loop == nil else { return }
        let tor = self.tor
        loop = Task { [weak self] in
            // Tor starts at once (its own thread); the engine may still be opening.
            let torUp = Task { do { try await tor.setForeground(true) } catch {
                #if DEBUG
                fputs("SWELL tor.setForeground failed: \(error)\n", stderr)
                #endif
                await MainActor.run { self?.network = "Tor did not start"; self?.networkOK = false }
            } }
            await MainActor.run { self?.network = "Connecting to Tor…"; self?.statusTask?.cancel(); self?.statusTask = Task { // status line (Settings > About only)
                while !Task.isCancelled {
                    let s = await tor.status
                    await MainActor.run {
                        switch s {
                        case .ready:
                            self?.network = "Connected"; self?.networkOK = true
                            if self?.readyAfter == nil, let t = self?.launchedAt { self?.readyAfter = Date().timeIntervalSince(t)
                                #if DEBUG
                                fputs("SWELL tor ready t=\(String(format: "%.2f", ProcessClock.sinceStart()))\n", stderr)
                                #endif
                            }
                        case .failed: self?.network = "Tor did not start"; self?.networkOK = false
                        default: self?.network = "Connecting to Tor…"; self?.networkOK = false
                        }
                    }
                    try? await Task.sleep(nanoseconds: 1_000_000_000)
                }
            } }
            guard let engine = await self?.eng() else { return }
            await engine.setForeground(true)
            _ = await torUp.value
            _ = try? await tor.socksPort()
            await engine.restoreAuth()
            await engine.prewarm() // opens the warm mailbox stream so the first send or receive does not pay for it
            #if DEBUG
            Task { await self?.debugActions() }
            #endif
            await engine.listen()  // live receive (WAIT); polls every 12 s if the mailbox is older
        }
    }

    /// Countries of the relays on the current route, for Settings > Network.
    func route() async -> [String]? { await tor.route() }

    func mailboxView() async -> [(queue: String, envelopes: Int, bytes: Int)] { await eng()?.mailboxView() ?? [] }

    /// A support report with no message content, names, contacts, keys or addresses: only the app version and counts.
    func exportDebugLog() -> URL? {
        let v = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "?"
        let b = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "?"
        var t = "Swell debug report\nversion \(v) (\(b))\niOS \(UIDevice.current.systemVersion), \(UIDevice.current.model)\n"
        t += "contacts \(contacts.count), blocked \(contacts.filter { $0.blocked }.count), verified \(contacts.filter { $0.verified }.count)\n"
        t += "chats with a ready queue \(contacts.filter { $0.queueReady }.count)\nreceipts \(receipts), typing \(typing), lock \(appLock), screen security \(screenSecurity)\n"
        t += "network \(networkOK ? "connected" : "not connected")\n"
        let url = FileManager.default.temporaryDirectory.appendingPathComponent("swell-debug-log.txt")
        try? t.write(to: url, atomically: true, encoding: .utf8)
        return url
    }

    #if DEBUG
    private var lastDump = ""
    /// Console trace for device tests: the newest messages of the first chat, so a script can check what arrived.
    private func debugDump(_ engine: Engine) async {
        guard CommandLine.arguments.contains("-swellTrace"), let c = contacts.first else { return }
        let ms = await engine.messages(c.id).suffix(8)
        var out = "SWELL chat caps=\(c.caps) waiting=\(c.waiting) name=\(c.displayName) exp=\(c.expireSec) avatar=\(avatars[c.id]?.count ?? 0)\n"
        for m in ms {
            out += "SWELL  \(m.incoming ? "<" : ">") id=\(m.id.prefix(6)) st=\(m.status.rawValue) dlv=\(m.delivered ? 1 : 0) rd=\(m.read ? 1 : 0) ed=\(m.edited ? 1 : 0) del=\(m.deleted ? 1 : 0) rx=\(m.myReaction)/\(m.theirReaction) re=\(m.replyTo.prefix(6)) ra=\(m.receivedAt) v=\(m.voiceMs)\(m.fileId.isEmpty ? "" : "f:\(m.fileMime):\(m.fileSize)") \(m.text)\n"
        }
        if out != lastDump { lastDump = out; fputs(out, stderr) }
    }

    /// Launch-argument actions for scripted device tests (Debug builds only), run once Tor is ready.
    private func debugActions() async {
        let a = CommandLine.arguments
        func val(_ k: String) -> String? { a.firstIndex(of: k).flatMap { $0 + 1 < a.count ? a[$0 + 1] : nil } }
        guard let engine = await eng() else { return }
        _ = try? await tor.socksPort()
        let t0 = Date()
        if let code = val("-swellAccept") { let e = await add(code: code); fputs("SWELL accept -> \(e ?? "ok") after \(Date().timeIntervalSince(t0))s\n", stderr) }
        if val("-swellReceipts") != nil { receipts = val("-swellReceipts") == "on" }
        if val("-swellTyping") != nil { typing = val("-swellTyping") == "on" }
        guard let id = (await engine.contacts()).first?.id else { return }
        if let s = val("-swellDisappear"), let n = Int(s) { setDisappearing(id, seconds: n) }
        if let t = val("-swellAutoSend") {
            let t1 = Date()
            _ = try? await engine.send(t, to: id)
            fputs("SWELL send done in \(Date().timeIntervalSince(t1))s\n", stderr)
        }
        if let t = val("-swellAutoReply"), let q = await engine.messages(id).last(where: { $0.incoming }) { _ = try? await engine.send(t, to: id, replyTo: q.id) }
        if let e = val("-swellAutoReact"), let q = await engine.messages(id).last(where: { $0.incoming }) { await engine.react(q.id, in: id, emoji: e) }
        if let t = val("-swellAutoEdit"), let q = await engine.messages(id).last(where: { !$0.incoming && $0.fileId.isEmpty }) { try? await engine.edit(q.id, in: id, text: t) }
        if a.contains("-swellAutoDelete"), let q = await engine.messages(id).last(where: { !$0.incoming }) { try? await engine.deleteForEveryone(q.id, in: id) }
        if let p = val("-swellAutoVoice"), let d = FileManager.default.contents(atPath: NSHomeDirectory() + "/Documents/" + p) {
            _ = try? await engine.sendVoice([UInt8](d), durationMs: 3000, to: id)
        }
        if let p = val("-swellAutoPhoto"), let d = FileManager.default.contents(atPath: NSHomeDirectory() + "/Documents/" + p), let j = PhotoPrep.jpeg(from: d) {
            let t1 = Date(); _ = try? await engine.sendFile([UInt8](j), name: PhotoPrep.name(), mime: "image/jpeg", to: id)
            fputs("SWELL photo \(j.count) bytes sent in \(Date().timeIntervalSince(t1))s\n", stderr)
        }
        if let p = val("-swellAutoFile"), let d = FileManager.default.contents(atPath: NSHomeDirectory() + "/Documents/" + p) {
            let t1 = Date(); _ = try? await engine.sendFile([UInt8](d), name: p, mime: p.hasSuffix(".pdf") ? "application/pdf" : "application/octet-stream", to: id)
            fputs("SWELL file \(d.count) bytes sent in \(Date().timeIntervalSince(t1))s\n", stderr)
        }
        if a.contains("-swellAutoNote") { _ = try? await engine.send("note test", to: Engine.noteId) }
        if a.contains("-swellAutoCode") { let t = Date(); let r = await myCode(); fputs("SWELL code in \(String(format: "%.4f", Date().timeIntervalSince(t)))s -> \(r.code ?? "" ?? r.error ?? "?")\n", stderr) }
        await reload()
    }
    #endif
}

/// Seconds since this process was started (for launch timing in Debug builds).
enum ProcessClock {
    static func sinceStart() -> Double {
        var info = kinfo_proc(); var size = MemoryLayout<kinfo_proc>.stride
        var mib: [Int32] = [CTL_KERN, KERN_PROC, KERN_PROC_PID, getpid()]
        guard sysctl(&mib, 4, &info, &size, nil, 0) == 0 else { return -1 }
        let t = info.kp_proc.p_un.__p_starttime
        return Date().timeIntervalSince1970 - (Double(t.tv_sec) + Double(t.tv_usec) / 1e6)
    }
}
