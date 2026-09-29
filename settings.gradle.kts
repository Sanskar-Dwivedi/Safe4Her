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
    }
}

rootProject.name = "Security4Her"

// Each module is included only once its build file exists, so either side can build alone.
listOf("wear", "mobile").forEach { name ->
    if (file("$name/build.gradle.kts").exists()) include(":$name")
}
