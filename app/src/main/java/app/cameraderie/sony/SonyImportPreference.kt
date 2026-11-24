package app.cameraderie.sony

internal enum class SonyImportPreference(val size:SonyImportSize?,val label:String) {
    ORIGINAL(SonyImportSize.ORIGINAL_JPEG,"Original JPEG"),
    TWO_M(SonyImportSize.TWO_MEGAPIXEL_JPEG,"2M JPEG"),
    VGA(SonyImportSize.VGA_JPEG,"VGA JPEG"),
    RAW(SonyImportSize.ORIGINAL_RAW,"Original RAW (ARW)"),
    ASK(null,"Ask every time");
    companion object {
        fun parse(value:String?)=entries.firstOrNull { it.name==value } ?: ORIGINAL
    }
}
