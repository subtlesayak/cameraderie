package app.cameraderie.gallery.media

import android.content.Context
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.Gravity
import android.view.SurfaceHolder
import android.view.SurfaceView
import android.widget.FrameLayout
import android.widget.ScrollView
import android.widget.SeekBar
import androidx.appcompat.app.AppCompatActivity
import app.cameraderie.sony.SonyScreenWindows
import app.cameraderie.ui.UiKit
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.Closeable
import java.io.InterruptedIOException
import java.util.Locale

/** Keeps transport, decoder preparation and seeking off the activity thread. */
internal class GalleryClipPlayer(
    activity: AppCompatActivity,
    ui: UiKit,
    name: String,
    private val source: GalleryClipDataSource,
    exportVideo:((GalleryVideoEdit)->Unit)?=null,
    dismissed: (GalleryClipPlayer) -> Unit
) : Closeable {
    private val main = Handler(Looper.getMainLooper())
    private val thread = HandlerThread("Cameraderie-recorded-preview").apply { start() }
    private val worker = Handler(thread.looper)
    @Volatile private var closed = false
    private var player: MediaPlayer? = null
    private var prepared = false
    private var dragging = false
    private val surface = PreviewSurface(activity)
    private val status = ui.text("Loading recorded preview…",secondary=true).apply {
        accessibilityLiveRegion = android.view.View.ACCESSIBILITY_LIVE_REGION_POLITE
    }
    private val time = ui.text("",UiKit.Type.small,secondary=true)
    private val seek = SeekBar(activity).apply {
        contentDescription = "Clip position"
        minimumHeight = ui.dp(48)
        isEnabled = false
    }
    private val toggle = ui.action("Play preview",primary=true) {
        worker.post { control { if(isPlaying) pause() else start() } }
    }.apply { isEnabled = false }
    private val saveFrame = exportVideo?.let { export ->
        ui.action("Save full-resolution frame") {
            atPlayhead { position -> export(GalleryVideoEdit.Frame(position)) }
        }.apply { isEnabled=false }
    }
    private val body = ui.column().apply {
        setPadding(ui.dp(24),0,ui.dp(24),ui.dp(12))
        val viewport = FrameLayout(activity).apply {
            setBackgroundColor(android.graphics.Color.BLACK)
            addView(surface,FrameLayout.LayoutParams(-1,-1,Gravity.CENTER))
        }
        val height = (activity.resources.configuration.screenHeightDp/3).coerceIn(100,240)
        addView(viewport,android.widget.LinearLayout.LayoutParams(-1,ui.dp(height)))
        ui.add(this,status,12)
        ui.add(this,seek)
        ui.add(this,time)
        ui.add(this,toggle,8)
        saveFrame?.let { ui.add(this,it,8) }
        ui.add(this,ui.text("To edit and export an MP4, import the original and open it in Imported Images.",
            UiKit.Type.small,secondary=true),8)
    }
    private val dialog = MaterialAlertDialogBuilder(activity).setTitle(name)
        .setView(ScrollView(activity).apply { addView(body) })
        .setNegativeButton("Close",null).create()
    private val ticker = object : Runnable {
        override fun run() {
            control { publishPosition() }
            if(!closed && prepared) worker.postDelayed(this,300)
        }
    }

    private fun atPlayhead(action:(Long)->Unit) {
        worker.post {
            control {
                val length=duration.toLong()
                if(length>0) {
                    val position=currentPosition.toLong().coerceIn(0,length)
                    if(isPlaying) pause()
                    main.post { if(!closed) action(minOf(position,length-1)) }
                }
            }
        }
    }

    init {
        seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onStartTrackingTouch(bar: SeekBar) { dragging = true }
            override fun onStopTrackingTouch(bar: SeekBar) {
                dragging = false
                seekTo(bar.progress)
            }
            override fun onProgressChanged(bar: SeekBar, position: Int, fromUser: Boolean) {
                if(fromUser) {
                    time.text = positionLabel(position,bar.max)
                    if(!dragging) seekTo(position)
                }
            }
        })
        surface.holder.addCallback(object : SurfaceHolder.Callback {
            override fun surfaceCreated(holder: SurfaceHolder) {
                worker.post {
                    if(!closed) {
                        val current = player
                        if(current == null) prepare(holder) else current.setDisplay(holder)
                    }
                }
            }
            override fun surfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) = Unit
            override fun surfaceDestroyed(holder: SurfaceHolder) {
                worker.post {
                    if(!closed) {
                        control { if(isPlaying) pause() }
                        player?.setDisplay(null)
                    }
                }
            }
        })
        dialog.setOnDismissListener { close(); dismissed(this) }
        SonyScreenWindows.showMedia(dialog,activity)
    }

    private fun prepare(holder: SurfaceHolder) {
        try {
            val clip = MediaPlayer()
            player = clip
            clip.setDisplay(holder)
            clip.setAudioAttributes(AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MOVIE).build())
            clip.setOnPreparedListener {
                if(!closed && player === clip) {
                    if (!checkSource()) return@setOnPreparedListener
                    prepared = true
                    main.post {
                        if(!closed) {
                            status.text = "Recorded camera proxy"
                            toggle.isEnabled = true
                        }
                    }
                    worker.post(ticker)
                }
            }
            clip.setOnVideoSizeChangedListener { _,width,height ->
                main.post { if(!closed) surface.setAspect(width,height) }
            }
            clip.setOnInfoListener { _,event,_ ->
                if(event==MediaPlayer.MEDIA_INFO_VIDEO_RENDERING_START)
                    android.util.Log.d("CameraderieClip","Recorded proxy rendered its first video frame")
                false
            }
            clip.setOnErrorListener { _,_,_ ->
                if (player === clip && checkSource()) failed()
                true
            }
            clip.setDataSource(source)
            clip.prepareAsync()
        } catch(_: Exception) {
            if (checkSource()) failed()
        }
    }

    private fun publishPosition() {
        val clip = player ?: return
        val duration = clip.duration.coerceAtLeast(0)
        val position = clip.currentPosition
        val playing = clip.isPlaying
        main.post {
            if(!closed) {
                seek.max = duration
                seek.isEnabled = duration > 0
                saveFrame?.isEnabled=duration>0
                if(!dragging) {
                    seek.progress = position
                    val label = positionLabel(position,duration)
                    if(time.text != label) time.text = label
                }
                val action = if(playing) "Pause preview" else "Play preview"
                if(toggle.text != action) toggle.text = action
            }
        }
    }

    private fun seekTo(position: Int) {
        worker.post { control { seekTo(position.toLong(),MediaPlayer.SEEK_CLOSEST) } }
    }

    private fun control(action: MediaPlayer.() -> Unit) {
        if(closed || !prepared || !checkSource()) return
        try { player?.action() } catch(_: IllegalStateException) { failed() }
    }

    private fun checkSource(): Boolean = try {
        source.checkCurrent()
        true
    } catch (_: InterruptedIOException) {
        failed("Preview interrupted. Close this preview and reopen it from the camera library.")
        false
    }

    private fun failed(message: String = "This camera clip’s preview is unavailable.") {
        if(closed) return
        releasePlayer()
        source.close()
        main.post {
            if(!closed) {
                status.text = message
                toggle.isEnabled = false
                seek.isEnabled = false
                saveFrame?.isEnabled=false
            }
        }
    }

    private fun releasePlayer() {
        prepared = false
        worker.removeCallbacks(ticker)
        player?.release()
        player = null
    }

    override fun close() {
        if(closed) return
        closed = true
        source.close()
        worker.post {
            releasePlayer()
            thread.quitSafely()
        }
        if(dialog.isShowing) dialog.dismiss()
    }

    private fun positionLabel(position: Int,duration: Int) = "${clock(position)} / ${clock(duration)}"
    private fun clock(milliseconds: Int): String {
        val seconds = milliseconds.coerceAtLeast(0) / 1000
        return String.format(Locale.getDefault(),"%d:%02d",seconds/60,seconds%60)
    }

    private class PreviewSurface(context: Context) : SurfaceView(context) {
        private var ratio = 0.0
        fun setAspect(width: Int,height: Int) {
            if(width > 0 && height > 0) {
                ratio = width.toDouble()/height
                requestLayout()
            }
        }

        override fun onMeasure(widthSpec: Int,heightSpec: Int) {
            val width = MeasureSpec.getSize(widthSpec)
            val height = MeasureSpec.getSize(heightSpec)
            if(ratio <= 0 || width == 0 || height == 0) {
                super.onMeasure(widthSpec,heightSpec)
            } else {
                setMeasuredDimension(minOf(width,(height*ratio).toInt()),minOf(height,(width/ratio).toInt()))
            }
        }
    }
}
