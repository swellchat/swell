import XCTest
import SwellCore
@testable import SwellSignal

final class LaneTests: XCTestCase {
    func testPlanIsStableAndSeparatesSendFromReceive() {
        let key = Codec.random(32)
        let a = LanePlan(deviceKey: key), b = LanePlan(deviceKey: key), other = LanePlan(deviceKey: Codec.random(32))
        var receive = Set<String>(), send = Set<String>()
        var differs = false
        for i in 0..<2000 {
            let q = Codec.random(32), id = "contact-\(i)"
            XCTAssertEqual(a.receiveLane(queueId: q), b.receiveLane(queueId: q), "same device key = same lane after a restart")
            XCTAssertEqual(a.sendLane(contactId: id), b.sendLane(contactId: id))
            if a.receiveLane(queueId: q) != other.receiveLane(queueId: q) { differs = true }
            receive.insert(a.receiveLane(queueId: q)); send.insert(a.sendLane(contactId: id))
        }
        XCTAssertEqual(receive, ["r0", "r1", "r2", "r3"])
        XCTAssertEqual(send.count, 2000, "two contacts never share a send lane")
        XCTAssertTrue(send.isDisjoint(with: receive))
        XCTAssertTrue(differs, "the mapping depends on the device key")
        XCTAssertTrue(a.receiveLane(queueId: Codec.random(32), privateContact: "x").hasPrefix("rp-"))
        XCTAssertNotEqual(a.receiveLane(queueId: Codec.random(32), privateContact: "x"), a.receiveLane(queueId: Codec.random(32), privateContact: "y"))
        XCTAssertFalse(LanePlan.newSpareName().hasPrefix("s-") || LanePlan.newSpareName().hasPrefix("r"))
    }

    func testNoPutSharesAStreamWithAWaitAndContactsGetOwnLanes() async throws {
        let net = try FakeNet()
        let e = try Engine(account: Account(entropy: [UInt8](repeating: 0x21, count: 16), registrationId: 5), kv: MemoryKeyValueStore(), transport: net,
                           auth: NoOnionAuth(), mailboxOnion: net.mailbox, profileName: "T", powBits: 8)
        var ids: [String] = []
        for _ in 0..<3 { ids.append(try await e.redeem(net.invite(), firstText: "Hi").id) }
        net.inviterOnline = false // every send now goes into the contact's mailbox queue (PUT)
        await e.setFallbackPoll(0.5)
        net.calls = []
        await e.prewarm()
        let listener = Task { await e.listen() }
        try await Task.sleep(nanoseconds: 800_000_000)
        for id in ids { _ = try await e.send("one", to: id); _ = try await e.send("two", to: id) }
        try await Task.sleep(nanoseconds: 1_500_000_000)
        listener.cancel()
        let puts = net.calls.filter { $0.op == 0x11 }, waits = net.calls.filter { $0.op == 0x17 }
        XCTAssertGreaterThanOrEqual(puts.count, 6); XCTAssertFalse(waits.isEmpty)
        XCTAssertTrue(Set(puts.map(\.tag)).isDisjoint(with: Set(net.calls.filter { $0.op != 0x11 }.map(\.tag))), "a PUT tag is used by no other kind of call")
        // each of the three contacts has exactly one send lane, and the three differ
        let lanes = Set(puts.map(\.tag).filter { $0.hasPrefix("s-") })
        XCTAssertEqual(lanes.count, 3, "\(lanes)")
        XCTAssertTrue(waits.allSatisfy { $0.tag.hasPrefix("r") })
        XCTAssertEqual(Set(waits.map(\.tag)).count, Set(waits.map(\.tag)).filter { $0.hasPrefix("r") }.count)
        // the first send to each contact took a pre-opened spare
        XCTAssertEqual(net.adopted.count, 3); XCTAssertEqual(Set(net.adopted.map(\.lane)), lanes)
        XCTAssertTrue(net.adopted.allSatisfy { $0.spare.hasPrefix("x-") })
    }

    func testReceiveLanesSurviveARestart() async throws {
        let net = try FakeNet(), kv = MemoryKeyValueStore()
        let acct = Account(entropy: [UInt8](repeating: 0x33, count: 16), registrationId: 7)
        func make() throws -> Engine { try Engine(account: acct, kv: kv, transport: net, auth: NoOnionAuth(), mailboxOnion: net.mailbox, profileName: "T", powBits: 8) }
        let e1 = try make()
        var secrets: [[UInt8]] = []
        for _ in 0..<6 { let c = try await e1.redeem(net.invite(), firstText: "Hi"); secrets.append(await e1.contact(c.id)!.myQueueSecret) }
        var first: [String] = []
        for s in secrets { first.append(await e1.laneForTesting(secret: s)) }
        let e2 = try make()
        var second: [String] = []
        for s in secrets { second.append(await e2.laneForTesting(secret: s)) }
        XCTAssertEqual(first, second)
    }

    func testOneWeekTimerSurvivesTheExpirySweep() async throws {
        let net = try FakeNet()
        let e = try Engine(account: Account(entropy: [UInt8](repeating: 0x44, count: 16), registrationId: 9), kv: MemoryKeyValueStore(), transport: net,
                           auth: NoOnionAuth(), mailboxOnion: net.mailbox, profileName: "T", powBits: 8)
        let c = try await e.redeem(net.invite(), firstText: "Hi")
        try await e.setDisappearing(c.id, seconds: 7 * 86400)
        let m = try await e.send("stays a week", to: c.id)
        await e.purgeExpired()
        let after = await e.messages(c.id)
        XCTAssertTrue(after.contains { $0.id == m.id }, "a message with a 1-week timer is not swept")
        XCTAssertEqual(after.first { $0.id == m.id }.map { $0.expiresAt - $0.sentAt }, 7 * 86400 * 1000)
    }
}
