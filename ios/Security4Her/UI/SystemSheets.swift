import SwiftUI
import MessageUI
import ContactsUI
import Contacts

struct MessageComposer: UIViewControllerRepresentable {
    let draft: MessageDraft
    var onComplete: (MessageComposeResult) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(onComplete) }
    func makeUIViewController(context: Context) -> MFMessageComposeViewController {
        let controller = MFMessageComposeViewController()
        controller.messageComposeDelegate = context.coordinator
        controller.recipients = draft.recipients; controller.body = draft.body
        return controller
    }
    func updateUIViewController(_ controller: MFMessageComposeViewController, context: Context) {}
    final class Coordinator: NSObject, MFMessageComposeViewControllerDelegate {
        let complete: (MessageComposeResult) -> Void
        init(_ complete: @escaping (MessageComposeResult) -> Void) { self.complete = complete }
        func messageComposeViewController(_ controller: MFMessageComposeViewController, didFinishWith result: MessageComposeResult) { complete(result) }
    }
}

struct ShareSheet: UIViewControllerRepresentable {
    let items: [Any]
    func makeUIViewController(context: Context) -> UIActivityViewController { .init(activityItems: items, applicationActivities: nil) }
    func updateUIViewController(_ controller: UIActivityViewController, context: Context) {}
}

struct ContactPicker: UIViewControllerRepresentable {
    let selected: (String, String) -> Void
    func makeCoordinator() -> Coordinator { Coordinator(selected) }
    func makeUIViewController(context: Context) -> CNContactPickerViewController {
        let controller = CNContactPickerViewController()
        controller.delegate = context.coordinator
        controller.displayedPropertyKeys = [CNContactPhoneNumbersKey]
        controller.predicateForEnablingContact = NSPredicate(format: "phoneNumbers.@count > 0")
        controller.predicateForSelectionOfContact = NSPredicate(value: false)
        controller.predicateForSelectionOfProperty = NSPredicate(format: "key == 'phoneNumbers'")
        return controller
    }
    func updateUIViewController(_ controller: CNContactPickerViewController, context: Context) {}
    final class Coordinator: NSObject, CNContactPickerDelegate {
        let selected: (String, String) -> Void
        init(_ selected: @escaping (String, String) -> Void) { self.selected = selected }
        func contactPicker(_ picker: CNContactPickerViewController, didSelect contactProperty: CNContactProperty) {
            guard let number = contactProperty.value as? CNPhoneNumber else { return }
            selected(CNContactFormatter.string(from: contactProperty.contact, style: .fullName) ?? "Contact", number.stringValue)
        }
    }
}

struct SharedFile: Identifiable { let url: URL; var id: URL { url } }
