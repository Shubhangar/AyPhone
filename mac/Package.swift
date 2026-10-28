// swift-tools-version:5.9
import PackageDescription

let package = Package(
    name: "AyPhone",
    platforms: [.macOS(.v14)],
    targets: [
        .executableTarget(name: "AyPhone", path: "Sources/AyPhone"),
        // The phone's Bluetooth fallback link, in its own process: inside the app, macOS hands
        // incoming channels from the phone to the hands-free (calls) connection instead.
        .executableTarget(name: "AyPhoneBluetooth", path: "Sources/AyPhoneBluetooth"),
    ]
)
