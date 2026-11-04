import XCTest
import SwellCore
@testable import SwellSignal

final class HardeningTests: XCTestCase {
    let acct = Account(entropy: [UInt8](repeating: 0x53, count: 16), registrationId: 5)
    func engine(_ net: FakeNet, _ kv: KeyValueStore) throws -> Engine {
        try Engine(account: acct, kv: kv, transport: net, auth: NoOnionAuth(), mailboxOnion: net.mailbox, profileName: "T", powBits: 8)
    }

    func testFailedCommitDoesNotAckAndSendDoesNotLeave() async throws {
        let net = try FakeNet()
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("hard-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let kv = try EncryptedFileStore(url: dir.appendingPathComponent("s.swk"), key: [UInt8](repeating: 1, count: 32))
        let e = try engine(net, kv)
        let c = try await e.redeem(net.invite(), firstText: "Hi")
        try net.putText("important")
        // make the disk unwritable
        try FileManager.default.setAttributes([.posixPermissions: 0o500], ofItemAtPath: dir.path)
        let n = await e.poll()
        XCTAssertEqual(n, 1)
        XCTAssertEqual(net.queues[Codec.hex(net.helloInfo!.queue)]!.count, 1, "not acknowledged")
        let none = await e.messages(c.id).filter(\.incoming)
        XCTAssertTrue(none.isEmpty)
        // a send must fail before anything leaves
        net.received = []
        do { _ = try await e.send("x", to: c.id); XCTFail("should throw") } catch { XCTAssertTrue(error is StoreError) }
        XCTAssertEqual(net.received, [])
        // disk back: the same envelope is now processed (the ratchet was not advanced by the failed attempt)
        try FileManager.default.setAttributes([.posixPermissions: 0o700], ofItemAtPath: dir.path)
        _ = await e.poll()
        let got = await e.messages(c.id).filter(\.incoming).map(\.text)
        XCTAssertEqual(got, ["important"])
        XCTAssertTrue(net.queues[Codec.hex(net.helloInfo!.queue)]!.isEmpty)
    }

    func testIncomingPhotoWithoutPartsWaitsAndInvalidFileIsDropped() async throws {
        let net = try FakeNet()
        let e = try engine(net, MemoryKeyValueStore())
        await e.setFilesDirectory(FileManager.default.temporaryDirectory.appendingPathComponent("swf-\(UUID().uuidString)"))
        let c = try await e.redeem(net.invite(), firstText: "Hi")
        let h = net.helloInfo!
        // a FILE that claims 10 MB is dropped without a notice; one whose parts never arrive shows nothing yet
        let o = Out().u8(8).raw(Codec.random(16)).i64(1_700_000_000_000).i32(0).str("").raw(Codec.random(16)).str("big.bin")
            .str("application/octet-stream").i64(10_000_000).raw(Codec.random(32)).i32(1)
        let enc = try net.inviter.encrypt(channelId: h.channel, plaintext: o.done())
        net.queues[Codec.hex(h.queue)]!.append(("f1", PeerFrame.msg(channelId: h.channel, ciphertextType: enc.type, ciphertext: enc.ciphertext)))
        _ = await e.poll()
        let inc = await e.messages(c.id).filter(\.incoming)
        XCTAssertTrue(inc.isEmpty)
        XCTAssertTrue(net.queues[Codec.hex(h.queue)]!.isEmpty)
    }

    func testInterruptedSendBecomesFailedAndExpiredDisappears() async throws {
        let net = try FakeNet()
        let kv = MemoryKeyValueStore()
        let t = Date()
        let e = try Engine(account: acct, kv: kv, transport: net, auth: NoOnionAuth(), mailboxOnion: net.mailbox, profileName: "T", powBits: 8, now: { t })
        let c = try await e.redeem(net.invite(), firstText: "Hi")
        let ms = Int64(t.timeIntervalSince1970 * 1000)
        let stuck = ChatMessage(id: "aa", contactId: c.id, text: "stuck", incoming: false, sentAt: ms + 1, status: .sending)
        let old = ChatMessage(id: "bb", contactId: c.id, text: "secret", incoming: false, sentAt: ms + 2, status: .sentDirect, expiresAt: ms - 1)
        kv.put("msg.\(c.id)", stuck.key, stuck.encode()); kv.put("msg.\(c.id)", old.key, old.encode())
        await e.recoverInterrupted()
        let after = await e.messages(c.id)
        XCTAssertEqual(after.first { $0.id == "aa" }?.status, .failed)
        XCTAssertNil(after.first { $0.id == "bb" })
        // retry works on a recovered message
        await e.purgeExpired()
        XCTAssertEqual(kv.list("msg.\(c.id)").count, 2, "expired record deleted")
        let lt = await e.contact(c.id)?.lastText
        XCTAssertNotEqual(lt, "secret")
    }

    func testShutdownStopsWork() async throws {
        let net = try FakeNet()
        let e = try engine(net, MemoryKeyValueStore())
        let c = try await e.redeem(net.invite(), firstText: "Hi")
        await e.shutdown()
        do { _ = try await e.send("late", to: c.id); XCTFail() } catch { XCTAssertTrue(error is CancellationError) }
        let n = await e.poll(); XCTAssertEqual(n, 0)
        XCTAssertEqual(net.received, [])
    }

    func testReportReachesInboxSealed() async throws {
        let net = try FakeNet()
        let e = try engine(net, MemoryKeyValueStore())
        let c = try await e.redeem(net.invite(), firstText: "Hi")
        try net.putText("rude one"); _ = await e.poll()
        let priv = Curve25519Key.make()
        let q = Codec.random(32)
        net.queues[Codec.hex(q)] = []
        let inbox = ReportInbox(mailbox: net.mailbox, queueId: q, publicKey: priv.pub)
        try await e.report(c.id, reason: "Spam or scam", note: "bad", includeLast: 10, inbox: inbox)
        let env = net.queues[Codec.hex(q)]![0].1
        let body = try Padding.unpad(try Report.open(privateKey: priv.raw, envelope: env))
        let r = In(body)
        XCTAssertEqual(try r.u8(), 1); _ = try r.i64()
        XCTAssertEqual(try r.str(), "Spam or scam \u{2014} bad")
        XCTAssertEqual(try r.bytes().count, 33)
        XCTAssertEqual(try r.i32(), 1); _ = try r.i64(); XCTAssertEqual(try r.u8(), 1); XCTAssertEqual(try r.str(), "rude one")
    }
}

import CryptoKit
enum Curve25519Key {
    static func make() -> (raw: [UInt8], pub: [UInt8]) {
        let k = Curve25519.KeyAgreement.PrivateKey()
        return ([UInt8](k.rawRepresentation), [UInt8](k.publicKey.rawRepresentation))
    }
}
