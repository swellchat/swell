import SwiftUI
import LocalAuthentication

/// Face ID / passcode check.
enum Gate {
    @MainActor static func unlock(reason: String) async -> Bool {
        let ctx = LAContext()
        var err: NSError?
        guard ctx.canEvaluatePolicy(.deviceOwnerAuthentication, error: &err) else { return true } // no passcode on this iPhone: nothing to ask
        return (try? await ctx.evaluatePolicy(.deviceOwnerAuthentication, localizedReason: reason)) ?? false
    }
}

/// Covers the app with a blur (app switcher, screen recording) and, when Lock Swell is on, asks to unlock.
struct PrivacyCover: ViewModifier {
    @EnvironmentObject var model: AppModel
    @Environment(\.scenePhase) private var phase
    @State private var locked = false
    @State private var captured = UIScreen.main.isCaptured
    @State private var asking = false

    func body(content: Content) -> some View {
        content
            .overlay {
                if (model.screenSecurity && (phase != .active || captured)) || locked {
                    ZStack {
                        Rectangle().fill(.regularMaterial).ignoresSafeArea()
                        VStack(spacing: 14) {
                            BrandMark(size: 72)
                            if locked && phase == .active {
                                Button { unlock() } label: { Label("Unlock Swell", systemImage: "faceid") }.buttonStyle(PrimaryButton()).frame(maxWidth: 240)
                            }
                        }
                    }.transition(.opacity)
                }
            }
            .animation(.easeOut(duration: 0.15), value: phase)
            .onChange(of: phase) { _, p in
                if p == .background, model.appLock { locked = true }
                if p == .active, locked { unlock() }
            }
            .onAppear { if model.appLock, model.stage == .ready { locked = true; unlock() } }
            .onReceive(NotificationCenter.default.publisher(for: UIScreen.capturedDidChangeNotification)) { _ in captured = UIScreen.main.isCaptured }
    }

    private func unlock() {
        guard !asking else { return }
        asking = true
        Task { if await Gate.unlock(reason: "Open Swell") { locked = false }; asking = false }
    }
}
extension View { func privacyCover() -> some View { modifier(PrivacyCover()) } }
