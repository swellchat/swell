import SwiftUI

@main
struct SwellApp: App {
    @StateObject private var model = AppModel()
    @Environment(\.scenePhase) private var phase

    var body: some Scene {
        WindowGroup {
            ZStack {
                Ocean.background.ignoresSafeArea()
                switch model.stage {
                case .ready: ChatListView()
                default: OnboardingView()
                }
            }
            .environmentObject(model)
            .tint(Ocean.accent)
            .preferredColorScheme(Debug.has("-swellLight") ? .light : nil)
            .onChange(of: phase, initial: true) { _, p in model.scene(p) }
            .onAppear { if Debug.has("-swellTrace") { UIApplication.shared.isIdleTimerDisabled = true } }
            .onOpenURL { model.handleLink($0) }
            .onContinueUserActivity(NSUserActivityTypeBrowsingWeb) { if let u = $0.webpageURL { model.handleLink(u) } }
            .alert("Add this contact?", isPresented: Binding(get: { model.pendingInvite != nil && model.stage == .ready }, set: { if !$0 { model.pendingInvite = nil } })) {
                Button("Add") { if let c = model.pendingInvite?.link { model.pendingInvite = nil; Task { _ = await model.add(code: c) } } }
                Button("Cancel", role: .cancel) { model.pendingInvite = nil }
            } message: { Text("Someone shared a Swell code with you. If you know who it is from, add them to your chats.") }
        }
    }
}
