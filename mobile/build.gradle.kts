// Security4Her — Android phone app (see CONTRACT.md; mobile/.../map/ is Claude-owned).
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
        versionCode = 1
        versionName = "0.1.0-phone"
        buildConfigField("String", "SUPABASE_URL", cfg("SUPABASE_URL"))
        buildConfigField("String", "SUPABASE_ANON_KEY", cfg("SUPABASE_ANON_KEY"))
        buildConfigField("String", "TRACK_BASE_URL", cfg("TRACK_BASE_URL"))
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
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug") // hackathon only
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-service:2.10.0")
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
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.10.2")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    implementation(platform("io.github.jan-tennert.supabase:bom:3.6.0"))
    implementation("io.github.jan-tennert.supabase:auth-kt")
    implementation("io.github.jan-tennert.supabase:postgrest-kt")
    implementation("io.ktor:ktor-client-okhttp:3.4.3")
    constraints {
        implementation("androidx.fragment:fragment:1.8.9") {
            because("old transitive Fragment breaks Activity Result lint checks")
        }
    }
}
