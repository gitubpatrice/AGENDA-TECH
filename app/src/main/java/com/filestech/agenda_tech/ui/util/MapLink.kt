package com.filestech.agenda_tech.ui.util

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.widget.Toast
import com.filestech.agenda_tech.R
import timber.log.Timber

/**
 * Hands a `geo:` link ([com.filestech.agenda_tech.domain.location.GeoLink]) to whatever maps app the
 * phone has. The app itself sends nothing anywhere: the maps app does the looking up.
 *
 * With no maps app, it says so: a tap that did nothing at all read as a broken button (seen on the S9,
 * whose Google Maps is disabled).
 *
 * Shared by the editor's place fields and the day's list, so a place opens the same way from both.
 */
fun openMapLink(context: Context, uri: String) {
    runCatching { context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(uri))) }
        .onFailure {
            Timber.w(it, "No maps app to open a geo: link")
            if (it is ActivityNotFoundException) {
                Toast.makeText(context, R.string.map_no_app, Toast.LENGTH_SHORT).show()
            }
        }
}
