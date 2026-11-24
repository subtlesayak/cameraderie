package app.cameraderie.gallery.net

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import app.cameraderie.ui.openAppPermissionSettings
import com.google.android.material.dialog.MaterialAlertDialogBuilder

internal object CameraWifiAccess {
    fun required(sdk: Int, targetSdk: Int): List<String> = buildList {
        add(if (sdk >= 33) Manifest.permission.NEARBY_WIFI_DEVICES else Manifest.permission.ACCESS_FINE_LOCATION)
        if (sdk >= 37 && targetSdk >= 37) add(Manifest.permission.ACCESS_LOCAL_NETWORK)
    }

    fun missing(context: Context): List<String> =
        required(Build.VERSION.SDK_INT, context.applicationInfo.targetSdkVersion).filter {
            context.checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED
        }

    fun request(activity: Activity, missing: List<String>, requestCode: Int) {
        // Android 12 requires requesting approximate and precise location together.
        val request = if (Manifest.permission.ACCESS_FINE_LOCATION in missing) {
            listOf(Manifest.permission.ACCESS_COARSE_LOCATION) + missing
        } else missing
        MaterialAlertDialogBuilder(activity)
            .setTitle("Allow camera Wi-Fi access")
            .setMessage(explanation(missing))
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Allow access") { _, _ ->
                if (!activity.isFinishing && !activity.isDestroyed) {
                    activity.requestPermissions(request.toTypedArray(), requestCode)
                }
            }
            .show()
    }

    fun showDenied(activity: Activity, missing: List<String>) {
        MaterialAlertDialogBuilder(activity)
            .setTitle("Camera Wi-Fi access is off")
            .setMessage("${explanation(missing)} Open app settings to check permissions, then connect again.")
            .setNegativeButton("Close", null)
            .setPositiveButton("Open app settings") { _, _ ->
                openAppPermissionSettings(activity)
            }
            .show()
    }

    private fun explanation(missing: List<String>): String = when {
        Manifest.permission.ACCESS_LOCAL_NETWORK in missing ->
            "Allow Nearby devices access so Cameraderie can reach the camera on its local Wi-Fi network."
        Manifest.permission.ACCESS_FINE_LOCATION in missing ->
            "Android 12 requires precise Location permission to join the camera’s Wi-Fi."
        else -> "Allow Nearby devices access so Cameraderie can join the camera’s Wi-Fi."
    }
}
