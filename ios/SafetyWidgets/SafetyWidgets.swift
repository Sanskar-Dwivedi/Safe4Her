import SwiftUI
import WidgetKit
import ActivityKit
import AppIntents

@main
struct SafetyWidgets: WidgetBundle {
    var body: some Widget {
        SafetyAccessWidget()
        JourneyActivityWidget()
        if #available(iOSApplicationExtension 18.0, *) {
            SafetySOSControl()
            SafetyFakeCallControl()
            JourneyCheckInControl()
        }
    }
}

private struct SafetyEntry: TimelineEntry { var date: Date }
private struct SafetyProvider: TimelineProvider {
    func placeholder(in context: Context) -> SafetyEntry { .init(date: Date()) }
    func getSnapshot(in context: Context, completion: @escaping (SafetyEntry) -> Void) { completion(.init(date: Date())) }
    func getTimeline(in context: Context, completion: @escaping (Timeline<SafetyEntry>) -> Void) {
        completion(Timeline(entries: [.init(date: Date())], policy: .never))
    }
}
struct SafetyAccessWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: "in.security4her.access", provider: SafetyProvider()) { _ in
            Label("Open SOS", systemImage: "sos.circle.fill")
                .containerBackground(.red.opacity(0.15), for: .widget)
                .widgetURL(URL(string: "security4her://sos"))
        }.configurationDisplayName("SOS access")
            .description("Open the app to begin its cancellable SOS countdown. Unlocking may be required.")
            .supportedFamilies([.systemSmall, .accessoryCircular, .accessoryRectangular, .accessoryInline])
    }
}

@available(iOSApplicationExtension 18.0, *)
struct SafetySOSControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "in.security4her.sos-control") {
            ControlWidgetButton(action: OpenSafetyControl(target: .sos)) { Label("Start SOS", systemImage: "sos.circle.fill") }
        }.displayName("Start SOS").description("Open Security4Her for its cancellable SOS countdown.")
    }
}

@available(iOSApplicationExtension 18.0, *)
struct SafetyFakeCallControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "in.security4her.fake-call-control") {
            ControlWidgetButton(action: OpenSafetyControl(target: .fakeCall)) { Label("Fake call", systemImage: "phone.arrow.down.left") }
        }.displayName("Fake call").description("Open the simulated incoming call screen.")
    }
}

@available(iOSApplicationExtension 18.0, *)
struct JourneyCheckInControl: ControlWidget {
    var body: some ControlWidgetConfiguration {
        StaticControlConfiguration(kind: "in.security4her.journey-control") {
            ControlWidgetButton(action: OpenSafetyControl(target: .journey)) { Label("Journey check-in", systemImage: "figure.walk") }
        }.displayName("Journey check-in").description("Open your journey. You still need to confirm arrival in the app.")
    }
}

struct JourneyActivityWidget: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: JourneyActivityAttributes.self) { context in
            VStack(alignment: .leading, spacing: 6) {
                Text("Security4Her journey").font(.headline)
                Text(context.state.status)
                if let deadline = context.state.deadline { Text("Check in by \(deadline.formatted(date: .omitted, time: .shortened))") }
                if let at = context.state.acknowledgedAt { Text("Server checked \(at, style: .relative) ago").font(.caption) }
                Text(context.isStale ? "Status may be out of date. Open to check in." : "Open to check in. This display does not monitor your journey.").font(.caption)
            }.padding().widgetURL(URL(string: "security4her://journey"))
        } dynamicIsland: { context in
            DynamicIsland {
                DynamicIslandExpandedRegion(.leading) { Image(systemName: "figure.walk") }
                DynamicIslandExpandedRegion(.center) { Text(context.state.status) }
                DynamicIslandExpandedRegion(.bottom) {
                    Link("Open check-in", destination: URL(string: "security4her://journey")!)
                }
            } compactLeading: { Image(systemName: "figure.walk") }
              compactTrailing: { Text("Check in") }
              minimal: { Image(systemName: "figure.walk") }
              .widgetURL(URL(string: "security4her://journey"))
        }
    }
}
