import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

// ------------------------------------------------------------------------------------------------
// Your keys and test buckets live in example/rivium.properties (NOT in git, see .gitignore).
// Copy example/rivium.properties.example to example/rivium.properties and fill it in.
// Only the PUBLIC key (rv_live_...) goes here. The server secret (rv_srv_...) stays on your
// token server and never goes into an app.
// ------------------------------------------------------------------------------------------------
val riviumProps = Properties().apply {
    val file = rootProject.file("rivium.properties")
    if (file.exists()) file.inputStream().use { load(it) }
}

fun riviumValue(name: String, fallback: String): String {
    val value = (riviumProps.getProperty(name) ?: System.getenv(name) ?: fallback).trim()
    // Escape for a Java string literal in BuildConfig.
    return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "co.rivium.storage.example"
    compileSdk = 34

    defaultConfig {
        applicationId = "co.rivium.storage.example"
        minSdk = 21
        targetSdk = 34
        versionCode = 2
        versionName = "2.0"

        buildConfigField("String", "RIVIUM_API_KEY", riviumValue("RIVIUM_API_KEY", "rv_live_xxxxxxxxxxxx"))
        buildConfigField("String", "TOKEN_SERVER_URL", riviumValue("TOKEN_SERVER_URL", ""))
        buildConfigField("String", "PUBLIC_BUCKET", riviumValue("PUBLIC_BUCKET", "rivium-example-public"))
        buildConfigField("String", "PRIVATE_BUCKET", riviumValue("PRIVATE_BUCKET", "rivium-example-private-images"))
        buildConfigField("String", "DEMO_USER_ID", riviumValue("DEMO_USER_ID", "demo-user"))
    }

    buildTypes {
        release {
            // R8 on, so a release build proves the SDK's consumer rules are enough (the app adds none for the SDK).
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Signed with the debug key so the release APK installs on a test phone. Use your own key for a store build.
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.6"
    }
}

dependencies {
    // The SDK from this repository's source (../rivium-storage), never the published artifact,
    // so unreleased SDK changes can be tested on a phone in debug and release builds.
    implementation(project(":rivium-storage"))

    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // OkHttp for the example's own calls: your token server, the public-link endpoint and the transform image.
    // (The SDK uses OkHttp too; same version.)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation(platform("androidx.compose:compose-bom:2023.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Coil shows images from a URL: a public link or a signed link (no headers), or a transform with headers.
    implementation("io.coil-kt:coil-compose:2.5.0")
}
