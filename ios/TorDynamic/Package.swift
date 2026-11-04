// swift-tools-version: 6.3
// Local copy of iCepa/Tor.framework 409.15.3 Package.swift, trimmed, with CTor as a DYNAMIC library.
// Why: tor.a bundles OpenSSL 3; libsignal_ffi bundles BoringSSL. Linked statically into one image
// they collide (OPENSSL_realloc etc.) and Tor crashes at start. A dynamic CTor keeps OpenSSL inside
// its own framework (two-level namespace), so it never binds to BoringSSL.
import PackageDescription

let package = Package(
    name: "TorFramework",
    platforms: [.iOS(.v15), .macOS(.v12)],
    products: [
        .library(name: "CTor", type: .dynamic, targets: ["CTor"]),
    ],
    targets: [
        .target(name: "TorCore", publicHeadersPath: "include"),
        .binaryTarget(
            name: "tor",
            url: "https://github.com/iCepa/Tor.framework/releases/download/409.15.3/tor.xcframework.zip",
            checksum: "a365013b33229a20f773e4dc7dc3f1c8ed6653a3e6c05ff9b67dc8286cc63528"
        ),
        .target(
            name: "CTor",
            dependencies: ["TorCore", "tor"],
            publicHeadersPath: "include",
            linkerSettings: [.linkedLibrary("z")]),
    ],
    swiftLanguageModes: [.v6]
)
