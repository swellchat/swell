import Foundation
import CryptoKit

/// Where reports go: a queue on a Jiji Labs mailbox and the public key reports are sealed to
/// (same string form as Android `swell.reportInbox`: `onion:queueIdHex:publicKeyHex`).
public struct ReportInbox: Equatable, Sendable {
    public let mailbox: String
    public let queueId: [UInt8]
    public let publicKey: [UInt8]
    public init(mailbox: String, queueId: [UInt8], publicKey: [UInt8]) { self.mailbox = mailbox; self.queueId = queueId; self.publicKey = publicKey }

    public init?(_ s: String) {
        let p = s.split(separator: ":").map(String.init)
        guard p.count == 3, p[0].utf8.count == 56, let q = try? Codec.unhex(p[1]), let k = try? Codec.unhex(p[2]),
              q.count == 32, k.count == 32 else { return nil }
        self.init(mailbox: p[0], queueId: q, publicKey: k)
    }
}

/// Sealed reports, byte-compatible with Kotlin `Report`: ephemeral X25519 + HKDF-SHA256 + AES-256-GCM.
/// Layout: `u8 1 | raw32 ephemeralPublic | raw12 nonce | bytes(ciphertext || 16-byte tag)`.
/// Key = HKDF(shared, salt = ephemeralPublic || inboxPublic, info = "swell report v1", 32).
public enum Report {
    static let info = Array("swell report v1".utf8)

    static func key(shared: SharedSecret, ephPub: [UInt8], inboxPub: [UInt8]) -> SymmetricKey {
        shared.hkdfDerivedSymmetricKey(using: SHA256.self, salt: Data(ephPub + inboxPub), sharedInfo: Data(info), outputByteCount: 32)
    }

    public static func seal(publicKey: [UInt8], plain: [UInt8]) throws -> [UInt8] {
        guard publicKey.count == 32 else { throw SwellError.format("report key") }
        let eph = Curve25519.KeyAgreement.PrivateKey()
        let ephPub = [UInt8](eph.publicKey.rawRepresentation)
        let shared = try eph.sharedSecretFromKeyAgreement(with: try Curve25519.KeyAgreement.PublicKey(rawRepresentation: publicKey))
        let box = try AES.GCM.seal(Data(plain), using: key(shared: shared, ephPub: ephPub, inboxPub: publicKey), nonce: AES.GCM.Nonce())
        return Out().u8(1).raw(ephPub).raw([UInt8](box.nonce)).bytes([UInt8](box.ciphertext) + [UInt8](box.tag)).done()
    }

    /// For tests and the inbox owner's tooling.
    public static func open(privateKey: [UInt8], envelope: [UInt8]) throws -> [UInt8] {
        let r = In(envelope)
        guard try r.u8() == 1 else { throw SwellError.format("report version") }
        let ephPub = try r.raw(32), nonce = try r.raw(12), ct = try r.bytes()
        try r.end()
        guard ct.count >= 16 else { throw SwellError.format("report size") }
        let priv = try Curve25519.KeyAgreement.PrivateKey(rawRepresentation: privateKey)
        let shared = try priv.sharedSecretFromKeyAgreement(with: try Curve25519.KeyAgreement.PublicKey(rawRepresentation: ephPub))
        let k = key(shared: shared, ephPub: ephPub, inboxPub: [UInt8](priv.publicKey.rawRepresentation))
        let box = try AES.GCM.SealedBox(nonce: AES.GCM.Nonce(data: nonce), ciphertext: ct.dropLast(16), tag: ct.suffix(16))
        return [UInt8](try AES.GCM.open(box, using: k))
    }

    /// Report body (Kotlin `Node.report`): `u8 1, i64 now, str reason, bytes identityKey, i32 n, (i64 at, u8 incoming, str text) * n`.
    public struct Entry: Sendable { public let sentAt: Int64; public let incoming: Bool; public let text: String
        public init(sentAt: Int64, incoming: Bool, text: String) { self.sentAt = sentAt; self.incoming = incoming; self.text = text } }

    public static func body(nowMillis: Int64, reason: String, identityKey: [UInt8], messages: [Entry]) -> [UInt8] {
        let list = Array(messages.prefix(50))
        let o = Out().u8(1).i64(nowMillis).str(String(reason.prefix(2000))).bytes(identityKey).i32(Int32(list.count))
        for m in list { o.i64(m.sentAt).u8(m.incoming ? 1 : 0).str(String(m.text.prefix(4000))) }
        return o.done()
    }
}
