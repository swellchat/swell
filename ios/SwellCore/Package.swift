// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "SwellCore",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "SwellCore", targets: ["SwellCore"])],
    targets: [
        .target(name: "SwellCore", resources: [.copy("Resources/bip39-english.txt")]),
        .testTarget(name: "SwellCoreTests", dependencies: ["SwellCore"], resources: [.copy("Resources/golden.json")]),
    ]
)
