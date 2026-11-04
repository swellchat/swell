import Foundation
import LibSignalClient

/// libsignal stores over [KeyValueStore]. Mirrors the Kotlin `KvSignalStore`: a changed identity key
/// for an existing channel is never trusted, and one-time pre-keys are deleted when used.
final class SignalStateStore: IdentityKeyStore, PreKeyStore, SignedPreKeyStore, KyberPreKeyStore, SessionStore, @unchecked Sendable {
    let kv: KeyValueStore
    private let identity: IdentityKeyPair
    private let registrationId: UInt32

    init(kv: KeyValueStore, identity: IdentityKeyPair, registrationId: UInt32) {
        self.kv = kv; self.identity = identity; self.registrationId = registrationId
    }

    private func a(_ address: ProtocolAddress) -> String { "\(address.name).\(address.deviceId)" }
    private func bytes(_ d: Data) -> [UInt8] { Array(d) }

    // MARK: identity
    func identityKeyPair(context: StoreContext) throws -> IdentityKeyPair { identity }
    func localRegistrationId(context: StoreContext) throws -> UInt32 { registrationId }

    func saveIdentity(_ identity: IdentityKey, for address: ProtocolAddress, context: StoreContext) throws -> IdentityChange {
        let new = bytes(identity.serialize())
        if let old = kv.get("idk", a(address)) {
            return old == new ? .newOrUnchanged : .replacedExisting // never overwritten silently
        }
        kv.put("idk", a(address), new)
        return .newOrUnchanged
    }

    func isTrustedIdentity(_ identity: IdentityKey, for address: ProtocolAddress, direction: Direction, context: StoreContext) throws -> Bool {
        guard let old = kv.get("idk", a(address)) else { return true }
        return old == bytes(identity.serialize())
    }

    func identity(for address: ProtocolAddress, context: StoreContext) throws -> IdentityKey? {
        try kv.get("idk", a(address)).map { try IdentityKey(bytes: $0) }
    }

    // MARK: pre-keys
    func loadPreKey(id: UInt32, context: StoreContext) throws -> PreKeyRecord {
        guard let b = kv.get("pk", "\(id)") else { throw SignalError.invalidKeyIdentifier("no pre-key \(id)") }
        return try PreKeyRecord(bytes: b)
    }
    func storePreKey(_ record: PreKeyRecord, id: UInt32, context: StoreContext) throws { kv.put("pk", "\(id)", bytes(record.serialize())) }
    func removePreKey(id: UInt32, context: StoreContext) throws { if kv.get("keep", "pk\(id)") == nil { kv.delete("pk", "\(id)") } }

    func loadSignedPreKey(id: UInt32, context: StoreContext) throws -> SignedPreKeyRecord {
        guard let b = kv.get("spk", "\(id)") else { throw SignalError.invalidKeyIdentifier("no signed pre-key \(id)") }
        return try SignedPreKeyRecord(bytes: b)
    }
    func storeSignedPreKey(_ record: SignedPreKeyRecord, id: UInt32, context: StoreContext) throws { kv.put("spk", "\(id)", bytes(record.serialize())) }

    func loadKyberPreKey(id: UInt32, context: StoreContext) throws -> KyberPreKeyRecord {
        guard let b = kv.get("kpk", "\(id)") else { throw SignalError.invalidKeyIdentifier("no kyber pre-key \(id)") }
        return try KyberPreKeyRecord(bytes: b)
    }
    func storeKyberPreKey(_ record: KyberPreKeyRecord, id: UInt32, context: StoreContext) throws { kv.put("kpk", "\(id)", bytes(record.serialize())) }
    /// Swell uses one-time Kyber pre-keys (one per invite), so a used key is deleted.
    func markKyberPreKeyUsed(id: UInt32, signedPreKeyId: UInt32, baseKey: PublicKey, context: StoreContext) throws {
        if kv.get("keep", "kpk\(id)") == nil { kv.delete("kpk", "\(id)") }
    }

    // MARK: sessions
    func loadSession(for address: ProtocolAddress, context: StoreContext) throws -> SessionRecord? {
        try kv.get("sess", a(address)).map { try SessionRecord(bytes: $0) }
    }
    func loadExistingSessions(for addresses: [ProtocolAddress], context: StoreContext) throws -> [SessionRecord] {
        try addresses.map { addr in
            guard let r = try loadSession(for: addr, context: context) else { throw SignalError.sessionNotFound("no session for \(addr.name)") }
            return r
        }
    }
    func storeSession(_ record: SessionRecord, for address: ProtocolAddress, context: StoreContext) throws {
        kv.put("sess", a(address), bytes(record.serialize()))
    }
}
