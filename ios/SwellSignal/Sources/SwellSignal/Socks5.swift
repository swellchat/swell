import Foundation
import Network
import SwellCore

/// Minimal async wrapper over a TCP `NWConnection` (the loopback SOCKS port of our own Tor).
private final class Flag: @unchecked Sendable { var value = false }

public final class TCPStream: @unchecked Sendable {
    private let conn: NWConnection

    public init(port: UInt16) {
        conn = NWConnection(host: "127.0.0.1", port: NWEndpoint.Port(rawValue: port)!, using: .tcp)
    }

    public func open() async throws {
        try Task.checkCancellation()
        try await withTaskCancellationHandler { try await openInner() } onCancel: { conn.cancel() }
    }

    private func openInner() async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            let done = Flag()
            conn.stateUpdateHandler = { state in
                guard !done.value else { return }
                switch state {
                case .ready: done.value = true; k.resume()
                case .failed(let e): done.value = true; k.resume(throwing: SwellError.transport("socks port: \(e)"))
                case .cancelled: done.value = true; k.resume(throwing: SwellError.transport("cancelled"))
                default: break
                }
            }
            conn.start(queue: .global(qos: .userInitiated))
        }
    }

    public func write(_ bytes: [UInt8]) async throws {
        try Task.checkCancellation()
        try await withTaskCancellationHandler { try await writeInner(bytes) } onCancel: { conn.cancel() }
    }

    private func writeInner(_ bytes: [UInt8]) async throws {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<Void, Error>) in
            conn.send(content: Data(bytes), completion: .contentProcessed { e in
                if let e { k.resume(throwing: SwellError.transport("write: \(e)")) } else { k.resume() }
            })
        }
    }

    public func read(exactly n: Int) async throws -> [UInt8] {
        if n == 0 { return [] }
        try Task.checkCancellation()
        return try await withTaskCancellationHandler { try await readInner(n) } onCancel: { conn.cancel() }
    }

    private func readInner(_ n: Int) async throws -> [UInt8] {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<[UInt8], Error>) in
            conn.receive(minimumIncompleteLength: n, maximumLength: n) { data, _, _, e in
                if let e { k.resume(throwing: SwellError.transport("read: \(e)")) }
                else if let data, data.count == n { k.resume(returning: Array(data)) }
                else { k.resume(throwing: SwellError.transport("connection closed")) }
            }
        }
    }

    public func close() { conn.cancel() }
}

/// SOCKS5 with remote name resolution (needed for .onion), same handshake as Kotlin `Socks5`.
/// `isolation` is sent as the SOCKS username, so Tor (IsolateSOCKSAuth) uses a separate circuit per value.
public enum Socks5 {
    public static func connect(socksPort: UInt16, host: String, port: UInt16, isolation: String?) async throws -> TCPStream {
        let s = TCPStream(port: socksPort)
        do {
            try await s.open()
            if let isolation {
                try await s.write([5, 1, 2])
                let g = try await s.read(exactly: 2)
                guard g == [5, 2] else { throw SwellError.transport("socks auth refused") }
                let u = Array(isolation.utf8.prefix(255))
                try await s.write([1, UInt8(u.count)] + u + [1, UInt8(ascii: "x")])
                let a = try await s.read(exactly: 2)
                guard a[1] == 0 else { throw SwellError.transport("socks auth refused") }
            } else {
                try await s.write([5, 1, 0])
                let g = try await s.read(exactly: 2)
                guard g == [5, 0] else { throw SwellError.transport("socks auth refused") }
            }
            let h = Array(host.utf8)
            guard (1...255).contains(h.count) else { throw SwellError.transport("host") }
            try await s.write([5, 1, 0, 3, UInt8(h.count)] + h + [UInt8(port >> 8), UInt8(port & 0xFF)])
            let r = try await s.read(exactly: 4)
            guard r[1] == 0 else { throw SwellError.transport("socks connect failed: code \(r[1])") }
            switch r[3] {
            case 1: _ = try await s.read(exactly: 6)
            case 4: _ = try await s.read(exactly: 18)
            case 3: let n = try await s.read(exactly: 1)[0]; _ = try await s.read(exactly: Int(n) + 2)
            default: throw SwellError.transport("socks reply")
            }
            return s
        } catch {
            s.close()
            throw error
        }
    }
}

private final class Once: @unchecked Sendable {
    private let lock = NSLock(); private var done = false
    func first() -> Bool { lock.withLock { if done { return false }; done = true; return true } }
}

/// Runs `body`, giving up with a transport error once `seconds` have passed in total. At the deadline, and
/// when the calling task is cancelled, `body` is cancelled too; the socket helpers close their connection
/// on cancellation, so nothing is left blocked in a socket call.
public func withDeadline<T: Sendable>(_ seconds: TimeInterval, _ body: @escaping @Sendable () async throws -> T) async throws -> T {
    let once = Once()
    let work = Task { try await body() }
    return try await withTaskCancellationHandler {
        try await withCheckedThrowingContinuation { (k: CheckedContinuation<T, Error>) in
            let timer = Task {
                try? await Task.sleep(nanoseconds: UInt64(seconds * 1_000_000_000))
                if once.first() { work.cancel(); k.resume(throwing: SwellError.transport("timed out")) }
            }
            Task {
                do { let v = try await work.value; if once.first() { timer.cancel(); k.resume(returning: v) } }
                catch { if once.first() { timer.cancel(); k.resume(throwing: error) } }
            }
        }
    } onCancel: { work.cancel() }
}

/// Serializes use of one stream: a request holds it until its answer is read.
private final class AsyncLock: @unchecked Sendable {
    private let m = NSLock(); private var busy = false; private var waiters: [CheckedContinuation<Void, Never>] = []
    func lock() async {
        await withCheckedContinuation { (k: CheckedContinuation<Void, Never>) in
            m.lock()
            if !busy { busy = true; m.unlock(); k.resume() } else { waiters.append(k); m.unlock() }
        }
    }
    func unlock() {
        m.lock()
        if waiters.isEmpty { busy = false; m.unlock() } else { let k = waiters.removeFirst(); m.unlock(); k.resume() }
    }
}

/// One long-lived stream to an onion service. A request that finds the stream broken (the server closes idle
/// streams) reconnects once; a fresh stream that fails is a real error.
final class WarmLane: @unchecked Sendable {
    private let gate = AsyncLock()
    private let m = NSLock()
    private var stream: TCPStream?
    private func take() -> TCPStream? { m.withLock { stream } }
    private func put(_ s: TCPStream?) { m.withLock { stream = s } }

    func close() { let s = take(); put(nil); s?.close() }

    func connect(socks: UInt16, onion: String, port: UInt16, lane: String) async throws -> TCPStream {
        if let s = take() { return s }
        let s = try await Socks5.connect(socksPort: socks, host: onion + ".onion", port: port, isolation: lane)
        put(s); return s
    }

    func request(socks: UInt16, onion: String, port: UInt16, lane: String, frame: [UInt8], seconds: TimeInterval) async throws -> [UInt8] {
        await gate.lock(); defer { gate.unlock() }
        return try await withDeadline(seconds) {
            for attempt in 0..<2 {
                let reused = self.take() != nil
                do {
                    let s = try await self.connect(socks: socks, onion: onion, port: port, lane: lane)
                    try await s.write(Out().i32(Int32(frame.count)).raw(frame).done())
                    let n = Int(try In(try await s.read(exactly: 4)).i32())
                    guard (1...Wire.maxFrame).contains(n) else { throw SwellError.transport("bad frame size") }
                    return try await s.read(exactly: n)
                } catch {
                    self.close()
                    if Task.isCancelled || !reused || attempt == 1 { throw error }
                }
            }
            throw SwellError.transport("unreachable")
        }
    }
}

/// `MailboxTransport` over a local SOCKS5 port (our own Tor). One request frame in, one answer frame out,
/// on a fresh stream per call (`i32 length | payload` both ways, PROTOCOL.md section 1). Used for mailbox
/// operations and for direct peer frames (GET_BUNDLE, HELLO, MSG) to an Android onion service.
/// `isolation` becomes the SOCKS username, so Tor gives each value its own circuit.
public struct SocksTransport: MailboxTransport {
    let socksPort: @Sendable () async throws -> UInt16
    public var port: UInt16 = 80
    /// Total time allowed for one answer, like Android RESPONSE_DEADLINE_MS.
    public var responseSeconds: TimeInterval = 60

    private let lanes = LaneTable()
    public init(socksPort: @escaping @Sendable () async throws -> UInt16) { self.socksPort = socksPort }

    private final class LaneTable: @unchecked Sendable {
        private let m = NSLock(); private var t: [String: WarmLane] = [:]
        func lane(_ key: String) -> WarmLane { m.withLock { if let l = t[key] { return l }; let l = WarmLane(); t[key] = l; return l } }
        func adopt(_ from: String, _ to: String) -> Bool { m.withLock { guard let l = t[from] else { return false }; t[from] = nil; t[to]?.close(); t[to] = l; return true } }
        func close(_ key: String) { m.withLock { let l = t[key]; t[key] = nil; return l }?.close() }
        func closeAll() { m.withLock { let all = Array(t.values); t = [:]; return all }.forEach { $0.close() } }
    }

    private func checked(_ onion: String) throws {
        guard onion.count == 56, onion.allSatisfy({ $0.isASCII && ($0.isLowercase || ("2"..."7").contains($0)) }) else {
            throw SwellError.format("bad onion address")
        }
    }

    public func warmRoundTrip(onion: String, request: [UInt8], lane: String, seconds: TimeInterval) async throws -> [UInt8] {
        try checked(onion)
        guard (1...Wire.maxFrame).contains(request.count) else { throw SwellError.format("frame size") }
        let socks = try await socksPort()
        return try await lanes.lane(onion + "|" + lane).request(socks: socks, onion: onion, port: port, lane: lane, frame: request, seconds: seconds)
    }

    public func warmUp(onion: String, lane: String) async {
        guard (try? checked(onion)) != nil, let socks = try? await socksPort() else { return }
        _ = try? await withDeadline(60) { try await self.lanes.lane(onion + "|" + lane).connect(socks: socks, onion: onion, port: self.port, lane: lane) }
    }

    public func dropWarm() async { lanes.closeAll() }
    public func adopt(onion: String, spare: String, as lane: String) async -> Bool { lanes.adopt(onion + "|" + spare, onion + "|" + lane) }
    public func closeLane(onion: String, lane: String) async { lanes.close(onion + "|" + lane) }

    public func roundTrip(onion: String, request: [UInt8], isolation: String) async throws -> [UInt8] {
        guard onion.count == 56, onion.allSatisfy({ $0.isASCII && ($0.isLowercase || ("2"..."7").contains($0)) }) else {
            throw SwellError.format("bad onion address")
        }
        guard (1...Wire.maxFrame).contains(request.count) else { throw SwellError.format("frame size") }
        let socks = try await socksPort()
        let seconds = responseSeconds
        let port = self.port
        return try await withDeadline(seconds) {
            let stream = try await Socks5.connect(socksPort: socks, host: onion + ".onion", port: port, isolation: isolation)
            defer { stream.close() }
            try await stream.write(Out().i32(Int32(request.count)).raw(request).done())
            let n = Int(try In(try await stream.read(exactly: 4)).i32())
            guard (1...Wire.maxFrame).contains(n) else { throw SwellError.transport("bad frame size") }
            return try await stream.read(exactly: n)
        }
    }
}
