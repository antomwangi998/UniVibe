plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// Push needs your own Firebase project: put its google-services.json in app/ (see docs/PUSH_NOTIFICATIONS.md).
// Without that file the app still builds and runs; push is simply switched off.
if (file("google-services.json").exists()) apply(plugin = "com.google.gms.google-services")

// API base URL comes from gradle.properties / -P, never from source. Blank = mock auth (debug only).
val apiBaseUrl = (project.findProperty("univibe.apiBaseUrl") as? String).orEmpty()
// OAuth *web application* client id used as the Google ID token audience ("server client id"). Public, not a secret.
// Blank = the "Continue with Google" button is hidden. See docs/GOOGLE_SIGN_IN.md.
val googleServerClientId = (project.findProperty("univibe.googleServerClientId") as? String).orEmpty()

android {
    namespace = "com.univibe.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.univibe.app" // placeholder: change before publishing
        minSdk = 26
        targetSdk = 34
        versionCode = (project.findProperty("univibe.versionCode") as? String)?.toIntOrNull() ?: 7
        versionName = (project.findProperty("univibe.versionName") as? String) ?: "0.1.6-live"
        
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("String", "GOOGLE_SERVER_CLIENT_ID", "\"$googleServerClientId\"")
    }

    signingConfigs {
        create("univibeDebug") {
            storeFile = file("univibe-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        debug {
            if (file("univibe-debug.keystore").exists()) {
                signingConfig = signingConfigs.getByName("univibeDebug")
            }
        }
        // Same as release (not debuggable, optimised) but not minified and signed with the fixed debug key, so it installs
        // over the debug app. Debug builds run Compose much slower; judge scrolling on this one.
        create("fast") {
            initWith(getByName("release"))
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            matchingFallbacks += listOf("release")
            if (file("univibe-debug.keystore").exists()) signingConfig = signingConfigs.getByName("univibeDebug")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    // Installs the baseline profile (this project's and the libraries') on first run, also for sideloaded APKs.
    implementation("androidx.profileinstaller:profileinstaller:1.4.1")
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.coil.compose) // loads picked photos from app storage
    implementation(libs.media3.exoplayer) // feed video: one shared player, autoplay as you scroll
    implementation(libs.media3.ui)
    implementation(libs.media3.datasource) // disk cache so re-scrolled videos are not downloaded again
    implementation(libs.media3.database)
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging) // push (FCM); inert until google-services.json is added
    implementation(libs.androidx.credentials) // Credential Manager: Sign in with Google and saving passwords
    implementation(libs.androidx.credentials.play.services.auth) // lets Credential Manager use Google Play services on Android 13 and older
    implementation(libs.googleid) // GoogleIdTokenCredential
    implementation(libs.okhttp) // live chat connection (WebSocket) only; ordinary requests still use HttpURLConnection
    implementation(libs.osmdroid.android) // venue map preview; OpenStreetMap tiles, no API key (see docs/MAPS.md)

    debugImplementation(libs.androidx.compose.ui.tooling)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation("org.json:json:20240303") // the Android stub of org.json does nothing in unit tests
}

// Print the full assertion message and stack trace of any failing unit test, so CI logs say what actually failed.
tasks.withType<Test>().configureEach {
    testLogging {
        events("failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
