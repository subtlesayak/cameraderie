package app.cameraderie.gallery.media.edit

import kotlin.math.roundToInt

internal data class TrimRange(val startMs: Long, val endMs: Long) {
    init {
        require(startMs >= 0 && endMs > startMs) { "The trim end must follow the start." }
    }
}

internal enum class CropHandle(val label: String, val left: Boolean = false, val top: Boolean = false) {
    MOVE("Move box"),
    TOP_LEFT("Top left corner", left = true, top = true),
    TOP_RIGHT("Top right corner", top = true),
    BOTTOM_LEFT("Bottom left corner", left = true),
    BOTTOM_RIGHT("Bottom right corner");

    companion object {
        val corners = listOf(TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT)
    }
}

/** Coordinates relative to the displayed video, excluding the letterbox. */
internal data class CropSelection(val left: Float, val top: Float, val right: Float, val bottom: Float) {
    init {
        require(left in 0f..1f && right in 0f..1f && left < right)
        require(top in 0f..1f && bottom in 0f..1f && top < bottom)
    }

    fun adjust(handle: CropHandle, dx: Float, dy: Float, minWidth: Float, minHeight: Float): CropSelection {
        if (handle == CropHandle.MOVE) {
            val x = dx.coerceIn(-left, 1f - right)
            val y = dy.coerceIn(-top, 1f - bottom)
            return CropSelection(left + x, top + y, right + x, bottom + y)
        }
        val minimumWidth = minOf(minWidth, right - left)
        val minimumHeight = minOf(minHeight, bottom - top)
        val l = if (handle.left) (left + dx).coerceIn(0f, right - minimumWidth) else left
        val t = if (handle.top) (top + dy).coerceIn(0f, bottom - minimumHeight) else top
        val r = if (handle.left) right else (right + dx).coerceIn(left + minimumWidth, 1f)
        val b = if (handle.top) bottom else (bottom + dy).coerceIn(top + minimumHeight, 1f)
        return CropSelection(l, t, r, b)
    }

    fun frame(width: Int, height: Int): CropFrame? {
        if (this == FULL) return null
        require(width >= 16 && height >= 16) { "This frame is too small to crop." }
        fun even(size: Int, fraction: Float) = (size.toDouble() * fraction / 2).roundToInt() * 2
        val maxX = width / 2 * 2
        val maxY = height / 2 * 2
        val x = even(width, left).coerceIn(0, maxX - 16)
        val y = even(height, top).coerceIn(0, maxY - 16)
        val right = even(width, right).coerceIn(x + 16, maxX)
        val bottom = even(height, bottom).coerceIn(y + 16, maxY)
        if (x == 0 && y == 0 && right == width && bottom == height) return null
        return CropFrame(
            left = 2f * x / width - 1f,
            right = 2f * right / width - 1f,
            bottom = 1f - 2f * bottom / height,
            top = 1f - 2f * y / height,
            width = right - x,
            height = bottom - y
        )
    }

    fun savedValues() = floatArrayOf(left, top, right, bottom)

    companion object {
        val FULL = CropSelection(0f, 0f, 1f, 1f)

        fun restore(values: FloatArray?): CropSelection {
            if (values?.size != 4) return FULL
            return runCatching { CropSelection(values[0], values[1], values[2], values[3]) }.getOrDefault(FULL)
        }
    }
}

internal data class CropFrame(
    val left: Float, val right: Float, val bottom: Float, val top: Float,
    val width: Int, val height: Int
)
