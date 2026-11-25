package app.cameraderie

import android.app.Instrumentation
import android.os.Bundle
import org.junit.runner.JUnitCore

/** Device checks use synthetic cache images; they never connect to a camera. */
class NativeChecks:Instrumentation() {
    internal var exportResumeProbe: String? = null
        private set
    internal var exportResumeImportId: String? = null
        private set
    private var gallerySeekProbe = false
    internal var galleryRenderedSeek = false
        private set
    override fun onCreate(arguments:Bundle?) {
        super.onCreate(arguments)
        instance=this
        exportResumeProbe = arguments?.getString("exportResumeProbe")?.takeUnless { it == "false" }
        exportResumeImportId = arguments?.getString("exportResumeImportId")
        galleryRenderedSeek = arguments?.getString("gallerySeekProbe") == "render"
        gallerySeekProbe = galleryRenderedSeek || arguments?.getString("gallerySeekProbe") == "true"
        start()
    }
    @Volatile var importedImagesFactory:(()->android.app.Activity)?=null
    override fun newActivity(cl:ClassLoader,className:String,intent:android.content.Intent):android.app.Activity {
        if(className==app.cameraderie.gallery.media.history.ImportedImagesActivity::class.java.name)importedImagesFactory?.let { return it() }
        return super.newActivity(cl,className,intent)
    }
    override fun onStart() {
        val result=if (gallerySeekProbe) {
            JUnitCore.runClasses(app.cameraderie.gallery.media.GallerySeekProbe::class.java)
        } else if (exportResumeProbe != null) {
            JUnitCore.runClasses(app.cameraderie.gallery.media.edit.ExportResumeProbe::class.java)
        } else JUnitCore.runClasses(app.cameraderie.sony.SonyMediaNativeTest::class.java,
            app.cameraderie.gallery.media.DocumentAlbumImportNativeTest::class.java,
            app.cameraderie.gallery.media.GalleryQueueJournalTest::class.java,
            app.cameraderie.gallery.media.GalleryQueueNotificationTest::class.java,
            app.cameraderie.gallery.media.history.ImportHistoryMigrationTest::class.java,
            app.cameraderie.projects.ProjectStoreNativeTest::class.java,
            app.cameraderie.gallery.media.edit.Main10LutNativeTest::class.java,
            app.cameraderie.telemetry.GpxTrackTest::class.java)
        val report=Bundle().apply {
            putInt("tests",result.runCount);putInt("failures",result.failureCount)
            putString("stream",if(result.wasSuccessful()) "Native media checks passed" else result.failures.joinToString("\n") {
                val location=it.exception.stackTrace.firstOrNull { frame -> frame.className.startsWith("app.cameraderie") }
                "${it.testHeader}: ${it.exception.javaClass.simpleName}: ${it.exception.message.orEmpty().take(500)} @ ${location?.className}:${location?.lineNumber}"
            })
        }
        finish(if(result.wasSuccessful()) android.app.Activity.RESULT_OK else android.app.Activity.RESULT_CANCELED,report)
    }
    companion object { lateinit var instance:NativeChecks }
}
