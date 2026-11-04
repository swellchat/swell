import XCTest
@testable import SwellCore

final class PowTests: XCTestCase {
    func testFastSolverMatchesVerifierAt22Bits() {
        let q = MailboxProtocol.queueId(secret: Codec.random(32))
        let t0 = Date()
        let n = MailboxProtocol.solvePow(queueId: q, bits: 22)
        XCTAssertTrue(MailboxProtocol.powOK(queueId: q, nonce: n, bits: 22))
        print("pow22 took \(Date().timeIntervalSince(t0)) s")
        let c = MailboxProtocol.solveCredPow(minute: 123, bits: 18)
        XCTAssertTrue(MailboxProtocol.credPowOK(minute: 123, nonce: c, bits: 18))
    }
}
