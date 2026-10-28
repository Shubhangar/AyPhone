import AppKit
import SwiftUI

@main
struct AyPhoneApp: App {
    @NSApplicationDelegateAdaptor(AppDelegate.self) private var appDelegate
    @ObservedObject private var state = AppState.shared

    var body: some Scene {
        Window("AyPhone", id: "main") {
            ContentView()
                .environmentObject(state)
                .environmentObject(state.files)
                .environmentObject(state.adb)
                .frame(minWidth: 860, minHeight: 580)
        }
        .defaultSize(width: 1040, height: 700)
        .windowStyle(.hiddenTitleBar)

        MenuBarExtra {
            MenuBarView()
                .environmentObject(state)
                .environmentObject(state.adb)
        } label: {
            MenuBarLabel(state: state)
        }
        .menuBarExtraStyle(.window)
    }
}

/// The menu bar icon. Also hands the app a way to reopen the main window from AppKit
/// (e.g. a Dock click), since SwiftUI's openWindow only exists inside views.
private struct MenuBarLabel: View {
    @ObservedObject var state: AppState
    @Environment(\.openWindow) private var openWindow

    var body: some View {
        Image(nsImage: state.connected ? LogoGlyph.connected : LogoGlyph.disconnected)
            .onAppear { state.openMainWindow = { openWindow(id: "main") } }
    }
}

final class AppDelegate: NSObject, NSApplicationDelegate {
    private let services = ServiceProvider()
    /// Turns a plain `kill` into a normal quit, so shutdown cleanup still runs.
    private var termSignal: DispatchSourceSignal?

    func applicationDidFinishLaunching(_ notification: Notification) {
        // Needed when launched as a bare SwiftPM executable rather than an .app bundle.
        NSApp.setActivationPolicy(.regular)
        NSApp.activate(ignoringOtherApps: true)
        // Finder → right-click → Services / Quick Actions → "Send to Phone".
        NSApp.servicesProvider = services
        NSUpdateDynamicServices()
        signal(SIGTERM, SIG_IGN)
        let source = DispatchSource.makeSignalSource(signal: SIGTERM, queue: .main)
        source.setEventHandler { NSApp.terminate(nil) }
        source.resume()
        termSignal = source
        MainActor.assumeIsolated { AppState.shared.start() }
    }

    /// Files dropped on the Dock icon (or "Open With → AyPhone") go to the phone.
    func application(_ application: NSApplication, open urls: [URL]) {
        MainActor.assumeIsolated { AppState.shared.sendFiles(urls) }
    }

    func applicationShouldTerminateAfterLastWindowClosed(_ sender: NSApplication) -> Bool {
        false // Keep running in the menu bar.
    }

    /// Clicking the Dock icon after closing the window brings it back.
    func applicationShouldHandleReopen(_ sender: NSApplication, hasVisibleWindows flag: Bool) -> Bool {
        if !flag { MainActor.assumeIsolated { AppState.shared.showMainWindow() } }
        return true
    }

    func applicationWillTerminate(_ notification: Notification) {
        MainActor.assumeIsolated {
            AppState.shared.adb.stopAll()
            AppState.shared.server.stop()
        }
    }
}

/// Backs the "Send to Phone" Finder service declared in Info.plist (NSServices).
final class ServiceProvider: NSObject {
    @objc func sendToPhone(_ pboard: NSPasteboard, userData: String?, error: AutoreleasingUnsafeMutablePointer<NSString?>) {
        let urls = pboard.readObjects(forClasses: [NSURL.self], options: [.urlReadingFileURLsOnly: true]) as? [URL] ?? []
        MainActor.assumeIsolated { AppState.shared.sendFiles(urls) }
    }
}
