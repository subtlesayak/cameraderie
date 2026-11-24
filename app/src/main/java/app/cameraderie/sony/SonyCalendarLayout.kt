package app.cameraderie.sony

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

internal object SonyCalendarLayout {
    fun cells(month:YearMonth,firstDay:DayOfWeek):List<LocalDate?> {
        val lead=(month.atDay(1).dayOfWeek.value-firstDay.value+7)%7
        return (0 until 42).map { index ->
            val day=index-lead+1
            if(day in 1..month.lengthOfMonth()) month.atDay(day) else null
        }
    }
}
