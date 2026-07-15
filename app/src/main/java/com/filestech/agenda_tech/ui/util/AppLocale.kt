package com.filestech.agenda_tech.ui.util

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import java.util.Locale

/**
 * The locale every screen formats dates and times with.
 *
 * Read from [LocalConfiguration] rather than `Locale.getDefault()` so it is **observable**: when the
 * user changes the system language, the configuration changes, every composable reading this
 * recomposes, and the dates reformat. `Locale.getDefault()` is read once per composition with no
 * subscription, so a screen already on-screen would keep formatting in the old language.
 *
 * An agenda is almost entirely formatted dates, which is why this is worth centralising rather than
 * repeating the read — and its fallback — at each of the five call sites.
 */
@Composable
fun rememberAppLocale(): Locale {
    val configuration = LocalConfiguration.current
    return remember(configuration) {
        // A running system always has at least one locale; this is a total-function guard, not a
        // real path. It sits inside remember so the non-observable read is never the one that
        // drives recomposition.
        configuration.locales[0] ?: Locale.getDefault()
    }
}
