package app.cameraderie.ui

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import android.widget.Toast

internal fun openAppPermissionSettings(activity: Activity) {
    try {
        activity.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            Uri.fromParts("package", activity.packageName, null)))
    } catch (_: ActivityNotFoundException) {
        Toast.makeText(activity, "Open Settings, then Apps → Cameraderie → Permissions.", Toast.LENGTH_LONG).show()
    }
}
