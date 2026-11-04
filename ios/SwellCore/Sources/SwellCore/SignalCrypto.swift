import Foundation

/// The session layer (PQXDH + Double Ratchet). The real implementation wraps LibSignalClient 0.86.x;
/// see docs/IOS-PLAN.md section 5. Nothing here hand-rolls the ratchet.
public protocol SignalCrypto {
    /// Our prekey bundle in the PROTOCOL.md section 4 layout, for the invite flow.
    func makeBundle() throws -> [UInt8]
    /// Starts a session from a peer bundle and returns the PreKeySignalMessage for HELLO.
    func startSession(channelId: [UInt8], peerBundle: [UInt8], firstMessage: [UInt8]) throws -> [UInt8]
    /// type 2 = whisper, 3 = prekey (PROTOCOL.md section 4, MSG).
    func encrypt(channelId: [UInt8], plaintext: [UInt8]) throws -> (type: Int, ciphertext: [UInt8])
    func decrypt(channelId: [UInt8], type: Int, ciphertext: [UInt8]) throws -> [UInt8]
    /// Safety number from NumericFingerprintGenerator(5200), version 2.
    func safetyNumber(local: [UInt8], remote: [UInt8]) throws -> String
}

/// Placeholder until LibSignalClient is linked. It refuses to run so nobody ships unencrypted chat.
public struct UnavailableSignalCrypto: SignalCrypto {
    public init() {}
    private func fail<T>() throws -> T { throw SwellError.crypto("LibSignalClient is not linked yet") }
    public func makeBundle() throws -> [UInt8] { try fail() }
    public func startSession(channelId: [UInt8], peerBundle: [UInt8], firstMessage: [UInt8]) throws -> [UInt8] { try fail() }
    public func encrypt(channelId: [UInt8], plaintext: [UInt8]) throws -> (type: Int, ciphertext: [UInt8]) { try fail() }
    public func decrypt(channelId: [UInt8], type: Int, ciphertext: [UInt8]) throws -> [UInt8] { try fail() }
    public func safetyNumber(local: [UInt8], remote: [UInt8]) throws -> String { try fail() }
}
