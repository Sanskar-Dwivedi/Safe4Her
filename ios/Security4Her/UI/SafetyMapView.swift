import SwiftUI
import MapKit

struct MapPlace: Identifiable {
    let id: String
    let name: String
    let coordinate: CLLocationCoordinate2D
    let category: String
}

struct SafetyMapView: View {
    @EnvironmentObject private var model: AppModel
    @State private var position: MapCameraPosition = .automatic
    @State private var places: [MapPlace] = []
    @State private var dangers: [DangerCell] = []
    @State private var busy = false
    @State private var reportSheet = false
    @State private var category = "poor_lighting"
    @State private var note = ""
    @State private var reportKind = "danger"
    @State private var reportName = ""
    @State private var publicSharing = false
    @StateObject private var feed = CommunityFeed()
    @State private var status = "Search nearby hospitals or police stations."
    var body: some View {
        VStack(spacing: 0) {
            Map(position: $position) {
                UserAnnotation()
                ForEach(places) { place in Marker(place.name, systemImage: place.category == "police" ? "shield" : "cross.case", coordinate: place.coordinate).tint(.green) }
                ForEach(dangers) { cell in MapCircle(center: .init(latitude: cell.lat, longitude: cell.lng), radius: 300).foregroundStyle(.red.opacity(0.2)) }
                ForEach(feed.reports) { report in
                    Annotation(report.name?.isEmpty == false ? report.name! : report.category.replacingOccurrences(of: "_", with: " "), coordinate: .init(latitude: report.lat, longitude: report.lng)) {
                        Image(systemName: report.kind == "danger" ? "exclamationmark.triangle.fill" : "checkmark.shield.fill")
                            .foregroundStyle(report.kind == "danger" ? .red : .green).padding(6).background(.regularMaterial, in: Circle())
                    }
                }
            }.mapControls { MapUserLocationButton(); MapCompass(); MapScaleView() }.frame(minHeight: 260)
            HStack {
                Button("Hospitals") { Task { await search("hospital") } }
                Button("Police") { Task { await search("police") } }
                Button("Community") { Task { await community() } }
                if busy { ProgressView() }
            }.buttonStyle(.bordered).padding(10)
            Text(status).font(.caption).foregroundStyle(.secondary).padding(.horizontal)
            List {
                ForEach(places) { place in
                    Button {
                        let item = MKMapItem(placemark: .init(coordinate: place.coordinate)); item.name = place.name
                        item.openInMaps(launchOptions: [MKLaunchOptionsDirectionsModeKey: MKLaunchOptionsDirectionsModeWalking])
                    } label: { Label(place.name, systemImage: "location") }
                }
                ForEach(feed.reports.prefix(40)) { report in
                    VStack(alignment: .leading, spacing: 4) {
                        Text(report.name?.isEmpty == false ? report.name! : report.category.replacingOccurrences(of: "_", with: " "))
                        if let note = report.note { Text(note).font(.caption).foregroundStyle(.secondary) }
                        Button("Confirm · \(feed.count(report)) reports of agreement") {
                            Task { do { try await feed.confirm(report) } catch { model.report(error) } }
                        }.disabled(model.demo)
                    }
                }
            }.frame(maxHeight: 190)
        }.navigationTitle("Safety map")
            .toolbar { Button("Report", systemImage: "exclamationmark.bubble") { reportSheet = true } }
            .onAppear { model.location.once() }
            .sheet(isPresented: $reportSheet) {
                NavigationStack {
                    Form {
                        Text("Report at your current location. Reports contribute to aggregated areas; the map is not a guarantee of safety.").font(.callout)
                        Picker("Type", selection: $reportKind) { Text("Danger").tag("danger"); Text("Safe area").tag("safe_area"); Text("Safe place").tag("safe_place") }
                        if reportKind != "danger" { TextField("Place or area name", text: $reportName) }
                        Picker("Category", selection: $category) {
                            Text("Poor lighting").tag("poor_lighting"); Text("Harassment").tag("harassment"); Text("Stalking").tag("stalking"); Text("Isolated").tag("isolated"); Text("Other").tag("other")
                        }
                        TextField("Optional note", text: $note, axis: .vertical)
                        Toggle("Share publicly with the community", isOn: $publicSharing)
                        Text("Public reports include this location and your note under a random author ID. Anyone can read or submit to the feed; confirmations are not verified identities. Do not include personal details.").font(.caption)
                        Button("Submit report") { Task { await submit() } }.disabled(busy || model.demo || (reportKind != "danger" && !publicSharing))
                        if model.demo { Text("Community submissions are unavailable in the offline demo.") }
                    }.navigationTitle("Report an area").toolbar { Button("Close") { reportSheet = false } }
                }
            }
    }
    private func search(_ kind: String) async {
        guard let fix = model.location.fix else { model.location.once(); status = "Waiting for location permission or a GPS fix."; return }
        busy = true; defer { busy = false }
        do {
            let request = MKLocalSearch.Request(); request.naturalLanguageQuery = kind
            request.region = .init(center: fix.coordinate, latitudinalMeters: 12000, longitudinalMeters: 12000)
            let result = try await MKLocalSearch(request: request).start()
            places = result.mapItems.enumerated().map { .init(id: "search-\($0.offset)", name: $0.element.name ?? kind, coordinate: $0.element.placemark.coordinate, category: kind) }
            position = .region(request.region); status = places.isEmpty ? "No matching places found." : "Tap a place for directions in Maps."
        } catch { status = error.localizedDescription }
    }
    private func community() async {
        guard let fix = model.location.fix else { status = "Allow location to load nearby community data."; return }
        busy = true; defer { busy = false }
        do {
            try await feed.sync()
            if model.demo { status = "Public community reports loaded; submissions are disabled in demo."; return }
            let safe = try JSONDecoder().decode([SafePlace].self, from: await model.backend.rpc("nearest_safe_places", ["p_lat": fix.lat, "p_lng": fix.lng, "p_n": 20]))
            dangers = try JSONDecoder().decode([DangerCell].self, from: await model.backend.rpc("danger_cells_in_bbox", ["p_min_lat": fix.lat - 0.1, "p_min_lng": fix.lng - 0.1, "p_max_lat": fix.lat + 0.1, "p_max_lng": fix.lng + 0.1]))
            places = safe.map { .init(id: "community-\($0.id)", name: $0.name, coordinate: .init(latitude: $0.lat, longitude: $0.lng), category: $0.category) }
            position = .region(.init(center: fix.coordinate, latitudinalMeters: 12000, longitudinalMeters: 12000))
            status = "\(places.count) listed places · \(dangers.count) aggregated report areas."
        } catch { status = error.localizedDescription }
    }
    private func submit() async {
        guard let fix = model.location.fix, fix.isFresh else { model.error = "A recent location fix is needed to submit a report."; return }
        busy = true; defer { busy = false }
        do {
            if publicSharing { try await feed.add(kind: reportKind, fix: fix, name: reportName, note: note, category: reportKind == "danger" ? category : "other") }
            else { _ = try await model.backend.rpc("report_unsafe", ["p_lat": fix.lat, "p_lng": fix.lng, "p_category": category, "p_note": note]) }
            note = ""; reportName = ""; reportSheet = false; status = "Report submitted."
        }
        catch { model.report(error) }
    }
}
