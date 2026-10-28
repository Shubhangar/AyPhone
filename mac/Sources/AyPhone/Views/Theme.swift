import SwiftUI

/// Shared look, from the logo: frosted glass over a deep purple→blue backdrop, a
/// violet→magenta accent, and springy motion.
enum Theme {
    /// Logo violet (#895AFF).
    static let indigo = Color(red: 0.537, green: 0.353, blue: 1.0)
    /// Logo magenta (#F36BFF), the accent's second colour.
    static let cyan = Color(red: 0.953, green: 0.420, blue: 1.0)
    /// Logo backdrop: deep purple (#361A6A) to deep blue (#084B7B).
    static let deepPurple = Color(red: 0.212, green: 0.102, blue: 0.416)
    static let deepBlue = Color(red: 0.031, green: 0.294, blue: 0.482)
    /// Night versions of the logo's purple and blue, used behind the UI so text stays readable.
    static let nightPurple = Color(red: 0.090, green: 0.043, blue: 0.180) // #170B2E
    static let nightBlue = Color(red: 0.016, green: 0.118, blue: 0.200)   // #041E33
    static let green = Color(red: 0.20, green: 0.84, blue: 0.48)
    /// Partner for green in gradients (answer, charging, received).
    static let mint = Color(red: 0.25, green: 0.86, blue: 0.78)
    static let orange = Color(red: 1.0, green: 0.58, blue: 0.20)
    static let pink = Color(red: 1.0, green: 0.30, blue: 0.52)
    static let red = Color(red: 1.0, green: 0.27, blue: 0.27)

    static let accentColors = [indigo, cyan]
    static let accent = LinearGradient(colors: accentColors, startPoint: .topLeading, endPoint: .bottomTrailing)

    static let spring = Animation.spring(response: 0.4, dampingFraction: 0.8)
    static let snappy = Animation.spring(response: 0.28, dampingFraction: 0.65)

    /// Stable avatar colors for names and numbers.
    static func colors(for seed: String) -> [Color] {
        let palettes: [[Color]] = [[indigo, cyan], [pink, orange], [green, mint], [orange, pink], [cyan, indigo]]
        let hash = seed.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xFFFF }
        return palettes[hash % palettes.count]
    }
}

// MARK: - Surfaces

/// Glassmorphism: a translucent blur of what's behind, a faint white sheen, and an edge that
/// catches the light at the top-left and picks up the accent at the bottom-right.
struct GlassBackground: ViewModifier {
    var radius: CGFloat

    func body(content: Content) -> some View {
        let shape = RoundedRectangle(cornerRadius: radius, style: .continuous)
        content
            .background {
                ZStack {
                    shape.fill(.ultraThinMaterial)
                    // A smoky tint keeps text crisp; the sheen keeps the glass feel.
                    shape.fill(Color.black.opacity(0.22))
                    shape.fill(LinearGradient(colors: [.white.opacity(0.07), .white.opacity(0.01)],
                                              startPoint: .topLeading, endPoint: .bottomTrailing))
                }
            }
            .overlay(
                shape.strokeBorder(LinearGradient(colors: [.white.opacity(0.24), .white.opacity(0.05), Theme.cyan.opacity(0.18)],
                                                  startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1)
            )
            .shadow(color: .black.opacity(0.28), radius: 22, y: 10)
    }
}

extension View {
    func glass(_ radius: CGFloat = 20) -> some View { modifier(GlassBackground(radius: radius)) }
}

struct Glass<Content: View>: View {
    var padding: CGFloat = 18
    var radius: CGFloat = 20
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .glass(radius)
    }
}

/// The window backdrop the glass sits on: the logo's purple→blue with slow magenta, violet
/// and blue glows drifting underneath.
struct AuroraBackground: View {
    @ViewState private var drift = false

    var body: some View {
        ZStack {
            LinearGradient(colors: [Theme.nightPurple, Theme.nightBlue], startPoint: .top, endPoint: .bottom)
            Circle()
                .fill(Theme.cyan.opacity(0.14))
                .frame(width: 520, height: 520)
                .blur(radius: 140)
                .offset(x: drift ? -220 : -80, y: drift ? -200 : -60)
            Circle()
                .fill(Theme.indigo.opacity(0.20))
                .frame(width: 560, height: 560)
                .blur(radius: 150)
                .offset(x: drift ? 260 : 140, y: drift ? 140 : 260)
            Circle()
                .fill(Color(red: 0.15, green: 0.55, blue: 0.95).opacity(0.12))
                .frame(width: 420, height: 420)
                .blur(radius: 130)
                .offset(x: drift ? 40 : -120, y: drift ? 320 : 180)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .ignoresSafeArea()
        .allowsHitTesting(false)
        .onAppear {
            withAnimation(.easeInOut(duration: 18).repeatForever(autoreverses: true)) { drift = true }
        }
    }
}

// MARK: - Controls

/// Lifts slightly on hover and squishes on press.
struct Pressable: ButtonStyle {
    var hoverScale: CGFloat = 1.03

    func makeBody(configuration: Configuration) -> some View {
        PressableBody(configuration: configuration, hoverScale: hoverScale)
    }
}

private struct PressableBody: View {
    let configuration: ButtonStyle.Configuration
    let hoverScale: CGFloat
    @ViewState private var hovering = false
    @Environment(\.isEnabled) private var enabled

    var body: some View {
        configuration.label
            .contentShape(Rectangle())
            .scaleEffect(configuration.isPressed ? 0.94 : (hovering && enabled ? hoverScale : 1))
            .brightness(hovering && enabled ? 0.04 : 0)
            .opacity(enabled ? 1 : 0.45)
            .animation(Theme.snappy, value: configuration.isPressed)
            .animation(Theme.spring, value: hovering)
            .onHover { hovering = $0 }
    }
}

extension ButtonStyle where Self == Pressable {
    static var pressable: Pressable { Pressable() }
    static func pressable(_ scale: CGFloat) -> Pressable { Pressable(hoverScale: scale) }
}

/// A round, glowing control (call answer/decline, media play).
struct RoundButton: View {
    let symbol: String
    var colors: [Color] = Theme.accentColors
    var size: CGFloat = 44
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: symbol)
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundStyle(.white)
                .frame(width: size, height: size)
                .background(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing), in: Circle())
                .shadow(color: (colors.first ?? .clear).opacity(0.45), radius: 10, y: 4)
        }
        .buttonStyle(.pressable(1.08))
    }
}

struct IconBadge: View {
    let symbol: String
    var colors: [Color] = Theme.accentColors
    var size: CGFloat = 38
    var pulsing = false

    var body: some View {
        Image(systemName: symbol)
            .font(.system(size: size * 0.44, weight: .semibold))
            .foregroundStyle(.white)
            .symbolEffect(.pulse, isActive: pulsing)
            .frame(width: size, height: size)
            .background(
                LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing),
                in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous)
            )
            .shadow(color: (colors.first ?? .clear).opacity(0.35), radius: 8, y: 3)
    }
}

/// Grid tile for quick actions.
struct ActionTile: View {
    let title: String
    var subtitle: String?
    let symbol: String
    var colors: [Color] = Theme.accentColors
    var active = false
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 12) {
                IconBadge(symbol: symbol, colors: colors, pulsing: active)
                VStack(alignment: .leading, spacing: 2) {
                    Text(title).font(.headline)
                    if let subtitle {
                        Text(subtitle).font(.caption).foregroundStyle(.secondary).lineLimit(1)
                    }
                }
            }
            .padding(16)
            .frame(maxWidth: .infinity, minHeight: 116, alignment: .topLeading)
            .glass(18)
            .overlay(
                RoundedRectangle(cornerRadius: 18, style: .continuous)
                    .strokeBorder(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing), lineWidth: 1.5)
                    .opacity(active ? 1 : 0)
            )
        }
        .buttonStyle(.pressable)
    }
}

struct Chip: View {
    let text: String
    var symbol: String?
    var tint: Color = .secondary

    var body: some View {
        HStack(spacing: 5) {
            if let symbol { Image(systemName: symbol) }
            Text(text)
        }
        .font(.caption.weight(.medium))
        .foregroundStyle(tint)
        .padding(.horizontal, 10)
        .padding(.vertical, 5)
        .background(.thinMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(.white.opacity(0.08)))
    }
}

// MARK: - Indicators

/// Rings rippling out from the phone while connected.
struct PulseRings: View {
    var active: Bool
    var color: Color = Theme.cyan
    @ViewState private var animate = false

    var body: some View {
        ZStack {
            ForEach(0..<3, id: \.self) { i in
                Circle()
                    .stroke(color.opacity(0.55), lineWidth: 1.5)
                    .scaleEffect(animate ? 1.9 : 0.85)
                    .opacity(animate ? 0 : (active ? 0.9 : 0))
                    .animation(
                        active ? .easeOut(duration: 2.7).repeatForever(autoreverses: false).delay(Double(i) * 0.9) : .easeOut(duration: 0.3),
                        value: animate
                    )
            }
        }
        .onAppear { animate = active }
        .onChange(of: active) { _, isActive in animate = isActive }
    }
}

struct BatteryRing: View {
    let level: Int?
    let charging: Bool
    var size: CGFloat = 86

    private var colors: [Color] {
        if charging { return [Theme.green, Theme.mint] }
        if let level, level <= 15 { return [Theme.red, Theme.orange] }
        return Theme.accentColors
    }

    var body: some View {
        ZStack {
            Circle().stroke(.primary.opacity(0.08), lineWidth: size * 0.09)
            Circle()
                .trim(from: 0, to: CGFloat(level ?? 0) / 100)
                .stroke(AngularGradient(colors: colors + [colors[0]], center: .center),
                        style: StrokeStyle(lineWidth: size * 0.09, lineCap: .round))
                .rotationEffect(.degrees(-90))
                .shadow(color: colors[0].opacity(0.4), radius: 6)
            VStack(spacing: 0) {
                HStack(alignment: .firstTextBaseline, spacing: 1) {
                    Text(level.map(String.init) ?? "–")
                        .font(.system(size: size * 0.28, weight: .semibold, design: .rounded))
                        .contentTransition(.numericText())
                    Text("%").font(.system(size: size * 0.13, weight: .medium)).foregroundStyle(.secondary)
                }
                Image(systemName: charging ? "bolt.fill" : "battery.75percent")
                    .font(.system(size: size * 0.13))
                    .foregroundStyle(charging ? Theme.green : .secondary)
                    .symbolEffect(.pulse, isActive: charging)
            }
        }
        .frame(width: size, height: size)
        .animation(.smooth(duration: 0.8), value: level)
    }
}

struct ToastView: View {
    let toast: Toast

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: toast.symbol)
                .foregroundStyle(Theme.accent)
                .symbolEffect(.bounce, value: toast.id)
            Text(toast.text).font(.callout.weight(.medium))
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 11)
        .background(.ultraThinMaterial, in: Capsule())
        .overlay(Capsule().strokeBorder(.white.opacity(0.12)))
        .shadow(color: .black.opacity(0.2), radius: 18, y: 8)
    }
}

struct EmptyState: View {
    let symbol: String
    let title: String
    let message: String

    var body: some View {
        VStack(spacing: 14) {
            Image(systemName: symbol)
                .font(.system(size: 44, weight: .light))
                .foregroundStyle(Theme.accent)
                .symbolEffect(.pulse)
            Text(title).font(.title2.weight(.semibold))
            Text(message)
                .multilineTextAlignment(.center)
                .foregroundStyle(.secondary)
                .frame(maxWidth: 360)
        }
        .frame(maxWidth: .infinity, maxHeight: .infinity)
        .padding(40)
    }
}

struct PageHeader<Trailing: View>: View {
    let title: String
    var subtitle: String?
    @ViewBuilder var trailing: Trailing

    var body: some View {
        HStack(alignment: .firstTextBaseline) {
            VStack(alignment: .leading, spacing: 4) {
                Text(title).font(.system(size: 30, weight: .bold, design: .rounded))
                if let subtitle { Text(subtitle).foregroundStyle(.secondary) }
            }
            Spacer()
            trailing
        }
    }
}

extension PageHeader where Trailing == EmptyView {
    init(title: String, subtitle: String? = nil) {
        self.init(title: title, subtitle: subtitle) { EmptyView() }
    }
}

struct Avatar: View {
    let name: String
    var size: CGFloat = 36

    private var initials: String {
        let letters = name.split(separator: " ").prefix(2).compactMap { $0.first(where: \.isLetter) }
        return letters.isEmpty ? "#" : String(letters).uppercased()
    }

    var body: some View {
        Text(initials)
            .font(.system(size: size * 0.38, weight: .semibold, design: .rounded))
            .foregroundStyle(.white)
            .frame(width: size, height: size)
            .background(LinearGradient(colors: Theme.colors(for: name), startPoint: .topLeading, endPoint: .bottomTrailing), in: Circle())
    }
}
