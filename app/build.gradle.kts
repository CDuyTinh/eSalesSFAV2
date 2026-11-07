import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    // Reads app/google-services.json (Firebase project esalesv2) for FCM.
    alias(libs.plugins.google.services)
    // Ships the profile :baselineprofile generates (src/release/generated).
    alias(libs.plugins.baselineprofile)
}

android {
    // Robolectric reads the merged resources: the Compose UI tests check what the
    // screens actually say.
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    namespace = "com.tinhcd.myesalessfa"
    compileSdk { version = release(37) }

    defaultConfig {
        applicationId = "com.tinhcd.myesalessfa"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // The Maps key, from local.properties like every other key here, so it
        // never reaches git. Empty is a working build with a blank grey map
        // rather than a failing one: a missing key must not stop someone
        // compiling the other nine screens.
        val props = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { stream -> load(stream) }
        }
        manifestPlaceholders["mapsApiKey"] = props.getProperty("maps.apiKey") ?: ""
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            // Debug-signed until the release keystore exists, so the benchmark
            // build types the profile plugin derives from this one can be installed.
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        // The sign-in screen shows the build number, which is the first thing
        // support asks a rep for.
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.messaging)
    implementation(project(":domain"))
    implementation(project(":core"))
    // :data is present only so Hilt can see the bindings in di/. Screens must
    // talk to :domain interfaces — ArchitectureTest enforces that.
    implementation(project(":data"))

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.core.splashscreen)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.process)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.androidx.camera.view)
    implementation(libs.mlkit.barcode)
    implementation(libs.androidx.glance.appwidget)
    // Installs the Baseline Profile on a sideloaded APK, where no store does it.
    implementation(libs.androidx.profileinstaller)
    baselineProfile(project(":baselineprofile"))
    implementation(libs.androidx.paging.compose)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    // The report menu's illustrations, kept as the legacy app's SVG files.
    implementation(libs.coil.svg)
    implementation(libs.play.services.maps)
    implementation(libs.maps.compose)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    testImplementation(libs.junit)
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
