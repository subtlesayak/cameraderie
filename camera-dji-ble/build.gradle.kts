plugins { id("com.android.library") }

android {
    namespace = "app.gripbridge.camera.dji.ble"
    compileSdk {
        version = release(37) { minorApiLevel = 2 }
    }
    defaultConfig { minSdk = 31 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    api(project(":camera-core"))
    api("no.nordicsemi.android:ble:2.11.0")
}
