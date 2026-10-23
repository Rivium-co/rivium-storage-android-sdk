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

rootProject.name = "RiviumStorageExample"
include(":app")
include(":rivium-storage")

// Include the SDK from parent directory
project(":rivium-storage").projectDir = file("../rivium-storage")

// The SDK reads its version (X-Rivium-SDK header, RiviumStorage.SDK_VERSION) from VERSION_NAME in the SDK's own
// gradle.properties. This build has a different root, so pass that value on; otherwise the SDK falls back to a default.
val sdkProps = java.util.Properties().apply {
    file("../gradle.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
gradle.beforeProject {
    if (path == ":rivium-storage") {
        sdkProps.getProperty("VERSION_NAME")?.let { extensions.extraProperties["VERSION_NAME"] = it }
    }
}
