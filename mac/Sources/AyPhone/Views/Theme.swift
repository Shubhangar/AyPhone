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

    /// Call buttons: answer violet→blue, decline/end violet→pink, neutral grey.
    static let answerColors = [Color(hex: 0x7F48ED), Color(hex: 0x3A4AF4)]
    static let declineColors = [Color(hex: 0x7045FF), pink]
    static let neutralColors = [Color(hex: 0x8F8F94), Color(hex: 0x66666B)]
    /// "Phone · Incoming call" above the caller's name.
    static let callLabel = Color(hex: 0xA6C2FF)
    /// The ring around the caller's avatar.
    static let avatarRing = Color(hex: 0x9D33D6)
    /// The "Connected" chip.
    static let connectedTint = Color(hex: 0xEE90FF)
    /// The connected dot in the sidebar.
    static let connectedDot = Color(hex: 0x13ECF3)
    /// Glass controls on glass: a faint white fill and edge.
    static let glassFill = Color.white.opacity(0.09)
    static let glassEdge = Color.white.opacity(0.16)

    /// Icon badges are solid: each gradient pair a view asks for maps to one colour.
    static func badgeColor(_ colors: [Color]) -> Color {
        let solid: [([Color], Color)] = [
            ([cyan, green], cyan),
            ([orange, pink], Color(hex: 0x6349D9)),
            ([indigo, pink], Color(hex: 0xB665EF)),
            ([pink, orange], pink),
            ([cyan, indigo], Color(hex: 0x3C75E8)),
        ]
        return solid.first { $0.0 == colors }?.1 ?? colors.first ?? indigo
    }

    static let spring = Animation.spring(response: 0.4, dampingFraction: 0.8)
    static let snappy = Animation.spring(response: 0.28, dampingFraction: 0.65)


    /// Stable avatar colors for names and numbers.
    static func colors(for seed: String) -> [Color] {
        let palettes: [[Color]] = [[indigo, cyan], [pink, orange], [green, mint], [orange, pink], [cyan, indigo]]
        let hash = seed.unicodeScalars.reduce(0) { ($0 &* 31 &+ Int($1.value)) & 0xFFFF }
        return palettes[hash % palettes.count]
    }
}

/// A light trackpad tap (on trackpads that have one) for actions that should feel physical.
enum Haptics {
    static func tap() {
        NSHapticFeedbackManager.defaultPerformer.perform(.generic, performanceTime: .now)
    }
}

/// A soft ring that expands from a control and fades, each time [trigger] changes.
struct RippleEffect<S: Shape>: ViewModifier {
    let shape: S
    var color: Color = .white
    let trigger: Int

    private struct Value { var scale: CGFloat = 1; var opacity: Double = 0 }

    func body(content: Content) -> some View {
        content.keyframeAnimator(initialValue: Value(), trigger: trigger) { view, value in
            view.overlay(shape.stroke(color, lineWidth: 2).scaleEffect(value.scale).opacity(value.opacity))
        } keyframes: { _ in
            KeyframeTrack(\.scale) {
                LinearKeyframe(1, duration: 0.01)
                CubicKeyframe(1.22, duration: 0.5)
            }
            KeyframeTrack(\.opacity) {
                LinearKeyframe(0.75, duration: 0.01)
                CubicKeyframe(0, duration: 0.5)
            }
        }
    }
}

extension View {
    func ripple<S: Shape>(_ shape: S, color: Color = .white, trigger: Int) -> some View {
        modifier(RippleEffect(shape: shape, color: color, trigger: trigger))
    }

    /// A slow glow that swells and settles, for something waiting on you (a ringing call).
    @ViewBuilder
    func breathingGlow(_ color: Color, active: Bool) -> some View {
        if active {
            phaseAnimator([false, true]) { view, swell in
                view.shadow(color: color.opacity(swell ? 0.6 : 0.15), radius: swell ? 14 : 5)
            } animation: { _ in .easeInOut(duration: 1.1) }
        } else {
            self
        }
    }

    /// A phone icon that wiggles every so often while a call rings (macOS 15 and later).
    @ViewBuilder
    func ringingWiggle(_ active: Bool) -> some View {
        if #available(macOS 15, *) {
            symbolEffect(.wiggle, options: .repeat(.periodic(delay: 0.9)), isActive: active)
        } else {
            self
        }
    }
}

extension Color {
    init(hex: UInt32) {
        self.init(red: Double((hex >> 16) & 0xFF) / 255, green: Double((hex >> 8) & 0xFF) / 255, blue: Double(hex & 0xFF) / 255)
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
    /// Keeps content that spills out (rings) inside the card's shape.
    var clipped = false
    @ViewBuilder var content: Content

    var body: some View {
        content
            .padding(padding)
            .frame(maxWidth: .infinity, alignment: .leading)
            .clipShape(RoundedRectangle(cornerRadius: clipped ? radius : 0, style: .continuous))
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
    @ViewState private var taps = 0

    var body: some View {
        Button {
            taps += 1
            Haptics.tap()
            action()
        } label: {
            Image(systemName: symbol)
                .font(.system(size: size * 0.4, weight: .semibold))
                .foregroundStyle(.white)
                .contentTransition(.symbolEffect(.replace))
                .symbolEffect(.bounce, value: taps)
                .frame(width: size, height: size)
                .background(LinearGradient(colors: colors, startPoint: .topLeading, endPoint: .bottomTrailing), in: Circle())
                .ripple(Circle(), color: colors.first ?? .white, trigger: taps)
                .shadow(color: (colors.first ?? .clear).opacity(0.45), radius: 10, y: 4)
                .animation(Theme.spring, value: colors)
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
            .background(Theme.badgeColor(colors), in: RoundedRectangle(cornerRadius: size * 0.3, style: .continuous))
            .shadow(color: Theme.badgeColor(colors).opacity(0.35), radius: 8, y: 3)
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
    @ViewState private var taps = 0
    @ViewState private var hovers = 0

    var body: some View {
        Button {
            taps += 1
            action()
        } label: {
            VStack(alignment: .leading, spacing: 12) {
                IconBadge(symbol: symbol, colors: colors, pulsing: active)
                    .symbolEffect(.bounce, value: taps + hovers)
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
                    .strokeBorder(Theme.badgeColor(colors), lineWidth: 1.5)
                    .opacity(active ? 1 : 0)
            )
        }
        .buttonStyle(.pressable)
        .onHover { if $0 { hovers += 1 } }
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
        .background(Theme.glassFill, in: Capsule())
        .overlay(Capsule().strokeBorder(Theme.glassEdge))
    }
}

/// A magnifying glass circling slowly, as if looking around (while searching for the phone).
struct SearchingGlass: View {
    var size: CGFloat = 40
    var radius: CGFloat = 8

    var body: some View {
        TimelineView(.animation) { context in
            let angle = context.date.timeIntervalSinceReferenceDate / 1.6 * 2 * .pi
            Image(systemName: "magnifyingglass")
                .font(.system(size: size, weight: .light))
                .foregroundStyle(Theme.accent)
                .offset(x: cos(angle) * radius, y: sin(angle) * radius)
        }
    }
}

/// A bare icon button whose icon bounces when clicked (media controls).
struct BouncingIconButton: View {
    let symbol: String
    let action: () -> Void
    @ViewState private var taps = 0

    var body: some View {
        Button {
            taps += 1
            action()
        } label: {
            Image(systemName: symbol).font(.system(size: 16, weight: .semibold)).frame(width: 28, height: 28)
                .symbolEffect(.bounce, value: taps)
        }
        .buttonStyle(.pressable(1.15))
    }
}

/// Figma's bordered button: white text on a faint glass fill (macOS's own bordered style
/// looks disabled on the dark glass). A destructive one gets magenta text.
struct GlassButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        GlassButtonBody(configuration: configuration)
    }
}

/// Brightens under the pointer and settles back when pressed.
private struct GlassButtonBody: View {
    let configuration: ButtonStyle.Configuration
    @ViewState private var hovering = false

    var body: some View {
        configuration.label
            .foregroundStyle(configuration.role == .destructive ? Theme.cyan : .white)
            .padding(.horizontal, 10)
            .padding(.vertical, 4)
            .background(Color.white.opacity(hovering ? 0.15 : 0.09), in: RoundedRectangle(cornerRadius: 6, style: .continuous))
            .onHover { inside in withAnimation(.easeOut(duration: 0.15)) { hovering = inside } }
            .overlay(RoundedRectangle(cornerRadius: 6, style: .continuous).strokeBorder(Theme.glassEdge))
            .opacity(configuration.isPressed ? 0.75 : 1)
            .scaleEffect(configuration.isPressed ? 0.96 : 1)
            .animation(Theme.snappy, value: configuration.isPressed)
            .contentShape(Rectangle())
    }
}

extension ButtonStyle where Self == GlassButtonStyle {
    static var glass: GlassButtonStyle { GlassButtonStyle() }
}

/// A settings row: icon and title on the left, the switch at the far right.
struct RowToggleStyle: ToggleStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 8) {
            configuration.label.labelStyle(RowLabelStyle())
                .symbolEffect(.bounce, value: configuration.isOn)
            Spacer(minLength: 12)
            Toggle("", isOn: configuration.$isOn.animation(Theme.spring)).toggleStyle(.switch).labelsHidden()
        }
    }
}

private struct RowLabelStyle: LabelStyle {
    func makeBody(configuration: Configuration) -> some View {
        HStack(spacing: 8) {
            configuration.icon.foregroundStyle(Theme.cyan).frame(width: 18)
            configuration.title
        }
    }
}

extension ToggleStyle where Self == RowToggleStyle {
    static var row: RowToggleStyle { RowToggleStyle() }
}

// MARK: - Indicators

/// Rings rippling out from the phone while connected.
struct PulseRings: View {
    var active: Bool
    var color: Color = Theme.cyan
    private static let period = 2.7

    /// Driven by the clock rather than a repeating animation, so each ring fades in as it
    /// starts and out as it grows, and the loop never jumps.
    var body: some View {
        TimelineView(.animation(paused: !active)) { context in
            let time = context.date.timeIntervalSinceReferenceDate / Self.period
            ZStack {
                ForEach(0..<3, id: \.self) { i in
                    let t = (time + Double(i) / 3).truncatingRemainder(dividingBy: 1)
                    Circle()
                        .stroke(color.opacity(0.55), lineWidth: 1.5)
                        .scaleEffect(0.85 + 1.05 * (1 - (1 - t) * (1 - t)))
                        .opacity(0.9 * min(1, t / 0.15) * (1 - t))
                }
            }
        }
        .opacity(active ? 1 : 0)
        .animation(.easeOut(duration: 0.3), value: active)
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
