import org.gradle.api.artifacts.component.ComponentIdentifier
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

plugins { id("com.android.application") }

abstract class WriteRuntimeLibraries : DefaultTask() {
    @get:Input
    abstract val libraries: ListProperty<String>

    @get:OutputDirectory
    abstract val assetDirectory: DirectoryProperty

    @TaskAction
    fun writeInventory() {
        val asset = assetDirectory.file("RESOLVED_RUNTIME_LIBRARIES.txt").get().asFile
        asset.parentFile.mkdirs()
        asset.writeText(libraries.get().joinToString("\n", postfix = "\n"))
    }
}

fun resolvedLibraryCoordinates(root: ResolvedComponentResult): List<String> {
    val visited = mutableSetOf<ComponentIdentifier>()
    val libraries = sortedSetOf<String>()

    fun visit(component: ResolvedComponentResult) {
        val id = component.id
        if (!visited.add(id)) return
        if (id is ModuleComponentIdentifier) {
            libraries.add("${id.group}:${id.module}:${id.version}")
        }
        component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach {
            visit(it.selected)
        }
    }

    visit(root)
    return libraries.toList()
}

androidComponents.onVariants { variant ->
    val inventory = tasks.register<WriteRuntimeLibraries>(variant.computeTaskName("write", "RuntimeLibraries")) {
        libraries.set(variant.runtimeConfiguration.incoming.resolutionResult.rootComponent.map(::resolvedLibraryCoordinates))
        assetDirectory.set(layout.buildDirectory.dir("generated/runtime-libraries/${variant.name}"))
    }
    variant.sources.assets?.addGeneratedSourceDirectory(inventory, WriteRuntimeLibraries::assetDirectory)
}

android {
    namespace = "app.cameraderie"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig {
        applicationId = "app.cameraderie"
        minSdk = 31
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "app.cameraderie.NativeChecks"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildTypes.named("release") {
        isDebuggable = false
        isMinifyEnabled = true
        isShrinkResources = true
        proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
    }
    sourceSets.named("main") { assets.srcDir(rootProject.file("licenses")) }
    lint { abortOnError = true }
}
dependencies {
    constraints {
        implementation("com.google.guava:guava:33.7.2-android") {
            because("Fixes CVE-2026-102554 in the runtime Guava dependency")
        }
        implementation("com.google.errorprone:error_prone_annotations:2.50.0") {
            because("Keeps the Gson compile classpath aligned with the existing runtime version")
        }
    }
    val media3Version = "1.11.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-inspector-frame:$media3Version")
    implementation("androidx.media3:media3-effect:$media3Version")
    implementation("androidx.media3:media3-transformer:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    implementation(project(":camera-dji-ble"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    implementation("androidx.exifinterface:exifinterface:1.4.2")
    androidTestImplementation("junit:junit:4.13.2")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("com.google.android.material:material:1.14.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.google.code.gson:gson:2.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.squareup.okhttp3:mockwebserver:4.12.0")
}
