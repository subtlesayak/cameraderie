package app.cameraderie.sony

import java.time.format.DateTimeFormatter

internal object SonyShootSummary {
    private val timeFormat=DateTimeFormatter.ofPattern("HH:mm")
    private val dateFormat=DateTimeFormatter.ofPattern("d MMM")
    fun time(shoot:ShootSessions.Session):String = if(shoot.hasTimes)
        "${shoot.start!!.toLocalTime().format(timeFormat)}–${shoot.end!!.toLocalTime().format(timeFormat)}"
        else "Capture time unavailable"
    fun filter(shoot:ShootSessions.Session):String =
        "${shoot.date?.format(dateFormat) ?: "Date unknown"} · ${if(shoot.hasTimes) shoot.start!!.toLocalTime().format(timeFormat) else "Time unknown"}"
}
