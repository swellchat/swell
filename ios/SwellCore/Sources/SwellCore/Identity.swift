import Foundation
import CryptoKit

/// BIP-39 English, 12 words = 128-bit entropy + 4-bit checksum.
public enum RecoveryPhrase {
    static let words: [String] = {
        guard let url = Bundle.module.url(forResource: "bip39-english", withExtension: "txt"),
              let text = try? String(contentsOf: url, encoding: .utf8) else { fatalError("wordlist missing") }
        let list = text.split(whereSeparator: \.isNewline).map { $0.trimmingCharacters(in: .whitespaces) }.filter { !$0.isEmpty }
        precondition(list.count == 2048, "wordlist")
        return list
    }()
    static let index: [String: Int] = Dictionary(uniqueKeysWithValues: words.enumerated().map { ($1, $0) })

    public static func fromEntropy(_ e: [UInt8]) throws -> [String] {
        guard e.count == 16 else { throw SwellError.format("entropy") }
        let cs = Int(Codec.sha256(e)[0] >> 4)
        // 132 bits = 128 entropy + 4 checksum, read 11 bits at a time.
        var bits = [Bool]()
        for byte in e { for k in (0..<8).reversed() { bits.append((byte >> UInt8(k)) & 1 == 1) } }
        for k in (0..<4).reversed() { bits.append((cs >> k) & 1 == 1) }
        return (0..<12).map { i in
            let v = bits[i * 11..<(i + 1) * 11].reduce(0) { $0 << 1 | ($1 ? 1 : 0) }
            return words[v]
        }
    }

    public static func toEntropy(_ phrase: [String]) throws -> [UInt8] {
        let ws = phrase.map { $0.trimmingCharacters(in: .whitespaces).lowercased() }.filter { !$0.isEmpty }
        guard ws.count == 12 else { throw SwellError.format("The recovery phrase has 12 words.") }
        var bits = [Bool]()
        for w in ws {
            guard let v = index[w] else { throw SwellError.format("Unknown word: \(w)") }
            for k in (0..<11).reversed() { bits.append((v >> k) & 1 == 1) }
        }
        let e = (0..<16).map { i in bits[i * 8..<(i + 1) * 8].reduce(UInt8(0)) { $0 << 1 | ($1 ? 1 : 0) } }
        let cs = bits[128..<132].reduce(0) { $0 << 1 | ($1 ? 1 : 0) }
        guard cs == Int(Codec.sha256(e)[0] >> 4) else { throw SwellError.format("The recovery phrase is not correct.") }
        return e
    }
}

/// Account entropy -> identity private key, same as Kotlin `Account.identity`.
public struct Account {
    public let entropy: [UInt8]
    public let registrationId: Int32

    public init(entropy: [UInt8], registrationId: Int32) { self.entropy = entropy; self.registrationId = registrationId }
    public static func create() -> Account { Account(entropy: Codec.random(16), registrationId: Int32.random(in: 1...0x3FFF)) }
    public static func restore(phrase: [String]) throws -> Account {
        Account(entropy: try RecoveryPhrase.toEntropy(phrase), registrationId: Int32.random(in: 1...0x3FFF))
    }

    public var phrase: [String] { (try? RecoveryPhrase.fromEntropy(entropy)) ?? [] }

    /// HKDF-SHA256(entropy, info = "swell identity v1", salt = 32 zero bytes, len = 32).
    public var identityPrivateKey: [UInt8] {
        let k = HKDF<SHA256>.deriveKey(inputKeyMaterial: SymmetricKey(data: entropy), salt: [UInt8](repeating: 0, count: 32),
                                       info: Array("swell identity v1".utf8), outputByteCount: 32)
        return k.withUnsafeBytes { Array($0) }
    }

    /// 33-byte serialized public key (0x05 || X25519 public), what libsignal's `ECPrivateKey.getPublicKey().serialize()` gives.
    public var identityPublicKey: [UInt8] {
        let priv = try! Curve25519.KeyAgreement.PrivateKey(rawRepresentation: identityPrivateKey)
        return [0x05] + Array(priv.publicKey.rawRepresentation)
    }
}
