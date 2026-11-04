import Foundation
import SwellCore

/// Fixed settings. The mailbox is the Jiji Labs Swell mailbox (an onion service); it sees only encrypted
/// envelopes addressed to random queue ids. Same address as the Android build default.
enum Config {
    static let mailboxOnion = "rzv7uqsazofsia3x4ex7zvxm66ldvuro2mujtxnb6f6t57kv2yw5m2id"

    /// Where reports go (a queue on the Jiji Labs mailbox, sealed to its public key). Same value as Android
    /// `swell.reportInbox`; the public key is not a secret.
    static let reportInbox = ReportInbox("rzv7uqsazofsia3x4ex7zvxm66ldvuro2mujtxnb6f6t57kv2yw5m2id:dff7acbab76ef193588dd0cf144e150cab06b43c6d0ba2ccd9577ca3d13f4f48:9ee70d7d84c57a9fb442dd178fc39436cd67e47767441a8f3fbf3437fc79dd35")
}
