pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
    resolutionStrategy {
        eachPlugin {
            if (requested.id.id in setOf("com.android.application", "com.android.library") && requested.version != null) {
                useModule("com.android.tools.build:gradle:${requested.version}")
            }
        }
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}
rootProject.name = "Cameraderie"
include(":app")

val cameraModulesDir = providers.gradleProperty("cameraModulesDir").orNull?.let { file(it) } ?: settingsDir
listOf("camera-core", "camera-dji-ble").forEach { module ->
    include(":$module")
    project(":$module").projectDir = cameraModulesDir.resolve(module)
}
