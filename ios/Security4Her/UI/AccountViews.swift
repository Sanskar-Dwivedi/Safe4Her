import SwiftUI
import AVFoundation
import UserNotifications
import CoreLocation

struct AuthenticationView: View {
    @EnvironmentObject private var model: AppModel
    @State private var creating = false
    @State private var name = ""
    @State private var email = ""
    @State private var password = ""
    var body: some View {
        NavigationStack {
            Form {
                Section {
                    VStack(alignment: .leading, spacing: 16) {
                        Image("BrandLogo").resizable().scaledToFit().frame(width: 76, height: 76).accessibilityHidden(true)
                        Text("Your safety.\nWithin reach.").font(.largeTitle.bold())
                        Text("Security4Her for iPhone").foregroundStyle(.secondary)
                    }.padding(.vertical, 20)
                }
                if !model.backend.config.configured {
                    Section { Text("This build has no server configuration. You can explore an offline demo; it cannot alert contacts.").foregroundStyle(.orange) }
                }
                Section(creating ? "Create account" : "Sign in") {
                    if creating { TextField("Name", text: $name).textContentType(.name) }
                    TextField("Email", text: $email).keyboardType(.emailAddress).textInputAutocapitalization(.never).autocorrectionDisabled().textContentType(.username)
                    SecureField("Password", text: $password).textContentType(creating ? .newPassword : .password)
                    Button {
                        Task { await model.authenticate(name: name, email: email, password: password, creating: creating) }
                    } label: { HStack { Text(creating ? "Create account" : "Sign in"); Spacer(); if model.busy { ProgressView() } } }
                        .disabled(model.busy || !model.backend.config.configured || email.isEmpty || password.count < 6 || (creating && name.isEmpty))
                    Button(creating ? "Already have an account? Sign in" : "Create an account") { creating.toggle() }
                }
                Section { Button("Explore offline demo") { model.enterDemo() } }
            }
        }
    }
}

struct VerificationView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        NavigationStack {
            Form {
                Section("Verify your account") {
                    Text("Complete the existing DigiLocker verification to use your account. Identity documents are processed by the configured verification service.")
                    Button("Open DigiLocker") { Task { await model.verifyIdentity() } }
                    Button("Refresh verification") { Task { do { model.profile = try await model.backend.profile(); if model.profile?.verifiedFemale == true { try await model.loadContacts() } } catch { model.report(error) } } }
                }
                #if DEBUG
                Section("Development backend only") {
                    Text("The backend must explicitly allow mock verification. This does not verify an identity.").font(.caption)
                    Button("Use backend mock verification") {
                        Task { do { _ = try await model.backend.rpc("mock_verify_female"); model.profile = try await model.backend.profile(); try await model.loadContacts() } catch { model.report(error) } }
                    }
                }
                #endif
                Button("Sign out") { Task { await model.signOut() } }
            }.navigationTitle("Verification")
        }
    }
}

struct ContactsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var editing: TrustedContact?
    @State private var adding = false
    var body: some View {
        List {
            Section {
                Text("SMS opens a composer for your approval. Link Telegram for automatic internet alerts. A linked chat is required for server-monitored journeys.").font(.callout).foregroundStyle(.secondary)
            }
            ForEach(model.contacts) { contact in
                Button { editing = contact } label: {
                    VStack(alignment: .leading, spacing: 6) {
                        Text(contact.name).font(.headline); Text(contact.phone).foregroundStyle(.secondary)
                        Text(contact.telegramChatId == nil ? "Telegram not linked" : "Telegram linked").font(.caption).foregroundStyle(contact.telegramChatId == nil ? .orange : .green)
                        if let code = contact.telegramLinkCode, contact.telegramChatId == nil {
                            Text("Ask them to send /start \(code) to your deployment’s Telegram bot.").font(.caption).textSelection(.enabled)
                        }
                    }
                }.buttonStyle(.plain)
                .swipeActions { Button("Delete", role: .destructive) { Task { await model.deleteContact(contact) } } }
            }
        }.navigationTitle("Trusted contacts")
            .toolbar { Button("Add", systemImage: "plus") { adding = true } }
            .refreshable { do { try await model.loadContacts() } catch { model.report(error) } }
            .sheet(isPresented: $adding) { ContactEditor(contact: .init(name: "", phone: ""), isNew: true) }
            .sheet(item: $editing) { ContactEditor(contact: $0, isNew: false) }
    }
}

struct ContactEditor: View {
    @EnvironmentObject private var model: AppModel
    @Environment(\.dismiss) private var dismiss
    @State var contact: TrustedContact
    let isNew: Bool
    @State private var picker = false
    var body: some View {
        NavigationStack {
            Form {
                Button("Choose from Contacts") { picker = true }
                TextField("Name", text: $contact.name)
                TextField("Phone number", text: $contact.phone).keyboardType(.phonePad)
                TextField("Relationship", text: Binding(get: { contact.relation ?? "" }, set: { contact.relation = $0 }))
                Stepper("Priority: \(contact.priority)", value: $contact.priority, in: 1...20)
                Toggle("Receive SOS alerts", isOn: $contact.receivesSos)
            }.navigationTitle(isNew ? "Add contact" : "Edit contact")
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                    ToolbarItem(placement: .confirmationAction) { Button("Save") { Task { await model.saveContact(contact, isNew: isNew); if model.error == nil { dismiss() } } }.disabled(contact.name.isEmpty || contact.phone.isEmpty) }
                }
                .sheet(isPresented: $picker) { ContactPicker { name, phone in contact.name = name; contact.phone = phone; picker = false } }
        }
    }
}

struct SettingsView: View {
    @EnvironmentObject private var model: AppModel
    @State private var newPIN = ""
    @State private var oldPIN = ""
    var body: some View {
        Form {
            Section(model.profile?.displayName ?? "Your account") {
                NavigationLink("Trusted contacts") { ContactsView() }
                NavigationLink("Permissions and iPhone setup") { SetupView() }
                NavigationLink("Activity log") { ActivityView() }
                NavigationLink("Offline help") { HelpView() }
            }
            Section("SOS") {
                Toggle("Test mode", isOn: $model.settings.testMode).disabled(model.demo || model.incident?.state == "active" || model.journey?.state == "active")
                TextField("Test phone number", text: $model.settings.testNumber).keyboardType(.phonePad)
                TextField("Call number", text: $model.settings.callNumber).keyboardType(.phonePad)
                Stepper("Countdown: \(model.settings.countdownSeconds) seconds", value: $model.settings.countdownSeconds, in: 3...30)
                Text("iOS requires confirmation for SMS and phone calls. Telegram alerts require internet and a configured server.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Evidence") {
                Toggle("Record audio during SOS", isOn: $model.settings.audioEvidence)
                Toggle("Record video while app is visible", isOn: $model.settings.videoEvidence)
                Text("Local recordings are removed on uninstall. Export important cases first. Encrypted recovery and automatic evidence sharing are not part of this version.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Safety PIN") {
                if SecureStore.hasPIN { SecureField("Current PIN", text: $oldPIN).keyboardType(.numberPad) }
                SecureField("New four-digit PIN", text: $newPIN).keyboardType(.numberPad)
                Button("Save PIN") {
                    guard SecureStore.acceptsPIN(oldPIN) else { model.error = "Incorrect current PIN."; return }
                    do { try SecureStore.setPIN(newPIN); oldPIN = ""; newPIN = ""; model.log("Safety PIN updated.") } catch { model.report(error) }
                }
                Text("This controls in-app SOS resolution and evidence deletion. It cannot prevent system uninstall or storage removal.").font(.caption).foregroundStyle(.secondary)
            }
            Section("Appearance") { Picker("Theme", selection: $model.settings.theme) { ForEach(Brand.themes, id: \.self) { Text($0.capitalized).tag($0) } } }
            Section("Pocket gesture") {
                Toggle("Triple knock while app is open", isOn: $model.settings.pocketGesture)
                    .onChange(of: model.settings.pocketGesture) { _, enabled in model.deterrents.monitorKnocks(enabled) }
                Text("Foreground practice only. Use the SOS Shortcut with Siri, Back Tap or the Action button for a system-supported launch. iOS does not provide Android’s always-on pocket service.").font(.caption).foregroundStyle(.secondary)
            }
            Section { Button("Sign out", role: .destructive) { Task { await model.signOut() } } }
        }.navigationTitle("Me")
    }
}

struct SetupView: View {
    @EnvironmentObject private var model: AppModel
    @State private var notificationStatus = "Not checked"
    var body: some View {
        List {
            Section("Permissions") {
                Button("Allow location") { model.location.requestPermission() }
                Button("Allow background location") { model.location.requestBackgroundPermission() }
                Text("Location status: \(String(describing: model.location.authorization))").font(.caption)
                Button("Allow microphone and camera") { Task { _ = await AVCaptureDevice.requestAccess(for: .audio); _ = await AVCaptureDevice.requestAccess(for: .video) } }
                Button("Allow notifications") { Task { do { notificationStatus = try await Notifications.shared.authorize() ? "Allowed" : "Not allowed" } catch { model.report(error) } } }
                Text("Notifications: \(notificationStatus)").font(.caption)
                Button("Open iPhone app settings") { if let url = URL(string: UIApplication.openSettingsURLString) { UIApplication.shared.open(url) } }
            }
            Section("Fast access") {
                Text("In Shortcuts, find Security4Her → Start SOS. You can assign that shortcut in iPhone Settings → Accessibility → Touch → Back Tap, or to the Action button on supported iPhones. Unlocking may be required.")
                Text("A separate Fake Call shortcut opens the simulated call. Background scheduled calls appear as notifications; tap to open them.")
            }
            Section("Availability") {
                Text("Camera evidence stops when this app leaves the foreground. Audio and location use permitted background modes but can be interrupted by iOS, calls, permissions or force quit.")
                Text("Safe Journey’s server deadline can alert linked Telegram contacts even when this app is suspended. Without a confirmed server deadline, only practice journeys are available.")
                Text("Galaxy Watch 4 does not pair with iPhone. This version targets iPhone only.")
            }
        }.navigationTitle("iPhone setup")
    }
}

struct ActivityView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        List(model.activity) { entry in VStack(alignment: .leading, spacing: 5) { Text(entry.message); Text(entry.at.formatted()).font(.caption).foregroundStyle(.secondary) } }
            .navigationTitle("Activity")
            .toolbar { ShareLink(item: model.activity.map { "\($0.at.formatted()): \($0.message)" }.joined(separator: "\n")) { Image(systemName: "square.and.arrow.up") } }
    }
}
