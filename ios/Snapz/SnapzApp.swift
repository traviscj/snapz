import SwiftUI

@main
struct SnapzApp: App {
    @StateObject private var app = AppState()
    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            Group {
                if app.signedIn { MainView() } else { LoginView() }
            }
            .environmentObject(app)
            .environmentObject(app.queue)
            .alert("Snapz", isPresented: Binding(get: { app.error != nil }, set: { if !$0 { app.error = nil } })) {
                Button("OK", role: .cancel) {}
            } message: {
                Text(app.error ?? "")
            }
            .onOpenURL { app.consider(link: $0) }
            .alert(
                "Point Snapz at \(app.offeredServer?.absoluteString ?? "")?",
                isPresented: Binding(get: { app.offeredServer != nil }, set: { if !$0 { app.offeredServer = nil } }),
                presenting: app.offeredServer
            ) { server in
                Button(app.servers.contains { $0.key == Shared.serverKey(server.absoluteString) } ? "Switch" : "Add") { Task { await app.switchServer(to: server) } }
                Button("Cancel", role: .cancel) {}
            } message: { _ in
                Text(switchNotice)
            }
            .onChange(of: scenePhase) { _, phase in
                guard phase == .active, app.signedIn else { return }
                app.queue.rescan()
                Task { await app.refreshVaults(); await app.refreshCollections() }
            }
        }
    }
}

extension SnapzApp {
    /// What switching costs, said before it happens.
    var switchNotice: String {
        guard let server = app.offeredServer else { return "" }
        let known = app.servers.first { $0.key == Shared.serverKey(server.absoluteString) }
        var parts = [known.map { app.isSignedIn($0) ? "Signed in there as \($0.principal ?? "?")." : "You will sign in there again." } ?? "You will sign in there."]
        if app.signedIn { parts.append("\(URL(string: app.serverURL)?.host ?? app.serverURL) stays saved; switch back from the title menu.") }
        let waiting = app.queue.pendingCount
        if waiting > 0 { parts.append("\(waiting) photo\(waiting == 1 ? "" : "s") waiting to upload still go\(waiting == 1 ? "es" : "") to the server they were taken for.") }
        return parts.joined(separator: " ")
    }
}

struct LoginView: View {
    @EnvironmentObject private var app: AppState
    @State private var pasted = ""
    @State private var busy = false

    var body: some View {
        NavigationStack {
            Form {
                Section("Server") {
                    TextField("https://snapz.example.net", text: $app.serverURL).rawTextEntry(url: true).font(.callout.monospaced())
                }
                let others = app.servers.filter { $0.key != app.activeKey && app.isSignedIn($0) }
                if !others.isEmpty {
                    Section("Signed in elsewhere") {
                        ForEach(others) { s in
                            Button { Task { if let u = URL(string: s.url) { await app.switchServer(to: u) } } } label: {
                                HStack { Text(s.host).font(.callout.monospaced()); Spacer(); Text(s.principal ?? "").font(.caption).foregroundStyle(.secondary) }
                            }
                        }
                    }
                }
                Section {
                    Button {
                        busy = true
                        Task { await app.signInWithSSO(deviceName: Device.name); busy = false }
                    } label: {
                        Label("Sign in with SSO", systemImage: "person.badge.key")
                    }
                    .disabled(busy || app.baseURL == nil)
                } footer: {
                    Text("Opens the server's sign-in page (Pocket ID / passkey). This device gets its own revocable token.")
                }
                Section {
                    SecureField("snapz_…", text: $pasted).rawTextEntry().font(.callout.monospaced())
                    Button("Paste from clipboard") { pasted = Pasteboard.string ?? pasted }
                    Button("Use token") {
                        busy = true
                        Task { await app.signIn(pastedToken: pasted); busy = false }
                    }
                    .disabled(busy || pasted.isEmpty || app.baseURL == nil)
                } header: {
                    Text("Or use an API token")
                } footer: {
                    Text("Mint one in the web UI under “tokens”. Useful when SSO isn't reachable from this network.")
                }
            }
            .navigationTitle("Snapz")
        }
    }
}

struct MainView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var queue: UploadQueue
    @State private var showCamera = false
    @State private var showQueue = false
    @State private var showSettings = false

    var body: some View {
        NavigationStack {
            CollectionsView()
                .navigationTitle("Snapz")
                .inlineTitle()
                .toolbar {
                    ToolbarItem(placement: .cancellationAction) {
                        Button { showSettings = true } label: { Image(systemName: "gearshape") }
                    }
                    ToolbarItem(placement: .principal) { VaultMenu() }
                    ToolbarItem(placement: .primaryAction) {
                        Button { showQueue = true } label: { QueueBadge() }
                    }
                }
                .safeAreaInset(edge: .bottom) {
                    Button { showCamera = true } label: {
                        HStack(spacing: 6) {
                            Image(systemName: "camera.fill")
                            Text(app.captureCollection.map { "→ \($0)" } ?? "camera").font(.callout.monospaced().weight(.semibold))
                        }
                        .frame(maxWidth: .infinity).padding(.vertical, 10)
                    }
                    .buttonStyle(.borderedProminent)
                    .disabled(app.collections.isEmpty)
                    .padding(.horizontal, 12).padding(.bottom, 4)
                }
        }
        // A different vault is a different set of collections: drop whatever was pushed.
        .id("\(app.activeKey)|\(app.vault ?? "")")
        .cover(isPresented: $showCamera) { CaptureView() }
        .sheet(isPresented: $showQueue) { QueueView() }
        .sheet(isPresented: $showSettings) { SettingsView() }
        .task { await app.refreshVaults(); await app.refreshCollections() }
    }
}

/// The vault switcher: title of the main screen. One vault = plain label.
struct VaultMenu: View {
    @EnvironmentObject private var app: AppState
    @State private var people: VaultInfo?
    @State private var renaming = false
    @State private var creating = false
    @State private var text = ""

    var body: some View {
        Menu {
            ForEach(app.vaults) { v in
                Button { Task { await app.selectVault(v.slug) } } label: {
                    Label("\(v.icon ?? "▣") \(v.slug) · \(v.photoCount)", systemImage: v.slug == app.vault ? "checkmark" : "")
                }
            }
            if let v = app.currentVault {
                Section {
                    Button { people = v } label: { Label("People…", systemImage: "person.2") }
                    if app.isMember {
                        Button { text = v.slug; renaming = true } label: { Label("Rename…", systemImage: "pencil") }
                    }
                    Button { text = ""; creating = true } label: { Label("New vault…", systemImage: "plus") }
                }
            }
            if app.servers.count > 1 {
                Section("servers") {
                    ForEach(app.servers) { s in
                        Button { Task { if let u = URL(string: s.url) { await app.switchServer(to: u) } } } label: {
                            Label(s.host + (app.isSignedIn(s) ? "" : " · signed out"), systemImage: s.key == app.activeKey ? "checkmark" : "")
                        }
                    }
                }
            }
        } label: {
            HStack(spacing: 4) {
                Text("\(app.currentVault?.icon ?? "") \(app.vault ?? "snapz")").font(.headline.monospaced())
                if app.servers.count > 1 { Text("@\(URL(string: app.serverURL)?.host?.split(separator: ".").first.map(String.init) ?? "")").font(.caption.monospaced()).foregroundStyle(.secondary) }
                Image(systemName: "chevron.down").font(.caption2.weight(.bold))
            }
        }
        .disabled(app.vaults.isEmpty)
        .accessibilityIdentifier("vault-menu")
        .sheet(item: $people) { PeopleView(vault: $0) }
        .alert("Your name for this vault", isPresented: $renaming) {
            TextField("slug", text: $text).rawTextEntry()
            Button("Rename") {
                let to = text.trimmingCharacters(in: .whitespacesAndNewlines).lowercased()
                if let from = app.vault, !to.isEmpty, to != from { Task { await app.renameVault(from, to: to) } }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("Letters, digits, - and _. Only your view changes; nobody else's name for it does.")
        }
        .alert("New vault", isPresented: $creating) {
            TextField("name", text: $text)
            Button("Create") {
                let name = text.trimmingCharacters(in: .whitespacesAndNewlines)
                if !name.isEmpty { Task { await app.createVault(name: name) } }
            }
            Button("Cancel", role: .cancel) {}
        } message: {
            Text("A separate set of collections, photos and records.")
        }
    }
}

/// Upload state at a glance: idle check, pending count, or a red count when something is stuck.
struct QueueBadge: View {
    @EnvironmentObject private var queue: UploadQueue

    var body: some View {
        HStack(spacing: 3) {
            if queue.pendingCount == 0 {
                Image(systemName: "checkmark.icloud").foregroundStyle(.secondary)
            } else {
                Image(systemName: queue.stuckCount > 0 ? "exclamationmark.icloud.fill" : queue.online ? "arrow.up.circle" : "icloud.slash")
                Text("\(queue.pendingCount)").font(.caption.monospacedDigit().weight(.bold))
            }
        }
        .foregroundStyle(queue.stuckCount > 0 ? Color.red : queue.online ? Color.accentColor : Color.orange)
    }
}

struct SettingsView: View {
    @EnvironmentObject private var app: AppState
    @EnvironmentObject private var queue: UploadQueue
    @Environment(\.dismiss) private var dismiss
    @State private var confirmSignOut = false
    @State private var adding = false
    @State private var newServer = "https://"

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    ForEach(app.servers) { s in
                        Button {
                            Task { if let u = URL(string: s.url) { await app.switchServer(to: u); dismiss() } }
                        } label: {
                            HStack(spacing: 6) {
                                Image(systemName: s.key == app.activeKey ? "checkmark" : "circle").font(.caption).foregroundStyle(s.key == app.activeKey ? Color.accentColor : Color.secondary)
                                Text(s.host).foregroundStyle(.primary)
                                Spacer()
                                Text(app.isSignedIn(s) ? (s.principal ?? "") : "signed out").font(.caption).foregroundStyle(.secondary)
                            }
                        }
                        .swipeActions {
                            if s.key != app.activeKey { Button("Forget", role: .destructive) { Task { await app.forget(s) } } }
                        }
                    }
                    Button("Add server…") { adding = true }
                } header: {
                    Text("Servers")
                } footer: {
                    Text("One is active at a time. Photos waiting to upload go to the server they were taken for. A snapz://host link adds one too.")
                }
                .font(.callout.monospaced())
                Section("Account") {
                    LabeledContent("Signed in as", value: app.principal ?? "?")
                    LabeledContent("Server", value: app.serverURL)
                    LabeledContent("Vault", value: app.vault ?? "?")
                    LabeledContent("Waiting to upload", value: "\(queue.pendingCount)")
                }
                .font(.callout.monospaced())
                Section {
                    Button("Sign out", role: .destructive) { confirmSignOut = true }
                } footer: {
                    Text("Revokes this device's token on the server. Photos still waiting to upload stay on the phone and go up after the next sign-in.")
                }
            }
            .navigationTitle("Settings").inlineTitle()
            .toolbar { ToolbarItem(placement: .confirmationAction) { Button("Done") { dismiss() } } }
            .alert("Add server", isPresented: $adding) {
                TextField("https://snapz.example.net", text: $newServer).rawTextEntry(url: true)
                Button("Add") {
                    let text = newServer.trimmingCharacters(in: .whitespacesAndNewlines)
                    if let u = URL(string: text), let scheme = u.scheme, ["http", "https"].contains(scheme), u.host != nil {
                        Task { await app.switchServer(to: u); dismiss() }
                    } else {
                        app.error = "Not a server address: \(text)"
                    }
                }
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("You will sign in there. The current server stays saved.")
            }
            .confirmationDialog("Sign out of \(app.serverURL)?", isPresented: $confirmSignOut, titleVisibility: .visible) {
                Button("Sign out", role: .destructive) { Task { await app.signOut(); dismiss() } }
            }
        }
    }
}
