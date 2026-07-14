package com.filestech.agenda_tech.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.height
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import androidx.glance.unit.ColorProvider
import com.filestech.agenda_tech.MainActivity
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

/**
 * Home-screen widget: shows today's date and the next few events, and opens the app when tapped.
 * Data is fetched through a Hilt [WidgetEntryPoint] (widgets run outside the Activity graph).
 * Colours are explicit day/night [ColorProvider]s so the widget needs no Compose theme.
 */
class AgendaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val data = loadData(context)
        provideContent { WidgetContent(data) }
    }

    private suspend fun loadData(context: Context): WidgetData {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        val today = LocalDate.now(zone)
        val startMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = today.plusDays(UPCOMING_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()

        val occurrences = entryPoint.observeOccurrences().invoke(startMillis, endMillis).first()
        val timeFormatter = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)

        val rows = occurrences
            .sortedBy { it.startUtcMillis }
            .take(MAX_ROWS)
            .map { occurrence ->
                val time = if (occurrence.event.allDay) {
                    context.getString(R.string.month_all_day)
                } else {
                    Instant.ofEpochMilli(occurrence.startUtcMillis).atZone(zone).format(timeFormatter)
                }
                WidgetRow(time = time, title = occurrence.event.title)
            }

        val subtitle = today
            .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
            .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() }

        return WidgetData(
            dayNumber = today.dayOfMonth.toString(),
            subtitle = subtitle,
            rows = rows,
            emptyLabel = context.getString(R.string.widget_no_events),
        )
    }

    private companion object {
        const val UPCOMING_DAYS = 7L
        const val MAX_ROWS = 6
    }
}

private data class WidgetData(
    val dayNumber: String,
    val subtitle: String,
    val rows: List<WidgetRow>,
    val emptyLabel: String,
)

private data class WidgetRow(val time: String, val title: String)

// Fixed brand look (indigo card, light text) so the widget reads well on any launcher wallpaper.
private val WidgetBackground = ColorProvider(Color(0xFF262660))
private val WidgetPrimary = ColorProvider(Color(0xFFA9C7FF))
private val WidgetOnBackground = ColorProvider(Color(0xFFFFFFFF))

@Composable
private fun WidgetContent(data: WidgetData) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(WidgetBackground)
            .padding(12.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
    ) {
        Text(
            text = data.dayNumber,
            style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = WidgetPrimary),
        )
        Text(
            text = data.subtitle,
            style = TextStyle(fontSize = 13.sp, color = WidgetOnBackground),
        )
        Spacer(GlanceModifier.height(8.dp))
        if (data.rows.isEmpty()) {
            Text(
                text = data.emptyLabel,
                style = TextStyle(fontSize = 13.sp, color = WidgetOnBackground),
            )
        } else {
            data.rows.forEach { row ->
                Row(modifier = GlanceModifier.padding(vertical = 2.dp)) {
                    Text(
                        text = row.time,
                        style = TextStyle(fontSize = 12.sp, color = WidgetPrimary),
                        modifier = GlanceModifier.width(58.dp),
                    )
                    Text(
                        text = row.title,
                        maxLines = 1,
                        style = TextStyle(fontSize = 13.sp, color = WidgetOnBackground),
                    )
                }
            }
        }
    }
}

/** Hilt access point for the widget, which lives outside the Activity/ViewModel graph. */
@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun observeOccurrences(): ObserveOccurrencesInRangeUseCase
}
