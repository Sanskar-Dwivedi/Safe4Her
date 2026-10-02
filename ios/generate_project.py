"""Regenerate the checked-in, dependency-free iPhone Xcode project (Python 3)."""
from pathlib import Path
import hashlib
import json
import shutil

ROOT = Path(__file__).resolve().parent
def ident(name):
    return hashlib.sha256(name.encode()).hexdigest()[:24].upper()
def quote(value):
    return json.dumps(value)

objects = []
def add(name, content):
    objects.append(f"\t\t{ident(name)} = {{ {content} }};")
    return ident(name)

sources = sorted((ROOT / "Security4Her").rglob("*.swift"))
refs, source_builds, resource_builds = [], [], []
for path in sources:
    rel = path.relative_to(ROOT).as_posix()
    ref = add(rel, f"isa = PBXFileReference; lastKnownFileType = sourcecode.swift; path = {quote(rel)}; sourceTree = SOURCE_ROOT;")
    refs.append(ref)
    source_builds.append(add("build:" + rel, f"isa = PBXBuildFile; fileRef = {ref};"))

for rel, kind in [("Security4Her/Assets.xcassets", "folder.assetcatalog"), ("Security4Her/PrivacyInfo.xcprivacy", "text.xml")]:
    ref = add(rel, f"isa = PBXFileReference; lastKnownFileType = {kind}; path = {quote(rel)}; sourceTree = SOURCE_ROOT;")
    refs.append(ref)
    resource_builds.append(add("build:" + rel, f"isa = PBXBuildFile; fileRef = {ref};"))
for rel, kind in [("Security4Her/Info.plist", "text.plist.xml"), ("Config/Base.xcconfig", "text.xcconfig")]:
    refs.append(add(rel, f"isa = PBXFileReference; lastKnownFileType = {kind}; path = {quote(rel)}; sourceTree = SOURCE_ROOT;"))

product = add("product", 'isa = PBXFileReference; explicitFileType = wrapper.application; path = Security4Her.app; sourceTree = BUILT_PRODUCTS_DIR;')
products = add("products", f'isa = PBXGroup; children = ({product},); name = Products; sourceTree = "<group>";')
main = add("main", f'isa = PBXGroup; children = ({",".join(refs + [products])},); sourceTree = "<group>";')
source_phase = add("sources", f'isa = PBXSourcesBuildPhase; buildActionMask = 2147483647; files = ({",".join(source_builds)},); runOnlyForDeploymentPostprocessing = 0;')
resource_phase = add("resources", f'isa = PBXResourcesBuildPhase; buildActionMask = 2147483647; files = ({",".join(resource_builds)},); runOnlyForDeploymentPostprocessing = 0;')
framework_phase = add("frameworks", 'isa = PBXFrameworksBuildPhase; buildActionMask = 2147483647; files = (); runOnlyForDeploymentPostprocessing = 0;')

for mode in ["Debug", "Release"]:
    add("project:" + mode, f'isa = XCBuildConfiguration; buildSettings = {{ SDKROOT = iphoneos; IPHONEOS_DEPLOYMENT_TARGET = 17.0; CLANG_ENABLE_MODULES = YES; SWIFT_VERSION = 5.0; }}; name = {mode};')
    settings = {
        "PRODUCT_NAME": "$(TARGET_NAME)", "PRODUCT_BUNDLE_IDENTIFIER": "in.security4her.ios",
        "INFOPLIST_FILE": "Security4Her/Info.plist", "GENERATE_INFOPLIST_FILE": "NO",
        "TARGETED_DEVICE_FAMILY": "1", "SUPPORTED_PLATFORMS": "iphoneos iphonesimulator",
        "SUPPORTS_MACCATALYST": "NO", "SUPPORTS_MAC_DESIGNED_FOR_IPHONE_IPAD": "NO",
        "CURRENT_PROJECT_VERSION": "1", "MARKETING_VERSION": "1.1.0", "CODE_SIGN_STYLE": "Automatic",
        "SWIFT_VERSION": "5.0", "SWIFT_STRICT_CONCURRENCY": "minimal", "ENABLE_PREVIEWS": "YES",
        "LD_RUNPATH_SEARCH_PATHS": "$(inherited) @executable_path/Frameworks",
        "SWIFT_OPTIMIZATION_LEVEL": "-Onone" if mode == "Debug" else "-O",
        "SWIFT_ACTIVE_COMPILATION_CONDITIONS": "DEBUG" if mode == "Debug" else "",
        "DEBUG_INFORMATION_FORMAT": "dwarf" if mode == "Debug" else "dwarf-with-dsym",
        "ASSETCATALOG_COMPILER_APPICON_NAME": "AppIcon",
    }
    text = " ".join(f"{key} = {quote(value)};" for key, value in settings.items())
    add("target:" + mode, f'isa = XCBuildConfiguration; baseConfigurationReference = {ident("Config/Base.xcconfig")}; buildSettings = {{ {text} }}; name = {mode};')
for kind in ["project", "target"]:
    add(kind + ":config", f'isa = XCConfigurationList; buildConfigurations = ({ident(kind + ":Debug")},{ident(kind + ":Release")},); defaultConfigurationIsVisible = 0; defaultConfigurationName = Release;')
target = add("target", f'isa = PBXNativeTarget; buildConfigurationList = {ident("target:config")}; buildPhases = ({source_phase},{framework_phase},{resource_phase},); buildRules = (); dependencies = (); name = Security4Her; productName = Security4Her; productReference = {product}; productType = "com.apple.product-type.application";')
project = add("project", f'isa = PBXProject; attributes = {{ BuildIndependentTargetsInParallel = YES; LastUpgradeCheck = 1600; TargetAttributes = {{ {target} = {{ CreatedOnToolsVersion = 16.0; }}; }}; }}; buildConfigurationList = {ident("project:config")}; compatibilityVersion = "Xcode 14.0"; developmentRegion = en; hasScannedForEncodings = 0; knownRegions = (en, Base); mainGroup = {main}; productRefGroup = {products}; projectDirPath = ""; projectRoot = ""; targets = ({target},);')
project_dir = ROOT / "Security4Her.xcodeproj"
project_dir.mkdir(exist_ok=True)
(project_dir / "project.pbxproj").write_text('// !$*UTF8*$!\n{\n\tarchiveVersion = 1;\n\tclasses = {};\n\tobjectVersion = 56;\n\tobjects = {\n' + '\n'.join(objects) + f'\n\t}};\n\trootObject = {project};\n}}\n', encoding="utf-8")
schemes = project_dir / "xcshareddata" / "xcschemes"
schemes.mkdir(parents=True, exist_ok=True)
reference = f'<BuildableReference BuildableIdentifier="primary" BlueprintIdentifier="{target}" BuildableName="Security4Her.app" BlueprintName="Security4Her" ReferencedContainer="container:Security4Her.xcodeproj"/>'
(schemes / "Security4Her.xcscheme").write_text(f'''<?xml version="1.0" encoding="UTF-8"?>
<Scheme LastUpgradeVersion="1600" version="1.3">
<BuildAction parallelizeBuildables="YES" buildImplicitDependencies="YES"><BuildActionEntries><BuildActionEntry buildForTesting="NO" buildForRunning="YES" buildForProfiling="YES" buildForArchiving="YES" buildForAnalyzing="YES">{reference}</BuildActionEntry></BuildActionEntries></BuildAction>
<LaunchAction buildConfiguration="Debug" selectedDebuggerIdentifier="Xcode.DebuggerFoundation.Debugger.LLDB" selectedLauncherIdentifier="Xcode.IDEFoundation.Launcher.LLDB" launchStyle="0" useCustomWorkingDirectory="NO" ignoresPersistentStateOnLaunch="NO" debugDocumentVersioning="YES" allowLocationSimulation="YES"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></LaunchAction>
<ProfileAction buildConfiguration="Release" shouldUseLaunchSchemeArgsEnv="YES" savedToolIdentifier="" useCustomWorkingDirectory="NO" debugDocumentVersioning="YES"><BuildableProductRunnable runnableDebuggingMode="0">{reference}</BuildableProductRunnable></ProfileAction>
<AnalyzeAction buildConfiguration="Debug"/><ArchiveAction buildConfiguration="Release" revealArchiveInOrganizer="YES"/>
</Scheme>
''', encoding="utf-8")
assets = ROOT / "Security4Her" / "Assets.xcassets"
logo = assets / "BrandLogo.imageset"
logo.mkdir(parents=True, exist_ok=True)
shutil.copyfile(ROOT.parent / "mobile/src/main/res/drawable-nodpi/logo_security4her.png", logo / "logo.png")
(assets / "Contents.json").write_text(json.dumps({"info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
(logo / "Contents.json").write_text(json.dumps({"images": [{"filename": "logo.png", "idiom": "universal"}], "info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
icon = assets / "AppIcon.appiconset"
icon.mkdir(exist_ok=True)
# Reuse the existing correctly sized 180px artwork without modifying brand assets.
# A 1024px marketing icon must be supplied before App Store submission.
shutil.copyfile(ROOT.parent / "web-install/apple-touch-icon.png", icon / "AppIcon-180.png")
(icon / "Contents.json").write_text(json.dumps({"images": [{"filename": "AppIcon-180.png", "idiom": "iphone", "size": "60x60", "scale": "3x"}], "info": {"author": "xcode", "version": 1}}, indent=2) + "\n")
print(f"Generated Xcode project with {len(sources)} Swift sources.")
