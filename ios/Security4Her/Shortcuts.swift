import AppIntents

struct SafetyShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: StartSafetySOS(), phrases: ["Start SOS with \(.applicationName)", "Help me with \(.applicationName)"], shortTitle: "Start SOS", systemImageName: "sos.circle.fill")
        AppShortcut(intent: StartFakeCall(), phrases: ["Fake call with \(.applicationName)"], shortTitle: "Fake Call", systemImageName: "phone.arrow.down.left")
    }
}
