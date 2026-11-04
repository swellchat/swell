import Foundation
import CryptoKit

public enum SwellError: Error, Equatable {
    case format(String)
    case rejected
    case transport(String)
    case crypto(String)
}

/// Binary writer. Same layout as Kotlin `Out`: big-endian integers, bytes/str carry an i32 length.
public final class Out {
    private var buf = [UInt8]()
    public init() {}
    @discardableResult public func u8(_ v: Int) -> Out { buf.append(UInt8(truncatingIfNeeded: v)); return self }
    @discardableResult public func i32(_ v: Int32) -> Out { raw(withUnsafeBytes(of: v.bigEndian, Array.init)) }
    @discardableResult public func i64(_ v: Int64) -> Out { raw(withUnsafeBytes(of: v.bigEndian, Array.init)) }
    @discardableResult public func raw(_ b: [UInt8]) -> Out { buf.append(contentsOf: b); return self }
    @discardableResult public func bytes(_ b: [UInt8]) -> Out { i32(Int32(b.count)); return raw(b) }
    @discardableResult public func str(_ s: String) -> Out { bytes(Array(s.utf8)) }
    public func done() -> [UInt8] { buf }
}

/// Bounded reader. Reads throw on underflow; `end()` rejects trailing bytes.
public final class In {
    private let b: [UInt8]
    private var pos = 0
    public init(_ b: [UInt8]) { self.b = b }
    public var remaining: Int { b.count - pos }

    public func u8() throws -> Int { Int(try raw(1)[0]) }
    public func i32() throws -> Int32 {
        let r = try raw(4); return Int32(bitPattern: r.reduce(UInt32(0)) { $0 << 8 | UInt32($1) })
    }
    public func i64() throws -> Int64 {
        let r = try raw(8); return Int64(bitPattern: r.reduce(UInt64(0)) { $0 << 8 | UInt64($1) })
    }
    public func raw(_ n: Int) throws -> [UInt8] {
        guard n >= 0, n <= remaining else { throw SwellError.format("short read") }
        defer { pos += n }
        return Array(b[pos..<pos + n])
    }
    public func bytes(max: Int = Wire.maxFrame) throws -> [UInt8] {
        let n = Int(try i32())
        guard n >= 0, n <= max else { throw SwellError.format("field size") }
        return try raw(n)
    }
    public func str(max: Int = 64 * 1024) throws -> String {
        guard let s = String(bytes: try bytes(max: max), encoding: .utf8) else { throw SwellError.format("utf8") }
        return s
    }
    public func end() throws { if remaining != 0 { throw SwellError.format("trailing bytes") } }
}

public enum Codec {
    public static func random(_ n: Int) -> [UInt8] {
        var g = SystemRandomNumberGenerator()
        return (0..<n).map { _ in UInt8.random(in: 0...255, using: &g) }
    }
    public static func hex(_ b: [UInt8]) -> String { b.map { String(format: "%02x", $0) }.joined() }
    public static func unhex(_ s: String) throws -> [UInt8] {
        let c = Array(s.utf8)
        guard c.count % 2 == 0 else { throw SwellError.format("hex") }
        return try stride(from: 0, to: c.count, by: 2).map { i in
            guard let v = UInt8(String(decoding: c[i..<i + 2], as: UTF8.self), radix: 16) else { throw SwellError.format("hex") }
            return v
        }
    }
    /// base64url without padding (Kotlin `Base64.getUrlEncoder().withoutPadding()`).
    public static func b64(_ b: [UInt8]) -> String {
        Data(b).base64EncodedString().replacingOccurrences(of: "+", with: "-")
            .replacingOccurrences(of: "/", with: "_").replacingOccurrences(of: "=", with: "")
    }
    public static func unb64(_ s: String) throws -> [UInt8] {
        var t = s.replacingOccurrences(of: "-", with: "+").replacingOccurrences(of: "_", with: "/")
        guard !t.contains("=") else { throw SwellError.format("base64") }
        while t.count % 4 != 0 { t += "=" }
        guard let d = Data(base64Encoded: t) else { throw SwellError.format("base64") }
        return [UInt8](d)
    }
    public static func sha256(_ b: [UInt8]) -> [UInt8] { Array(SHA256.hash(data: b)) }
}
