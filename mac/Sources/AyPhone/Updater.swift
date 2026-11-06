import AppKit
import Foundation
import Security
import os

private let log = Logger(subsystem: "com.ayphone.mac", category: "update")

/// Updates from GitHub: checks the latest release now and then, and on the user's click
/// downloads its .dmg, makes sure the app inside is signed the same way as this one (so only
/// our own builds get installed), swaps it in and relaunches.
@MainActor
final class Updater: ObservableObject {
    struct Release: Equatable {
        var version: String
        var notes: String
        var dmg: URL
    }

    enum Phase: Equatable {
        case idle, downloading, installing
        case failed(String)
    }

    static let latestRelease = URL(string: "https://api.github.com/repos/Shubhangar/AyPhone/releases/latest")!

    /// A newer release than this copy, once found.
    @Published private(set) var available: Release?
    @Published private(set) var phase: Phase = .idle
    /// Tells the user once per version (a notification with an Update button).
    var onNewRelease: (String) -> Void = { _ in }

    private var timer: Timer?

    static var currentVersion: String {
        Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0"
    }

    func start() {
        Task { await check() }
        timer = Timer.scheduledTimer(withTimeInterval: 6 * 3600, repeats: true) { [weak self] _ in
            MainActor.assumeIsolated { Task { await self?.check() } }
        }
    }

    func check() async {
        var request = URLRequest(url: Self.latestRelease)
        request.setValue("application/vnd.github+json", forHTTPHeaderField: "Accept")
        guard let (data, _) = try? await URLSession.shared.data(for: request),
              let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
              let tag = json["tag_name"] as? String,
              let assets = json["assets"] as? [[String: Any]],
              let dmg = assets.lazy.compactMap({ $0["browser_download_url"] as? String })
                .first(where: { $0.hasSuffix("-mac.dmg") }).flatMap(URL.init(string:)) else { return }
        let version = tag.hasPrefix("v") ? String(tag.dropFirst()) : tag
        guard Self.isNewer(version, than: Self.currentVersion) else {
            available = nil
            return
        }
        let release = Release(version: version, notes: json["body"] as? String ?? "", dmg: dmg)
        if available != release { log.notice("update available: \(version, privacy: .public)") }
        available = release
        let key = "notifiedUpdate"
        if UserDefaults.standard.string(forKey: key) != version {
            UserDefaults.standard.set(version, forKey: key)
            onNewRelease(version)
        }
    }

    /// "0.1.10" is newer than "0.1.9".
    static func isNewer(_ a: String, than b: String) -> Bool {
        let x = a.split(separator: ".").map { Int($0) ?? 0 }
        let y = b.split(separator: ".").map { Int($0) ?? 0 }
        for i in 0..<max(x.count, y.count) {
            let p = i < x.count ? x[i] : 0, q = i < y.count ? y[i] : 0
            if p != q { return p > q }
        }
        return false
    }

    func install() {
        log.notice("update clicked (available: \(self.available?.version ?? "none", privacy: .public), phase: \(String(describing: self.phase), privacy: .public))")
        guard let release = available, phase != .downloading, phase != .installing else { return }
        phase = .downloading
        Task {
            do {
                let app = try await download(release)
                phase = .installing
                try relaunch(replacingWith: app)
            } catch {
                log.error("update failed: \(error.localizedDescription, privacy: .public)")
                phase = .failed(error.localizedDescription)
            }
        }
    }

    private struct UpdateError: LocalizedError {
        var errorDescription: String?
        init(_ text: String) { errorDescription = text }
    }

    /// Downloads and opens the .dmg, and copies out the app once its signature checks out.
    private func download(_ release: Release) async throws -> URL {
        log.notice("downloading \(release.dmg.absoluteString, privacy: .public)")
        let (file, _) = try await URLSession.shared.download(from: release.dmg)
        log.notice("downloaded; checking the signature")
        let work = FileManager.default.temporaryDirectory.appendingPathComponent("AyPhoneUpdate-\(UUID().uuidString)")
        let mount = work.appendingPathComponent("mount")
        try FileManager.default.createDirectory(at: mount, withIntermediateDirectories: true)
        try run("/usr/bin/hdiutil", ["attach", file.path, "-nobrowse", "-readonly", "-quiet", "-mountpoint", mount.path])
        defer { try? run("/usr/bin/hdiutil", ["detach", mount.path, "-quiet", "-force"]) }
        let staged = work.appendingPathComponent("AyPhone.app")
        try run("/usr/bin/ditto", [mount.appendingPathComponent("AyPhone.app").path, staged.path])
        guard Self.signedLikeUs(staged) else { throw UpdateError("The download isn't signed like this copy of AyPhone, so it wasn't installed.") }
        return staged
    }

    /// Whether the app at `url` meets this app's own code requirement (same identifier, same
    /// signing certificate).
    static func signedLikeUs(_ url: URL) -> Bool {
        var me: SecCode?
        var requirement: SecRequirement?
        var other: SecStaticCode?
        guard SecCodeCopySelf([], &me) == errSecSuccess, let me,
              SecCodeCopyDesignatedRequirement(me as! SecStaticCode, [], &requirement) == errSecSuccess,
              SecStaticCodeCreateWithPath(url as CFURL, [], &other) == errSecSuccess, let other else { return false }
        return SecStaticCodeCheckValidity(other, SecCSFlags(rawValue: kSecCSCheckAllArchitectures), requirement) == errSecSuccess
    }

    /// Hands over to a small script that waits for us to quit, swaps the app and opens the new one.
    private func relaunch(replacingWith app: URL) throws {
        let target = Bundle.main.bundleURL
        let script = """
            while kill -0 \(ProcessInfo.processInfo.processIdentifier) 2>/dev/null; do sleep 0.2; done
            rm -rf "$2.old" && mv "$2" "$2.old" && ditto "$1" "$2" && rm -rf "$2.old" || { rm -rf "$2"; mv "$2.old" "$2"; }
            xattr -dr com.apple.quarantine "$2" 2>/dev/null
            open "$2"
            """
        let p = Process()
        p.executableURL = URL(fileURLWithPath: "/bin/sh")
        p.arguments = ["-c", script, "sh", app.path, target.path]
        try p.run()
        log.notice("installing update and relaunching")
        NSApp.terminate(nil)
    }

    @discardableResult
    private func run(_ tool: String, _ arguments: [String]) throws -> Int32 {
        let p = Process()
        p.executableURL = URL(fileURLWithPath: tool)
        p.arguments = arguments
        p.standardOutput = FileHandle.nullDevice
        p.standardError = FileHandle.nullDevice
        try p.run()
        p.waitUntilExit()
        guard p.terminationStatus == 0 else { throw UpdateError("\((tool as NSString).lastPathComponent) failed (\(p.terminationStatus))") }
        return p.terminationStatus
    }
}
