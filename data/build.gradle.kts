// Implements the :domain contracts against Supabase (remote) and Room (the
// small local cache: config, translations, and the outbox).

// Imported explicitly: inside a Gradle build script `java` resolves to the
// JavaPluginExtension, so `java.util.Properties` does not compile.
import java.util.Properties

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "com.tinhcd.myesalessfa.data"
    compileSdk { version = release(37) }

    defaultConfig {
        minSdk = 26

        // Supplied from local.properties (gitignored) so keys never reach git.
        val props = Properties().apply {
            val f = rootProject.file("local.properties")
            if (f.exists()) f.inputStream().use { stream -> load(stream) }
        }
        buildConfigField(
            "String", "SUPABASE_URL",
            "\"${props.getProperty("supabase.url") ?: ""}\""
        )
        buildConfigField(
            "String", "SUPABASE_PUBLISHABLE_KEY",
            "\"${props.getProperty("supabase.publishableKey") ?: ""}\""
        )
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        buildConfig = true
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.generateKotlin", "true")
}

dependencies {
    implementation(project(":domain"))

    // Supabase SDK for auth only — sign-in, session persistence and token
    // refresh. Every data call goes through Retrofit below.
    implementation(platform(libs.supabase.bom))
    implementation(libs.supabase.auth)
    implementation(libs.supabase.storage)
    implementation(libs.ktor.client.okhttp)

    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging.interceptor)

    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    // @HiltWorker support for the outbox worker.
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    testImplementation(libs.junit)
    // Asserts the requests Retrofit actually builds. Without a device to run the
    // app on, this is what proves the annotations produce the URLs verified
    // against the live API by hand.
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.turbine)
    testImplementation(libs.mockk)
}
