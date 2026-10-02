import AppIntents

struct StartSafetySOS: AppIntent {
    static var title: LocalizedStringResource = "Start SOS"
    static var description = IntentDescription("Open Security4Her and begin its cancellable SOS countdown.")
    static var openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        await AppModel.shared.launch()
        AppModel.shared.beginCountdown(trigger: "button")
        return .result()
    }
}

struct StartFakeCall: AppIntent {
    static var title: LocalizedStringResource = "Fake Call"
    static var description = IntentDescription("Open the simulated incoming call screen. This does not place a real call.")
    static var openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        await AppModel.shared.launch()
        AppModel.shared.deterrents.ring()
        return .result()
    }
}

struct SafetyShortcuts: AppShortcutsProvider {
    static var appShortcuts: [AppShortcut] {
        AppShortcut(intent: StartSafetySOS(), phrases: ["Start SOS with \(.applicationName)", "Help me with \(.applicationName)"], shortTitle: "Start SOS", systemImageName: "sos.circle.fill")
        AppShortcut(intent: StartFakeCall(), phrases: ["Fake call with \(.applicationName)"], shortTitle: "Fake Call", systemImageName: "phone.arrow.down.left")
    }
}
