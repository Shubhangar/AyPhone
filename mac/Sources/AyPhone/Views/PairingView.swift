import CoreImage.CIFilterBuiltins
import SwiftUI

struct PairingView: View {
    @EnvironmentObject private var state: AppState
    @EnvironmentObject private var adb: AdbManager
    @ViewState private var confirmReset = false
    @ViewState private var rotation = 0.0
    @ViewState private var enablingClipboard = false

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                PageHeader(title: "Pairing & Setup", subtitle: "Pair once. After that your phone reconnects on its own.") {
                    Button {
                        state.mirrorScreen()
                    } label: {
                        Label("Mirror screen", systemImage: "rectangle.on.rectangle")
                    }
                    .buttonStyle(.borderedProminent).tint(Theme.indigo)
                    .disabled(!state.connected)
                    .help("Mirror your phone. No developer settings needed; tap Start now on the phone.")
                }
                HStack(alignment: .top, spacing: 20) {
                    qrCard
                    VStack(spacing: 16) {
                        bluetoothCallsCard
                        advancedCard
                        if adb.advancedEnabled {
                            usbCard
                            mirroringCard
                            clipboardCard
                        }
                    }
                    .animation(Theme.spring, value: adb.advancedEnabled)
                }
            }
            .padding(.horizontal, 28)
            .padding(.vertical, 32)
            .frame(maxWidth: 980, alignment: .leading)
            .frame(maxWidth: .infinity)
        }
        .confirmationDialog("Reset pairing?", isPresented: $confirmReset) {
            Button("Reset", role: .destructive) { state.resetPairing() }
        } message: {
            Text("All paired phones are disconnected and must scan the new code.")
        }
    }

    // MARK: QR

    private var qrCard: some View {
        VStack(spacing: 18) {
            if let image = qrImage(state.pairingPayload) {
                Image(nsImage: image)
                    .interpolation(.none)
                    .resizable()
                    .frame(width: 230, height: 230)
                    .padding(16)
                    .background(.white, in: RoundedRectangle(cornerRadius: 20, style: .continuous))
                    .overlay {
                        // A slowly rotating gradient edge around the code.
                        AngularGradient(colors: [Theme.indigo, Theme.cyan, Theme.pink, Theme.indigo], center: .center)
                            .rotationEffect(.degrees(rotation))
                            .scaleEffect(1.6)
                            .mask(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(lineWidth: 3))
                            .allowsHitTesting(false)
                    }
                    .shadow(color: Theme.cyan.opacity(0.3), radius: 24)
                    .onAppear {
                        withAnimation(.linear(duration: 8).repeatForever(autoreverses: false)) { rotation = 360 }
                    }
            }
            VStack(spacing: 6) {
                Text("Scan with your phone").font(.headline)
                Text("In AyPhone on Android, tap **Pair with Mac**. Both devices need the same Wi-Fi, or a USB cable.")
                    .font(.callout).foregroundStyle(.secondary).multilineTextAlignment(.center)
            }
            HStack {
                Button("Refresh code") { state.refreshPairingPayload() }
                    .help("Use this if your Mac's IP address changed")
                Button("Reset pairing…", role: .destructive) { confirmReset = true }
            }
            .buttonStyle(.glass)
        }
        .padding(24)
        .frame(width: 340)
        .glass(26)
    }

    // MARK: Setup cards

    private var advancedCard: some View {
        SetupCard(symbol: "hammer.fill", colors: [Color.gray, Color.gray.opacity(0.7)], title: "Advanced features (developer options)",
                  status: adb.advancedEnabled ? "On" : "Off", statusColor: adb.advancedEnabled ? Theme.connectedTint : .secondary) {
            Text("Not needed for everyday use: notifications, calls, messages, files, clipboard and Mirror screen all work without it.")
                .font(.callout).foregroundStyle(.secondary)
            Text("Turning this on adds phone apps in their own Mac windows, the phone camera, advanced mirroring, USB connection and fully automatic clipboard, but it needs USB debugging switched on on the phone. Keep it off unless you use those.")
                .font(.caption).foregroundStyle(.secondary)
            Toggle("Use advanced features", isOn: $adb.advancedEnabled)
                .toggleStyle(.row)
        }
    }

    private var usbCard: some View {
        SetupCard(symbol: "cable.connector", colors: [Theme.cyan, Theme.green], title: "USB connection",
                  status: usbStatus.text, statusColor: usbStatus.color) {
            Text("Plug in your phone with USB debugging on. Forwarding turns on automatically, and the phone prefers USB for speed.")
                .font(.callout).foregroundStyle(.secondary)
            if adb.adbPath == nil {
                Text("adb wasn't found. Run android/scripts/setup-and-build.sh, or install it with: brew install android-platform-tools")
                    .font(.caption).foregroundStyle(Theme.orange).textSelection(.enabled)
            }
        }
    }

    private var wirelessStatus: String {
        if let serial = adb.wirelessSerial { return "Connected over Wi-Fi (\(serial.split(separator: ":").first ?? ""))" }
        if !adb.wirelessEnabled { return "Off: mirroring needs the cable" }
        if adb.hasUSBPhone { return "Setting up…" }
        return "Plug in over USB once to set up. After a phone restart, plug in once again."
    }

    private var usbStatus: (text: String, color: Color) {
        if adb.adbPath == nil { return ("adb missing", Theme.orange) }
        if adb.hasUSBPhone { return ("Plugged in · forwarding on", Theme.connectedTint) }
        if adb.usbState == "unauthorized" { return ("Tap Allow on your phone", Theme.orange) }
        return ("No phone plugged in", .secondary)
    }

    private var mirroringCard: some View {
        SetupCard(symbol: "rectangle.on.rectangle", colors: [Theme.indigo, Theme.pink], title: "Advanced: apps in windows & camera",
                  status: adb.scrcpyPath == nil ? "Not installed" : "Ready",
                  statusColor: adb.scrcpyPath == nil ? .secondary : Theme.connectedTint) {
            Text("Mirror screen on the Overview page works for everyone. With USB or wireless debugging on, the open-source scrcpy (about 10 MB) adds phone apps in their own Mac windows, the phone camera, and advanced mirroring with full keyboard and multi-touch.")
                .font(.callout).foregroundStyle(.secondary)
            Toggle(isOn: $adb.wirelessEnabled) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("Mirror over Wi-Fi")
                    Text(wirelessStatus).font(.caption).foregroundStyle(adb.wirelessSerial != nil ? Theme.connectedTint : .secondary)
                }
            }
            .toggleStyle(.row)
            HStack {
                if adb.scrcpyPath == nil {
                    Button {
                        Task { await adb.installScrcpy() }
                    } label: {
                        if adb.installingScrcpy {
                            HStack(spacing: 6) { ProgressView().controlSize(.small); Text("Installing…") }
                        } else {
                            Text("Install mirroring")
                        }
                    }
                    .buttonStyle(.borderedProminent).tint(Theme.indigo)
                    .disabled(adb.installingScrcpy)
                } else {
                    Button("Advanced mirroring") { state.mirrorScreenAdvanced() }
                        .disabled(!adb.hasDevice)
                    Button("Phone camera") { state.openCamera() }
                        .disabled(!adb.hasDevice)
                }
            }
            if !adb.hasDevice {
                Text("These need a debugging connection: turn on USB debugging on the phone and plug it in. For everyday mirroring, use Mirror screen at the top of this page.")
                    .font(.caption).foregroundStyle(.secondary)
            }
        }
    }

    private var bluetoothCallsCard: some View {
        SetupCard(symbol: "phone.connection.fill", colors: [Theme.cyan, Theme.green], title: "Calls on this Mac (Bluetooth)",
                  status: bluetoothStatus.text, statusColor: bluetoothStatus.color) {
            Text("Answer regular phone calls on the Mac and talk through its speakers and mic, then move the call back to your phone anytime. Experimental. Android keeps call audio away from apps, so this uses Bluetooth, like a car kit.")
                .font(.callout).foregroundStyle(.secondary)
            Text("One-time setup: on the phone, turn on Bluetooth. On the Mac, open System Settings → Bluetooth and pair your phone. Then turn this on.")
                .font(.caption).foregroundStyle(.secondary)
            if state.bluetooth.macAudioBlocked {
                Label("This Mac's version of macOS doesn't let apps take phone-call audio, so calls answered here are heard on the phone. Answering, declining and ending from the Mac still work.",
                      systemImage: "speaker.slash.fill")
                    .font(.caption).foregroundStyle(Theme.orange)
            }
            Toggle("Use this Mac for phone calls", isOn: Binding(
                get: { state.bluetooth.enabled },
                set: { state.bluetooth.enabled = $0 }
            ))
                .toggleStyle(.row)
        }
    }

    private var bluetoothStatus: (text: String, color: Color) {
        switch state.bluetooth.status {
        case .off: return ("Off", .secondary)
        case .noPhone: return ("Pair your phone in Bluetooth settings", Theme.orange)
        case .connecting(let name): return ("Connecting to \(name)…", .secondary)
        case .ready(let name): return ("Ready · \(name)", Theme.connectedTint)
        case .failed(let message): return (message, Theme.orange)
        }
    }

    private var clipboardCard: some View {
        SetupCard(symbol: "doc.on.clipboard", colors: [Theme.orange, Theme.pink], title: "Automatic phone clipboard",
                  status: "Optional", statusColor: .secondary) {
            Text("Android blocks apps from reading the clipboard in the background, so phone→Mac copying needs a tap. This one-time USB setup makes it automatic: when you copy on the phone, AyPhone briefly reads it and sends it here.")
                .font(.callout).foregroundStyle(.secondary)
            Button {
                enablingClipboard = true
                Task {
                    _ = await adb.enableAutomaticClipboard()
                    enablingClipboard = false
                }
            } label: {
                if enablingClipboard {
                    HStack(spacing: 6) { ProgressView().controlSize(.small); Text("Setting up…") }
                } else {
                    Text("Enable over USB")
                }
            }
            .disabled(!adb.hasDevice || enablingClipboard)
        }
    }

    private func qrImage(_ text: String) -> NSImage? {
        let filter = CIFilter.qrCodeGenerator()
        filter.message = Data(text.utf8)
        filter.correctionLevel = "M"
        guard let output = filter.outputImage else { return nil }
        let scaled = output.transformed(by: CGAffineTransform(scaleX: 10, y: 10))
        let rep = NSCIImageRep(ciImage: scaled)
        let image = NSImage(size: rep.size)
        image.addRepresentation(rep)
        return image
    }
}

private struct SetupCard<Content: View>: View {
    let symbol: String
    let colors: [Color]
    let title: String
    let status: String
    let statusColor: Color
    @ViewBuilder var content: Content

    var body: some View {
        Glass(radius: 20) {
            VStack(alignment: .leading, spacing: 10) {
                HStack(spacing: 12) {
                    IconBadge(symbol: symbol, colors: colors, size: 34)
                    Text(title).font(.headline)
                    Spacer()
                    Text(status)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(statusColor)
                        .contentTransition(.opacity)
                        .animation(Theme.spring, value: status)
                }
                content
            }
        }
    }
}
