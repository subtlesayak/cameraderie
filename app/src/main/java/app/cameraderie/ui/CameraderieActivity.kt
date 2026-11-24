package app.cameraderie.ui

import android.os.Build
import android.view.View
import android.view.ViewGroup.LayoutParams.MATCH_PARENT
import android.widget.FrameLayout
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.insets.ColorProtection
import androidx.core.view.insets.ProtectionLayout
import app.cameraderie.R

/** Keeps system navigation readable on themed edge-to-edge screens. */
abstract class CameraderieActivity : AppCompatActivity() {
    override fun setContentView(view: View) {
        if (Build.VERSION.SDK_INT < 35) {
            super.setContentView(view)
            return
        }
        WindowCompat.setDecorFitsSystemWindows(window, false)
        val color = getColor(R.color.gb_systemNavigation)
        val protected = ProtectionLayout(this, listOf(
            ColorProtection(WindowInsetsCompat.Side.BOTTOM, color),
            ColorProtection(WindowInsetsCompat.Side.LEFT, color),
            ColorProtection(WindowInsetsCompat.Side.RIGHT, color)
        ))
        protected.addView(view, FrameLayout.LayoutParams(MATCH_PARENT, MATCH_PARENT))
        super.setContentView(protected)
    }

    override fun onPostResume() {
        super.onPostResume()
        WindowCompat.getInsetsController(window, window.decorView).apply {
            isAppearanceLightStatusBars = resources.getBoolean(R.bool.gb_light_bars)
            isAppearanceLightNavigationBars = false
        }
    }
}
