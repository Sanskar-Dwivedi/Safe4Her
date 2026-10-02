import SwiftUI

enum Brand {
    static let sos = Color(red: 1, green: 0.30, blue: 0.37)
    static let themes = ["black", "dusk", "midnight", "plum", "daylight"]
    static func accent(_ key: String) -> Color {
        switch key {
        case "dusk": return Color(red: 0.31, green: 0.82, blue: 0.77)
        case "midnight": return Color(red: 0.55, green: 0.62, blue: 1)
        case "plum": return Color(red: 0.88, green: 0.41, blue: 0.62)
        case "daylight": return Color(red: 0.61, green: 0.18, blue: 0.42)
        default: return .primary
        }
    }
}

struct SafetyCard<Content: View>: View {
    var title: String
    @ViewBuilder var content: Content
    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            Text(title).font(.headline)
            content
        }.frame(maxWidth: .infinity, alignment: .leading).padding(20)
            .background(Color(uiColor: .secondarySystemGroupedBackground), in: RoundedRectangle(cornerRadius: 22))
    }
}

struct StatusLine: View {
    var title: String
    var detail: String
    var good = false
    var body: some View {
        HStack(alignment: .top) {
            Image(systemName: good ? "checkmark.circle.fill" : "info.circle").foregroundStyle(good ? .green : .secondary)
            VStack(alignment: .leading, spacing: 3) { Text(title).font(.subheadline.bold()); Text(detail).font(.caption).foregroundStyle(.secondary) }
            Spacer(minLength: 0)
        }
    }
}
