import SwiftUI

/// Who can see one vault: members and their roles, sharing with someone who already signs in,
/// and (for the people who run the server) inviting someone new, which yields a link to send.
struct PeopleView: View {
    let vault: VaultInfo
    @EnvironmentObject private var app: AppState
    @Environment(\.dismiss) private var dismiss

    @State private var members: [Member] = []
    @State private var invites: [Invite] = []
    @State private var email = ""
    @State private var name = ""
    @State private var role = "viewer"
    @State private var busy = false
    @State private var problem: String?
    @State private var fresh: Invite?

    private let roles = ["viewer", "editor", "owner"]
    private var pending: [Invite] { invites.filter { $0.acceptedAt == nil } }

    var body: some View {
        NavigationStack {
            List {
                if let problem {
                    Text(problem).font(.callout).foregroundStyle(.red)
                }
                Section("People") {
                    if members.isEmpty {
                        Text(app.isMember ? "Only you." : "Nobody but the people who run the server.").font(.callout).foregroundStyle(.secondary)
                    }
                    ForEach(members) { m in
                        HStack {
                            Text(m.principal).font(.callout.monospaced()).lineLimit(1).truncationMode(.middle)
                            Spacer()
                            Text(m.role).font(.caption.monospaced()).foregroundStyle(.secondary)
                        }
                        .swipeActions {
                            if vault.canShare {
                                Button("Remove", role: .destructive) { run { members = try await $0.unshare(vault.slug, principal: m.principal) } }
                            }
                        }
                    }
                }
                if vault.canShare {
                    Section {
                        TextField("email", text: $email).rawTextEntry().font(.callout.monospaced())
                        if app.isAdmin {
                            TextField("name (for someone new)", text: $name).font(.callout)
                        }
                        Picker("Role", selection: $role) {
                            ForEach(roles, id: \.self) { Text($0).tag($0) }
                        }
                        .pickerStyle(.segmented)
                        HStack {
                            Button("Share") {
                                run { members = try await $0.share(vault.slug, with: address, role: role); email = "" }
                            }
                            .buttonStyle(.bordered)
                            if app.isAdmin {
                                Spacer()
                                Button("Invite") {
                                    run {
                                        fresh = try await $0.invite(to: vault.slug, email: address, role: role, name: name)
                                        email = ""; name = ""
                                        try await load($0)
                                    }
                                }
                                .buttonStyle(.borderedProminent)
                            }
                        }
                        .disabled(busy || address.isEmpty)
                    } header: {
                        Text("Add someone")
                    } footer: {
                        Text(app.isAdmin
                             ? "Share: for someone who already signs in here. Invite: makes their sign-in account if they have none and gives you a link to send them."
                             : "For someone who already signs in here. viewer can look, editor can change things, owner can also share.")
                    }
                }
                if !pending.isEmpty {
                    Section {
                        ForEach(pending) { i in
                            VStack(alignment: .leading, spacing: 4) {
                                HStack {
                                    Text(i.email).font(.callout.monospaced()).lineLimit(1).truncationMode(.middle)
                                    Spacer()
                                    Text(i.role).font(.caption.monospaced()).foregroundStyle(.secondary)
                                }
                                HStack {
                                    if let u = URL(string: i.url) { ShareLink(item: u) { Label("Send link", systemImage: "square.and.arrow.up") } }
                                    Spacer()
                                    Text("until \(i.expiresAt.formatted(date: .abbreviated, time: .omitted))").font(.caption).foregroundStyle(.secondary)
                                }
                                .font(.callout)
                            }
                            .listRowBackground(i.code == fresh?.code ? Color.accentColor.opacity(0.15) : nil)
                            .swipeActions {
                                Button("Withdraw", role: .destructive) { run { try await $0.withdrawInvite(i.code); try await load($0) } }
                            }
                        }
                    } header: {
                        Text("Invited, not signed in yet")
                    } footer: {
                        Text("Whoever opens a link can set up that account, so send it privately. It stops working once they have signed in.")
                    }
                }
            }
            .navigationTitle("\(vault.icon ?? "") \(vault.slug)").inlineTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .task { run { try await load($0) } }
        }
    }

    private var address: String { email.trimmingCharacters(in: .whitespacesAndNewlines).lowercased() }

    private func load(_ api: API) async throws {
        members = try await api.members(of: vault.slug)
        // Invitations make accounts; only the people who run the server see them.
        invites = app.isAdmin ? ((try? await api.invites(of: vault.slug)) ?? []) : []
    }

    private func run(_ work: @escaping (API) async throws -> Void) {
        guard let api = app.api else { return }
        busy = true
        problem = nil
        Task {
            do { try await work(api) } catch { problem = error.localizedDescription }
            busy = false
        }
    }
}
