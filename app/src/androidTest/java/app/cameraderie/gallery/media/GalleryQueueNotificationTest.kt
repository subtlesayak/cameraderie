package app.cameraderie.gallery.media

import android.app.Notification
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.ServiceConnection
import android.net.Uri
import android.os.IBinder
import app.cameraderie.GalleryService
import app.cameraderie.NativeChecks
import app.cameraderie.gallery.GalleryQueueNotificationCommand
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.Assert.*
import org.junit.Test

class GalleryQueueNotificationTest {
    @Test fun notificationActionsAreImmutableAndScopedToAnAttempt() {
        val instrumentation = NativeChecks.instance
        val context = instrumentation.targetContext
        lateinit var service: GalleryService
        val bound = CountDownLatch(1)
        val connection = object : ServiceConnection {
            override fun onServiceConnected(name: ComponentName, binder: IBinder) {
                service = (binder as GalleryService.LocalBinder).service
                bound.countDown()
            }
            override fun onServiceDisconnected(name: ComponentName) = Unit
        }
        assertTrue(context.bindService(Intent(context, GalleryService::class.java), connection, Context.BIND_AUTO_CREATE))
        try {
            assertTrue("Gallery service did not bind", bound.await(8, TimeUnit.SECONDS))
            val first = UUID.randomUUID().toString()
            val second = UUID.randomUUID().toString()
            instrumentation.runOnMainSync {
                val downloading = service.notification(percent = 35, pauseTicket = first)
                val paused = service.notification(percent = 35, resumeTicket = first)
                val reconnecting = service.notification(percent = 35, pauseTicket = second, reconnecting = true)
                val pause = downloading.actions.single { it.title == "Pause downloads" }.actionIntent
                val resume = paused.actions.single { it.title == "Resume downloads" }.actionIntent
                assertEquals(35, downloading.extras.getInt(Notification.EXTRA_PROGRESS))
                assertEquals("Total progress · 35%", downloading.extras.getCharSequence(Notification.EXTRA_TEXT))
                assertEquals(35, paused.extras.getInt(Notification.EXTRA_PROGRESS))
                assertEquals("Total progress · 35% · Refreshing library", reconnecting.extras.getCharSequence(Notification.EXTRA_TEXT))
                assertEquals(35, reconnecting.extras.getInt(Notification.EXTRA_PROGRESS))
                assertEquals(GalleryQueueNotificationCommand.PAUSE.pendingIntent(context, second),
                    reconnecting.actions.single { it.title == "Pause downloads" }.actionIntent)
                assertFalse(downloading.actions.any { it.title == "Resume downloads" })
                assertFalse(paused.actions.any { it.title == "Pause downloads" })
                assertFalse(service.notification().actions.any { it.title == "Resume downloads" })
                assertTrue(pause.isImmutable)
                assertTrue(resume.isImmutable)
                assertEquals(pause, GalleryQueueNotificationCommand.PAUSE.pendingIntent(context, first))
                assertEquals(resume, GalleryQueueNotificationCommand.RESUME.pendingIntent(context, first))
                assertNotEquals(pause, GalleryQueueNotificationCommand.PAUSE.pendingIntent(context, second))
                assertNotEquals(resume, GalleryQueueNotificationCommand.RESUME.pendingIntent(context, second))
                assertNotEquals(pause, resume)
            }
            for (command in GalleryQueueNotificationCommand.entries) {
                val correct = command.intent(context, first)
                assertEquals(first, command.ticket(correct))
                assertNull(command.ticket(Intent(command.action)))
                assertNull(command.ticket(Intent(correct).setData(Uri.parse("cameraderie://gallery-queue/one/two"))))
                assertNull(command.ticket(Intent(correct).setData(Uri.parse("cameraderie://other/$first"))))
            }
        } finally {
            context.unbindService(connection)
        }
    }
}
