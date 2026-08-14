pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "MyeSalesSFA"

// Four modules. The boundary that earns its keep is :domain — a pure JVM
// module, so business rules cannot reach for android.* and their tests run
// without an emulator. Features live as packages inside :app until one of them
// is big enough to deserve its own module.
include(":app")
include(":domain")
include(":data")
include(":core")
