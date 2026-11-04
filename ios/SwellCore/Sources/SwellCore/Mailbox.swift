import Foundation
import CryptoKit
import CommonCrypto

/// Mailbox protocol v2 (PROTOCOL.md section 6). Frames: u8 VERSION=2 | u8 op | body.
public enum MailboxProtocol {
    public static let version = 2
    public static let put = 0x11, get = 0x12, ack = 0x13, create = 0x14, creds = 0x15, peek = 0x16, wait = 0x17
    public static let maxWaitMs = 25_000
    /// A newer mailbox accepts up to 64 queues in one WAIT; an older one only 8 (and rejects more).
    public static let maxWaitQueues = 64
    public static let rOK = 0x80, rList = 0x82, rCreds = 0x83, rReject = 0xFF
    public static let maxEnvelope = 80 * 1024
    public static let maxSecretsPerGet = 8
    public static let powBits = 22
    public static let credPowBits = 18

    /// queueId = SHA-256("swell-queue" || secret)
    public static func queueId(secret: [UInt8]) -> [UInt8] { Codec.sha256(Array("swell-queue".utf8) + secret) }

    static func powHash(queueId: [UInt8], nonce: Int64) -> [UInt8] {
        Codec.sha256(Array("swell-pow".utf8) + queueId + Out().i64(nonce).done())
    }
    static func credHash(minute: Int64, nonce: Int64) -> [UInt8] {
        Codec.sha256(Array("swell-cred".utf8) + Out().i64(minute).i64(nonce).done())
    }

    public static func leadingZeroBits(_ h: [UInt8], atLeast bits: Int) -> Bool {
        var left = bits
        for byte in h {
            if left <= 0 { return true }
            if left >= 8 { if byte != 0 { return false }; left -= 8 }
            else { return byte >> UInt8(8 - left) == 0 }
        }
        return left <= 0
    }

    public static func powOK(queueId: [UInt8], nonce: Int64, bits: Int) -> Bool {
        leadingZeroBits(powHash(queueId: queueId, nonce: nonce), atLeast: bits)
    }
    public static func credPowOK(minute: Int64, nonce: Int64, bits: Int) -> Bool {
        leadingZeroBits(credHash(minute: minute, nonce: nonce), atLeast: bits)
    }

    /// Returned by the solvers when the surrounding task was cancelled before a nonce was found.
    public static let powCancelled: Int64 = -1

    /// Brute-force from a random start (the server accepts any nonce; each CREDS nonce works once).
    /// Uses a fixed buffer and CommonCrypto so a phone does 22 bits in a few seconds.
    public static func solvePow(queueId: [UInt8], bits: Int = powBits) -> Int64 {
        search(prefix: Array("swell-pow".utf8) + queueId, bits: bits)
    }
    public static func solveCredPow(minute: Int64, bits: Int = credPowBits) -> Int64 {
        search(prefix: Array("swell-cred".utf8) + Out().i64(minute).done(), bits: bits)
    }

    private static func search(prefix: [UInt8], bits: Int) -> Int64 {
        var n = Int64.random(in: 0..<Int64.max / 2)
        var tries: UInt64 = 0
        var buf = prefix + [UInt8](repeating: 0, count: 8)
        let off = prefix.count, len = buf.count
        var digest = [UInt8](repeating: 0, count: Int(CC_SHA256_DIGEST_LENGTH))
        let fullBytes = bits / 8, restBits = bits % 8
        let mask: UInt8 = restBits == 0 ? 0 : 0xFF << UInt8(8 - restBits)
        return buf.withUnsafeMutableBufferPointer { b in
            digest.withUnsafeMutableBufferPointer { d in
                while true {
                    var v = UInt64(bitPattern: n)
                    for i in (0..<8).reversed() { b[off + i] = UInt8(truncatingIfNeeded: v); v >>= 8 }
                    CC_SHA256(b.baseAddress, CC_LONG(len), d.baseAddress)
                    var ok = true
                    for i in 0..<fullBytes where d[i] != 0 { ok = false; break }
                    if ok && (mask == 0 || d[fullBytes] & mask == 0) { return n }
                    n &+= 1
                    tries &+= 1
                    if tries & 0x3FFF == 0, Task.isCancelled { return powCancelled }
                }
            }
        }
    }

    // MARK: Requests

    public static func createRequest(secret: [UInt8], nonce: Int64) -> [UInt8] {
        Out().u8(version).u8(create).raw(secret).i64(nonce).done()
    }
    public static func putRequest(queueId: [UInt8], envelope: [UInt8]) -> [UInt8] {
        Out().u8(version).u8(put).raw(queueId).bytes(envelope).done()
    }
    public static func getRequest(secrets: [[UInt8]]) throws -> [UInt8] {
        guard (1...maxSecretsPerGet).contains(secrets.count) else { throw SwellError.format("count") }
        let o = Out().u8(version).u8(get).i32(Int32(secrets.count))
        secrets.forEach { o.raw($0) }
        return o.done()
    }
    public static func ackRequest(secret: [UInt8], ids: [String]) -> [UInt8] {
        let o = Out().u8(version).u8(ack).raw(secret).i32(Int32(ids.count))
        ids.forEach { o.str($0) }
        return o.done()
    }
    /// Read-only: the oldest envelope of a queue, by queue id (v3 invites). Needs mailbox 1.1; older servers reject it.
    public static func peekRequest(queueId: [UInt8]) -> [UInt8] { Out().u8(version).u8(peek).raw(queueId).done() }
    /// Long poll for up to 8 queues: answers like GET as soon as one has mail, or empty after `maxWaitMs`.
    public static func waitRequest(secrets: [[UInt8]], maxWaitMs: Int = maxWaitMs) throws -> [UInt8] {
        guard (1...maxWaitQueues).contains(secrets.count) else { throw SwellError.format("count") }
        let o = Out().u8(version).u8(wait).i32(Int32(max(0, min(maxWaitMs, Self.maxWaitMs)))).i32(Int32(secrets.count))
        secrets.forEach { o.raw($0) }
        return o.done()
    }
    public static func credsRequest(minute: Int64, nonce: Int64) -> [UInt8] {
        Out().u8(version).u8(creds).i64(minute).i64(nonce).done()
    }

    // MARK: Responses

    public struct Item: Equatable { public let index: Int; public let id: String; public let envelope: [UInt8] }
    public struct Credentials: Equatable {
        public let urls: [String]; public let username: String; public let password: String; public let expiresAtMillis: Int64
    }

    /// Parses a plain OK/reject answer. Anything but 0x80 is a rejection.
    public static func parseOK(_ r: [UInt8]) throws {
        guard r.count == 1, Int(r[0]) == rOK else { throw SwellError.rejected }
    }
    public static func parseList(_ r: [UInt8]) throws -> [Item] {
        let i = In(r)
        guard try i.u8() == rList else { throw SwellError.rejected }
        let n = Int(try i.i32())
        guard (0...50).contains(n) else { throw SwellError.format("count") }
        let items = try (0..<n).map { _ in Item(index: Int(try i.i32()), id: try i.str(max: 64), envelope: try i.bytes(max: maxEnvelope)) }
        try i.end()
        return items
    }
    public static func parseCreds(_ r: [UInt8]) throws -> Credentials {
        let i = In(r)
        guard try i.u8() == rCreds else { throw SwellError.rejected }
        let n = Int(try i.i32())
        guard (0...16).contains(n) else { throw SwellError.format("count") }
        let urls = try (0..<n).map { _ in try i.str(max: 512) }
        let c = Credentials(urls: urls, username: try i.str(max: 256), password: try i.str(max: 256), expiresAtMillis: try i.i64())
        try i.end()
        return c
    }
}

/// One request frame in, one answer frame out, over a Tor stream. The app supplies the Tor-backed implementation.
public protocol MailboxTransport {
    /// `isolation` becomes the SOCKS username so each call can use its own circuit.
    func roundTrip(onion: String, request: [UInt8], isolation: String) async throws -> [UInt8]
    /// Same, over a long-lived stream named `lane` (kept open between calls, reopened if it broke).
    /// Foreground use only. The default is a fresh stream per call.
    func warmRoundTrip(onion: String, request: [UInt8], lane: String, seconds: TimeInterval) async throws -> [UInt8]
    /// Opens the stream of `lane` ahead of the first request. The default does nothing.
    func warmUp(onion: String, lane: String) async
    /// Closes every long-lived stream (app goes to the background).
    func dropWarm() async
}

public extension MailboxTransport {
    func warmRoundTrip(onion: String, request: [UInt8], lane: String, seconds: TimeInterval) async throws -> [UInt8] {
        try await roundTrip(onion: onion, request: request, isolation: lane)
    }
    func warmUp(onion: String, lane: String) async {}
    func dropWarm() async {}
}

/// Mailbox client operations on top of a transport.
public struct MailboxClient {
    public let onion: String
    let transport: MailboxTransport
    public init(onion: String, transport: MailboxTransport) { self.onion = onion; self.transport = transport }

    private func call(_ req: [UInt8]) async throws -> [UInt8] {
        try await transport.roundTrip(onion: onion, request: req, isolation: Codec.hex(Codec.random(8)))
    }

    /// Creates our own queue (PoW at 22 bits, a few seconds of CPU on a phone). Do this off the main actor.
    public func create(secret: [UInt8], bits: Int = MailboxProtocol.powBits) async throws {
        let q = MailboxProtocol.queueId(secret: secret)
        let nonce = MailboxProtocol.solvePow(queueId: q, bits: bits)
        try MailboxProtocol.parseOK(try await call(MailboxProtocol.createRequest(secret: secret, nonce: nonce)))
    }
    public func put(queueId: [UInt8], envelope: [UInt8]) async throws {
        try MailboxProtocol.parseOK(try await call(MailboxProtocol.putRequest(queueId: queueId, envelope: envelope)))
    }
    /// Polling rule: one queue per request, each on its own circuit.
    public func fetch(secret: [UInt8]) async throws -> [MailboxProtocol.Item] {
        try MailboxProtocol.parseList(try await call(MailboxProtocol.getRequest(secrets: [secret])))
    }
    public func ack(secret: [UInt8], ids: [String]) async throws {
        guard !ids.isEmpty else { return }
        try MailboxProtocol.parseOK(try await call(MailboxProtocol.ackRequest(secret: secret, ids: ids)))
    }
    public func credentials(bits: Int = MailboxProtocol.credPowBits, now: Date = Date()) async throws -> MailboxProtocol.Credentials {
        let minute = Int64(now.timeIntervalSince1970 * 1000) / 60_000
        let nonce = MailboxProtocol.solveCredPow(minute: minute, bits: bits)
        return try MailboxProtocol.parseCreds(try await call(MailboxProtocol.credsRequest(minute: minute, nonce: nonce)))
    }
}
