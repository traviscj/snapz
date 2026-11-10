import SwiftUI

/// nil = the list of namespaces; otherwise the records in one.
struct RecordsRoute: Hashable {
    let ns: String?
}

/// Read-only window onto what transforms have derived (order_items, products, …).
struct RecordsView: View {
    let ns: String?

    @EnvironmentObject private var app: AppState
    @State private var namespaces: [RecordNamespace] = []
    @State private var records: [Record] = []
    @State private var loaded = false
    @State private var open: Record?
    @State private var filter = ""

    private var shown: [Record] {
        guard !filter.isEmpty else { return records }
        return records.filter { $0.k.localizedCaseInsensitiveContains(filter) || ($0.v?.compact.localizedCaseInsensitiveContains(filter) ?? false) }
    }

    var body: some View {
        List {
            if ns == nil {
                ForEach(namespaces) { n in
                    NavigationLink(value: RecordsRoute(ns: n.ns)) {
                        HStack {
                            Text(n.ns)
                            Spacer()
                            Text("\(n.count)")
                            Text(Fmt.age(n.updatedAt)).foregroundStyle(.secondary).frame(width: 34, alignment: .trailing)
                        }
                        .font(.callout.monospaced())
                    }
                }
            } else {
                ForEach(shown) { r in
                    Button { open = r } label: { row(r) }.buttonStyle(.plain)
                }
            }
        }
        .listStyle(.plain)
        .environment(\.defaultMinListRowHeight, 30)
        .navigationTitle(ns ?? "records").inlineTitle()
        .searchable(text: $filter, prompt: "filter")
        .refreshable { await load() }
        .task { await load() }
        .sheet(item: $open) { r in
            NavigationStack {
                ScrollView {
                    Text(r.v?.pretty ?? "null").font(.caption.monospaced()).textSelection(.enabled)
                        .frame(maxWidth: .infinity, alignment: .leading).padding(10)
                }
                .navigationTitle(r.k).inlineTitle()
                .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { open = nil } } }
            }
        }
        .overlay {
            if loaded && (ns == nil ? namespaces.isEmpty : records.isEmpty) {
                ContentUnavailableView("No records", systemImage: "tray", description: Text("Transforms write their output here."))
            }
        }
    }

    /// Key on the first line; the fields that fit on the second. Objects lead with name/qty when
    /// they have them, since that's what an order list is read for.
    private func row(_ r: Record) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            HStack {
                Text(title(r)).fontWeight(.semibold).lineLimit(1)
                Spacer()
                if case .object(let o)? = r.v, let q = o["qty"] { Text("×\(q.compact)").fontWeight(.bold) }
                Text(Fmt.age(r.updatedAt)).foregroundStyle(.secondary).frame(width: 34, alignment: .trailing)
            }
            .font(.callout.monospaced())
            Text(summary(r)).font(.caption2.monospaced()).foregroundStyle(.secondary).lineLimit(1)
        }
        .contentShape(Rectangle())
    }

    private func title(_ r: Record) -> String {
        if case .object(let o)? = r.v, case .string(let name)? = o["name"], !name.isEmpty { return name }
        return r.k
    }

    private func summary(_ r: Record) -> String {
        guard case .object(let o)? = r.v else { return r.v?.compact ?? "null" }
        let rest = o.keys.sorted().filter { !["name", "qty", "photos"].contains($0) }.compactMap { k -> String? in
            if case .null = o[k]! { return nil }
            return "\(k)=\(o[k]!.compact)"
        }
        return ([title(r) == r.k ? nil : r.k] + rest).compactMap { $0 }.joined(separator: "  ")
    }

    private func load() async {
        guard let api = app.api else { return }
        do {
            if let ns { records = try await api.records(ns: ns) } else { namespaces = try await api.recordNamespaces() }
        } catch {
            app.error = error.localizedDescription
        }
        loaded = true
    }
}
