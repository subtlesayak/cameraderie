package app.cameraderie.gallery.media.edit

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View
import androidx.core.graphics.withClip

/** Maps the normalized crop to a fitted video frame inside the preview. */
internal class CropOverlay(context: Context) : View(context) {
    private data class Drag(val id: Int, val handle: CropHandle, val x: Float, val y: Float, val start: CropSelection)

    private val frame = RectF()
    private val box = RectF()
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val dpScale = resources.displayMetrics.density
    private var drag: Drag? = null
    private var sourceWidth = 0
    private var sourceHeight = 0
    private var aspect = 0f
    var onSelectionChanged: ((CropSelection) -> Unit)? = null
    private var selection = CropSelection.FULL

    init {
        // The adjacent Fine-tune crop action offers the same moves by tap, keyboard and TalkBack.
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    fun setVideoSize(width: Int, height: Int) {
        sourceWidth = width
        sourceHeight = height
        setVideoAspect(width.toFloat() / height)
    }

    fun setVideoAspect(aspect: Float) {
        if (!aspect.isFinite() || aspect <= 0f) return
        this.aspect = aspect
        fitFrame(width, height)
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        fitFrame(w, h)
    }

    private fun fitFrame(width: Int, height: Int) {
        if (aspect <= 0f || width == 0 || height == 0) {
            frame.setEmpty()
        } else {
            val fittedWidth = minOf(width.toFloat(), height * aspect)
            val fittedHeight = minOf(height.toFloat(), width / aspect)
            val x = (width - fittedWidth) / 2f
            val y = (height - fittedHeight) / 2f
            frame.set(x, y, x + fittedWidth, y + fittedHeight)
        }
        invalidate()
    }

    fun setSelection(crop: CropSelection) {
        selection = crop
        invalidate()
    }

    fun nudge(handle: CropHandle, dx: Float, dy: Float) {
        apply(selection.adjust(handle, dx, dy, minimumWidth(), minimumHeight()))
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        if (frame.isEmpty) return
        updateBox()
        canvas.withClip(frame) {
            paint.style = Paint.Style.FILL
            paint.color = 0x99000000.toInt()
            canvas.drawRect(frame.left, frame.top, frame.right, box.top, paint)
            canvas.drawRect(frame.left, box.bottom, frame.right, frame.bottom, paint)
            canvas.drawRect(frame.left, box.top, box.left, box.bottom, paint)
            canvas.drawRect(box.right, box.top, frame.right, box.bottom, paint)

            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 3.5f * dpScale
            paint.color = Color.BLACK
            canvas.drawRect(box, paint)
            paint.strokeWidth = 1.5f * dpScale
            paint.color = Color.WHITE
            canvas.drawRect(box, paint)
            paint.strokeWidth = dpScale
            paint.color = 0xaaffffff.toInt()
            for (line in 1..2) {
                val x = box.left + box.width() * line / 3
                val y = box.top + box.height() * line / 3
                canvas.drawLine(x, box.top, x, box.bottom, paint)
                canvas.drawLine(box.left, y, box.right, y, paint)
            }

            paint.style = Paint.Style.FILL
            for (handle in CropHandle.corners) {
                val x = handleX(handle)
                val y = handleY(handle)
                paint.color = Color.BLACK
                canvas.drawCircle(x, y, 8f * dpScale, paint)
                paint.color = Color.WHITE
                canvas.drawCircle(x, y, 6f * dpScale, paint)
            }
        }
    }

    private fun updateBox() {
        box.set(frame.left + selection.left * frame.width(), frame.top + selection.top * frame.height(),
            frame.left + selection.right * frame.width(), frame.top + selection.bottom * frame.height())
    }

    private fun handleX(handle: CropHandle): Float {
        val inset = minOf(8f * dpScale, frame.width() / 2)
        return (if (handle.left) box.left else box.right).coerceIn(frame.left + inset, frame.right - inset)
    }

    private fun handleY(handle: CropHandle): Float {
        val inset = minOf(8f * dpScale, frame.height() / 2)
        return (if (handle.top) box.top else box.bottom).coerceIn(frame.top + inset, frame.bottom - inset)
    }

    private fun handleAt(x: Float, y: Float): CropHandle? {
        if (!frame.contains(x, y)) return null
        updateBox()
        fun distanceSquared(handle: CropHandle): Float {
            val dx = x - handleX(handle)
            val dy = y - handleY(handle)
            return dx * dx + dy * dy
        }
        val nearest = CropHandle.corners.minBy(::distanceSquared)
        val hitRadius = 24f * dpScale
        return when {
            distanceSquared(nearest) <= hitRadius * hitRadius -> nearest
            box.contains(x, y) -> CropHandle.MOVE
            else -> null
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!isEnabled || frame.isEmpty) return false
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            val handle = handleAt(event.x, event.y) ?: return false
            drag = Drag(event.getPointerId(0), handle, event.x, event.y, selection)
            parent.requestDisallowInterceptTouchEvent(true)
            return true
        }

        val active = drag ?: return false
        val index = event.findPointerIndex(active.id)
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE, MotionEvent.ACTION_UP, MotionEvent.ACTION_POINTER_UP -> {
                if (index >= 0) {
                    val dx = (event.getX(index) - active.x) / frame.width()
                    val dy = (event.getY(index) - active.y) / frame.height()
                    apply(active.start.adjust(active.handle, dx, dy, minimumWidth(), minimumHeight()))
                }
                if (event.actionMasked != MotionEvent.ACTION_MOVE &&
                    event.getPointerId(event.actionIndex) == active.id) {
                    finishDrag()
                    performClick()
                }
            }
            MotionEvent.ACTION_CANCEL -> finishDrag()
        }
        return true
    }

    override fun performClick(): Boolean {
        super.performClick()
        return true
    }

    private fun finishDrag() {
        drag = null
        parent.requestDisallowInterceptTouchEvent(false)
    }

    private fun minimumWidth() = minimum(sourceWidth, frame.width())
    private fun minimumHeight() = minimum(sourceHeight, frame.height())
    private fun minimum(source: Int, displayed: Float): Float =
        maxOf(16f / source.coerceAtLeast(16), minOf(0.45f, 40f * dpScale / displayed.coerceAtLeast(1f)))
            .coerceAtMost(1f)

    private fun apply(crop: CropSelection) {
        if (selection == crop) return
        setSelection(crop)
        onSelectionChanged?.invoke(crop)
    }
}
