import Foundation
import Network
import TorCore
import CTor
import SwellCore
import SwellSignal

/// Our Tor client (Tor.framework 409.15.3 = C Tor 0.4.9). Foreground only: no Network Extension, no VPN.
///
/// C Tor runs in-process on a thread and cannot be restarted in the same process, so the thread starts
/// once with `DisableNetwork 1`; `setForeground(true)` turns the network on and `false` turns it off
/// (the app is about to be suspended). The SOCKS and control ports listen on 127.0.0.1 only; the
/// control port needs the cookie file in our private data directory.
actor TorService {
    enum Status: Equatable { case stopped, starting, bootstrapping, ready, failed(String) }

    private(set) var status: Status = .stopped
    private var thread: TorThread?
    private var controller: TorController?
    private var socks: UInt16 = 0
    private var controlPort: UInt16 = 0
    private var observer: Any?
    private var readyWaiters: [UUID: CheckedContinuation<Void, Error>] = [:]

    private static func freePort() throws -> UInt16 {
        let fd = socket(AF_INET, SOCK_STREAM, 0)
        defer { close(fd) }
        var addr = sockaddr_in()
        addr.sin_family = sa_family_t(AF_INET)
        addr.sin_addr.s_addr = UInt32(0x7F00_0001).bigEndian
        addr.sin_port = 0
        let ok = withUnsafePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { bind(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
        var len = socklen_t(MemoryLayout<sockaddr_in>.size)
        let got = withUnsafeMutablePointer(to: &addr) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { getsockname(fd, $0, &len) } }
        guard ok == 0, got == 0 else { throw SwellError.transport("no free port") }
        return UInt16(bigEndian: addr.sin_port)
    }

    /// Starts Tor once and connects the control port. Returns when the control connection is authenticated.
    func start() async throws {
        guard status == .stopped || { if case .failed = status { return true } else { return false } }() else { return }
        status = .starting
        do {
            let base = try FileManager.default.url(for: .applicationSupportDirectory, in: .userDomainMask, appropriateFor: nil, create: true)
                .appendingPathComponent("tor", isDirectory: true)
            try FileManager.default.createDirectory(at: base, withIntermediateDirectories: true, attributes: [.protectionKey: FileProtectionType.completeUntilFirstUserAuthentication])
            var exclude = URLResourceValues(); exclude.isExcludedFromBackup = true
            var baseURL = base; try baseURL.setResourceValues(exclude)
            let cache = try FileManager.default.url(for: .cachesDirectory, in: .userDomainMask, appropriateFor: nil, create: true).appendingPathComponent("tor", isDirectory: true)
            try FileManager.default.createDirectory(at: cache, withIntermediateDirectories: true)

            if thread == nil { socks = try Self.freePort(); controlPort = try Self.freePort() }
            let control = controlPort
            let conf = TorConfiguration()
            conf.ignoreMissingTorrc = true
            conf.cookieAuthentication = true
            conf.clientOnly = true
            conf.avoidDiskWrites = true
            conf.dataDirectory = base
            conf.cacheDirectory = cache
            conf.socksPort = UInt(socks)
            // Booted with the network off; setForeground(true) turns it on.
            conf.arguments.addObjects(from: ["--ControlPort", "127.0.0.1:\(control)", "--DisableNetwork", "1", "--SocksPolicy", "accept 127.0.0.1", "--Log", "notice stderr"])
            // C Tor allows one TorThread per process: a retry after a failed start must reuse the running one.
            if thread == nil {
                // A cookie left by a previous run would be read before Tor rewrites it (authentication mismatch).
                try? FileManager.default.removeItem(at: base.appendingPathComponent("control_auth_cookie"))
                let t = TorThread(configuration: conf)
                t.start()
                thread = t
            }

            // The cookie appears once Tor has opened its control port.
            var cookie: Data?
            for _ in 0..<150 { if let c = conf.cookie, !c.isEmpty { cookie = c; break }; try await Task.sleep(nanoseconds: 100_000_000) }
            guard let cookie else { throw SwellError.transport("tor did not start") }
            // TorController connects inside init (a second connect() returns NO), so retry by making a new one.
            var ctl = TorController(socketHost: "127.0.0.1", port: control)
            var connected = ctl.isConnected
            for _ in 0..<50 where !connected {
                try await Task.sleep(nanoseconds: 100_000_000)
                ctl = TorController(socketHost: "127.0.0.1", port: control)
                connected = ctl.isConnected
            }
            guard connected else { throw SwellError.transport("tor control port") }
            try await Self.bool { ctl.authenticate(with: cookie, completion: $0) }
            observer = ctl.addObserver(forCircuitEstablished: { [weak self] established in
                if established { Task { await self?.markReady() } }
            })
            controller = ctl
            status = .bootstrapping
        } catch {
            status = .failed("\(error)")
            throw error
        }
    }

    private static func bool(_ call: (@escaping (Bool, Error?) -> Void) -> Void) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            call { ok, e in if ok { k.resume() } else { k.resume(throwing: e ?? SwellError.transport("tor refused the command")) } }
        }
    }

    private func markReady() {
        status = .ready
        readyWaiters.values.forEach { $0.resume() }
        readyWaiters = [:]
    }

    /// App came to the foreground (true) or is about to leave it (false).
    func setForeground(_ on: Bool) async throws {
        if on { try await start() }
        guard let controller else { return }
        try await Self.bool { controller.setConfForKey("DisableNetwork", withValue: on ? "0" : "1", completion: $0) }
        if !on { status = .bootstrapping } // circuits are torn down; they are rebuilt on the next foreground
    }

    /// Waits until Tor has built a circuit (about 5 to 60 s on a cold start), then returns the SOCKS port.
    func socksPort(timeout: TimeInterval = 120) async throws -> UInt16 {
        if status != .ready { try await withDeadline(timeout) { [self] in try await self.waitReady() } }
        return socks
    }

    /// A waiter is resumed exactly once: by `markReady`, or removed and resumed with a cancellation error when
    /// the waiting task is cancelled (timeout, app left the foreground), so none stays registered.
    private func waitReady() async throws {
        let id = UUID()
        try await withTaskCancellationHandler {
            try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
                if Task.isCancelled { k.resume(throwing: CancellationError()) }
                else if status == .ready { k.resume() }
                else { readyWaiters[id] = k }
            }
        } onCancel: { Task { await self.dropWaiter(id) } }
    }

    private func dropWaiter(_ id: UUID) { readyWaiters.removeValue(forKey: id)?.resume(throwing: CancellationError()) }

    /// 0...100 from Tor's own bootstrap report (nil if it cannot be read yet).
    func bootstrapPercent() async -> Int? {
        guard let controller, status != .ready else { return status == .ready ? 100 : nil }
        return await withCheckedContinuation { (k: CheckedContinuation<Int?, Never>) in
            controller.getInfoForKeys(["status/bootstrap-phase"]) { values in
                let s = values.first ?? ""
                if let r = s.range(of: "PROGRESS="), let n = Int(s[r.upperBound...].prefix(while: { $0.isNumber })) { k.resume(returning: n) } else { k.resume(returning: nil) }
            }
        }
    }

    /// Registers (or with `nil` removes) the x25519 private key for reaching a client-authorized onion
    /// service, like Android `TorControlHost.clientAuth`: `ONION_CLIENT_AUTH_ADD <onion> x25519:<base64>`.
    func clientAuth(onion: String, privateKey: [UInt8]?) async throws {
        guard let controller else { throw SwellError.transport("tor is not running") }
        let args: [String]
        let command: String
        if let privateKey {
            guard privateKey.count == 32 else { throw SwellError.format("auth key") }
            command = "ONION_CLIENT_AUTH_ADD"
            args = [onion, "x25519:" + Data(privateKey).base64EncodedString()]
        } else {
            command = "ONION_CLIENT_AUTH_REMOVE"
            args = [onion]
        }
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            controller.sendCommand(command, arguments: args, data: nil) { codes, _, stop in
                stop.pointee = true
                // Tor control-spec: 250 = done; 251 = existing credentials were replaced (ADD) or there were none
                // to remove (REMOVE); 252 = added and a cached descriptor was decrypted. All three are success.
                if let c = codes.first?.intValue, c == 250 || c == 251 || c == 252 { k.resume() }
                else { k.resume(throwing: SwellError.transport("\(command) refused (\(codes.first?.intValue ?? 0))")) }
                return true
            }
        }
    }

    /// The newest finished circuit that ends at an onion service: the country of each relay on the way, entry first
    /// (nil when Tor is not ready or reports nothing). Read from Tor's control port; nothing is sent anywhere.
    func route() async -> [String]? {
        guard let controller, status == .ready else { return nil }
        func info(_ key: String) async -> String {
            await withCheckedContinuation { (k: CheckedContinuation<String, Never>) in
                controller.getInfoForKeys([key]) { k.resume(returning: $0.first ?? "") }
            }
        }
        let lines = (await info("circuit-status")).split(separator: "\n").map(String.init)
        // "<id> BUILT $FP~nick,$FP~nick,... PURPOSE=HS_CLIENT_REND ..." : prefer an onion-service circuit, else any built one.
        let built = lines.filter { $0.contains(" BUILT ") }
        guard let line = built.last(where: { $0.contains("HS_CLIENT") }) ?? built.last,
              let path = line.split(separator: " ").dropFirst(2).first else { return nil }
        var out: [String] = []
        for hop in path.split(separator: ",") {
            let fp = hop.split(separator: "~").first.map { String($0).trimmingCharacters(in: CharacterSet(charactersIn: "$")) } ?? ""
            let ns = await info("ns/id/\(fp)")
            let ip = ns.split(separator: "\n").first(where: { $0.hasPrefix("r ") })?.split(separator: " ").dropFirst(5).first.map(String.init) ?? ""
            let cc = ip.isEmpty ? "" : (await info("ip-to-country/\(ip)")).uppercased()
            out.append(cc.isEmpty || cc == "??" ? "" : cc)
        }
        return out
    }
}
