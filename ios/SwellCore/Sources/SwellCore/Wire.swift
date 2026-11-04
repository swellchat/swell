import Foundation

/// Length-prefixed frames: i32 length + payload (1...320 KiB).
public enum Wire {
    public static let maxFrame = 320 * 1024

    public static func frame(_ payload: [UInt8]) throws -> [UInt8] {
        guard (1...maxFrame).contains(payload.count) else { throw SwellError.format("frame size") }
        return Out().i32(Int32(payload.count)).raw(payload).done()
    }

    /// Parses the length header. Returns the payload length, or throws on a bad size.
    public static func payloadLength(header: [UInt8]) throws -> Int {
        let n = Int(try In(header).i32())
        guard (1...maxFrame).contains(n) else { throw SwellError.format("bad frame size") }
        return n
    }
}

/// Plaintext is padded to a bucket before encryption: i32 length + data + zeros.
public enum Padding {
    public static let buckets = [1024, 4096, 16384, 65536, 262144]

    public static func pad(_ b: [UInt8]) throws -> [UInt8] {
        let need = b.count + 4
        guard let size = buckets.first(where: { $0 >= need }) else { throw SwellError.format("message too large") }
        return Out().i32(Int32(b.count)).raw(b).raw([UInt8](repeating: 0, count: size - need)).done()
    }

    public static func unpad(_ p: [UInt8]) throws -> [UInt8] {
        guard p.count >= 4 else { throw SwellError.format("padding") }
        let n = Int(try In(Array(p[0..<4])).i32())
        guard n >= 0, n <= p.count - 4 else { throw SwellError.format("padding") }
        return Array(p[4..<4 + n])
    }
}

/// Peer frames (PROTOCOL.md section 4). An envelope in a mailbox is a complete MSG frame.
public enum PeerFrame {
    public static let protocolVersion = 2
    public static let getBundle = 1, hello = 2, msg = 3, ping = 4
    public static let rOK = 0x80, rBundle = 0x81, rReject = 0xFF

    public static func msg(channelId: [UInt8], ciphertextType: Int, ciphertext: [UInt8]) -> [UInt8] {
        Out().u8(protocolVersion).u8(msg).raw(channelId).u8(ciphertextType).bytes(ciphertext).done()
    }
    public static func hello(secret: [UInt8], channelId: [UInt8], preKeyMessage: [UInt8]) -> [UInt8] {
        Out().u8(protocolVersion).u8(hello).raw(secret).raw(channelId).bytes(preKeyMessage).done()
    }
}
