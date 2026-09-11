package com.filestech.agenda_tech.widget

import android.content.Context
import android.content.Intent
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.ImageProvider
import androidx.glance.LocalContext
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.action.actionStartActivity
import androidx.glance.appwidget.cornerRadius
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.fillMaxWidth
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.filestech.agenda_tech.MainActivity
import com.filestech.agenda_tech.R
import com.filestech.agenda_tech.domain.birthday.BirthdayAge
import com.filestech.agenda_tech.domain.birthday.birthdayDisplayTitle
import com.filestech.agenda_tech.domain.repository.LockRepository
import com.filestech.agenda_tech.domain.repository.SettingsRepository
import com.filestech.agenda_tech.domain.usecase.ObserveOccurrencesInRangeUseCase
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.flow.first
import timber.log.Timber

/**
 * Home-screen widget: shows today's date and the next few events, and opens the app when tapped.
 * Data is fetched through a Hilt [WidgetEntryPoint] (widgets run outside the Activity graph).
 * Colours are a fixed brand card (see [WidgetPalette]) rather than theme-following: a widget is
 * drawn over the user's wallpaper, which the app neither controls nor can measure, so following the
 * light/dark theme would still leave it unreadable over a busy photo.
 */
class AgendaWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        // The widget is a process that starts without the app, on the platform's schedule. Everything
        // [loadData] touches can refuse: the Keystore is not always reachable straight after boot, and
        // the database open behind it is the same one `DatabaseFactory` has a whole failure taxonomy
        // for. Letting that escape means the Glance worker fails, the host keeps the PREVIOUS render —
        // and that render may carry the very titles the lock was just turned on to hide.
        //
        // So it fails **closed**, into a card with no rows: no agenda content can survive a failure to
        // read the agenda. Empty is also honest here, since the alternative is showing a day that may
        // no longer exist.
        val data = runCatching { loadData(context) }
            .getOrElse { error ->
                Timber.w(error, "AgendaWidget: cannot read the agenda — rendering an empty card")
                blankData(context)
            }
        provideContent { WidgetContent(data) }
    }

    /** What the widget shows when the agenda cannot be read: the date, and nothing about the agenda. */
    private fun blankData(context: Context): WidgetData {
        val locale = Locale.getDefault()
        val today = LocalDate.now(ZoneId.systemDefault())
        return WidgetData(
            dayNumber = today.dayOfMonth.toString(),
            subtitle = today
                .format(DateTimeFormatter.ofLocalizedDate(FormatStyle.FULL).withLocale(locale))
                .replaceFirstChar { if (it.isLowerCase()) it.titlecase(locale) else it.toString() },
            rows = emptyList(),
            emptyLabel = context.getString(R.string.widget_no_events),
        )
    }

    private suspend fun loadData(context: Context): WidgetData {
        val entryPoint = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val zone = ZoneId.systemDefault()
        val locale = Locale.getDefault()
        val today = LocalDate.now(zone)
        val startMillis = today.atStartOfDay(zone).toInstant().toEpochMilli()
        val endMillis = today.plusDays(UPCOMING_DAYS).atStartOfDay(zone).toInstant().toEpochMilli()

        val occurrences = entryPoint.observeOccurrences().invoke(startMillis, endMillis).first()
        // LOCK-3 — the widget lives on the home screen, outside the app-lock gate. When a PIN/biometric
        // lock is enabled we force-hide titles; the user's explicit "hide titles" preference also
        // still applies.
        //
        // This decides what the NEXT render shows, and nothing more. The claim that used to sit here —
        // "enabling the lock never leaves event titles readable on the home screen" — needed something
        // to redraw the widget at that moment, and nothing did: `updateAll` had a single caller in the
        // whole app, after a restore. `agenda_widget_info.xml` asks the platform for a 30-minute
        // period, so the titles the lock was turned on to hide stayed on the home screen for up to
        // half an hour. `SettingsViewModel.refreshWidget()` is what makes the sentence true; it now
        // delegates to `AgendaChangeNotifier.onAgendaChanged(rearmReminders = false)`, the seam every
        // agenda write goes through. If that call is ever dropped from the lock path, this line
        // silently goes back to being a 30-minute promise. `SettingsViewModelWidgetRefreshTest`
        // fails if it is.
        val hideTitles = entryPoint.settingsRepository().current().widgetHideTitles ||
            entryPoint.lockRepository().isLockEnabled()
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
                // SEC-W1 — respect the "hide titles in widget" privacy setting. The age rides with
                // the title and is hidden with it: it is exactly the kind of thing the setting exists
                // to keep off a home screen other people can see.
                val title = birthdayDisplayTitle(
                    resources = context.resources,
                    title = occurrence.event.title,
                    age = BirthdayAge.of(occurrence.event, occurrence.startUtcMillis, zone),
                )
                WidgetRow(time = time, title = if (hideTitles) "" else title)
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
/**
 * Le fond de la carte, en ressource plutot qu'en couleur inline.
 *
 * Un `shape` porte les angles arrondis de 10dp : `GlanceModifier.cornerRadius` n'est applique qu'a
 * partir d'Android 12 et reste **sans effet** en dessous, alors que le minSdk est 26 et que
 * l'appareil de recette tourne en Android 10. Le drawable, lui, arrondit partout. `cornerRadius` est
 * pose en plus pour qu'Android 12+ rogne aussi le CONTENU et non le seul fond.
 *
 * La couleur vit dans `colors.xml` : elle etait ecrite en dur ici ET dans `AgendaIconWidget`, et le
 * drawable en aurait fait une troisieme copie.
 */
private const val WIDGET_CORNER_RADIUS_DP = 13

/**
 * Les deux couleurs du damier du logo, reparties comme lui : **la date sur le bleu, les evenements
 * sur le rouge**.
 *
 * ## Pourquoi la zone rouge prend toute la hauteur restante
 *
 * `defaultWeight()` et non une hauteur fixe : sans lui, un jour a un seul rendez-vous laisserait le
 * bas de la carte transparent, et l'angle arrondi du bas se dessinerait au milieu du widget, sur le
 * fond d'ecran. La zone doit descendre jusqu'en bas quel que soit le nombre de lignes.
 *
 * ## Pourquoi tous les textes sont blancs
 *
 * Mesure, pas gout : le blanc rend 6,01:1 sur le bleu `#0B60C5` et 5,24:1 sur le rouge `#CA331F`,
 * donc au-dessus du seuil de 4,5:1 des deux cotes, y compris pour les libelles de 12 sp. Le bleu
 * clair `#A9C7FF` qui servait d'accent sur l'ancien fond indigo n'y arrivait pas : 3,52:1 sur le
 * bleu, 3,07:1 sur le rouge — lisible en 30 sp gras, **sous le seuil** pour l'heure d'un rendez-vous.
 * La hierarchie passe donc par la taille et la graisse, pas par la teinte.
 */
@Composable
private fun WidgetContent(data: WidgetData) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            // Rogne le CONTENU sur Android 12+. En dessous il est sans effet, et ce sont les formes
            // des deux zones qui portent les angles — d'ou leurs coins arrondis separes.
            .cornerRadius(WIDGET_CORNER_RADIUS_DP.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
    ) {
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .background(ImageProvider(R.drawable.widget_zone_blue))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            Text(
                text = data.dayNumber,
                style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = WidgetPalette.OnCard),
            )
            Text(
                text = data.subtitle,
                style = TextStyle(fontSize = 13.sp, color = WidgetPalette.OnCard),
            )
        }
        Column(
            modifier = GlanceModifier
                .fillMaxWidth()
                .defaultWeight()
                .background(ImageProvider(R.drawable.widget_zone_red))
                .padding(horizontal = 12.dp, vertical = 10.dp),
        ) {
            if (data.rows.isEmpty()) {
                Text(
                    text = data.emptyLabel,
                    style = TextStyle(fontSize = 13.sp, color = WidgetPalette.OnCard),
                )
            } else {
                data.rows.forEach { row ->
                    Row(modifier = GlanceModifier.padding(vertical = 2.dp)) {
                        Text(
                            text = row.time,
                            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = WidgetPalette.OnCard),
                            modifier = GlanceModifier.width(58.dp),
                        )
                        Text(
                            text = row.title,
                            maxLines = 1,
                            style = TextStyle(fontSize = 13.sp, color = WidgetPalette.OnCard),
                        )
                    }
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
    fun settingsRepository(): SettingsRepository
    fun lockRepository(): LockRepository
}
