import AuthenticationServices
import Foundation
import SwiftUI

/// A server this phone has been pointed at. Its device token is in the Keychain under `token@<key>`.
struct SavedServer: Codable, Identifiable, Hashable {
    var id: String { key }
    let key: String
    var url: String
    var principal: String?
    /// The vault last used there.
    var vault: String?

    /// Host (and port when it has one): what tells two servers apart in a menu.
    var host: String {
        guard let u = URL(string: url), let h = u.host else { return url }
        return u.port.map { "\(h):\($0)" } ?? h
    }
}

@MainActor
final class AppState: ObservableObject {
    /// Every server signed in to at some point; one of them (serverURL) is active.
    @Published private(set) var servers: [SavedServer] = []

    @Published var serverURL: String { didSet { UserDefaults.standard.set(serverURL, forKey: "serverURL"); Shared.defaults.set(serverURL, forKey: "serverURL") } }
    @Published private(set) var token: String?
    @Published private(set) var principal: String?
    @Published var collections: [PhotoCollection] = []
    @Published private(set) var vaults: [VaultInfo] = []
    /// The vault being browsed and shot into; nil until the server has been asked (= its default).
    @Published private(set) var vault: String? { didSet { UserDefaults.standard.set(vault, forKey: "vault"); Shared.defaults.set(vault, forKey: "vault"); if token != nil { remember() } } }
    /// Where the camera shoots into; remembered across launches.
    @Published var captureCollection: String? { didSet { UserDefaults.standard.set(captureCollection, forKey: "captureCollection") } }
    @Published var error: String?
    /// The "snap first, file later" collection (server-configured; learned from /api/inbox).
    @Published var inboxSlug = "inbox" { didSet { Shared.defaults.set(inboxSlug, forKey: "inboxSlug") } }

    let queue = UploadQueue()
    private let webSignIn = WebSignIn()

    init() {
        serverURL = UserDefaults.standard.string(forKey: "serverURL")
            ?? (Bundle.main.object(forInfoDictionaryKey: "SnapzDefaultServer") as? String) ?? ""
        Self.followMovedServer(&serverURL)
        captureCollection = UserDefaults.standard.string(forKey: "captureCollection")
        vault = UserDefaults.standard.string(forKey: "vault")
        token = Keychain.get("token")
        principal = UserDefaults.standard.string(forKey: "principal")
        #if DEBUG
        // Simulator/UI-test hook: `SIMCTL_CHILD_SNAPZ_DEBUG_TOKEN=… xcrun simctl launch … -serverURL http://localhost:8788`
        if let t = ProcessInfo.processInfo.environment["SNAPZ_DEBUG_TOKEN"], !t.isEmpty {
            token = t
            principal = "debug"
            // Persist, so later launches from the home screen stay signed in to the same server.
            Keychain.set("token", t)
            UserDefaults.standard.set("debug", forKey: "principal")
            UserDefaults.standard.set(serverURL, forKey: "serverURL")
        }
        #endif
        if let data = UserDefaults.standard.data(forKey: "servers"), let saved = try? JSONDecoder().decode([SavedServer].self, from: data) { servers = saved }
        if let token {
            // A build from before saved servers kept one token: file it under its server.
            if Keychain.get(tokenKey(activeKey)) == nil { Keychain.set(tokenKey(activeKey), token) }
            remember()
        }
        // The share extension reads these (didSet does not fire for assignments made in init).
        Shared.defaults.set(serverURL, forKey: "serverURL")
        Shared.defaults.set(vault, forKey: "vault")
        configureQueue()
    }

    var signedIn: Bool { token != nil }

    // MARK: saved servers

    var activeKey: String { Shared.serverKey(serverURL) }
    private func tokenKey(_ key: String) -> String { "token@\(key)" }
    func isSignedIn(_ s: SavedServer) -> Bool { Keychain.get(tokenKey(s.key)) != nil }

    /// Record the active server (and who / which vault) in the saved list.
    private func remember() {
        let key = activeKey
        guard !key.isEmpty else { return }
        let entry = SavedServer(key: key, url: serverURL.trimmingCharacters(in: .whitespacesAndNewlines), principal: principal, vault: vault)
        if let i = servers.firstIndex(where: { $0.key == key }) {
            if servers[i] == entry { return }
            servers[i] = entry
        } else {
            servers.append(entry)
        }
        UserDefaults.standard.set(try? JSONEncoder().encode(servers), forKey: "servers")
    }

    private func client(forServer key: String) -> API? {
        guard let s = servers.first(where: { $0.key == key }), let base = URL(string: s.url), let token = Keychain.get(tokenKey(key)) else { return nil }
        return API(base: base, token: token)
    }

    /// Drop a saved server: its device token is revoked there (best effort) and forgotten here.
    func forget(_ s: SavedServer) async {
        guard s.key != activeKey else { return }
        await client(forServer: s.key)?.logout()
        Keychain.set(tokenKey(s.key), nil)
        servers.removeAll { $0.key == s.key }
        UserDefaults.standard.set(try? JSONEncoder().encode(servers), forKey: "servers")
    }

    /// The server moved (SnapzLegacyServers lists where it used to be): point a saved URL at the new
    /// home. The token stays: device tokens live in the server's database, which moved with it.
    private static func followMovedServer(_ url: inout String) {
        let norm = { (s: String) in s.trimmingCharacters(in: .whitespacesAndNewlines).trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased() }
        guard let target = Bundle.main.object(forInfoDictionaryKey: "SnapzDefaultServer") as? String, !norm(target).isEmpty,
              let legacy = Bundle.main.object(forInfoDictionaryKey: "SnapzLegacyServers") as? String,
              legacy.split(separator: " ").map({ norm(String($0)) }).contains(norm(url)) else { return }
        url = target
        UserDefaults.standard.set(target, forKey: "serverURL")
    }

    var baseURL: URL? {
        let s = serverURL.trimmingCharacters(in: .whitespacesAndNewlines)
        guard let u = URL(string: s), let scheme = u.scheme, ["http", "https"].contains(scheme), u.host != nil else { return nil }
        return u
    }

    var api: API? {
        guard let baseURL, let token else { return nil }
        return API(base: baseURL, token: token, vault: vault)
    }

    var currentVault: VaultInfo? { vaults.first { $0.slug == vault } }

    func selectVault(_ slug: String) async {
        guard slug != vault else { return }
        vault = slug
        collections = []
        captureCollection = UserDefaults.standard.string(forKey: "captureCollection.\(slug)")
        configureQueue()
        await refreshCollections()
    }

    /// Learns the vault list, and falls back to the server's default when the remembered one is gone.
    func refreshVaults() async {
        guard let api else { return }
        do {
            let l = try await api.with(vault: nil).vaults()
            vaults = l.vaults
            if vault == nil || !l.vaults.contains(where: { $0.slug == vault }) {
                vault = l.vaults.contains(where: { $0.slug == l.default }) ? l.default : l.vaults.first?.slug
                configureQueue()
            }
        } catch {
            self.error = error.localizedDescription
        }
    }

    private func configureQueue() {
        queue.api = api
        queue.server = activeKey
        queue.apiFor = { [weak self] key in self?.client(forServer: key) }
        queue.onUploaded = { [weak self] in Task { await self?.refreshCollections() } }
        queue.onUnauthorized = { [weak self] in self?.error = "The server rejected this device's token. Sign in again; queued photos are kept." }
        queue.kick()
    }

    private func adopt(token: String, principal: String) {
        Keychain.set("token", token)
        Keychain.set(tokenKey(activeKey), token)
        UserDefaults.standard.set(principal, forKey: "principal")
        self.token = token
        self.principal = principal
        remember()
        configureQueue()
    }

    func signInWithSSO(deviceName: String) async {
        guard let baseURL else { error = "Enter the server URL first (https://…)"; return }
        do {
            let t = try await webSignIn.signIn(server: baseURL, deviceName: deviceName)
            adopt(token: t.token, principal: t.principal)
        } catch let e as ASWebAuthenticationSessionError where e.code == .canceledLogin {
            // user closed the sheet: not an error worth a banner
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// Fallback when SSO isn't reachable: paste a token minted in the web UI.
    func signIn(pastedToken: String) async {
        guard let baseURL else { error = "Enter the server URL first (https://…)"; return }
        let t = pastedToken.trimmingCharacters(in: .whitespacesAndNewlines)
        do {
            let me = try await API(base: baseURL, token: t).me()
            adopt(token: t, principal: me.principal)
        } catch {
            self.error = error.localizedDescription
        }
    }

    /// A server someone asked the app to use via a `snapz://<host>` link; nothing changes until confirmed.
    @Published var offeredServer: URL?

    /// `snapz://snapz.example.net` (optionally `:port`, and `?insecure=1` for plain http on a LAN)
    /// offers to point the app at that server. `snapz://auth…` is the sign-in callback, not this.
    func consider(link: URL) {
        guard link.scheme?.lowercased() == "snapz", let host = link.host, !host.isEmpty, host.lowercased() != "auth" else { return }
        let insecure = URLComponents(url: link, resolvingAgainstBaseURL: false)?.queryItems?.contains { $0.name == "insecure" && $0.value == "1" } ?? false
        var c = URLComponents()
        c.scheme = insecure ? "http" : "https"
        c.host = host
        c.port = link.port
        guard let target = c.url else { return }
        if let baseURL, baseURL.absoluteString.trimmingCharacters(in: CharacterSet(charactersIn: "/")).lowercased() == target.absoluteString.lowercased() {
            error = "Already using \(target.absoluteString)."
            return
        }
        offeredServer = target
    }

    /// Make [server] the active one. Nothing is signed out: the server being left stays saved with
    /// its token, and photos queued for it still go to it. A server never signed in to shows the
    /// sign-in screen.
    func switchServer(to server: URL) async {
        let key = Shared.serverKey(server.absoluteString)
        guard key != activeKey else { return }
        let saved = servers.first { $0.key == key }
        collections = []
        vaults = []
        captureCollection = nil
        serverURL = saved?.url ?? server.absoluteString
        token = Keychain.get(tokenKey(key))
        // The share extension uses whatever is filed under "token": keep it the active server's.
        Keychain.set("token", token)
        principal = token == nil ? nil : saved?.principal
        UserDefaults.standard.set(principal, forKey: "principal")
        vault = saved?.vault
        configureQueue()
        if token != nil {
            await refreshVaults()
            await refreshCollections()
        }
    }

    /// Revokes this device's token server-side. Queued, un-uploaded photos stay on the phone.
    func signOut() async {
        await api?.logout()
        Keychain.set("token", nil)
        Keychain.set(tokenKey(activeKey), nil)
        token = nil
        principal = nil
        collections = []
        vaults = []
        vault = nil
        queue.api = nil
    }

    func refreshCollections() async {
        if vaults.isEmpty { await refreshVaults() }
        guard let api else { return }
        do {
            collections = try await api.collections()
            if captureCollection == nil || !collections.contains(where: { $0.slug == captureCollection }) {
                captureCollection = collections.first?.slug
            }
            if let vault, let c = captureCollection { UserDefaults.standard.set(c, forKey: "captureCollection.\(vault)") }
        } catch {
            self.error = error.localizedDescription
        }
    }
}
