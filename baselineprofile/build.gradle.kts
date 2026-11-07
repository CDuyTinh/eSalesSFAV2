// Generates the app's Baseline Profile and measures what it buys.
//
// Run on a phone (API 28+) with the rep signed in, so the journeys reach the
// screens a rep actually opens:
//   ./gradlew :app:generateBaselineProfile        writes app/src/release/generated/
//   ./gradlew :baselineprofile:connectedBenchmarkReleaseAndroidTest
// The second prints cold-start times with and without the profile.
plugins {
    alias(libs.plugins.android.test)
    alias(libs.plugins.baselineprofile)
}

android {
    namespace = "com.tinhcd.myesalessfa.baselineprofile"
    compileSdk { version = release(37) }

    defaultConfig {
        // Profiles are captured on API 28+; older phones still receive them.
        minSdk = 28
        targetSdk = 37
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    targetProjectPath = ":app"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

// The connected phone, not a Gradle-managed emulator: the journeys need a
// signed-in rep, which only a real session on a real device has.
baselineProfile {
    useConnectedDevices = true
}

dependencies {
    implementation(libs.androidx.junit)
    implementation(libs.androidx.uiautomator)
    implementation(libs.androidx.benchmark.macro.junit4)
}
