import AppIntents

enum SafetyAction: String { case sos, fakeCall, journey }

@available(iOS 18.0, *)
enum SafetyControlDestination: String, AppEnum {
    case sos, fakeCall, journey
    static var typeDisplayRepresentation: TypeDisplayRepresentation = "Safety action"
    static var caseDisplayRepresentations: [Self: DisplayRepresentation] = [
        .sos: "Start SOS", .fakeCall: "Fake call", .journey: "Journey check-in"
    ]
}

@available(iOS 18.0, *)
struct OpenSafetyControl: OpenIntent {
    static var title: LocalizedStringResource = "Open safety action"
    static var openAppWhenRun = true
    @Parameter(title: "Action") var target: SafetyControlDestination
    init() { target = .sos }
    init(target: SafetyControlDestination) { self.target = target }
    @MainActor func perform() async throws -> some IntentResult & ProvidesDialog {
        let action: SafetyAction
        switch target { case .sos: action = .sos; case .fakeCall: action = .fakeCall; case .journey: action = .journey }
        let message = try await performSafetyAction(action)
        return .result(dialog: IntentDialog(stringLiteral: message))
    }
}

// Included in both targets. openAppWhenRun routes execution to the containing app.
struct StartSafetySOS: AppIntent {
    static var title: LocalizedStringResource = "Start SOS"
    static var description = IntentDescription("Open Security4Her and begin a cancellable SOS countdown once the app is active.")
    static var openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult & ProvidesDialog {
        let message = try await performSafetyAction(.sos)
        return .result(dialog: IntentDialog(stringLiteral: message))
    }
}

struct StartFakeCall: AppIntent {
    static var title: LocalizedStringResource = "Fake Call"
    static var openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult & ProvidesDialog {
        let message = try await performSafetyAction(.fakeCall)
        return .result(dialog: IntentDialog(stringLiteral: message))
    }
}

struct OpenJourneyCheckIn: LiveActivityIntent {
    static var title: LocalizedStringResource = "Open journey check-in"
    static var openAppWhenRun = true
    @MainActor func perform() async throws -> some IntentResult {
        #if !WIDGET_EXTENSION
        _ = try await AppModel.shared.requestSafetyAction(.journey)
        #endif
        return .result()
    }
}

@MainActor private func performSafetyAction(_ action: SafetyAction) async throws -> String {
    #if WIDGET_EXTENSION
    throw SafetyIntentFailure.openApp
    #else
    return try await AppModel.shared.requestSafetyAction(action)
    #endif
}

private enum SafetyIntentFailure: Error, CustomLocalizedStringResourceConvertible {
    case openApp
    var localizedStringResource: LocalizedStringResource { "Open Security4Her to continue. No SOS has started." }
}
