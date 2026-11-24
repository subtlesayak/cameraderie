package app.cameraderie.sony

import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeFormatterBuilder
import java.time.format.ResolverStyle
import java.time.temporal.ChronoField

/** Camera-reported time. A date without time never invents a shoot boundary. */
data class CameraCaptureTime(val date:LocalDate,val time:LocalTime?,val offset:ZoneOffset?=null) {
    val wallTime:LocalDateTime? get()=time?.let { LocalDateTime.of(date,it) }
    fun epochMillis(defaultZone:ZoneId):Long?=wallTime?.let { wall ->
        if(offset!=null) wall.atOffset(offset).toInstant().toEpochMilli() else wall.atZone(defaultZone).toInstant().toEpochMilli()
    }
    companion object {
        private val ptp=DateTimeFormatterBuilder().appendPattern("uuuuMMdd'T'HHmmss")
            .optionalStart().appendFraction(ChronoField.NANO_OF_SECOND,0,9,true).optionalEnd()
            .toFormatter().withResolverStyle(ResolverStyle.STRICT)
        private val ptpOffset=DateTimeFormatterBuilder().append(ptp).appendOffset("+HHMM","Z").toFormatter().withResolverStyle(ResolverStyle.STRICT)
        fun parse(text:String?):CameraCaptureTime? {
            val value=text?.trim()?.takeIf { it.length in 8..64 } ?: return null
            fun valid(date:LocalDate)=date.year in 1900..2200
            for(format in listOf(DateTimeFormatter.ISO_OFFSET_DATE_TIME,ptpOffset)) try {
                val parsed=OffsetDateTime.parse(value,format)
                if(valid(parsed.toLocalDate())) return CameraCaptureTime(parsed.toLocalDate(),parsed.toLocalTime(),parsed.offset)
            } catch(_:Exception) { }
            for(format in listOf(DateTimeFormatter.ISO_LOCAL_DATE_TIME,ptp,DateTimeFormatter.ofPattern("uuuu:MM:dd HH:mm:ss").withResolverStyle(ResolverStyle.STRICT))) try {
                val parsed=LocalDateTime.parse(value,format)
                if(valid(parsed.toLocalDate())) return CameraCaptureTime(parsed.toLocalDate(),parsed.toLocalTime())
            } catch(_:Exception) { }
            try { val date=LocalDate.parse(value,DateTimeFormatter.ISO_LOCAL_DATE);if(valid(date)) return CameraCaptureTime(date,null) } catch(_:Exception) { }
            return null
        }
    }
}
