// swift-tools-version: 6.0
import PackageDescription

// Run the same policy regressions without UIKit or an Apple SDK.
let package = Package(name: "SafetyPolicies", products: [.library(name: "SafetyPolicies", targets: ["SafetyPolicies"])],
    targets: [
        .target(name: "SafetyPolicies", path: "Security4Her/Core", exclude: ["AppModel.swift", "Backend.swift", "Models.swift", "SecureStore.swift"], sources: ["SafetyPolicy.swift"]),
        .testTarget(name: "SafetyPolicyTests", dependencies: ["SafetyPolicies"], path: "Security4HerTests", exclude: ["RuntimeCompatibilityTests.swift"], sources: ["SafetyPolicyTests.swift"])
    ])
