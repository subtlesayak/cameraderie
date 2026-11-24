package app.cameraderie.gallery

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.net.Uri
import app.cameraderie.GalleryService

/** Service actions are bound to one transfer or pause, including in Android's PendingIntent cache. */
internal enum class GalleryQueueNotificationCommand(val action: String, val label: String) {
    PAUSE("PAUSE_GALLERY_QUEUE", "Pause downloads"),
    RESUME("RESUME_GALLERY_QUEUE", "Resume downloads");

    fun intent(context: Context, ticket: String): Intent =
        Intent(context, GalleryService::class.java).setAction(action).setData(
            Uri.Builder().scheme("cameraderie").authority("gallery-queue").appendPath(ticket).build(),
        )

    fun pendingIntent(context: Context, ticket: String): PendingIntent =
        PendingIntent.getService(context, 0, intent(context, ticket), PendingIntent.FLAG_IMMUTABLE)

    fun ticket(intent: Intent): String? =
        intent.data?.takeIf { intent.action == action && it.scheme == "cameraderie" && it.authority == "gallery-queue" }
            ?.pathSegments?.singleOrNull()
}
