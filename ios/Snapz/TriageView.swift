import SwiftUI

struct TriageRoute: Hashable {}

/// File the inbox one photo at a time. The server ranks every collection by P(collection | photo)
/// from how similar photos were filed before; the best guess is the big button, the rest follow in
/// order with size and fill tracking their probability. Each filing is new evidence, so the list
/// is re-ranked after every tap.
struct TriageView: View {
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss
    @State private var items: [InboxItem] = []
    @State private var loaded = false
    @State private var busy = false
    @State private var filed = 0
    @State private var skipped = Set<String>()

    private var current: InboxItem? { items.first { !skipped.contains($0.id) } }
    private var remaining: Int { items.filter { !skipped.contains($0.id) }.count }

    var body: some View {
        VStack(spacing: 6) {
            if let item = current, let api = app.api {
                RemoteImage(path: item.photo.url, api: api, maxPixel: 1600, fill: false)
                    .aspectRatio(item.photo.aspect, contentMode: .fit)
                    .overlay { DetectionBoxes(barcodes: item.photo.meta?.barcodes ?? []) }
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
                    .id(item.id)
                HStack {
                    Text(Fmt.stamp.string(from: item.photo.takenAt))
                    Text(Fmt.age(item.photo.takenAt)).foregroundStyle(.secondary)
                    Spacer()
                    Text(item.photo.barcodes.joined(separator: " ")).foregroundStyle(.green)
                }
                .font(.caption.monospaced()).padding(.horizontal, 10)
                choices(item)
                HStack {
                    Button(role: .destructive) { act(item) { _ = try await $0.deletePhoto(item.photo.token) } } label: { Label("Trash", systemImage: "trash") }
                    Spacer()
                    Button { skipped.insert(item.id) } label: { Label("Later", systemImage: "arrow.uturn.right") }
                }
                .font(.callout).padding(.horizontal, 14).padding(.bottom, 4)
            } else if loaded {
                ContentUnavailableView(filed > 0 ? "Inbox filed" : "Inbox is empty", systemImage: "tray",
                                       description: Text(skipped.isEmpty ? "Shoot into \(app.inboxSlug) to decide later." : "\(skipped.count) left for later."))
            } else {
                ProgressView().frame(maxHeight: .infinity)
            }
        }
        .navigationTitle(remaining > 0 ? "inbox · \(remaining) left" : "inbox").inlineTitle()
        .disabled(busy)
        .task { await load() }
        .refreshable { await load() }
    }

    /// Best guess on top, full width. The rest two-across, ordered by probability.
    @ViewBuilder private func choices(_ item: InboxItem) -> some View {
        let ranked = item.suggestions
        VStack(spacing: 5) {
            if let top = ranked.first {
                choice(top, item, height: 58, font: .title3.monospaced().weight(.bold))
            }
            let rest = Array(ranked.dropFirst())
            LazyVGrid(columns: [GridItem(.flexible(), spacing: 5), GridItem(.flexible(), spacing: 5)], spacing: 5) {
                ForEach(rest, id: \.self) { s in
                    // 34pt for a long shot up to 50pt for a near-tie with the top guess.
                    choice(s, item, height: 34 + 16 * min(1, s.p / max(ranked.first?.p ?? 1, 0.001)), font: .callout.monospaced().weight(.semibold))
                }
            }
        }
        .padding(.horizontal, 10)
    }

    private func choice(_ s: Suggestion, _ item: InboxItem, height: CGFloat, font: Font) -> some View {
        Button {
            act(item) { _ = try await $0.updatePhoto(item.photo.token, collection: s.collection) }
        } label: {
            HStack {
                Text("\(app.collections.first { $0.slug == s.collection }?.icon ?? "") \(s.collection)").lineLimit(1)
                Spacer()
                Text("\(Int((s.p * 100).rounded()))%").foregroundStyle(.secondary)
            }
            .font(font).padding(.horizontal, 10)
            .frame(maxWidth: .infinity, minHeight: height)
            .background {
                GeometryReader { geo in
                    ZStack(alignment: .leading) {
                        Color.secondary.opacity(0.15)
                        Color.accentColor.opacity(0.35).frame(width: geo.size.width * s.p)
                    }
                }
            }
            .clipShape(RoundedRectangle(cornerRadius: 6))
        }
        .buttonStyle(.plain)
        .accessibilityIdentifier("file-\(s.collection)")
    }

    private func act(_ item: InboxItem, _ op: @escaping (API) async throws -> Void) {
        guard let api = app.api else { return }
        busy = true
        Task {
            defer { busy = false }
            do {
                try await op(api)
                filed += 1
                items.removeAll { $0.id == item.id }
                await load()               // re-rank with the new evidence
                await app.refreshCollections()
            } catch {
                app.error = error.localizedDescription
            }
        }
    }

    private func load() async {
        guard let api = app.api else { return }
        do {
            let r = try await api.inbox()
            app.inboxSlug = r.inbox
            items = r.photos
        } catch {
            app.error = error.localizedDescription
        }
        loaded = true
    }
}
