package app.cameraderie.sony

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.view.View

/** Non-interactive visual counterpart of the parent tile's checked semantics.
 * Opaque black/white markers remain distinct over both dark and light photos. */
internal class SonySelectionMarker(context:Context):View(context) {
    var checked=false
        set(value) { if(field!=value) { field=value;invalidate() } }
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val checkPath=Path()
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO;isClickable=false;isFocusable=false }
    override fun onDraw(canvas:Canvas) {
        val cx=width/2f;val cy=height/2f;val radius=minOf(width,height)/2f-1
        paint.style=Paint.Style.FILL;paint.color=Color.BLACK;canvas.drawCircle(cx,cy,radius,paint)
        paint.color=Color.WHITE;paint.strokeWidth=resources.displayMetrics.density*2
        paint.style=if(checked) Paint.Style.FILL else Paint.Style.STROKE
        canvas.drawCircle(cx,cy,radius*.72f,paint)
        if(checked) {
            paint.style=Paint.Style.STROKE;paint.strokeCap=Paint.Cap.ROUND;paint.strokeJoin=Paint.Join.ROUND;paint.color=Color.BLACK
            checkPath.rewind();checkPath.moveTo(cx-radius*.4f,cy);checkPath.lineTo(cx-radius*.1f,cy+radius*.28f);checkPath.lineTo(cx+radius*.42f,cy-radius*.3f)
            canvas.drawPath(checkPath,paint)
        }
    }
}
