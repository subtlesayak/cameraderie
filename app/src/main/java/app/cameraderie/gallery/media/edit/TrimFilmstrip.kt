package app.cameraderie.gallery.media.edit

import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.util.Log
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import androidx.annotation.OptIn
import androidx.core.content.ContextCompat
import androidx.media3.common.MediaItem
import androidx.media3.common.util.UnstableApi
import androidx.media3.effect.Presentation
import androidx.media3.inspector.frame.FrameExtractor
import app.cameraderie.ui.UiKit
import com.google.common.util.concurrent.ListenableFuture
import java.util.concurrent.ExecutionException

@OptIn(UnstableApi::class)
internal class TrimFilmstrip(context: Context, ui: UiKit) : LinearLayout(context) {
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val tiles = List(6) {
        ImageView(context).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP
            addView(this, LayoutParams(0, ui.dp(44), 1f))
        }
    }
    private var extractor: FrameExtractor? = null
    private var pending: ListenableFuture<FrameExtractor.Frame>? = null
    private var nextTile = 0

    init {
        orientation = HORIZONTAL
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
        background = GradientDrawable().apply {
            setColor(ui.tone.outline)
            cornerRadius = ui.dp(8).toFloat()
        }
        clipToOutline = true
        visibility = GONE
    }

    fun start(video: VerifiedVideo) {
        if (extractor != null || nextTile == tiles.size) return
        val current = try {
            FrameExtractor.Builder(context, MediaItem.fromUri(Uri.fromFile(video.file)))
                .setEffects(listOf(Presentation.createForWidthAndHeight(
                    160, 96, Presentation.LAYOUT_SCALE_TO_FIT_WITH_CROP
                )))
                .build()
        } catch (error: RuntimeException) {
            fail(error)
            return
        }
        extractor = current
        loadNext(current, video.durationMs)
    }

    fun stop() {
        val current = extractor
        extractor = null
        pending?.cancel(false)
        pending = null
        current?.close()
    }

    private fun loadNext(current: FrameExtractor, durationMs: Long) {
        if (nextTile == tiles.size) {
            stop()
            return
        }
        val fraction = (nextTile + 0.5) / tiles.size
        val positionMs = (durationMs * fraction).toLong().coerceIn(0, durationMs - 1)
        val request = current.getFrame(positionMs)
        pending = request
        request.addListener({
            if (extractor === current) {
                pending = null
                try {
                    tiles[nextTile].setImageBitmap(request.get().bitmap)
                    visibility = VISIBLE
                    nextTile++
                    loadNext(current, durationMs)
                } catch (error: ExecutionException) {
                    fail(error.cause ?: error)
                }
            }
        }, mainExecutor)
    }

    private fun fail(error: Throwable) {
        nextTile = tiles.size
        stop()
        Log.w("TrimFilmstrip", "Could not decode the original's filmstrip", error)
    }
}
