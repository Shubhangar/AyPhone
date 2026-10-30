import SwiftUI

/// The logo's entrance when the app opens: it springs in with a little nod, sends out a
/// violet ring, then lifts away to reveal the window. Once per launch.
struct LaunchSplash: View {
    @MainActor private static var played = false
    @ViewState private var visible = !LaunchSplash.played
    @ViewState private var logoIn = false
    @ViewState private var nod = 0
    @ViewState private var ring = false
    @ViewState private var leaving = false

    var body: some View {
        if visible {
            ZStack {
                LinearGradient(colors: [Theme.nightPurple, Theme.nightBlue], startPoint: .top, endPoint: .bottom)
                Circle()
                    .fill(Theme.indigo.opacity(0.35))
                    .frame(width: 220, height: 220)
                    .blur(radius: 70)
                    .scaleEffect(logoIn ? 1 : 0.4)
                Circle()
                    .stroke(Theme.indigo.opacity(0.8), lineWidth: 2)
                    .frame(width: 110, height: 110)
                    .scaleEffect(ring ? 2.4 : 0.8)
                    .opacity(ring ? 0 : (logoIn ? 0.9 : 0))
                Image(nsImage: LogoGlyph.connected)
                    .resizable()
                    .renderingMode(.template)
                    .foregroundStyle(.white)
                    .frame(width: 88, height: 88)
                    .keyframeAnimator(initialValue: 0.0, trigger: nod) { view, angle in
                        view.rotationEffect(.degrees(angle))
                    } keyframes: { _ in
                        SpringKeyframe(-12, duration: 0.18)
                        SpringKeyframe(8, duration: 0.18)
                        SpringKeyframe(0, duration: 0.3)
                    }
                    .scaleEffect(leaving ? 1.25 : (logoIn ? 1 : 0.55))
                    .opacity(logoIn ? 1 : 0)
                    .blur(radius: logoIn ? 0 : 8)
                    .shadow(color: Theme.indigo.opacity(0.6), radius: 18)
            }
            .ignoresSafeArea()
            .opacity(leaving ? 0 : 1)
            .allowsHitTesting(!leaving)
            .task { await play() }
        }
    }

    private func play() async {
        Self.played = true
        withAnimation(.spring(response: 0.55, dampingFraction: 0.7)) { logoIn = true }
        try? await Task.sleep(for: .milliseconds(380))
        nod += 1
        withAnimation(.easeOut(duration: 0.9)) { ring = true }
        try? await Task.sleep(for: .milliseconds(650))
        withAnimation(.easeIn(duration: 0.35)) { leaving = true }
        try? await Task.sleep(for: .milliseconds(380))
        visible = false
    }
}
