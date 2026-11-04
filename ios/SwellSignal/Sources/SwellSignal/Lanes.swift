import Foundation
import CryptoKit
import SwellCore

/// Which Tor stream (isolation tag) carries which mailbox call (docs/DESIGN-SWELL-LANES.md).
///
/// - Our own queues are split into `receiveLanes` fixed groups by HMAC(device key, queue id) mod K. Each group has its own
///   stream and WAIT loop; ACKs use the same one. Groups are never reshuffled.
/// - A queue of a contact in Private Mode gets a lane of its own.
/// - A PUT to a contact goes on that contact's send lane, which no other contact and no receive lane ever uses.
/// The device key is random, kept in the encrypted store, and never leaves the phone, so the mapping is stable across restarts
/// and cannot be recomputed by the mailbox operator.
public struct LanePlan: Sendable {
    public static let receiveLanes = 4
    public static let spareCount = 2
    public static let sendIdleSeconds = 120.0
    private let key: SymmetricKey

    public init(deviceKey: [UInt8]) { key = SymmetricKey(data: deviceKey) }

    private func mac(_ label: String, _ data: [UInt8]) -> [UInt8] {
        Array(HMAC<SHA256>.authenticationCode(for: Data(label.utf8) + Data(data), using: key))
    }

    /// "r0"..."r3", or "rp-<tag>" for a Private Mode contact's queue.
    public func receiveLane(queueId: [UInt8], privateContact: String? = nil) -> String {
        if let c = privateContact { return "rp-" + Codec.hex(Array(mac("private", Array(c.utf8)).prefix(6))) }
        let h = mac("recv", queueId)
        let n = (UInt32(h[0]) << 24) | (UInt32(h[1]) << 16) | (UInt32(h[2]) << 8) | UInt32(h[3])
        return "r\(Int(n % UInt32(Self.receiveLanes)))"
    }

    /// "s-<tag>", unique per contact (48-bit tag), never equal to a receive lane or a spare.
    public func sendLane(contactId: String) -> String { "s-" + Codec.hex(Array(mac("send", Array(contactId.utf8)).prefix(6))) }

    public static func newSpareName() -> String { "x-" + Codec.hex(Codec.random(6)) }
}
