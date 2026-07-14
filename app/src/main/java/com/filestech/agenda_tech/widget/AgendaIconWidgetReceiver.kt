package com.filestech.agenda_tech.widget

import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver

/** Registers the compact [AgendaIconWidget] with the home screen. */
class AgendaIconWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = AgendaIconWidget()
}
