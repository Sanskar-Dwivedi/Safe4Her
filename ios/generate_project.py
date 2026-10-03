"""Generate app, widget and XCTest targets deterministically; preserve existing artwork."""
from pathlib import Path
import hashlib
import json

ROOT = Path(__file__).resolve().parent
objects, refs = [], []
def ident(name):
    return hashlib.sha256(name.encode()).hexdigest()[:24].upper()
def add(name, content):
    objects.append(f"\t\t{ident(name)} = {{ {content} }};")
    return ident(name)
def listing(values):
    return "(" + ",".join(values) + ("," if values else "") + ")"
def reference(rel, kind):
    key = ident(rel)
    if key not in refs:
        add(rel, f"isa = PBXFileReference; lastKnownFileType = {kind}; path = {json.dumps(rel)}; sourceTree = SOURCE_ROOT;")
        refs.append(key)
    return key
def phase(name, kind, files):
    return add(name, f"isa = {kind}; buildActionMask = 2147483647; files = {listing(files)}; runOnlyForDeploymentPostprocessing = 0;")
def configs(name, extra):
    for mode in ("Debug", "Release"):
        settings = {"SDKROOT": "iphoneos", "IPHONEOS_DEPLOYMENT_TARGET": "17.0", "SWIFT_VERSION": "5.0",
            "CLANG_ENABLE_MODULES": "YES", "SWIFT_STRICT_CONCURRENCY": "minimal",
            "SWIFT_OPTIMIZATION_LEVEL": "-Onone" if mode == "Debug" else "-O",
            "SWIFT_ACTIVE_COMPILATION_CONDITIONS": ("DEBUG " if mode == "Debug" else "") + ("WIDGET_EXTENSION" if name == "widgets" else ""),
            "DEBUG_INFORMATION_FORMAT": "dwarf" if mode == "Debug" else "dwarf-with-dsym", **extra}
        text = " ".join(f"{key} = {json.dumps(value)};" for key, value in settings.items())
        base = f'baseConfigurationReference = {ident("Config/Base.xcconfig")};' if name != "project" else ""
        add(name + ":" + mode, f"isa = XCBuildConfiguration; {base} buildSettings = {{ {text} }}; name = {mode};")
    return add(name + ":config", f'isa = XCConfigurationList; buildConfigurations = ({ident(name + ":Debug")},{ident(name + ":Release")},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
def dependency(name, target):
    proxy = add(name + ":proxy", f'isa = PBXContainerItemProxy; containerPortal = {ident("project")}; proxyType = 1; remoteGlobalIDString = {ident(target)}; remoteInfo = {target};')
    return add(name + ":dependency", f"isa = PBXTargetDependency; target = {ident(target)}; targetProxy = {proxy};")

shared = sorted((ROOT / "Shared").glob("*.swift"))
targets = [
    ("target", "Security4Her", "app", "application", sorted((ROOT / "Security4Her").rglob("*.swift")) + shared),
    ("widgets", "SafetyWidgets", "appex", "app-extension", sorted((ROOT / "SafetyWidgets").glob("*.swift")) + shared),
    ("tests", "Security4HerTests", "xctest", "bundle.unit-test", sorted((ROOT / "Security4HerTests").glob("*.swift"))),
]
reference("Config/Base.xcconfig", "text.xcconfig")
products = []
for key, name, ext, product_type, sources in targets:
    source_builds = []
    for path in sources:
        rel = path.relative_to(ROOT).as_posix()
        ref = reference(rel, "sourcecode.swift")
        source_builds.append(add(f"{key}:build:{rel}", f"isa = PBXBuildFile; fileRef = {ref};"))
    resources = []
    if key == "target":
        for rel, kind in [("Security4Her/Assets.xcassets", "folder.assetcatalog"), ("Security4Her/PrivacyInfo.xcprivacy", "text.xml")]:
            ref = reference(rel, kind)
            resources.append(add("build:" + rel, f"isa = PBXBuildFile; fileRef = {ref};"))
    phases = [phase(key + ":sources", "PBXSourcesBuildPhase", source_builds),
              phase(key + ":frameworks", "PBXFrameworksBuildPhase", []),
              phase(key + ":resources", "PBXResourcesBuildPhase", resources)]
    dependencies = []
    if key == "target":
        build = add("embed:widgets", f'isa = PBXBuildFile; fileRef = {ident("widgets:product")}; settings = {{ ATTRIBUTES = (RemoveHeadersOnCopy,); }};')
        phases.append(add("embed", f'isa = PBXCopyFilesBuildPhase; buildActionMask = 2147483647; dstPath = ""; dstSubfolderSpec = 13; files = ({build},); name = "Embed App Extensions"; runOnlyForDeploymentPostprocessing = 0;'))
        dependencies.append(dependency("app-widgets", "widgets"))
    elif key == "tests":
        dependencies.append(dependency("tests-app", "target"))
    product = add(key + ":product", f'isa = PBXFileReference; explicitFileType = {"wrapper.application" if ext == "app" else "wrapper.app-extension" if ext == "appex" else "wrapper.cfbundle"}; path = {name}.{ext}; sourceTree = BUILT_PRODUCTS_DIR;')
    products.append(product)
    settings = {"PRODUCT_NAME": "$(TARGET_NAME)", "PRODUCT_BUNDLE_IDENTIFIER": "in.security4her.ios" + ("" if key == "target" else "." + key),
        "TARGETED_DEVICE_FAMILY": "1", "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator", "SUPPORTS_MACCATALYST": "NO",
        "SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD": "NO", "CURRENT_PROJECT_VERSION": "2", "MARKETING_VERSION": "1.1.0",
        "CODE_SIGN_STYLE": "Automatic", "LD_RUNPATH_SEARCH_PATHS": "$(inherited) @executable_path/Frameworks @executable_path/../../Frameworks"}
    if key == "target":
        settings.update(INFOPLIST_FILE="Security4Her/Info.plist", GENERATE_INFOPLIST_FILE="NO",
                        ASSETCATALOG_COMPILER_APPICON_NAME="AppIcon", ENABLE_TESTABILITY="YES")
        reference("Security4Her/Info.plist", "text.plist.xml")
    elif key == "widgets":
        settings.update(INFOPLIST_FILE="SafetyWidgets/Info.plist", GENERATE_INFOPLIST_FILE="NO", SKIP_INSTALL="YES", APPLICATION_EXTENSION_API_ONLY="YES")
        reference("SafetyWidgets/Info.plist", "text.plist.xml")
    else:
        settings.update(GENERATE_INFOPLIST_FILE="YES", SKIP_INSTALL="YES", TEST_HOST="$(BUILT_PRODUCTS_DIR)/Security4Her.app/$(BUNDLE_EXECUTABLE_FOLDER_PATH)/Security4Her", BUNDLE_LOADER="$(TEST_HOST)")
    configuration = configs(key, settings)
    add(key, f'isa = PBXNativeTarget; buildConfigurationList = {configuration}; buildPhases = {listing(phases)}; buildRules = (); dependencies = {listing(dependencies)}; name = {name}; productName = {name}; productReference = {product}; productType = "com.apple.product-type.{product_type}";')

products_group = add("products", f'isa = PBXGroup; children = {listing(products)}; name = Products; sourceTree = "<group>";')
main = add("main", f'isa = PBXGroup; children = {listing(refs + [products_group])}; sourceTree = "<group>";')
configuration = configs("project", {})
project = add("project", f'isa = PBXProject; attributes = {{ BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 2600; }}; buildConfigurationList = {configuration}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en,Base); mainGroup = {main}; productRefGroup = {products_group}; projectDirPath = ""; projectRoot = ""; targets = {listing([ident(x[0]) for x in targets])};')
project_dir = ROOT / "Security4Her.xcodeproj"
project_dir.mkdir(exist_ok=True)
(project_dir / "project.pbxproj").write_text('// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {};\n\tobjectVersion = 56;\n\tobjects = {\n' + '\n'.join(objects) + f'\n\t}};\n\trootObject = {project};\n}}\n', encoding="utf-8")
def buildable(key, name, ext):
    return f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{ident(key)}" BuildableName="{name}.{ext}" BlueprintName="{name}" ReferencedContainer="container:Security4Her.xcodeproj"/>'
app = buildable("target", "Security4Her", "app")
tests = buildable("tests", "Security4HerTests", "xctest")
schemes = project_dir / "xcshareddata" / "xcschemes"
schemes.mkdir(parents=True, exist_ok=True)
(schemes / "Security4Her.xcscheme").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="2600" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="YES" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{app}</BuildActionEntry></BuildActionEntries></BuildAction>
<TestAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" shouldUseLaunchSchemeArgsEnv="YES"><Testables><TestableReference skipped="NO">{tests}</TestableReference></Testables></TestAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{app}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''', encoding="utf-8")
print("Generated app, widgets and XCTest targets; existing assets preserved.")
