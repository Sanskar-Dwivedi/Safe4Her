import SwiftUI
import MapKit

struct DestinationResult: Identifiable {
    let id = UUID()
    let item: MKMapItem
}

struct JourneyView: View {
    @EnvironmentObject private var model: AppModel
    @State private var query = ""
    @State private var results: [DestinationResult] = []
    @State private var destination: DestinationResult?
    @State private var minutes = 30
    @State private var walking = true
    @State private var searching = false
    @State private var routeNote = "Choose a destination to estimate travel time."
    @ObservedObject private var presentation = JourneyPresentation.shared
    var body: some View {
        Form {
            if let journey = model.journey, journey.state == "active" || journey.endPending {
                Section("Current journey") {
                    Text(journey.name).font(.title2.bold())
                    Text("Check in by \(journey.deadline.formatted(date: .omitted, time: .shortened))")
                    if let pending = journey.pendingDeadline {
                        Text("Requested deadline: \(pending.formatted(date: .omitted, time: .shortened)). The previous server deadline remains in effect until confirmed.").foregroundStyle(.orange)
                    }
                    if let at = journey.acknowledgedAt { Text("Last server confirmation: \(at.formatted())").font(.caption) }
                    Text(presentation.reminderStatus).font(.caption)
                    Text(journey.serverPending ? "Server confirmation is pending. A deadline may already be armed; end this journey to cancel it before trying again." : journey.serverArmed ? "Server deadline armed: linked Telegram contacts can be alerted if you miss check-in." : "Practice only: keep the app open. No automatic contact alerts.").foregroundStyle(journey.serverArmed ? .green : .orange)
                    if let reason = journey.checkReason { Text(reason).foregroundStyle(.orange) }
                    if let until = journey.checkUntil { Text(until, style: .timer).font(.title.monospacedDigit()) }
                    if journey.endPending { Text("Waiting for the server to confirm cancellation. The previous deadline may still alert contacts.").foregroundStyle(.orange) }
                    Button("I’m OK — give me 10 more minutes") { Task { await model.extendJourney() } }.disabled(journey.endPending || journey.serverPending)
                    Button("I’ve arrived — end journey") { Task { await model.endJourney(reason: "arrived") } }
                    Button("End monitoring without confirming arrival", role: .destructive) { Task { await model.endJourney() } }
                }
            } else {
                Section("Where are you going?") {
                    TextField("Search destination", text: $query).onSubmit { Task { await search() } }
                    Button(searching ? "Searching…" : "Search") { Task { await search() } }.disabled(searching || query.trimmingCharacters(in: .whitespaces).isEmpty)
                    ForEach(results) { result in
                        Button { destination = result; Task { await estimate() } } label: {
                            HStack {
                                VStack(alignment: .leading) { Text(result.item.name ?? "Destination"); Text(result.item.placemark.title ?? "").font(.caption).foregroundStyle(.secondary) }
                                if destination?.id == result.id { Image(systemName: "checkmark.circle.fill") }
                            }
                        }
                    }
                }
                Section("Arrival and check-in") {
                    Toggle("Walking route", isOn: $walking).onChange(of: walking) { _, _ in Task { await estimate() } }
                    Text(routeNote).font(.caption).foregroundStyle(.secondary)
                    Stepper("Check in within \(minutes) minutes", value: $minutes, in: 5...720, step: 5)
                    Text("Driving routes also cover car/auto estimates. Set your own deadline for bus or scooter travel. Unexpected stops or movement away prompt a check-in when location updates are available.").font(.caption).foregroundStyle(.secondary)
                    Button("Start safe journey") {
                        guard let destination else { return }
                        Task { await model.startJourney(name: destination.item.name ?? query, coordinate: destination.item.placemark.coordinate, minutes: minutes) }
                    }.disabled(destination == nil)
                }
            }
            Section { Text("The server can monitor an acknowledged deadline without the app running. Route changes and automatic arrival need location updates. Force quitting or losing permission stops those updates; check in manually if needed.").font(.footnote).foregroundStyle(.secondary) }
        }.navigationTitle("Safe journey").onAppear { model.location.once() }
    }
    private func search() async {
        searching = true; defer { searching = false }
        do {
            let request = MKLocalSearch.Request(); request.naturalLanguageQuery = query
            if let fix = model.location.fix { request.region = .init(center: fix.coordinate, latitudinalMeters: 20000, longitudinalMeters: 20000) }
            let response = try await MKLocalSearch(request: request).start()
            results = response.mapItems.map { .init(item: $0) }
            if results.isEmpty { routeNote = "No destinations found. Try a more specific address." }
        } catch { model.report(error) }
    }
    private func estimate() async {
        guard let destination, let fix = model.location.fix else { routeNote = "Location is unavailable. Choose the check-in time manually."; return }
        do {
            let request = MKDirections.Request()
            request.source = MKMapItem(placemark: .init(coordinate: fix.coordinate)); request.destination = destination.item
            request.transportType = walking ? .walking : .automobile
            let response = try await MKDirections(request: request).calculateETA()
            minutes = min(720, max(5, Int(ceil(response.expectedTravelTime / 300)) * 5 + 5))
            routeNote = "Estimated route: \(Int(response.distance)) metres. Deadline includes a five-minute allowance."
        } catch { routeNote = "Route estimate unavailable. Set a check-in deadline manually." }
    }
}
