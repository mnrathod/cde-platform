// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "CdeSDK",
    // PDFKit is available on iOS 11+, but async/await and the concurrency
    // model the SDK is written against need 13. Below that a host app would
    // get a very different API surface, so the floor is stated rather than
    // worked around.
    platforms: [.iOS(.v13)],
    products: [
        .library(name: "CdeSDK", targets: ["CdeSDK"])
    ],
    targets: [
        // No third-party dependencies. Rendering is PDFKit, networking is
        // URLSession, storage is the file system and the keychain — every one
        // of them a system framework. An SDK that drags in a dependency tree
        // is an SDK that host apps fight with.
        .target(name: "CdeSDK", path: "Sources/CdeSDK"),
        .testTarget(name: "CdeSDKTests", dependencies: ["CdeSDK"], path: "Tests/CdeSDKTests"),
    ]
)
