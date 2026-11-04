import XCTest
@testable import SwellCore

final class LimitsTests: XCTestCase {
    func testHugeInviteRejectedBeforeDecoding() {
        let huge = Invite.prefix + String(repeating: "A", count: 5_000_000)
        XCTAssertThrowsError(try Invite.decode(huge))
    }

    func testPowStopsWhenCancelled() async {
        let t = Task.detached { MailboxProtocol.solvePow(queueId: [UInt8](repeating: 1, count: 32), bits: 40) } // would run for hours
        try? await Task.sleep(nanoseconds: 200_000_000)
        t.cancel()
        let n = await t.value
        XCTAssertEqual(n, MailboxProtocol.powCancelled)
    }

    func testReportInboxParsesAndSeals() throws {
        let hex = String(repeating: "ab", count: 32)
        let i = ReportInbox(String(repeating: "a", count: 56) + ":" + hex + ":" + hex)
        XCTAssertNotNil(i)
        XCTAssertNil(ReportInbox("x:y:z"))
    }
    func testWaitAndPeekRequests() throws {
        let q = [UInt8](repeating: 7, count: 32)
        XCTAssertEqual(MailboxProtocol.peekRequest(queueId: q), [2, 0x16] + q)
        let w = try MailboxProtocol.waitRequest(secrets: [q, q], maxWaitMs: 99_000)
        XCTAssertEqual(Array(w.prefix(2)), [2, 0x17])
        XCTAssertEqual(Array(w[2..<6]), [0, 0, 0x61, 0xA8]) // 25000 ms, capped
        XCTAssertEqual(Array(w[6..<10]), [0, 0, 0, 2])
        XCTAssertEqual(w.count, 10 + 64)
        XCTAssertThrowsError(try MailboxProtocol.waitRequest(secrets: []))
    }
    func testMailboxInviteMatchesKotlin() throws {
        let code = "swell:AwAAADhhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYWFhYQABAgMEBQYHCAkKCwwNDg8QERITFBUWFxgZGhscHR4fZGVmZ2hpamtsbW5vcHFyc3R1dnd4eXp7fH1-f4CBgoMAAAAhBwgJCgsMDQ4PEBESExQVFhcYGRobHB0eHyAhIiMkJSYn"
        guard case .mailbox(let inv) = try InviteCode.parse(code) else { return XCTFail("version") }
        XCTAssertEqual(inv.mailbox, String(repeating: "a", count: 56)); XCTAssertEqual(inv.queueId, (0..<32).map { UInt8($0) })
        XCTAssertEqual(inv.sealKey, (0..<32).map { UInt8(100 + $0) }); XCTAssertEqual(inv.identityKey.count, 33)
        XCTAssertEqual(inv.encode(), code, "byte-identical re-encoding")
        // the same code in a link, and without the prefix (as in a link fragment)
        XCTAssertEqual(try InviteCode.parse("https://jijilabsdigital.com/swell/i#" + code), .mailbox(inv))
        XCTAssertEqual(try InviteCode.parse(String(code.dropFirst(6))), .mailbox(inv))
        // surrounded by words, lines, spaces; link inside a sentence; percent-encoded colon
        XCTAssertEqual(try InviteCode.parse("  Chat with me on Swell: https://jijilabsdigital.com/swell/i#" + code + "\n\nSee you!  "), .mailbox(inv))
        XCTAssertEqual(try InviteCode.parse("hi\n" + code + " thanks"), .mailbox(inv))
        XCTAssertEqual(try InviteCode.parse("https://jijilabsdigital.com/swell/i#" + code.replacingOccurrences(of: "swell:", with: "swell%3A")), .mailbox(inv))
        XCTAssertThrowsError(try InviteCode.parse("hello there, nothing to see https://example.com/page"))
        // a bundle sealed by Kotlin opens here; a wrong key or queue does not
        let sealed = Array(Data(hex: "059fb9ddfd0731dff05b506b6fa0bd367d9ada72659d1268f92631992232076eab44570c54d2fed9828a0609"))
        XCTAssertEqual(String(decoding: try inv.open(sealed: sealed), as: UTF8.self), "bundle-bytes-123")
        XCTAssertThrowsError(try MailboxInvite(mailbox: inv.mailbox, queueId: inv.queueId, sealKey: Codec.random(32), identityKey: inv.identityKey).open(sealed: sealed))
        XCTAssertThrowsError(try MailboxInvite(mailbox: inv.mailbox, queueId: Codec.random(32), sealKey: inv.sealKey, identityKey: inv.identityKey).open(sealed: sealed))
        // and what we seal round-trips through the envelope
        let env = MailboxInvite.bundleEnvelope(sealed: try inv.seal(bundle: [1, 2, 3]))
        XCTAssertEqual(try inv.open(sealed: try MailboxInvite.sealedBundle(fromEnvelope: env)), [1, 2, 3])
    }
}

private extension Data {
    init(hex: String) {
        var d = Data(); var i = hex.startIndex
        while i < hex.endIndex { let j = hex.index(i, offsetBy: 2); d.append(UInt8(hex[i..<j], radix: 16)!); i = j }
        self = d
    }
}
