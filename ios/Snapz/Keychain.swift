import Foundation
import Security

/// The device token lives in the Keychain (this-device-only, available after first unlock so
/// queued uploads can finish in the background). It is stored in the App Group's access group so
/// the share extension can use it; a private copy is the fallback where there is no group.
enum Keychain {
    private static let privateService = Bundle.main.bundleIdentifier ?? "snapz"

    private static func privateQuery(_ key: String) -> [String: Any] {
        [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: privateService, kSecAttrAccount as String: key]
    }

    private static func sharedQuery(_ key: String) -> [String: Any]? {
        guard let group = Shared.group else { return nil }
        return [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: "snapz", kSecAttrAccount as String: key,
                kSecAttrAccessGroup as String: group]
    }

    private static func read(_ query: [String: Any]) -> String? {
        var q = query
        q[kSecReturnData as String] = true
        q[kSecMatchLimit as String] = kSecMatchLimitOne
        var out: AnyObject?
        guard SecItemCopyMatching(q as CFDictionary, &out) == errSecSuccess, let data = out as? Data else { return nil }
        return String(data: data, encoding: .utf8)
    }

    @discardableResult
    private static func write(_ query: [String: Any], _ value: String?) -> Bool {
        SecItemDelete(query as CFDictionary)
        guard let value else { return true }
        var q = query
        q[kSecValueData as String] = Data(value.utf8)
        q[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly
        return SecItemAdd(q as CFDictionary, nil) == errSecSuccess
    }

    static func get(_ key: String) -> String? {
        if let shared = sharedQuery(key), let v = read(shared) { return v }
        guard let v = read(privateQuery(key)) else { return nil }
        // Stored by a build from before the share extension: make it visible to the extension too.
        if let shared = sharedQuery(key) { write(shared, v) }
        return v
    }

    static func set(_ key: String, _ value: String?) {
        write(privateQuery(key), value)
        if let shared = sharedQuery(key) { write(shared, value) }
    }
}
