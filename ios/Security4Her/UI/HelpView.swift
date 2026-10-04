import SwiftUI

struct HelpView: View {
    @State private var question = ""
    @State private var answers: [String] = []
    var body: some View {
        List {
            Section { Text("Offline app guidance. This helper cannot contact emergency services or assess your situation.").font(.callout).foregroundStyle(.secondary) }
            Section("Ask about a feature") {
                TextField("SOS, evidence, fake call, journey…", text: $question).onSubmit(ask)
                Button("Ask") { ask() }.disabled(question.isEmpty)
            }
            ForEach(Array(answers.enumerated()), id: \.offset) { _, answer in Text(answer).textSelection(.enabled) }
            Section("Quick help") {
                DisclosureGroup("Start an SOS") { Text("Hold SOS for 1.5 seconds and let the cancellable countdown finish. Telegram alerts require internet and linked contacts. Tap Send SOS text to approve an SMS or Open phone call to call.") }
                DisclosureGroup("Use without internet") { Text("You can record evidence, use the siren and fake-call screen, and open a carrier SMS or phone call. Telegram and live-map updates wait for internet.") }
                DisclosureGroup("Protect evidence") { Text("Evidence is local, with fingerprint checks and an in-app deletion lock. Export important cases to a safe location. The exported ZIP is unencrypted, and uninstall removes app-private files.") }
                DisclosureGroup("Get help quickly") { Text("Use the system Emergency SOS feature when you need emergency services. Security4Her is an additional tool and does not replace it.") }
            }
        }.navigationTitle("Offline help")
    }
    private func ask() {
        let input = question.lowercased()
        let answer: String
        if input.contains("evidence") || input.contains("record") || input.contains("delete") {
            answer = "Evidence appears in the Evidence tab. Audio may continue in the background after you start it; video needs the app visible. Files can be exported after recording ends and deleted after 24 hours with your configured PIN. Uninstall removes local files."
        } else if input.contains("call") {
            answer = "Fake call simulates an incoming call without contacting anyone. Schedule it on Home. If the app is in the background, tap its notification to open. Real calls require iPhone confirmation."
        } else if input.contains("journey") {
            answer = "Choose a destination and deadline in Journey. A live journey needs linked Telegram contacts and a confirmed server monitor. Check in or end the journey before the deadline; cancellation needs server confirmation."
        } else if input.contains("watch") || input.contains("gesture") || input.contains("knock") {
            answer = "This port targets iPhone only. Galaxy Watch 4 cannot pair with iPhone. Use the Start SOS Shortcut with Siri, Back Tap or a supported Action button. Triple-knock practice works only while this app is open."
        } else if input.contains("sms") || input.contains("offline") || input.contains("internet") {
            answer = "iOS requires you to approve SMS in the message composer. Telegram and live maps need internet. Without data, use a carrier SMS or phone call and keep local evidence."
        } else {
            answer = "Hold SOS on Home to start a countdown. Add trusted contacts and review iPhone setup in Me. I can explain evidence, journeys, fake calls, gestures and offline behavior."
        }
        answers.insert("You: \(question)\n\n\(answer)", at: 0); question = ""
    }
}
