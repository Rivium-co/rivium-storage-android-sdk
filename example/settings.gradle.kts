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
