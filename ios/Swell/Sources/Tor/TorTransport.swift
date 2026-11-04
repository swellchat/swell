import Foundation
import SwellCore
import SwellSignal

/// `MailboxTransport` over our own Tor client (see [SocksTransport]).
func torTransport(_ tor: TorService) -> SocksTransport {
    SocksTransport(socksPort: { try await tor.socksPort() })
}
