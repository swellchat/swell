import Foundation
import CryptoKit

/// Invite v2: "swell:" + base64url( u8 2 | str onion | raw(32) secret | bytes identityKey | raw(32) authKey ).
/// Note: v2 invites carry the inviter's onion. Mailbox invites (v3) are a later addition (PROTOCOL.md section 8).
public struct Invite: Equatable {
    public static let prefix = "swell:"
    static let version = 2
    public static let maxTextBytes = 1024
    public let onion: String
    public let secret: [UInt8]
    public let identityKey: [UInt8]
    public let authKey: [UInt8]

    public init(onion: String, secret: [UInt8], identityKey: [UInt8], authKey: [UInt8]) {
        self.onion = onion; self.secret = secret; self.identityKey = identityKey; self.authKey = authKey
    }

    public func encode() -> String {
        Invite.prefix + Codec.b64(Out().u8(Invite.version).str(onion).raw(secret).bytes(identityKey).raw(authKey).done())
    }

    public static func decode(_ text: String) throws -> Invite {
        // Size first: a pasted string is never decoded before it is known to be plausible (v2 is about 220 characters).
        guard text.utf8.count <= maxTextBytes else { throw SwellError.format("invite too long") }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix(prefix) else { throw SwellError.format("not a Swell invite") }
        let r = In(try Codec.unb64(String(t.dropFirst(prefix.count))))
        guard try r.u8() == version else { throw SwellError.format("unsupported invite version") }
        let onion = try r.str(max: 64)
        guard onion.utf8.count == 56, onion.utf8.allSatisfy({ ($0 >= 97 && $0 <= 122) || ($0 >= 50 && $0 <= 55) }) else {
            throw SwellError.format("bad onion address")
        }
        let secret = try r.raw(32)
        let ik = try r.bytes(max: 64)
        let auth = try r.raw(32)
        try r.end()
        return Invite(onion: onion, secret: secret, identityKey: ik, authKey: auth)
    }
}

/// Mailbox invite (v3, made by an iPhone, which has no onion service). The code points at a queue on a mailbox:
/// `"swell:" + base64url( u8 3 | str mailboxOnion | raw32 queueId | raw32 sealKey | bytes identityKey )`.
/// docs/IOS-PLAN.md section 10. The inviter's prekey bundle waits in that queue, sealed with `sealKey`.
public struct MailboxInvite: Equatable {
    static let version = 3
    public let mailbox: String
    public let queueId: [UInt8]
    public let sealKey: [UInt8]
    public let identityKey: [UInt8]

    public init(mailbox: String, queueId: [UInt8], sealKey: [UInt8], identityKey: [UInt8]) {
        self.mailbox = mailbox; self.queueId = queueId; self.sealKey = sealKey; self.identityKey = identityKey
    }

    public func encode() -> String {
        Invite.prefix + Codec.b64(Out().u8(MailboxInvite.version).str(mailbox).raw(queueId).raw(sealKey).bytes(identityKey).done())
    }

    public static func decode(_ text: String) throws -> MailboxInvite {
        guard text.utf8.count <= Invite.maxTextBytes else { throw SwellError.format("invite too long") }
        let t = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard t.hasPrefix(Invite.prefix) else { throw SwellError.format("not a Swell invite") }
        let r = In(try Codec.unb64(String(t.dropFirst(Invite.prefix.count))))
        guard try r.u8() == version else { throw SwellError.format("unsupported invite version") }
        let mb = try r.str(max: 64)
        guard mb.utf8.count == 56, mb.utf8.allSatisfy({ ($0 >= 97 && $0 <= 122) || ($0 >= 50 && $0 <= 55) }) else { throw SwellError.format("bad mailbox address") }
        let q = try r.raw(32), seal = try r.raw(32), ik = try r.bytes(max: 64)
        try r.end()
        return MailboxInvite(mailbox: mb, queueId: q, sealKey: seal, identityKey: ik)
    }

    /// `raw12 nonce | AES-256-GCM(sealKey, bundle, aad = queueId)` (ciphertext followed by the 16-byte tag).
    public func seal(bundle: [UInt8]) throws -> [UInt8] {
        let box = try AES.GCM.seal(Data(bundle), using: SymmetricKey(data: sealKey), nonce: AES.GCM.Nonce(data: Codec.random(12)), authenticating: Data(queueId))
        guard let combined = box.combined else { throw SwellError.crypto("seal") }
        return Array(combined)
    }

    public func open(sealed: [UInt8]) throws -> [UInt8] {
        guard sealed.count >= 12 + 16 else { throw SwellError.format("sealed bundle") }
        do {
            let box = try AES.GCM.SealedBox(combined: Data(sealed))
            return Array(try AES.GCM.open(box, using: SymmetricKey(data: sealKey), authenticating: Data(queueId)))
        } catch { throw SwellError.format("sealed bundle") }
    }

    /// The envelope that holds the sealed bundle in the invite queue: `u8 2 | raw16 zero | u8 0 | bytes sealed`.
    /// Its second byte (0) tells it apart from a HELLO frame (type 2).
    public static func bundleEnvelope(sealed: [UInt8]) -> [UInt8] {
        Out().u8(2).raw([UInt8](repeating: 0, count: 16)).u8(0).bytes(sealed).done()
    }
    public static func sealedBundle(fromEnvelope e: [UInt8]) throws -> [UInt8] {
        let r = In(e)
        guard try r.u8() == 2 else { throw SwellError.format("envelope") }
        _ = try r.raw(16); _ = try r.u8()
        let s = try r.bytes(max: MailboxProtocol.maxEnvelope)
        return s
    }
}

/// What a pasted text, a scanned QR or a tapped link holds.
public enum InviteCode: Equatable {
    case direct(Invite)
    case mailbox(MailboxInvite)

    public static let linkPrefix = "https://jijilabsdigital.com/swell/i#"

    /// Finds an invite in any text: a link (`https://jijilabsdigital.com/swell/i#...`), a `swell:` code or a bare code,
    /// with any text, line breaks and spaces around it (a pasted message, a scanned QR, a tapped link).
    public static func parse(_ text: String) throws -> InviteCode {
        guard text.utf8.count <= 4096 else { throw SwellError.format("invite too long") }
        var candidates: [String] = []
        let b64 = CharacterSet(charactersIn: "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789-_")
        func tokens(_ s: String) -> [String] {
            var out: [String] = [], cur = ""
            for u in s.unicodeScalars {
                if b64.contains(u) || u == ":" || u == "#" || u == "/" || u == "." || u == "%" { cur.unicodeScalars.append(u) }
                else { if !cur.isEmpty { out.append(cur) }; cur = "" }
            }
            if !cur.isEmpty { out.append(cur) }
            return out
        }
        for tok in tokens(text) {
            var t = tok.removingPercentEncoding ?? tok
            if let i = t.firstIndex(of: "#"), t.lowercased().contains("jijilabsdigital.com") { t = String(t[t.index(after: i)...]) }
            if let r = t.range(of: "swell:") { t = String(t[r.lowerBound...]) } else { t = Invite.prefix + t }
            candidates.append(t)
        }
        // A link's fragment can hold the code as one token or be split by "swell:" -> also try the code after the last "#".
        for t in candidates.sorted(by: { $0.count > $1.count }) {
            let version = (try? Codec.unb64(String(t.dropFirst(Invite.prefix.count))).first) ?? nil
            switch version {
            case 2: if let i = try? Invite.decode(t) { return .direct(i) }
            case 3: if let i = try? MailboxInvite.decode(t) { return .mailbox(i) }
            default: continue
            }
        }
        throw SwellError.format("not a Swell invite")
    }

    public var link: String? {
        switch self {
        case .direct(let i): return InviteCode.linkPrefix + i.encode()
        case .mailbox(let i): return InviteCode.linkPrefix + i.encode()
        }
    }
}
