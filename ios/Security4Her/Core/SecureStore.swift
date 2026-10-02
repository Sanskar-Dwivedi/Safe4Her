import Foundation
import Security
import CryptoKit

enum SecureStore {
    private static let service = "in.security4her.ios"
    static func read(_ account: String) -> Data? {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service,
            kSecAttrAccount as String: account, kSecReturnData as String: true, kSecMatchLimit as String: kSecMatchLimitOne]
        var result: CFTypeRef?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess else { return nil }
        return result as? Data
    }
    static func write(_ data: Data, account: String) throws {
        let query: [String: Any] = [kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account]
        let attributes: [String: Any] = [kSecValueData as String: data, kSecAttrAccessible as String: kSecAttrAccessibleAfterFirstUnlockThisDeviceOnly]
        var status = SecItemUpdate(query as CFDictionary, attributes as CFDictionary)
        if status == errSecItemNotFound { status = SecItemAdd(query.merging(attributes) { _, new in new } as CFDictionary, nil) }
        guard status == errSecSuccess else { throw AppFailure.message("Could not securely save credentials (\(status)).") }
    }
    static func remove(_ account: String) {
        SecItemDelete([kSecClass as String: kSecClassGenericPassword, kSecAttrService as String: service, kSecAttrAccount as String: account] as CFDictionary)
    }
    static var hasPIN: Bool { read("safety-pin") != nil }
    static func setPIN(_ pin: String) throws {
        guard pin.count == 4, pin.allSatisfy({ $0.isNumber && $0.isASCII }) else { throw AppFailure.message("Use four digits.") }
        var salt = Data(count: 32)
        let status = salt.withUnsafeMutableBytes { SecRandomCopyBytes(kSecRandomDefault, 32, $0.baseAddress!) }
        guard status == errSecSuccess else { throw AppFailure.message("Could not generate PIN protection.") }
        try write(salt + Data(SHA256.hash(data: salt + Data(pin.utf8))), account: "safety-pin")
    }
    static func acceptsPIN(_ pin: String) -> Bool {
        guard let stored = read("safety-pin"), stored.count == 64 else { return !hasPIN }
        let candidate = Data(SHA256.hash(data: stored.prefix(32) + Data(pin.utf8)))
        return zip(stored.suffix(32), candidate).reduce(UInt8(0)) { $0 | ($1.0 ^ $1.1) } == 0
    }
}
