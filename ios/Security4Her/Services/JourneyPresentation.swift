import ActivityKit
import SwiftUI
import Combine
#if canImport(AlarmKit)
import AlarmKit
#endif

/// A display and reminder only. The server owns escalation; dismissing UI never ends a session.
@MainActor
final class JourneyPresentation: ObservableObject {
    static let shared = JourneyPresentation()
    @Published private(set) var reminderStatus = "No reminder scheduled"
    private var work: Task<Void, Never>?
    private var reminderKey: String?
    private var generation = 0
    private var alarmID: UUID? {
        get { UserDefaults.standard.string(forKey: "journeyAlarmID").flatMap(UUID.init(uuidString:)) }
        set { UserDefaults.standard.set(newValue?.uuidString, forKey: "journeyAlarmID") }
    }

    func update(_ journey: JourneyState?, alarmEnabled: Bool) {
        generation += 1
        let revision = generation
        let previous = work
        work = Task {
            await previous?.value
            guard revision == generation else { return }
            await apply(journey, alarmEnabled: alarmEnabled, revision: revision)
        }
    }

    private func apply(_ journey: JourneyState?, alarmEnabled: Bool, revision: Int) async {
        let visible = journey.map { $0.state == "active" || $0.endPending } ?? false
        let status = journey.map { $0.endPending ? "Ending · awaiting server" : $0.serverPending ? "Waiting for server" :
            $0.pendingDeadline != nil ? "Deadline change awaiting server" : $0.serverArmed ? "Server monitoring armed" : "Practice · no remote alerts" } ?? "Journey ended"
        let content = ActivityContent(state: JourneyActivityAttributes.ContentState(status: status,
            deadline: journey?.serverArmed == true ? journey?.deadline : nil, acknowledgedAt: journey?.acknowledgedAt),
            staleDate: Date().addingTimeInterval(90))
        var matching: Activity<JourneyActivityAttributes>?
        for activity in Activity<JourneyActivityAttributes>.activities {
            if visible && activity.attributes.journeyID == journey?.id { matching = activity; await activity.update(content) }
            else { await activity.end(nil, dismissalPolicy: .immediate) }
        }
        if visible, let journey, matching == nil, ActivityAuthorizationInfo().areActivitiesEnabled {
            do { _ = try Activity.request(attributes: JourneyActivityAttributes(journeyID: journey.id), content: content, pushType: nil) }
            catch { /* Live Activities may be disabled or the app may be backgrounded. The server session continues. */ }
        }
        guard let journey, journey.state == "active", !journey.endPending, !journey.serverPending else {
            Notifications.shared.cancel("journey"); reminderKey = nil
            if cancelAlarm() { reminderStatus = "No reminder scheduled" }
            return
        }
        let key = "\(journey.id):\(journey.deadline.timeIntervalSince1970):\(alarmEnabled)"
        guard key != reminderKey else { return }
        // Keep the previous reminder until the new server deadline has been acknowledged.
        let date = journey.deadline.addingTimeInterval(-120)
        guard journey.deadline > Date() else { reminderStatus = "Deadline passed · open check-in"; return }
        guard revision == generation, cancelAlarm() else { return }
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *), alarmEnabled {
            do {
                guard try await AlarmManager.shared.requestAuthorization() == .authorized else {
                    throw AppFailure.message("Alarm permission is off")
                }
                guard revision == generation else { return }
                let alert = AlarmPresentation.Alert(title: "Journey check-in",
                    stopButton: AlarmButton(text: "Dismiss", textColor: .white, systemImageName: "stop.circle"),
                    secondaryButton: AlarmButton(text: "Open check-in", textColor: .white, systemImageName: "arrow.right.circle"),
                    secondaryButtonBehavior: .custom)
                let attributes = AlarmAttributes(presentation: AlarmPresentation(alert: alert), metadata: CheckInMetadata(), tintColor: Color.purple)
                let id = UUID()
                _ = try await AlarmManager.shared.schedule(id: id, configuration: .alarm(
                    schedule: .fixed(max(date, Date().addingTimeInterval(2))), attributes: attributes, secondaryIntent: OpenJourneyCheckIn()))
                alarmID = id
                guard revision == generation else { _ = cancelAlarm(); return }
                reminderKey = key; Notifications.shared.cancel("journey")
                reminderStatus = "System alarm scheduled. Dismissal does not check you in."; return
            } catch { reminderStatus = "Alarm unavailable; trying a notification." }
        }
        #endif
        do {
            guard try await Notifications.shared.authorize() else { reminderStatus = "Notifications are off. Check in manually."; return }
            guard revision == generation else { return }
            try await Notifications.shared.schedule(id: "journey", title: "Journey check-in",
                body: "Open Security4Her and confirm you are safe. Dismissing this reminder does not check you in.",
                after: max(1, date.timeIntervalSinceNow), category: "journey")
            reminderKey = key; reminderStatus = "Notification scheduled; sound depends on iPhone settings."
        } catch { reminderStatus = "Reminder could not be scheduled. Check in manually." }
    }
    @discardableResult private func cancelAlarm() -> Bool {
        #if canImport(AlarmKit)
        if #available(iOS 26.0, *), let id = alarmID {
            do {
                // An alarm the user already dismissed is no longer present.
                if try AlarmManager.shared.alarms.contains(where: { $0.id == id }) { try AlarmManager.shared.cancel(id: id) }
            } catch { reminderStatus = "Could not cancel the previous alarm. Review alarms on this iPhone; dismissing one does not check you in."; return false }
        }
        #endif
        alarmID = nil
        return true
    }
}

#if canImport(AlarmKit)
@available(iOS 26.0, *)
private struct CheckInMetadata: AlarmMetadata {}
#endif
