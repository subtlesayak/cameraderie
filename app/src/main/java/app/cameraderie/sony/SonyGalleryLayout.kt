package app.cameraderie.sony

/** Density follows usable content width and text size, never physical pixels. */
internal object SonyGalleryLayout {
    enum class Mode(val label:String) { COMPACT("Compact grid"),COMFORTABLE("Comfortable grid"),LIST("List") }
    fun columns(mode:Mode,widthDp:Int,fontScale:Float):Int {
        require(widthDp>=0 && fontScale>0 && fontScale.isFinite())
        if(mode==Mode.LIST) return 1
        if(mode==Mode.COMPACT && compactAvailable(widthDp,fontScale)) return 3
        return if(widthDp>=280 && fontScale<1.5f) 2 else 1
    }
    fun importedColumns(mode: Mode, widthDp: Int, fontScale: Float): Int {
        val standard = columns(mode, widthDp, fontScale)
        return if (standard == 3 && widthDp >= 352) 4 else standard
    }

    fun compactAvailable(widthDp:Int,fontScale:Float)=widthDp>=320 && fontScale<=1.15f
}
