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

android {
    namespace = "com.univibe.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.univibe.app" // placeholder: change before publishing
        minSdk = 26
        targetSdk = 34
        versionCode = 4
        versionName = "0.1.3-chat"
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
    }

    buildTypes {
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
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging) // push (FCM); inert until google-services.json is added
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
