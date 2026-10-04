import Foundation
import UserNotifications

@MainActor
final class Notifications: NSObject, UNUserNotificationCenterDelegate {
    static let shared = Notifications()
    var onAction: ((String) -> Void)?
    func configure() {
        let center = UNUserNotificationCenter.current()
        center.delegate = self
        let open = UNNotificationAction(identifier: "open-fake-call", title: "Open call", options: [.foreground])
        let check = UNNotificationAction(identifier: "journey-check", title: "Check in", options: [.foreground])
        center.setNotificationCategories([
            .init(identifier: "fake-call", actions: [open], intentIdentifiers: []),
            .init(identifier: "journey", actions: [check], intentIdentifiers: [])
        ])
    }
    func authorize() async throws -> Bool {
        try await UNUserNotificationCenter.current().requestAuthorization(options: [.alert, .sound, .badge])
    }
    func schedule(id: String, title: String, body: String, after seconds: TimeInterval, category: String = "") async throws {
        let content = UNMutableNotificationContent()
        content.title = title; content.body = body; content.sound = .default; content.categoryIdentifier = category
        content.userInfo = ["action": category]
        let trigger = UNTimeIntervalNotificationTrigger(timeInterval: max(1, seconds), repeats: false)
        try await UNUserNotificationCenter.current().add(.init(identifier: id, content: content, trigger: trigger))
    }
    func cancel(_ id: String) {
        UNUserNotificationCenter.current().removePendingNotificationRequests(withIdentifiers: [id])
        UNUserNotificationCenter.current().removeDeliveredNotifications(withIdentifiers: [id])
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, willPresent notification: UNNotification,
                                           withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        if notification.request.content.categoryIdentifier == "fake-call" {
            Task { @MainActor in self.onAction?("fake-call") }
            completionHandler([])
        } else { completionHandler([.banner, .sound]) }
    }
    nonisolated func userNotificationCenter(_ center: UNUserNotificationCenter, didReceive response: UNNotificationResponse,
                                           withCompletionHandler completionHandler: @escaping () -> Void) {
        let action = response.notification.request.content.categoryIdentifier
        Task { @MainActor in self.onAction?(action); completionHandler() }
    }
}
