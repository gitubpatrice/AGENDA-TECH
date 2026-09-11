package com.filestech.agenda_tech.system.alarm

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.filestech.agenda_tech.core.di.ApplicationScope
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CoroutineScope
import javax.inject.Inject
import javax.inject.Provider

/**
 * Reschedules every reminder alarm after a reboot — exact alarms do not survive one. Fires on
 * `BOOT_COMPLETED`, which is delivered after the user unlocks (the SQLCipher database lives in
 * credential-encrypted storage and is only readable then).
 *
 * ## Why the scheduler arrives through a [Provider] (audit F2)
 *
 * `@Inject lateinit var` is **eager**: Hilt sets every injected field when it injects the receiver,
 * which happens on the **main thread** inside `onReceive`, before this method's own body runs.
 * Resolving [ReminderScheduler] pulls `EventRepository` → `EventDao` → `AppDatabase`, i.e.
 * `DatabaseFactory.build()`: loading the SQLCipher native library, an IPC to the Keystore, an AES-GCM
 * decrypt, opening SQLCipher, and possibly copying the whole database file aside for the legacy rekey.
 * All of it on the main thread of a broadcast, at boot, when the disk is at its busiest — and if the
 * broadcast budget runs out the process is killed with `rescheduleAll()` never having run. Exact alarms
 * do not survive a reboot, so that means **every reminder silently gone**.
 *
 * `MainActivity` (`3b4da6f`) and `MainApplication` (`6aafe38`) were both moved to a `Provider` for
 * exactly this reason. The two receivers were the asymmetric twins nobody had fixed. A `Provider`
 * injects a factory, not the object, so the graph is built inside the coroutine — off the main
 * thread, after `goAsync()` has already extended the deadline. That sequence now lives in
 * [rescheduleRemindersAsync], shared with [ExactAlarmPermissionReceiver].
 */
@AndroidEntryPoint
class BootReceiver : BroadcastReceiver() {

    /** Resolved inside the coroutine, never at injection time — see the class KDoc. */
    @Inject lateinit var scheduler: Provider<ReminderScheduler>

    /** Safe to inject directly: [ApplicationScope] only needs a dispatcher, never the database. */
    @Inject @ApplicationScope lateinit var scope: CoroutineScope

    override fun onReceive(context: Context, intent: Intent) {
        // MY_PACKAGE_REPLACED s'ajoute a BOOT_COMPLETED (audit AG-4, point E.4).
        //
        // Une mise a jour de l'application (`adb install -r`, ou une mise a jour F-Droid) peut
        // annuler les alarmes selon la version d'Android : le comportement d'AlarmManagerService
        // sur EXTRA_REPLACING a change plusieurs fois, et l'application ne peut pas le mesurer de
        // l'interieur. Le filet coute une ligne de manifeste et un `||` ici ; l'absence de filet
        // coute des rappels qui ne sonnent plus, en silence, apres chaque mise a jour.
        //
        // Les deux diffusions sont PROTEGEES par la plateforme (seul le systeme peut les emettre),
        // donc exporter ce receiver n'ouvre toujours rien — contrairement au cas mesure faux pour
        // ExactAlarmPermissionReceiver, ou l'action n'etait protegee qu'a partir d'API 31.
        val action = intent.action
        if (action != Intent.ACTION_BOOT_COMPLETED && action != Intent.ACTION_MY_PACKAGE_REPLACED) return
        rescheduleRemindersAsync(scope, scheduler, reason = action)
    }
}
