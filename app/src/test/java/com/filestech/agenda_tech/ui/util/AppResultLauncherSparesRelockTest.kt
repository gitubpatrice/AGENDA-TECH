package com.filestech.agenda_tech.ui.util

import androidx.activity.result.contract.ActivityResultContracts
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test

/**
 * Which launches spare the app its re-lock.
 *
 * A permission dialog must not: it only pauses the activity, so a pass handed to it could be used by a
 * press on Home right after (Gemini Pro review, 2026-09-15). Every screen-covering picker the app uses
 * must.
 */
class AppResultLauncherSparesRelockTest {

    @Test
    fun `a permission request never spares the lock`() {
        assertThat(ActivityResultContracts.RequestPermission().sparesRelock()).isFalse()
        assertThat(ActivityResultContracts.RequestMultiplePermissions().sparesRelock()).isFalse()
    }

    @Test
    fun `every picker the app opens spares the lock`() {
        assertThat(ActivityResultContracts.CreateDocument("text/calendar").sparesRelock()).isTrue()
        assertThat(ActivityResultContracts.OpenDocument().sparesRelock()).isTrue()
        assertThat(ActivityResultContracts.OpenDocumentTree().sparesRelock()).isTrue()
        assertThat(ActivityResultContracts.StartActivityForResult().sparesRelock()).isTrue()
    }
}
