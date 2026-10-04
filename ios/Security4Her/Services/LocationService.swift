import Foundation
import CoreLocation
import Combine

@MainActor
final class LocationService: NSObject, ObservableObject, @preconcurrency CLLocationManagerDelegate {
    @Published var fix: Fix?
    @Published var authorization: CLAuthorizationStatus = .notDetermined
    @Published var preciseLocation = false
    @Published var error: String?
    var onFix: ((Fix) -> Void)?
    // Core Location delivers delegate calls on the initializing thread's run loop.
    // This manager is created on MainActor; the legacy delegate conformance keeps
    // that isolation and checks it at runtime instead of sending the manager away.
    private let manager = CLLocationManager()
    private var reasons = Set<String>()
    func confirmArrival(_ needed: Bool) { manager.distanceFilter = needed ? kCLDistanceFilterNone : 10 }
    override init() {
        super.init()
        manager.delegate = self
        authorization = manager.authorizationStatus
        preciseLocation = manager.accuracyAuthorization == .fullAccuracy
        manager.desiredAccuracy = kCLLocationAccuracyBest
        manager.distanceFilter = 10
        manager.pausesLocationUpdatesAutomatically = false
        manager.showsBackgroundLocationIndicator = true
    }
    func requestPermission() { manager.requestWhenInUseAuthorization() }
    func requestBackgroundPermission() { manager.requestAlwaysAuthorization() }
    func once() {
        if authorization == .notDetermined { requestPermission() }
        else if authorization == .authorizedAlways || authorization == .authorizedWhenInUse { manager.requestLocation() }
    }
    func start(_ reason: String) {
        reasons.insert(reason)
        if authorization == .notDetermined { requestPermission(); return }
        guard authorization == .authorizedAlways || authorization == .authorizedWhenInUse else {
            error = "Location permission is off. Alerts can still be sent without a location."; return
        }
        manager.allowsBackgroundLocationUpdates = true
        manager.startUpdatingLocation()
    }
    func stop(_ reason: String) {
        reasons.remove(reason)
        if reasons.isEmpty { manager.stopUpdatingLocation(); manager.allowsBackgroundLocationUpdates = false }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorization = manager.authorizationStatus
        preciseLocation = manager.accuracyAuthorization == .fullAccuracy
        if authorization == .authorizedAlways || authorization == .authorizedWhenInUse {
            error = nil
            if reasons.isEmpty { manager.requestLocation() } else { manager.allowsBackgroundLocationUpdates = true; manager.startUpdatingLocation() }
        } else if authorization == .denied || authorization == .restricted {
            error = "Location permission is off. Automatic arrival cannot be confirmed. Open the journey to check in manually."
        }
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard let location = locations.last, location.horizontalAccuracy >= 0 else { return }
        let value = Fix(lat: location.coordinate.latitude, lng: location.coordinate.longitude, accuracy: location.horizontalAccuracy, at: location.timestamp)
        if let fix, value.at < fix.at { return }
        fix = value; error = nil; onFix?(value)
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { self.error = error.localizedDescription }
}
