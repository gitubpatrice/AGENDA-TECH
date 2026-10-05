package com.filestech.agenda_tech.system.alarm

import android.content.Intent
import com.google.common.truth.Truth.assertThat
import org.junit.jupiter.api.Test
import org.w3c.dom.Element
import java.io.File
import javax.xml.parsers.DocumentBuilderFactory

/**
 * What [BootReceiver] handles and what its manifest entry lets the system deliver must be the same
 * list. Each half alone looks right: an action in the code but not in the intent-filter is never
 * delivered — reminders would stay on the old zone's midnight after a journey — and an action in the
 * intent-filter but not in the code wakes the process for nothing.
 */
class BootReceiverManifestTest {

    @Test
    fun `the manifest delivers exactly the actions the receiver handles`() {
        assertThat(manifestActions()).containsExactlyElementsIn(BootReceiver.HANDLED_ACTIONS)
    }

    @Test
    fun `a change of time zone is one of them`() {
        // The reason this list was opened up: all-day reminders ring at the phone's midnight.
        assertThat(BootReceiver.HANDLED_ACTIONS).contains(Intent.ACTION_TIMEZONE_CHANGED)
        assertThat(manifestActions()).contains(Intent.ACTION_TIMEZONE_CHANGED)
    }

    private fun manifestActions(): List<String> {
        val manifest = File(MANIFEST)
        check(manifest.isFile) { "Manifeste introuvable depuis ${File(".").absolutePath} — le test ne lirait rien." }
        val document = DocumentBuilderFactory.newInstance()
            .apply { isNamespaceAware = true }
            .newDocumentBuilder()
            .parse(manifest)
        val receivers = document.getElementsByTagName("receiver")
        val bootReceiver = (0 until receivers.length)
            .map { receivers.item(it) as Element }
            .single { it.getAttributeNS(ANDROID_NS, "name") == ".system.alarm.BootReceiver" }
        val actions = bootReceiver.getElementsByTagName("action")
        return (0 until actions.length).map { (actions.item(it) as Element).getAttributeNS(ANDROID_NS, "name") }
    }

    private companion object {
        /** Relative to the module directory, which is the working directory of Gradle's unit tests. */
        const val MANIFEST = "src/main/AndroidManifest.xml"
        const val ANDROID_NS = "http://schemas.android.com/apk/res/android"
    }
}
