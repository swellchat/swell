import XCTest
import Foundation
import SwellCore
@testable import SwellSignal

/// Live test against the Mac peer over real Tor (run by tools/ios-live.sh; skipped without SWELL_LIVE=1).
/// Same Engine as the iPhone app, with a brew `tor` on the Mac instead of Tor.framework.
final class LiveTests: XCTestCase {
    struct ControlAuth: OnionAuth {
        let port: UInt16, cookie: [UInt8]
        func setClientAuth(onion: String, privateKey: [UInt8]?) async throws {
            let fd = socket(AF_INET, SOCK_STREAM, 0); defer { close(fd) }
            var a = sockaddr_in(); a.sin_family = sa_family_t(AF_INET); a.sin_port = port.bigEndian; a.sin_addr.s_addr = UInt32(0x7F00_0001).bigEndian
            let ok = withUnsafePointer(to: &a) { $0.withMemoryRebound(to: sockaddr.self, capacity: 1) { connect(fd, $0, socklen_t(MemoryLayout<sockaddr_in>.size)) } }
            guard ok == 0 else { throw SwellError.transport("control connect") }
            func cmd(_ s: String) -> String {
                _ = s.withCString { write(fd, $0, strlen($0)) }
                var buf = [UInt8](repeating: 0, count: 512); let n = read(fd, &buf, 511)
                return n > 0 ? String(decoding: buf[0..<n], as: UTF8.self) : ""
            }
            XCTAssertTrue(cmd("AUTHENTICATE \(Codec.hex(cookie))\r\n").hasPrefix("250"))
            let r = privateKey.map { cmd("ONION_CLIENT_AUTH_ADD \(onion) x25519:\(Data($0).base64EncodedString())\r\n") } ?? cmd("ONION_CLIENT_AUTH_REMOVE \(onion)\r\n")
            XCTAssertTrue(r.hasPrefix("250") || r.hasPrefix("251"), r)
        }
    }

    func testRedeemAndChatWithMacPeer() async throws {
        let env = ProcessInfo.processInfo.environment
        try XCTSkipUnless(env["SWELL_LIVE"] == "1")
        let socks = UInt16(env["SWELL_SOCKS"]!)!, control = UInt16(env["SWELL_CONTROL"]!)!
        let cookie = [UInt8](try Data(contentsOf: URL(fileURLWithPath: env["SWELL_COOKIE"]!)))
        let dir = FileManager.default.temporaryDirectory.appendingPathComponent("live-\(UUID().uuidString)")
        defer { try? FileManager.default.removeItem(at: dir) }
        let kv = try EncryptedFileStore(url: dir.appendingPathComponent("s.swk"), key: Codec.random(32))
        let e = try Engine(account: Account.create(), kv: kv, transport: SocksTransport(socksPort: { socks }),
                           auth: ControlAuth(port: control, cookie: cookie), mailboxOnion: env["SWELL_MAILBOX"]!, profileName: "Mac iOS-engine")
        let t0 = Date()
        let c = try await e.redeem(try Invite.decode(env["SWELL_INVITE"]!), firstText: "hello from the iOS engine")
        print("LIVE redeemed in \(Date().timeIntervalSince(t0)) s name=\(c.name) mailbox=\(c.hasMailbox) onion=\(!c.onion.isEmpty)")
        let m = try await e.send("ping one", to: c.id)
        print("LIVE send status \(m.status)")
        XCTAssertNotEqual(m.status, .failed)
        var got: [String] = []
        for _ in 0..<30 {
            _ = await e.poll()
            got = await e.messages(c.id).filter(\.incoming).map(\.text)
            if got.contains(where: { $0.contains("ping one") }) { break }
            try await Task.sleep(nanoseconds: 4_000_000_000)
        }
        print("LIVE received \(got)")
        XCTAssertTrue(got.contains(where: { $0.contains("ping one") }))
        for _ in 0..<20 {
            if await e.contact(c.id)?.hasMailbox == true { break }
            _ = await e.poll(); try await Task.sleep(nanoseconds: 4_000_000_000)
        }
        let cc = await e.contact(c.id)!
        print("LIVE peer mailbox known: \(cc.hasMailbox)")
        // The peer now knows our mailbox: a second text works even with no direct route.
        await e.setForeground(false)
        let m2 = try await e.send("ping two via mailbox", to: c.id)
        print("LIVE send2 status \(m2.status)")
        XCTAssertEqual(m2.status, .queued)
    }
}
