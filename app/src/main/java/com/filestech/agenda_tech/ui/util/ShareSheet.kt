package com.filestech.agenda_tech.ui.util

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import timber.log.Timber

/*
 * Hands what "Share" built to the phone's share sheet. The app sends nothing itself: the user picks the
 * app that does — a message, a mail, another calendar.
 */

/** A few lines of text, for a message or a mail. */
fun shareText(context: Context, subject: String, text: String) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/plain"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_TEXT, text)
    }
    open(context, send)
}

/**
 * An `.ics` file, for another calendar. The read grant goes to the app picked and to no other: the
 * flag together with the [ClipData], which is what carries the grant through the share sheet.
 */
fun shareCalendarFile(context: Context, subject: String, uri: Uri) {
    val send = Intent(Intent.ACTION_SEND).apply {
        type = "text/calendar"
        putExtra(Intent.EXTRA_SUBJECT, subject)
        putExtra(Intent.EXTRA_STREAM, uri)
        clipData = ClipData.newRawUri(subject, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
    open(context, send)
}

private fun open(context: Context, send: Intent) {
    runCatching { context.startActivity(Intent.createChooser(send, null)) }
        .onFailure { Timber.w(it, "The share sheet could not be opened") }
}
