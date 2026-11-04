import Foundation
import LibSignalClient
import SwellCore

/// [SignalCrypto] on LibSignalClient 0.86.5: PQXDH (Kyber-1024 one-time pre-key) then the Double Ratchet.
/// Wire details follow docs/PROTOCOL.md: protocol address = (hex(channelId), 1); every plaintext is
/// padded to a size bucket before encryption; the bundle layout is section 4.
///
/// `firstMessage` and `plaintext` are the unpadded inner messages; padding is added and removed here
/// so no caller can forget it.
public final class LibSignalCrypto: SignalCrypto, @unchecked Sendable {
    private let store: SignalStateStore
    private let identity: IdentityKeyPair
    private let registrationId: Int32
    private let ctx = NullContext()
    private let lock = NSLock()
    private let now: () -> Date

    public static let signedPreKeyRotateSeconds: TimeInterval = 7 * 24 * 3600

    public init(account: Account, kv: KeyValueStore, now: @escaping () -> Date = Date.init) throws {
        let priv = try PrivateKey(Data(account.identityPrivateKey))
        identity = IdentityKeyPair(publicKey: priv.publicKey, privateKey: priv)
        registrationId = account.registrationId
        store = SignalStateStore(kv: kv, identity: identity, registrationId: UInt32(account.registrationId))
        self.now = now
    }

    public var identityKey: [UInt8] { Array(identity.identityKey.serialize()) }

    /// The identity key a session partner showed (saved on the first message), 33 bytes.
    public func remoteIdentity(channelId: [UInt8]) -> [UInt8]? { store.kv.get("idk", "\(Codec.hex(channelId)).1") }

    private func address(_ channelId: [UInt8]) throws -> ProtocolAddress {
        try ProtocolAddress(name: Codec.hex(channelId), deviceId: 1)
    }

    private func millis() -> UInt64 { UInt64(now().timeIntervalSince1970 * 1000) }
    private func randomId() -> UInt32 { UInt32.random(in: 1..<0x7FFF_FFF0) }

    /// Current signed pre-key, rotated after a week (older ones stay so late invites still work).
    private func signedPreKey() throws -> SignedPreKeyRecord {
        if let b = store.kv.get("acct", "spk"), b.count == 4 {
            let id = try In(b).i32()
            if let rec = try? store.loadSignedPreKey(id: UInt32(id), context: ctx),
               now().timeIntervalSince1970 - Double(rec.timestamp) / 1000 < Self.signedPreKeyRotateSeconds { return rec }
        }
        let pair = PrivateKey.generate()
        let sig = identity.privateKey.generateSignature(message: pair.publicKey.serialize())
        let rec = try SignedPreKeyRecord(id: randomId(), timestamp: millis(), privateKey: pair, signature: sig)
        try store.storeSignedPreKey(rec, id: rec.id, context: ctx)
        store.kv.put("acct", "spk", Out().i32(Int32(rec.id)).done())
        return rec
    }

    public func makeBundle() throws -> [UInt8] {
        try lock.withLock {
            let spk = try signedPreKey()
            let preKey = try PreKeyRecord(id: randomId(), privateKey: PrivateKey.generate())
            try store.storePreKey(preKey, id: preKey.id, context: ctx)
            let kem = KEMKeyPair.generate()
            let kemSig = identity.privateKey.generateSignature(message: kem.publicKey.serialize())
            let kyber = try KyberPreKeyRecord(id: randomId(), timestamp: millis(), keyPair: kem, signature: kemSig)
            try store.storeKyberPreKey(kyber, id: kyber.id, context: ctx)
            return Out().i32(registrationId)
                .i32(Int32(preKey.id)).bytes(Array(try preKey.publicKey().serialize()))
                .i32(Int32(spk.id)).bytes(Array(try spk.publicKey().serialize())).bytes(Array(spk.signature))
                .bytes(identityKey)
                .i32(Int32(kyber.id)).bytes(Array(kem.publicKey.serialize())).bytes(Array(kemSig))
                .done()
        }
    }

    private func decodeBundle(_ b: [UInt8]) throws -> PreKeyBundle {
        let r = In(b)
        let reg = try r.i32()
        let preId = try r.i32(); let pre = try PublicKey(try r.bytes(max: 64))
        let spkId = try r.i32(); let spk = try PublicKey(try r.bytes(max: 64)); let spkSig = try r.bytes(max: 128)
        let ik = try IdentityKey(bytes: try r.bytes(max: 64))
        let kyId = try r.i32(); let ky = try KEMPublicKey(try r.bytes(max: 4096)); let kySig = try r.bytes(max: 128)
        try r.end()
        return try PreKeyBundle(registrationId: UInt32(bitPattern: reg), deviceId: 1, prekeyId: UInt32(bitPattern: preId), prekey: pre,
                                signedPrekeyId: UInt32(bitPattern: spkId), signedPrekey: spk, signedPrekeySignature: spkSig,
                                identity: ik, kyberPrekeyId: UInt32(bitPattern: kyId), kyberPrekey: ky, kyberPrekeySignature: kySig)
    }

    public func startSession(channelId: [UInt8], peerBundle: [UInt8], firstMessage: [UInt8]) throws -> [UInt8] {
        try lock.withLock {
            let addr = try address(channelId)
            let bundle = try decodeBundle(peerBundle)
            try processPreKeyBundle(bundle, for: addr, sessionStore: store, identityStore: store, now: now(), context: ctx)
            return try encryptLocked(addr, firstMessage).ciphertext
        }
    }

    private func encryptLocked(_ addr: ProtocolAddress, _ plaintext: [UInt8]) throws -> (type: Int, ciphertext: [UInt8]) {
        let msg = try signalEncrypt(message: try Padding.pad(plaintext), for: addr, sessionStore: store, identityStore: store, now: now(), context: ctx)
        return (Int(msg.messageType.rawValue), Array(msg.serialize()))
    }

    public func encrypt(channelId: [UInt8], plaintext: [UInt8]) throws -> (type: Int, ciphertext: [UInt8]) {
        try lock.withLock { try encryptLocked(try address(channelId), plaintext) }
    }

    public func decrypt(channelId: [UInt8], type: Int, ciphertext: [UInt8]) throws -> [UInt8] {
        try lock.withLock {
            let addr = try address(channelId)
            let padded: Data
            switch type {
            case 3:
                padded = try signalDecryptPreKey(message: try PreKeySignalMessage(bytes: ciphertext), from: addr, sessionStore: store,
                                                 identityStore: store, preKeyStore: store, signedPreKeyStore: store, kyberPreKeyStore: store, context: ctx)
            case 2:
                padded = try signalDecrypt(message: try SignalMessage(bytes: ciphertext), from: addr, sessionStore: store, identityStore: store, context: ctx)
            default: throw SwellError.crypto("unknown ciphertext type \(type)")
            }
            return try Padding.unpad(Array(padded))
        }
    }

    /// `NumericFingerprintGenerator(5200)`, version 2; each side passes its own identity key as the stable id.
    public func safetyNumber(local: [UInt8], remote: [UInt8]) throws -> String {
        let l = try IdentityKey(bytes: local), r = try IdentityKey(bytes: remote)
        let f = try NumericFingerprintGenerator(iterations: 5200).create(
            version: 2, localIdentifier: local, localKey: l.publicKey, remoteIdentifier: remote, remoteKey: r.publicKey)
        return f.displayable.formatted
    }
}
