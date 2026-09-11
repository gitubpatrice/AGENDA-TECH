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
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import com.filestech.agenda_tech.MainActivity
import com.filestech.agenda_tech.R
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.TextStyle as JavaTextStyle
import java.util.Locale

/**
 * Compact "icon-style" 1×1 widget: the weekday, the day number, and the month — a live alternative
 * to a dynamic launcher icon (which Android doesn't natively support). Tapping opens the app. It
 * needs no data access (only today's date), so unlike [AgendaWidget] it has no Hilt entry point.
 */
class AgendaIconWidget : GlanceAppWidget() {

    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val locale = Locale.getDefault()
        val today = LocalDate.now(ZoneId.systemDefault())
        val weekday = today.dayOfWeek.getDisplayName(JavaTextStyle.SHORT, locale).uppercase(locale)
        val month = today.month.getDisplayName(JavaTextStyle.SHORT, locale).uppercase(locale)
        val day = today.dayOfMonth.toString()
        provideContent { IconContent(weekday = weekday, day = day, month = month) }
    }
}

/**
 * Le petit widget est plus arrondi que le grand (8dp contre 13), et c'est deliberé : a la taille
 * d'une icone de lanceur, un rayon egal a celui d'une grande carte parait presque droit.
 *
 * Le BLEU, lui, est strictement le meme — les deux formes lisent `@color/widget_background`, donc
 * les deux widgets ne peuvent pas diverger de teinte.
 */
private const val ICON_CORNER_RADIUS_DP = 8

@Composable
private fun IconContent(weekday: String, day: String, month: String) {
    val context = LocalContext.current
    Column(
        modifier = GlanceModifier
            .fillMaxSize()
            .background(ImageProvider(R.drawable.widget_icon_background))
            .cornerRadius(ICON_CORNER_RADIUS_DP.dp)
            .padding(6.dp)
            .clickable(actionStartActivity(Intent(context, MainActivity::class.java))),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = weekday,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = WidgetPalette.OnCard),
        )
        Text(
            text = day,
            style = TextStyle(fontSize = 30.sp, fontWeight = FontWeight.Bold, color = WidgetPalette.OnCard),
        )
        Text(
            text = month,
            style = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.Medium, color = WidgetPalette.OnCard),
        )
    }
}
