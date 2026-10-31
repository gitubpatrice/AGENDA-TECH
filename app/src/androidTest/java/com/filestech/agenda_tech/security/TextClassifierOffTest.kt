package com.filestech.agenda_tech.security

import android.content.Context
import android.view.ContextThemeWrapper
import android.view.textclassifier.TextClassification
import android.view.textclassifier.TextClassificationContext
import android.view.textclassifier.TextClassificationManager
import android.view.textclassifier.TextClassifier
import androidx.test.core.app.ActivityScenario
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.filestech.agenda_tech.MainActivity
import com.google.common.truth.Truth.assertThat
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The selection toolbar's "Translate" would hand an event's text to another app: the text classifier
 * is off for the whole of [MainActivity]. See [turnOffTextClassifier].
 *
 * On the real activity, where the fields live: the manager Compose reads, a session made from it as
 * Compose makes one, and the context a dialog or a sheet wraps. The control, the application's own
 * manager keeping the system classifier, shows the first assertion can fail and that the setting stays
 * local to the activity.
 *
 * ⚠️ The activity is launched ONCE, in one test. Each `@HiltAndroidTest` builds its own component,
 * hence its own settings DataStore on `agenda_settings`; a second launch in the same process opens a
 * second live DataStore on that file, which DataStore refuses by crashing the process.
 */
@SdkSuppress(minSdkVersion = 28)
@HiltAndroidTest
@RunWith(AndroidJUnit4::class)
class TextClassifierOffTest {

    // Order 0: Hilt builds its component before anything launches `MainActivity`.
    @get:Rule(order = 0)
    val hilt = HiltAndroidRule(this)

    @Test
    fun theActivityTurnsTheTextClassifierOffForEveryWindow() {
        hilt.inject()
        ActivityScenario.launch(MainActivity::class.java).use { scenario ->
            var ofTheActivity: TextClassificationManager? = null
            var ofTheWindow: TextClassificationManager? = null
            scenario.onActivity { activity ->
                ofTheActivity = activity.getSystemService(TextClassificationManager::class.java)
                // A Compose dialog or bottom sheet wraps the activity's context.
                ofTheWindow = ContextThemeWrapper(activity, android.R.style.Theme_DeviceDefault_Dialog)
                    .getSystemService(TextClassificationManager::class.java)
            }
            val manager = checkNotNull(ofTheActivity)

            assertThat(manager.textClassifier).isSameInstanceAs(TextClassifier.NO_OP)
            assertThat(ofTheWindow).isSameInstanceAs(manager)
            // What Compose does before showing the toolbar: a session, then a classification.
            val session = manager.createTextClassificationSession(
                TextClassificationContext.Builder(
                    ApplicationProvider.getApplicationContext<Context>().packageName,
                    TextClassifier.WIDGET_TYPE_EDITTEXT,
                ).build(),
            )
            val classification = session.classifyText(TextClassification.Request.Builder(WORD, 0, WORD.length).build())
            session.destroy()
            assertThat(classification.actions).isEmpty()
        }
    }

    @Test
    fun theControlTheApplicationManagerKeepsTheSystemClassifier() {
        val application = ApplicationProvider.getApplicationContext<Context>()
            .getSystemService(TextClassificationManager::class.java)
        assertThat(application.textClassifier).isNotSameInstanceAs(TextClassifier.NO_OP)
    }

    private companion object {
        const val WORD = "Intensive"
    }
}
