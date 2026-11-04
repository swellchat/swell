// swift-tools-version:5.9
import PackageDescription
import Foundation

// LibSignalClient 0.86.5 (same version as the Android build). Its Package.swift lives in swift/ of
// the libsignal repo, so it is used as a local path dependency: run scripts/setup-libsignal.sh once.
// That script links the checkout at ../vendor/libsignal and builds libsignal_ffi for macOS tests.
let here = URL(fileURLWithPath: #filePath).deletingLastPathComponent().path
let ffiMac = "\(here)/../vendor/libsignal/target/debug"

let package = Package(
    name: "SwellSignal",
    platforms: [.iOS(.v17), .macOS(.v14)],
    products: [.library(name: "SwellSignal", targets: ["SwellSignal"])],
    dependencies: [
        .package(path: "../SwellCore"),
        .package(path: "../vendor/libsignal/swift"),
    ],
    targets: [
        .target(name: "SwellSignal", dependencies: [
            .product(name: "SwellCore", package: "SwellCore"),
            .product(name: "LibSignalClient", package: "swift"),
        ]),
        .testTarget(
            name: "SwellSignalTests",
            dependencies: ["SwellSignal"],
            // macOS tests link the debug libsignal_ffi.a built by scripts/setup-libsignal.sh.
            linkerSettings: [.unsafeFlags(["-L\(ffiMac)"], .when(platforms: [.macOS]))]
        ),
    ]
)
