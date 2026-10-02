import SwiftUI
import MessageUI

@main
struct Security4HerApp: App {
    @StateObject private var model = AppModel.shared
    @Environment(\.scenePhase) private var phase
    var body: some Scene {
        WindowGroup {
            RootView().environmentObject(model)
                .preferredColorScheme(model.settings.theme == "daylight" ? .light : .dark)
                .tint(Brand.accent(model.settings.theme))
                .task { await model.launch() }
                .onChange(of: phase) { _, new in model.setForeground(new == .active) }
                .alert("Security4Her", isPresented: Binding(get: { model.error != nil }, set: { if !$0 { model.error = nil } })) {
                    Button("OK") { model.error = nil }
                } message: { Text(model.error ?? "") }
                .sheet(item: $model.messageDraft) { draft in
                    if MFMessageComposeViewController.canSendText() {
                        MessageComposer(draft: draft) { result in
                            model.log(result == .sent ? "Message composer reported sent; delivery is not confirmed." : result == .cancelled ? "SMS cancelled." : "SMS could not be sent.")
                            model.messageDraft = nil
                        }.ignoresSafeArea()
                    } else {
                        VStack(spacing: 20) {
                            Text("SMS is unavailable on this device").font(.title2.bold())
                            Text(draft.body).textSelection(.enabled)
                            ShareLink(item: draft.body) { Label("Share alert", systemImage: "square.and.arrow.up") }
                            Button("Close") { model.messageDraft = nil }
                        }.padding()
                    }
                }
                .fullScreenCover(isPresented: $model.deterrents.callVisible) { FakeCallView().environmentObject(model) }
        }
    }
}

struct RootView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        Group {
            if model.loading { ProgressView("Opening Security4Her") }
            else if model.profile == nil { AuthenticationView() }
            else if model.profile?.verifiedFemale != true { VerificationView() }
            else {
                TabView(selection: $model.selectedTab) {
                    NavigationStack { HomeView() }.tabItem { Label("Home", systemImage: "shield.lefthalf.filled") }.tag(0)
                    NavigationStack { JourneyView() }.tabItem { Label("Journey", systemImage: "figure.walk") }.tag(1)
                    NavigationStack { SafetyMapView() }.tabItem { Label("Map", systemImage: "map") }.tag(2)
                    NavigationStack { EvidenceListView() }.tabItem { Label("Evidence", systemImage: "lock.doc") }.tag(3)
                    NavigationStack { SettingsView() }.tabItem { Label("Me", systemImage: "person.crop.circle") }.tag(4)
                }
                .safeAreaInset(edge: .top, spacing: 0) {
                    if model.demo || model.settings.testMode { Text(model.demo ? "OFFLINE DEMO · No remote alerts" : "TEST MODE · No automatic contact alerts").font(.caption2.bold()).frame(maxWidth: .infinity).padding(7).background(.orange.opacity(0.15)) }
                }
            }
        }
    }
}
