// Security4Her — Android phone app (see CONTRACT.md).
import java.util.Properties
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
        minSdk = 26
        targetSdk = 35
        // Bump both for every release; the in-app updater compares versionCode with web-install/version.json.
        versionCode = 28
        versionName = "1.1.0-beta.18"
        buildConfigField("String", "SUPABASE_URL", cfg("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_ANON_KEY", cfg("SUPABASE_ANON_KEY"))
        buildConfigField("String", "TRACK_BASE_URL", cfg("TRACK_BASE_URL"))
        // Where the app looks for new versions (published with web-install/publish.sh).
        buildConfigField("String", "UPDATE_URL", "\"${localProps.getProperty("UPDATE_URL", "https://security4her.vercel.app/version.json")}\"")
        // Live map page (github.com/AtharvaLakhe/s4h-live); blank in local.properties falls back to ntfy.sh's own page.
        buildConfigField("String", "LIVE_PAGE_URL", "\"${localProps.getProperty("LIVE_PAGE_URL", "https://atharvalakhe.github.io/s4h-live/")}\"")
        manifestPlaceholders["MAPS_API_KEY"] = localProps.getProperty("MAPS_API_KEY", "")
        buildConfigField("boolean", "HAS_MAPS_KEY", localProps.getProperty("MAPS_API_KEY", "").isNotBlank().toString())
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
    // Compress code and native libraries inside the APK so the download is smaller.
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
    testImplementation("junit:junit:4.13.2")
    val composeBom = platform("androidx.compose:compose-bom:2026.05.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
    implementation("androidx.lifecycle:lifecycle-process:2.10.0")
    implementation("com.google.android.gms:play-services-wearable:19.0.0")
    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.maps.android:maps-compose:6.12.2")
    implementation("org.osmdroid:osmdroid-android:6.1.20") // real map without an API key
    implementation("androidx.compose.material:material-icons-core:1.7.8")
    // Evidence video during SOS
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.camera:camera-video:1.4.2")
    implementation("androidx.camera:camera-view:1.4.2") // scanning a child's family QR code
    implementation("androidx.work:work-runtime-ktx:2.10.1") // family SOS check when the app isn't open
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(platform("io.github.jan-tennert.supabase:bom:3.6.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.ktor:ktor-client-okhttp:3.4.3")
    // Installs the watch app from the phone over Wi-Fi (wireless debugging), no computer needed
    implementation("com.github.MuntashirAkon:libadb-android:3.1.1")
    implementation("org.conscrypt:conscrypt-android:2.5.3") // TLS 1.3 for adb pairing on every phone
    implementation("org.bouncycastle:bcpkix-jdk15to18:1.81") // the phone's adb certificate
    implementation("com.google.zxing:core:3.5.3") // the family-link QR code, drawn on the phone
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("old transitive Fragment breaks Activity Result lint checks")
        }
    }
}
