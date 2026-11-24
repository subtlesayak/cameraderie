package app.cameraderie.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.WindowInsets
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.net.toUri
import androidx.core.view.AccessibilityDelegateCompat
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import app.cameraderie.R
import com.google.android.material.appbar.MaterialToolbar

internal const val RUNTIME_LIBRARIES_ASSET = "RESOLVED_RUNTIME_LIBRARIES.txt"

class AboutActivity : CameraderieActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ui = UiKit(this)
        val root = ui.column().apply { setBackgroundColor(ui.tone.background) }
        root.setOnApplyWindowInsetsListener { view, insets ->
            val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout())
            view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            WindowInsets.CONSUMED
        }
        ui.add(root, MaterialToolbar(this).apply {
            title = "About Cameraderie"
            setTitleTextColor(ui.tone.text)
            setNavigationIcon(R.drawable.ic_arrow_back)
            navigationContentDescription = "Back to Cameraderie"
            setNavigationOnClickListener { finish() }
        })

        val (scroll, body) = ui.page()
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        val info = packageManager.getPackageInfo(packageName, 0)
        val app = ui.card(body)
        val identity = LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(ImageView(context).apply {
                setImageResource(R.drawable.ic_cameraderie)
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(ui.dp(56), ui.dp(56)).apply { marginEnd = ui.dp(16) })
            addView(ui.column().apply {
                ui.add(this, ui.text("Cameraderie", UiKit.Type.section, bold = true).apply {
                    isAccessibilityHeading = true
                })
                ui.add(this, ui.text("Version ${info.versionName.orEmpty()} · build ${info.longVersionCode}",
                    UiKit.Type.small, secondary = true))
                ui.add(this, ui.text(packageName, UiKit.Type.small, secondary = true))
            }, LinearLayout.LayoutParams(0, -2, 1f))
        }
        ui.add(app, identity)
        ui.add(app, ui.text("Browse camera media, import originals, and edit video on Android."), 16)
        ui.add(app, ui.text("Separated from OpenGrip · Apache License 2.0", UiKit.Type.small, secondary = true), 8)
        ui.add(app, ui.action("Cameraderie on GitHub") {
            openSource("https://github.com/subtlesayak/cameraderie")
        }, 16)
        ui.add(app, ui.action("Dependencies and licenses") {
            startActivity(Intent(this, NoticesActivity::class.java))
        }, 8)

        val versions = runCatching {
            assets.open(RUNTIME_LIBRARIES_ASSET).bufferedReader().useLines { lines ->
                lines.filter(String::isNotBlank).associate {
                    it.substringBeforeLast(':') to it.substringAfterLast(':')
                }
            }
        }.getOrDefault(emptyMap())
        val libraries = ui.card(body, "Libraries")
        for (library in credits) {
            val title = listOfNotNull(library.name, versions[library.coordinate]).joinToString(" ")
            ui.add(libraries, sourceRow(ui, title, library))
        }
        setContentView(root)
    }

    private fun sourceRow(ui: UiKit, title: String, credit: LibraryCredit): View =
        LinearLayout(this).apply {
            gravity = Gravity.CENTER_VERTICAL
            minimumHeight = ui.dp(56)
            isFocusable = true
            setPaddingRelative(ui.dp(4), ui.dp(8), ui.dp(4), ui.dp(8))
            val ripple = TypedValue()
            if (theme.resolveAttribute(android.R.attr.selectableItemBackground, ripple, true)) {
                setBackgroundResource(ripple.resourceId)
            }
            contentDescription = "Open $title source. ${credit.role}"
            ViewCompat.setAccessibilityDelegate(this, object : AccessibilityDelegateCompat() {
                override fun onInitializeAccessibilityNodeInfo(host: View, info: AccessibilityNodeInfoCompat) {
                    super.onInitializeAccessibilityNodeInfo(host, info)
                    info.className = "android.widget.Button"
                }
            })
            addView(ui.column().apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                ui.add(this, ui.text(title, bold = true))
                ui.add(this, ui.text(credit.role, UiKit.Type.small, secondary = true), 2)
            }, LinearLayout.LayoutParams(0, -2, 1f))
            addView(ImageView(context).apply {
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
                setImageResource(R.drawable.ic_arrow_forward)
                setColorFilter(ui.tone.secondary)
            }, LinearLayout.LayoutParams(ui.dp(20), ui.dp(20)).apply { marginStart = ui.dp(12) })
            setOnClickListener { openSource(credit.source) }
        }

    private fun openSource(url: String) {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
        } catch (_: ActivityNotFoundException) {
            Toast.makeText(this, "Install a browser to open this link.", Toast.LENGTH_SHORT).show()
        }
    }

    private data class LibraryCredit(val name: String, val role: String, val source: String, val coordinate: String)

    private companion object {
        val credits = listOf(
            LibraryCredit("AndroidX Media3", "Video playback, editing, and export.",
                "https://github.com/androidx/media", "androidx.media3:media3-transformer"),
            LibraryCredit("Nordic Android BLE Library", "Camera communication over Bluetooth Low Energy.",
                "https://github.com/NordicSemiconductor/Android-BLE-Library", "no.nordicsemi.android:ble"),
            LibraryCredit("Material Components for Android", "Android controls, themes, and dialogs.",
                "https://github.com/material-components/material-components-android", "com.google.android.material:material"),
            LibraryCredit("AndroidX RecyclerView", "Scrolling media grids and lists.",
                "https://android.googlesource.com/platform/frameworks/support/+/androidx-main/recyclerview/", "androidx.recyclerview:recyclerview"),
            LibraryCredit("AndroidX ExifInterface", "Reads and writes supported image metadata.",
                "https://android.googlesource.com/platform/frameworks/support/+/androidx-main/exifinterface/", "androidx.exifinterface:exifinterface"),
            LibraryCredit("ZXing Android Embedded", "QR scanner for camera setup.",
                "https://github.com/journeyapps/zxing-android-embedded", "com.journeyapps:zxing-android-embedded"),
            LibraryCredit("ZXing", "Decodes camera setup QR codes.",
                "https://github.com/zxing/zxing", "com.google.zxing:core"),
            LibraryCredit("OkHttp", "HTTP connections for camera communication.",
                "https://github.com/square/okhttp", "com.squareup.okhttp3:okhttp"),
            LibraryCredit("Gson", "Reads and writes JSON.",
                "https://github.com/google/gson", "com.google.code.gson:gson"),
            LibraryCredit("Kotlin Coroutines", "Coordinates background camera and import work.",
                "https://github.com/Kotlin/kotlinx.coroutines", "org.jetbrains.kotlinx:kotlinx-coroutines-android"),
            LibraryCredit("Kotlin standard library", "Core Kotlin runtime support.",
                "https://github.com/JetBrains/kotlin", "org.jetbrains.kotlin:kotlin-stdlib"),
        )
    }
}
