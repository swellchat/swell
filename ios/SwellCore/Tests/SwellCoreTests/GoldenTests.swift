import XCTest
@testable import SwellCore

/// Vectors come from the Kotlin side (android/core GoldenVectors.kt). A mismatch means iOS and Android disagree.
final class GoldenTests: XCTestCase {
    static let g: [String: String] = {
        let url = Bundle.module.url(forResource: "golden", withExtension: "json")!
        return try! JSONSerialization.jsonObject(with: Data(contentsOf: url)) as! [String: String]
    }()
    func v(_ k: String) -> String { Self.g[k]! }
    func seq(_ n: Int, _ s: Int) -> [UInt8] { (0..<n).map { UInt8(truncatingIfNeeded: s + $0) } }
    func hex(_ b: [UInt8]) -> String { Codec.hex(b) }

    func testOutEncoding() throws {
        let out = Out().u8(2).i32(-2).i64(0x0102030405060708).bytes(seq(5, 1)).str("héllo").raw(seq(3, 9)).done()
        XCTAssertEqual(hex(out), v("out_mixed"))
        let r = In(out)
        XCTAssertEqual(try r.u8(), 2); XCTAssertEqual(try r.i32(), -2); XCTAssertEqual(try r.i64(), 0x0102030405060708)
        XCTAssertEqual(try r.bytes(), seq(5, 1)); XCTAssertEqual(try r.str(), "héllo"); XCTAssertEqual(try r.raw(3), seq(3, 9))
        try r.end()
    }

    func testReaderRejectsBadInput() {
        XCTAssertThrowsError(try In([0, 0]).i32())
        XCTAssertThrowsError(try In([0, 0, 0, 9, 1]).bytes())
        XCTAssertThrowsError(try In([0, 0, 0, 0, 1]).end())
        XCTAssertThrowsError(try In([0xFF, 0xFF, 0xFF, 0xFF]).bytes())
    }

    func testPaddingBuckets() throws {
        for n in [0, 1, 1020, 1021, 4092, 4093, 16380, 65532, 65533, 262140] {
            let data = (0..<n).map { UInt8($0 % 251) }
            let p = try Padding.pad(data)
            XCTAssertEqual(String(p.count), v("pad_\(n)_size"), "size \(n)")
            XCTAssertEqual(hex(Array(p.prefix(16))), v("pad_\(n)_head"), "head \(n)")
            XCTAssertEqual(try Padding.unpad(p), data)
            XCTAssertTrue(p.dropFirst(4 + n).allSatisfy { $0 == 0 })
        }
        XCTAssertThrowsError(try Padding.pad([UInt8](repeating: 1, count: 262141)))
        XCTAssertThrowsError(try Padding.unpad([0, 0, 0, 9, 1]))
    }

    func testFrames() throws {
        XCTAssertEqual(try Wire.frame([7, 8]), [0, 0, 0, 2, 7, 8])
        XCTAssertThrowsError(try Wire.frame([]))
        XCTAssertThrowsError(try Wire.frame([UInt8](repeating: 0, count: Wire.maxFrame + 1)))
        XCTAssertEqual(try Wire.payloadLength(header: [0, 0, 0, 2]), 2)
        XCTAssertThrowsError(try Wire.payloadLength(header: [0, 0, 0, 0]))
        XCTAssertThrowsError(try Wire.payloadLength(header: [0x7F, 0, 0, 0]))
    }

    func testInviteMatchesKotlin() throws {
        let onion = "abcdefghijklmnopqrstuvwxyz234567abcdefghijklmnopqrstuvwx"
        let inv = Invite(onion: onion, secret: seq(32, 0), identityKey: seq(33, 100), authKey: seq(32, 200))
        XCTAssertEqual(inv.encode(), v("invite"))
        XCTAssertEqual(try Invite.decode("  " + v("invite") + "\n"), inv)
        XCTAssertThrowsError(try Invite.decode("hello"))
        XCTAssertThrowsError(try Invite.decode("swell:AAAA"))
        XCTAssertThrowsError(try Invite.decode(v("invite") + "AA")) // trailing garbage
    }

    func testQueueIdAndFrames() throws {
        let secret = seq(32, 7)
        XCTAssertEqual(hex(secret), v("queue_secret"))
        let q = MailboxProtocol.queueId(secret: secret)
        XCTAssertEqual(hex(q), v("queue_id"))
        let nonce = Int64(v("pow12_nonce"))!
        XCTAssertTrue(MailboxProtocol.powOK(queueId: q, nonce: nonce, bits: 12))
        XCTAssertEqual(hex(MailboxProtocol.createRequest(secret: secret, nonce: nonce)), v("create_frame_pow12"))
        let minute = Int64(v("cred_minute"))!, cn = Int64(v("cred10_nonce"))!
        XCTAssertTrue(MailboxProtocol.credPowOK(minute: minute, nonce: cn, bits: 10))
        XCTAssertEqual(hex(MailboxProtocol.credsRequest(minute: minute, nonce: cn)), v("creds_frame_cred10"))
    }

    func testPowSolvers() {
        let q = MailboxProtocol.queueId(secret: seq(32, 1))
        let n = MailboxProtocol.solvePow(queueId: q, bits: 12)
        XCTAssertTrue(MailboxProtocol.powOK(queueId: q, nonce: n, bits: 12))
        let c = MailboxProtocol.solveCredPow(minute: 5, bits: 10)
        XCTAssertTrue(MailboxProtocol.credPowOK(minute: 5, nonce: c, bits: 10))
        XCTAssertTrue(MailboxProtocol.leadingZeroBits([0, 0x1F], atLeast: 11))
        XCTAssertFalse(MailboxProtocol.leadingZeroBits([0, 0x1F], atLeast: 12))
        XCTAssertTrue(MailboxProtocol.leadingZeroBits([0, 0], atLeast: 16))
    }

    func testMailboxRequestsAndResponses() throws {
        let s = seq(32, 0)
        XCTAssertEqual(MailboxProtocol.putRequest(queueId: s, envelope: [9]), [2, 0x11] + s + [0, 0, 0, 1, 9])
        XCTAssertEqual(try MailboxProtocol.getRequest(secrets: [s]), [2, 0x12, 0, 0, 0, 1] + s)
        XCTAssertThrowsError(try MailboxProtocol.getRequest(secrets: []))
        XCTAssertThrowsError(try MailboxProtocol.getRequest(secrets: Array(repeating: s, count: 9)))
        XCTAssertEqual(MailboxProtocol.ackRequest(secret: s, ids: ["a"]), [2, 0x13] + s + [0, 0, 0, 1, 0, 0, 0, 1, 0x61])
        try MailboxProtocol.parseOK([0x80])
        XCTAssertThrowsError(try MailboxProtocol.parseOK([0xFF]))
        let list = Out().u8(0x82).i32(1).i32(0).str("id1").bytes([1, 2, 3]).done()
        XCTAssertEqual(try MailboxProtocol.parseList(list), [.init(index: 0, id: "id1", envelope: [1, 2, 3])])
        XCTAssertThrowsError(try MailboxProtocol.parseList(list + [0]))
        XCTAssertThrowsError(try MailboxProtocol.parseList([0xFF]))
        let cr = Out().u8(0x83).i32(1).str("turn:x").str("u").str("p").i64(5).done()
        XCTAssertEqual(try MailboxProtocol.parseCreds(cr).username, "u")
    }

    func testRecoveryPhraseAndIdentity() throws {
        let e = seq(16, 1)
        XCTAssertEqual(hex(e), v("entropy"))
        let phrase = try RecoveryPhrase.fromEntropy(e)
        XCTAssertEqual(phrase.joined(separator: " "), v("phrase"))
        XCTAssertEqual(try RecoveryPhrase.toEntropy(phrase), e)
        XCTAssertEqual(try RecoveryPhrase.fromEntropy([UInt8](repeating: 0, count: 16)).joined(separator: " "), v("phrase_zero"))
        let acct = Account(entropy: e, registrationId: 1)
        XCTAssertEqual(hex(acct.identityPrivateKey), v("identity_priv"))
        XCTAssertEqual(hex(acct.identityPublicKey), v("identity_pub"))
        XCTAssertEqual(hex(Account(entropy: [UInt8](repeating: 0, count: 16), registrationId: 1).identityPublicKey), v("identity_pub_zero"))
        // restore is case/space tolerant, bad checksum and unknown word are refused
        XCTAssertEqual(try Account.restore(phrase: phrase.map { " " + $0.uppercased() }).entropy, e)
        var bad = phrase; bad[11] = "abandon"
        XCTAssertThrowsError(try RecoveryPhrase.toEntropy(bad))
        bad[0] = "zzzz"
        XCTAssertThrowsError(try RecoveryPhrase.toEntropy(bad))
        XCTAssertThrowsError(try RecoveryPhrase.toEntropy(Array(phrase.prefix(11))))
    }

    // MARK: client against an in-memory server

    final class FakeBox: MailboxTransport {
        var queues: [String: [(String, [UInt8])]] = [:]
        var n = 0
        func roundTrip(onion: String, request: [UInt8], isolation: String) async throws -> [UInt8] {
            let r = In(request)
            _ = try r.u8()
            switch try r.u8() {
            case 0x14:
                let q = MailboxProtocol.queueId(secret: try r.raw(32))
                guard MailboxProtocol.powOK(queueId: q, nonce: try r.i64(), bits: 8) else { return [0xFF] }
                queues[Codec.hex(q)] = []; return [0x80]
            case 0x11:
                let q = Codec.hex(try r.raw(32)); let env = try r.bytes()
                guard queues[q] != nil else { return [0xFF] }
                n += 1; queues[q]!.append(("m\(n)", env)); return [0x80]
            case 0x12:
                _ = try r.i32()
                let q = Codec.hex(MailboxProtocol.queueId(secret: try r.raw(32)))
                let items = queues[q] ?? []
                let o = Out().u8(0x82).i32(Int32(items.count))
                items.forEach { o.i32(0).str($0.0).bytes($0.1) }
                return o.done()
            case 0x13:
                let q = Codec.hex(MailboxProtocol.queueId(secret: try r.raw(32)))
                let k = Int(try r.i32()); let ids = try (0..<k).map { _ in try r.str() }
                queues[q]?.removeAll { ids.contains($0.0) }; return [0x80]
            default: return [0xFF]
            }
        }
    }

    func testClientRoundTrip() async throws {
        let box = FakeBox(); let c = MailboxClient(onion: "x", transport: box)
        let secret = seq(32, 50)
        let q = MailboxProtocol.queueId(secret: secret)
        do { try await c.put(queueId: q, envelope: [1]); XCTFail("queue must exist first") } catch SwellError.rejected {}
        try await c.create(secret: secret, bits: 8)
        let env = PeerFrame.msg(channelId: seq(16, 0), ciphertextType: 3, ciphertext: [5, 5])
        try await c.put(queueId: q, envelope: env)
        let got = try await c.fetch(secret: secret)
        XCTAssertEqual(got.map(\.envelope), [env])
        try await c.ack(secret: secret, ids: got.map(\.id))
        let after = try await c.fetch(secret: secret)
        XCTAssertTrue(after.isEmpty)
    }

    func testSignalStubRefuses() {
        XCTAssertThrowsError(try UnavailableSignalCrypto().makeBundle())
    }
}
