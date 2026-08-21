// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "CdeSDK",
    // 16 is where PDFPageOverlayViewProvider arrives, which is how the viewer
    // gives each PDF page its own markup overlay. The alternative — one
    // overlay for the whole view — cannot tell which page a shape belongs to
    // during a scroll, so supporting 13 would mean shipping a second, worse
    // viewer alongside this one rather than the same viewer with less.
    platforms: [.iOS(.v16)],
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
