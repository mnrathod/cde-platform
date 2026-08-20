import Foundation
import Security

/// Holds the session token between launches.
///
/// The keychain rather than `UserDefaults`: a bearer token is a credential,
/// and `UserDefaults` is a plist that lands in unencrypted backups and is
/// readable on a jailbroken device. `kSecAttrAccessibleAfterFirstUnlock`
/// rather than `WhenUnlocked` so a background sync can still send queued
/// markup while the phone is in a pocket — the queue is the reason the SDK
/// exists offline at all.
public final class TokenStore: @unchecked Sendable {

    private let service: String
    private let lock = NSLock()

    public init(service: String = "com.cde.sdk.session") {
        self.service = service
    }

    public var token: String? { read(.token) }
    public var username: String? { read(.username) }
    public var role: String? { read(.role) }

    public func store(token: String, username: String, role: String) {
        write(.token, token)
        write(.username, username)
        write(.role, role)
    }

    public func clear() {
        for key in Key.allCases { delete(key) }
    }

    // MARK: - Keychain

    private enum Key: String, CaseIterable {
        case token, username, role
    }

    private func query(_ key: Key) -> [String: Any] {
        [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: service,
            kSecAttrAccount as String: key.rawValue,
        ]
    }

    private func read(_ key: Key) -> String? {
        lock.lock(); defer { lock.unlock() }

        var request = query(key)
        request[kSecReturnData as String] = true
        request[kSecMatchLimit as String] = kSecMatchLimitOne

        var result: CFTypeRef?
        guard SecItemCopyMatching(request as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data
        else { return nil }
        return String(data: data, encoding: .utf8)
    }

    private func write(_ key: Key, _ value: String) {
        lock.lock(); defer { lock.unlock() }

        let attributes: [String: Any] = [
            kSecValueData as String: Data(value.utf8),
            kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlock,
        ]

        // Update in place when it exists; adding over an existing item fails
        // with errSecDuplicateItem rather than replacing it.
        let status = SecItemUpdate(query(key) as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound {
            var insert = query(key)
            insert.merge(attributes) { current, _ in current }
            SecItemAdd(insert as CFDictionary, nil)
        }
    }

    private func delete(_ key: Key) {
        lock.lock(); defer { lock.unlock() }
        SecItemDelete(query(key) as CFDictionary)
    }
}
