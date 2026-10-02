import SwiftUI

struct HomeView: View {
    @EnvironmentObject private var model: AppModel
    @State private var pin = ""
    @State private var resolving = false
    var body: some View {
        ScrollView {
            VStack(spacing: 18) {
                HStack {
                    VStack(alignment: .leading, spacing: 6) {
                        Text("Here for you,\n\(model.profile?.displayName ?? "always").").font(.largeTitle.bold())
                        Text("Help starts with one action.").foregroundStyle(.secondary)
                    }; Spacer()
                    Image("BrandLogo").resizable().scaledToFit().frame(width: 58, height: 58).accessibilityHidden(true)
                }.padding(.top, 12)
                if let seconds = model.countdown {
                    SafetyCard(title: "SOS in \(seconds) seconds") {
                        Text("Keep the app open until the countdown finishes.")
                        Button("Cancel countdown", role: .cancel) { model.cancelCountdown() }.buttonStyle(.borderedProminent)
                    }
                } else if let incident = model.incident, incident.state == "active" {
                    SafetyCard(title: incident.test ? "Test SOS active" : "SOS active") {
                        Text(incident.startedAt, style: .timer).font(.largeTitle.monospacedDigit())
                        StatusLine(title: "Server", detail: incident.serverAccepted ? "Incident accepted" : incident.error ?? "Waiting for internet", good: incident.serverAccepted)
                        StatusLine(title: "Telegram", detail: incident.test ? "Disabled in Test Mode" : "\(incident.telegramAccepted) accepted · \(incident.telegramPending) queued", good: incident.telegramAccepted > 0)
                        StatusLine(title: "Evidence", detail: "Audio \(model.evidence.audioOn ? "recording" : "off") · video \(model.evidence.videoOn ? "recording" : "off")", good: model.evidence.audioOn || model.evidence.videoOn)
                        if let failure = model.evidence.error { Text(failure).font(.caption).foregroundStyle(.orange) }
                        Button("Send SOS text") { model.composeSOS() }.buttonStyle(.borderedProminent)
                        Button("Open phone call") { model.dial() }.buttonStyle(.bordered)
                        if let raw = incident.shareURL, let url = URL(string: raw) { ShareLink("Share live map", item: url) }
                        Button("I’m safe — end SOS") { resolving = true }.padding(.top, 8)
                    }
                } else {
                    VStack(spacing: 12) {
                        Text("SOS").font(.system(size: 58, weight: .black, design: .rounded))
                        Text("HOLD FOR 1.5 SECONDS").font(.caption.bold()).tracking(1.5)
                    }.foregroundStyle(.white).frame(maxWidth: .infinity).frame(height: 205)
                        .background(Brand.sos, in: RoundedRectangle(cornerRadius: 32))
                        .contentShape(RoundedRectangle(cornerRadius: 32))
                        .onLongPressGesture(minimumDuration: 1.5) { model.beginCountdown() }
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("Start SOS countdown")
                        .accessibilityHint("Double tap to start a cancellable countdown")
                        .accessibilityAddTraits(.isButton)
                        .accessibilityAction { model.beginCountdown() }
                    if model.incident?.endPending == true { Text("Ending the previous SOS on the server is still pending.").foregroundStyle(.orange) }
                }
                LazyVGrid(columns: [.init(.flexible()), .init(.flexible())], spacing: 12) {
                    NavigationLink { FakeCallSetupView() } label: { action("Fake call", "phone.arrow.down.left") }
                    NavigationLink { SirenView() } label: { action("Siren", "speaker.wave.3.fill") }
                    Button { model.selectedTab = 1 } label: { action("Safe journey", "figure.walk") }
                    Button { model.selectedTab = 2 } label: { action("Safe places", "map.fill") }
                }.buttonStyle(.plain)
                SafetyCard(title: "Share live location") {
                    if model.liveTopic != nil {
                        Text(model.liveStatus).foregroundStyle(.secondary)
                        if let url = model.liveURL { ShareLink("Send live-map link", item: url) }
                        Button("Stop sharing", role: .destructive) { model.stopLive() }
                    } else {
                        Text("Share for one hour using the existing ntfy relay. Anyone with the link can see updates; already published updates are not recalled.").font(.caption).foregroundStyle(.secondary)
                        Button("Start sharing") { model.startLive() }
                    }
                }
                if model.contacts.isEmpty { NavigationLink("Add your trusted contacts") { ContactsView() } }
                NavigationLink("Review iPhone permissions and shortcuts") { SetupView() }.font(.footnote)
            }.padding(20)
        }.background(Color(uiColor: .systemGroupedBackground)).navigationTitle("Security4Her").navigationBarTitleDisplayMode(.inline)
            .alert("End SOS", isPresented: $resolving) {
                if SecureStore.hasPIN { SecureField("Safety PIN", text: $pin).keyboardType(.numberPad) }
                Button("End SOS") { Task { await model.endSOS(pin: pin); pin = "" } }
                Button("Keep active", role: .cancel) { pin = "" }
            } message: { Text("Recording will stop. The server must confirm before the live incident is resolved remotely.") }
    }
    private func action(_ title: String, _ icon: String) -> some View {
        VStack(alignment: .leading, spacing: 14) { Image(systemName: icon).font(.title2); Text(title).font(.headline) }
            .frame(maxWidth: .infinity, alignment: .leading).padding(20)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22))
    }
}

struct FakeCallSetupView: View {
    @EnvironmentObject private var model: AppModel
    @State private var delay = 10.0
    var body: some View {
        Form {
            Section("Caller") { TextField("Caller name", text: $model.settings.fakeCaller); TextField("Voice after answering", text: $model.settings.fakeVoice, axis: .vertical) }
            Section {
                Picker("Ring after", selection: $delay) { Text("Now").tag(0.0); Text("10 seconds").tag(10.0); Text("30 seconds").tag(30.0); Text("1 minute").tag(60.0) }
                Button("Schedule fake call") { Task { await model.scheduleFakeCall(seconds: delay) } }
                Button("Cancel scheduled call", role: .destructive) { Notifications.shared.cancel("fake-call") }
            } footer: { Text("When the app is in the background, iOS shows a notification. Tap it to open the full-screen simulation. This does not place a real phone call.") }
        }.navigationTitle("Fake call")
    }
}

struct FakeCallView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        VStack(spacing: 22) {
            Spacer()
            Image(systemName: "person.crop.circle.fill").font(.system(size: 100)).foregroundStyle(.gray)
            Text(model.settings.fakeCaller).font(.largeTitle.bold())
            Text(model.deterrents.talking ? "Connected" : "Incoming call").foregroundStyle(.secondary)
            Spacer()
            HStack(spacing: 80) {
                Button { model.deterrents.endCall() } label: { Image(systemName: "phone.down.fill").font(.title).frame(width: 78, height: 78).background(.red, in: Circle()) }.accessibilityLabel("End simulated call")
                if !model.deterrents.talking {
                    Button { model.deterrents.answer(model.settings.fakeVoice) } label: { Image(systemName: "phone.fill").font(.title).frame(width: 78, height: 78).background(.green, in: Circle()) }.accessibilityLabel("Answer simulated call")
                }
            }.foregroundStyle(.white)
            Spacer().frame(height: 70)
        }.frame(maxWidth: .infinity).background(Color.black).foregroundStyle(.white)
            .interactiveDismissDisabled()
    }
}

struct SirenView: View {
    @EnvironmentObject private var model: AppModel
    var body: some View {
        VStack(spacing: 28) {
            Image(systemName: "speaker.wave.3.fill").font(.system(size: 90)).foregroundStyle(Brand.sos)
            Text(model.deterrents.sirenOn ? "Siren sounding" : "Draw attention").font(.largeTitle.bold())
            Text("Sound uses the iPhone’s current output volume. Check volume before relying on it.").multilineTextAlignment(.center).foregroundStyle(.secondary)
            Button(model.deterrents.sirenOn ? "Stop siren" : "Start siren") {
                if model.deterrents.sirenOn { model.deterrents.stopSiren() } else { model.deterrents.startSiren() }
            }.buttonStyle(.borderedProminent).tint(Brand.sos).controlSize(.large)
            Button(model.deterrents.torchOn ? "Turn torch off" : "Turn torch on") { model.deterrents.setTorch(!model.deterrents.torchOn) }
            if let error = model.deterrents.error { Text(error).foregroundStyle(.orange) }
        }.padding(28).navigationTitle("Siren").onDisappear { model.deterrents.stopSiren() }
    }
}
