import XCTest
import SwellCore
@testable import SwellSignal

/// Cross-platform crypto interop against the Kotlin side (android/core InteropTest). No network.
/// Transcript format and replay rules are described in InteropTest.kt; fixtures in interop/fixtures.
/// `SWELL_INTEROP_GEN=1` (tools/interop-gen.sh) lets this side append its next step.
final class InteropTests: XCTestCase {
    let gen = ProcessInfo.processInfo.environment["SWELL_INTEROP_GEN"] == "1"
    let dir: URL = {
        if let d = ProcessInfo.processInfo.environment["SWELL_INTEROP_DIR"] { return URL(fileURLWithPath: d) }
        return URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .deletingLastPathComponent().deletingLastPathComponent().deletingLastPathComponent()
            .appendingPathComponent("interop/fixtures")
    }()

    enum Kind: String { case BUNDLE, HELLO, STATE, TEXT }
    let kinds: [Kind] = [.BUNDLE, .HELLO, .STATE, .TEXT, .TEXT]
    let mailbox = String(repeating: "m", count: 56)

    func account(_ who: String) -> Account {
        who == "K" ? Account(entropy: [UInt8](repeating: 0x4B, count: 16), registrationId: 1234)
                   : Account(entropy: [UInt8](repeating: 0x53, count: 16), registrationId: 5678)
    }

    func msg(_ o: Out, _ text: String) {
        o.raw([UInt8](repeating: 0x11, count: 16)).i64(1_700_000_000_000).i32(0).str(text)
    }

    func plain(_ kind: Kind, _ sender: String, _ step: Int) -> [UInt8] {
        let name = sender == "K" ? "Kotlin Peer" : "Swift Peer"
        let onion = sender == "K" ? String(repeating: "k", count: 56) : "" // an iPhone is mailbox-only
        let o = Out()
        switch kind {
        case .HELLO:
            o.u8(1).str(onion).str(name).str(mailbox).bytes([UInt8](repeating: 0x22, count: 32)).bytes([UInt8](repeating: 0x33, count: 32))
            msg(o, "hello from \(name)")
        case .STATE:
            o.u8(5).str(name).str(mailbox).bytes([UInt8](repeating: 0x44, count: 32)).str(onion).bytes([UInt8](repeating: 0x55, count: 32))
        case .TEXT:
            o.u8(2); msg(o, "step \(step) from \(name) \u{e9}\u{4e16}")
        case .BUNDLE: fatalError()
        }
        return o.done()
    }

    func parse(_ url: URL) -> [String: String] {
        guard let text = try? String(contentsOf: url, encoding: .utf8) else { return [:] }
        var m = [String: String]()
        for line in text.split(separator: "\n") {
            guard let i = line.firstIndex(of: "=") else { continue }
            m[String(line[..<i])] = String(line[line.index(after: i)...])
        }
        return m
    }

    func save(_ url: URL, _ p: [String: String]) throws {
        try (p.keys.sorted().map { "\($0)=\(p[$0]!)" }.joined(separator: "\n") + "\n").write(to: url, atomically: true, encoding: .utf8)
    }

    func testSafetyNumberMatchesKotlin() throws {
        let k = account("K").identityPublicKey, s = account("S").identityPublicKey
        let crypto = try LibSignalCrypto(account: account("S"), kv: MemoryKeyValueStore())
        let ours = try crypto.safetyNumber(local: s, remote: k)
        let theirs = try LibSignalCrypto(account: account("K"), kv: MemoryKeyValueStore()).safetyNumber(local: k, remote: s)
        XCTAssertEqual(ours, theirs, "symmetric")
        let golden = try String(contentsOf: dir.appendingPathComponent("safety.txt"), encoding: .utf8).trimmingCharacters(in: .whitespacesAndNewlines)
        XCTAssertEqual(ours, golden)
    }

    func testIdentityDerivationMatchesLibsignalKey() throws {
        let crypto = try LibSignalCrypto(account: account("S"), kv: MemoryKeyValueStore())
        XCTAssertEqual(crypto.identityKey, account("S").identityPublicKey)
    }

    func testKotlinInvites() throws { try run("kotlin_invites", inviter: "K", redeemer: "S") }
    func testSwiftInvites() throws { try run("swift_invites", inviter: "S", redeemer: "K") }

    func run(_ name: String, inviter: String, redeemer: String) throws {
        let url = dir.appendingPathComponent("\(name).txt")
        var p = parse(url)
        let me = "S"
        let channel = [UInt8](repeating: inviter == "K" ? 0xA1 : 0xB2, count: 16)
        var kv = MemoryKeyValueStore()
        var crypto = try LibSignalCrypto(account: account(me), kv: kv)
        var bundle: [UInt8]?

        for i in 1...5 {
            let kind = kinds[i - 1]
            let sender = i % 2 == 1 ? inviter : redeemer
            let has = p["step.\(i).ct"] != nil
            if sender == me {
                if has {
                    kv = try MemoryKeyValueStore.load(try Codec.unhex(p["step.\(i).kv"]!))
                    crypto = try LibSignalCrypto(account: account(me), kv: kv)
                    continue
                }
                guard gen else { return XCTFail("\(name) step \(i) missing; run tools/interop-gen.sh") }
                var ct: [UInt8], type = 0, pl = [UInt8]()
                switch kind {
                case .BUNDLE: ct = try crypto.makeBundle()
                case .HELLO:
                    pl = plain(kind, me, i)
                    ct = try crypto.startSession(channelId: channel, peerBundle: bundle!, firstMessage: pl); type = 3
                default:
                    pl = plain(kind, me, i)
                    (type, ct) = try crypto.encrypt(channelId: channel, plaintext: pl)
                }
                p["step.\(i).sender"] = me; p["step.\(i).kind"] = kind.rawValue; p["step.\(i).type"] = "\(type)"
                p["step.\(i).ct"] = Codec.hex(ct); p["step.\(i).plain"] = Codec.hex(pl); p["step.\(i).kv"] = Codec.hex(kv.dump())
                try save(url, p)
                return
            }
            if !has { if gen { return } else { return XCTFail("\(name) step \(i) missing; run tools/interop-gen.sh") } }
            let ct = try Codec.unhex(p["step.\(i).ct"]!)
            if kind == .BUNDLE {
                bundle = ct
                // Bundle layout (PROTOCOL.md section 4): the identity key is the 7th field; check it derives from the sender's entropy.
                let r = In(ct); _ = try r.i32(); _ = try r.i32(); _ = try r.bytes(); _ = try r.i32(); _ = try r.bytes(); _ = try r.bytes()
                XCTAssertEqual(try r.bytes(), account(sender).identityPublicKey, "\(name) bundle identity")
                continue
            }
            let type = Int(p["step.\(i).type"]!)!
            XCTAssertEqual(type, kind == .HELLO ? 3 : 2, "\(name) step \(i) type")
            let got = try crypto.decrypt(channelId: channel, type: type, ciphertext: ct)
            XCTAssertEqual(got, plain(kind, sender, i), "\(name) step \(i) plaintext")
            XCTAssertEqual(got, try Codec.unhex(p["step.\(i).plain"]!), "\(name) step \(i) golden plaintext")
        }
    }
}
