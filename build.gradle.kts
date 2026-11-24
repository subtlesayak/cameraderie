plugins {
    id("com.android.application") version "9.1.1" apply false
    id("com.android.library") version "9.1.1" apply false
}

configure(listOf(project(":camera-core"), project(":camera-dji-ble"))) {
    layout.buildDirectory.set(rootProject.layout.buildDirectory.dir("camera-modules/$name"))
}
