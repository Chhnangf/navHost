package com.chhangf.annal.ui.calendar.compontent

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.dp
import com.chhangf.annal.data.core.calendar.DateTimeConstants
import com.chhangf.annal.ui.calendar.utils.dayViewModifier
import com.chhangf.annal.ui.theme.CalendarTheme
import java.time.LocalDate

@Composable
internal fun InlineCalendar(
    loadedDates: Array<List<LocalDate>>,
    selectedDate: LocalDate,
    theme: CalendarTheme,
    loadNextWeek: (nextWeekDate: LocalDate) -> Unit,
    loadPrevWeek: (endWeekDate: LocalDate) -> Unit,
    onDayClick: (LocalDate) -> Unit
) {
    val itemWidth = LocalConfiguration.current.screenWidthDp / DateTimeConstants.DAYS_IN_WEEK
    CalendarPager(
        loadedDates = loadedDates,
        loadNextDates = loadNextWeek,
        loadPrevDates = loadPrevWeek
    ) { currentPage ->
        Row {
            loadedDates[currentPage]
                .forEach { date ->
                    Box(
                        modifier = Modifier
                            .width(itemWidth.dp)
                            .padding(5.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        DayView(
                            date,
                            theme = theme,
                            isSelected = selectedDate == date,
                            onDayClick = onDayClick,
                            modifier = Modifier.dayViewModifier(date)
                        )
                    }
                }
        }
    }
}