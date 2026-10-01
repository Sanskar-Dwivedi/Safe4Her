// Security4Her — Wear OS watch app (see CONTRACT.md).
import java.net.URI
import java.util.Properties
import java.util.zip.ZipFile
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
fun cfg(key: String) = "\"${localProps.getProperty(key, "")}\""

android {
    namespace = "in.security4her.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "in.security4her.app"
        minSdk = 30
        targetSdk = 35
        // Bump both for every release; the watch compares versionCode with the "watch" entry in web-install/version.json.
        versionCode = 9
        versionName = "1.1.0-beta.1"
        buildConfigField("String", "SUPABASE_URL", cfg("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_ANON_KEY", cfg("SUPABASE_ANON_KEY"))
        buildConfigField("String", "TRACK_BASE_URL", cfg("TRACK_BASE_URL"))
        // Watches are ARM; x86_64 stays for the emulator. Skipping 32-bit x86/MIPS saves ~11 MB of speech-engine libraries.
        ndk { abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64") }
        val updateUrl = localProps.getProperty("UPDATE_URL", "https://security4her.vercel.app/version.json")
        buildConfigField("String", "UPDATE_URL", "\"$updateUrl\"")
        // Plain HTTP is allowed only for a local test server (UPDATE_URL=http://localhost:… in local.properties).
        manifestPlaceholders["cleartext"] = updateUrl.startsWith("http://localhost").toString()
    }

    signingConfigs {
        getByName("debug") {
            storeFile = rootProject.file("keystore/debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug { signingConfig = signingConfigs.getByName("debug") }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // The same key as every earlier build, so installed apps update in place.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    // Compress code and the speech engine inside the APK: it downloads in about half the size.
    packaging {
        jniLibs { useLegacyPackaging = true }
        dex { useLegacyPackaging = true }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.wear.compose:compose-material3:1.6.2")
    implementation("androidx.wear.compose:compose-foundation:1.6.2")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.work:work-runtime-ktx:2.10.1")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // Voice SOS: offline speech recognition on the watch (Vosk). JNA comes as the Android AAR with native libs.
    implementation("com.alphacephei:vosk-android:0.3.75") { exclude(group = "net.java.dev.jna") }
    implementation("net.java.dev.jna:jna:5.18.1@aar")
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("old transitive Fragment breaks Activity Result lint checks")
        }
    }

    testImplementation("junit:junit:4.13.2")
}

// ---- Voice SOS speech model --------------------------------------------------------------------------
// The small English Vosk model (~41 MB download, ~70 MB unpacked) is fetched at build time instead of being
// committed. It lands in src/main/assets/model-en-us/ (git-ignored); the app copies it to internal storage once.
val voiceModelDir: File = file("src/main/assets/model-en-us")
val fetchVoiceModel by tasks.registering {
    description = "Downloads the offline speech model used by Voice SOS."
    outputs.dir(voiceModelDir)
    onlyIf { !File(voiceModelDir, "uuid").exists() }
    doLast {
        val zip = File(temporaryDir, "vosk-model.zip")
        URI("https://alphacephei.com/vosk/models/vosk-model-small-en-us-0.15.zip").toURL()
            .openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        voiceModelDir.deleteRecursively()
        ZipFile(zip).use { z ->
            z.entries().asSequence().filter { !it.isDirectory && !it.name.endsWith("README") }.forEach { e ->
                val out = File(voiceModelDir, e.name.substringAfter('/'))
                out.parentFile.mkdirs()
                z.getInputStream(e).use { input -> out.outputStream().use { input.copyTo(it) } }
            }
        }
        File(voiceModelDir, "uuid").writeText("vosk-model-small-en-us-0.15")
    }
}
tasks.named("preBuild") { dependsOn(fetchVoiceModel) }
