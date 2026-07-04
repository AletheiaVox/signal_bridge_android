plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.android.gms.oss-licenses-plugin")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Load signing credentials from keystore.properties (gitignored)
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps: Map<String, String> = if (keystorePropsFile.exists()) {
    keystorePropsFile.readLines()
        .filter { "=" in it && !it.trimStart().startsWith("#") }
        .associate { line ->
            val (k, v) = line.split("=", limit = 2)
            k.trim() to v.trim()
        }
} else {
    emptyMap()
}

android {
    namespace = "com.signalbridge.app"
    compileSdk = 34

    signingConfigs {
        create("release") {
            storeFile = file(keystoreProps.getOrDefault("storeFile", "../keystore.jks"))
            storePassword = keystoreProps.getOrDefault("storePassword", "")
            keyAlias = keystoreProps.getOrDefault("keyAlias", "")
            keyPassword = keystoreProps.getOrDefault("keyPassword", "")
        }
    }

    defaultConfig {
        applicationId = "com.signalbridge.app"
        minSdk = 26
        targetSdk = 34
        versionCode = 3
        versionName = "1.3.0"

        // Default server URL — users can change in Settings
        buildConfigField("String", "DEFAULT_SERVER_URL", "\"https://signal-bridge.duckdns.org\"")
        buildConfigField("String", "DEFAULT_INTIFACE_URL", "\"ws://127.0.0.1:12345\"")
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.activity:activity-compose:1.8.2")

    // Compose UI
    implementation(platform("androidx.compose:compose-bom:2024.02.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.7.7")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Ktor (networking — WebSocket + HTTP, OkHttp engine)
    implementation("io.ktor:ktor-client-okhttp:2.3.8")
    implementation("io.ktor:ktor-client-websockets:2.3.8")
    implementation("io.ktor:ktor-client-content-negotiation:2.3.8")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.8")

    // Kotlin Serialization (JSON)
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")

    // Encrypted SharedPreferences (token storage)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // DataStore (settings persistence)
    implementation("androidx.datastore:datastore-preferences:1.0.0")

    // AppCompat (required by OSS Licenses activity)
    implementation("androidx.appcompat:appcompat:1.6.1")

    // OSS Licenses (Play Store compliance — third-party license display)
    implementation("com.google.android.gms:play-services-oss-licenses:17.1.0")
}
