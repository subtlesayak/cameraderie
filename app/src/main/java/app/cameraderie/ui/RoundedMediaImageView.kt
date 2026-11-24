package app.cameraderie.ui

import android.content.Context
import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import androidx.appcompat.widget.AppCompatImageView

/** Clips the visible image itself, including a letterboxed FIT_CENTER preview.
 * The image matrix and scale type still determine its aspect ratio/cropping. */
class RoundedMediaImageView(context:Context,cornerDp:Float=12f):AppCompatImageView(context) {
    var cornerRadiusDp=cornerDp
        set(value) { require(value>=0);field=value;invalidate() }
    private val imageRect=RectF()
    private val clipPath=Path()
    override fun onDraw(canvas:Canvas) {
        val content=drawable
        if(content==null || width<=paddingLeft+paddingRight || height<=paddingTop+paddingBottom) {
            super.onDraw(canvas);return
        }
        imageRect.set(content.bounds)
        imageMatrix.mapRect(imageRect)
        imageRect.offset(paddingLeft.toFloat(),paddingTop.toFloat())
        if(!imageRect.intersect(paddingLeft.toFloat(),paddingTop.toFloat(),(width-paddingRight).toFloat(),(height-paddingBottom).toFloat())) return
        val radius=(cornerRadiusDp*resources.displayMetrics.density)
            .coerceAtMost(minOf(imageRect.width(),imageRect.height())/2f)
        clipPath.rewind();clipPath.addRoundRect(imageRect,radius,radius,Path.Direction.CW)
        val checkpoint=canvas.save()
        canvas.clipPath(clipPath);super.onDraw(canvas);canvas.restoreToCount(checkpoint)
    }
}
