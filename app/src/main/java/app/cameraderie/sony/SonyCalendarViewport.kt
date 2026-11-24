package app.cameraderie.sony

import android.content.Context
import android.view.View
import android.view.ViewGroup
import android.widget.HorizontalScrollView

/** HorizontalScrollView ignores a child's layout width during measurement.
 * Bound the actual measure specs; only the explicit accessible minimum may
 * require scrolling, never the unwrapped intrinsic width of the legend. */
internal class SonyCalendarViewport(context:Context,content:View):HorizontalScrollView(context) {
    init { isFillViewport=true;addView(content,LayoutParams(-2,-2)) }
    private fun boundedWidth(child:View,parentWidthMeasureSpec:Int,used:Int):Int {
        val available=(View.MeasureSpec.getSize(parentWidthMeasureSpec)-paddingLeft-paddingRight-used).coerceAtLeast(0)
        return View.MeasureSpec.makeMeasureSpec(maxOf(available,child.minimumWidth),View.MeasureSpec.EXACTLY)
    }
    override fun measureChild(child:View,parentWidthMeasureSpec:Int,parentHeightMeasureSpec:Int) {
        val params=child.layoutParams
        child.measure(boundedWidth(child,parentWidthMeasureSpec,0),getChildMeasureSpec(parentHeightMeasureSpec,paddingTop+paddingBottom,params.height))
    }
    override fun measureChildWithMargins(child:View,parentWidthMeasureSpec:Int,widthUsed:Int,parentHeightMeasureSpec:Int,heightUsed:Int) {
        val params=child.layoutParams as ViewGroup.MarginLayoutParams
        child.measure(boundedWidth(child,parentWidthMeasureSpec,params.leftMargin+params.rightMargin+widthUsed),
            getChildMeasureSpec(parentHeightMeasureSpec,paddingTop+paddingBottom+params.topMargin+params.bottomMargin+heightUsed,params.height))
    }
}
