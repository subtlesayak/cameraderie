package app.cameraderie.sony

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class SonyCalendarLayoutTest {
    @Test fun leapFebruaryHasAllDaysExactlyOnce() {
        val days=SonyCalendarLayout.cells(YearMonth.of(2024,2),DayOfWeek.MONDAY)
        assertEquals(42,days.size);assertEquals(29,days.filterNotNull().size)
        assertEquals(LocalDate.of(2024,2,1),days[3]);assertEquals(LocalDate.of(2024,2,29),days[31])
    }
    @Test fun localeFirstWeekdayChangesOnlyLeadingBlankCells() {
        val month=YearMonth.of(2026,11)
        assertEquals(month.atDay(1),SonyCalendarLayout.cells(month,DayOfWeek.SUNDAY)[0])
        assertEquals(month.atDay(1),SonyCalendarLayout.cells(month,DayOfWeek.MONDAY)[6])
    }
    @Test fun emptyCellsDoNotLeakDatesFromAdjacentMonths() {
        val month=YearMonth.of(2026,2);val days=SonyCalendarLayout.cells(month,DayOfWeek.MONDAY)
        assertEquals((1..28).toList(),days.filterNotNull().map { it.dayOfMonth })
        assertTrue(days.filterNotNull().all { YearMonth.from(it)==month })
    }
}
