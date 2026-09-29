pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // libadb-android and its SPAKE2 pairing helper are published only on JitPack
        maven("https://jitpack.io") { content { includeGroupByRegex("com[.]github[.]MuntashirAkon.*") } }
    }
}

rootProject.name = "Security4Her"

// Each module is included only once its build file exists, so either side can build alone.
listOf("wear", "mobile").forEach { name ->
    if (file("$name/build.gradle.kts").exists()) include(":$name")
}
