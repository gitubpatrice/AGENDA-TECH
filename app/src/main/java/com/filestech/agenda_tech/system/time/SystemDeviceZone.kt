package com.filestech.agenda_tech.system.time

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import androidx.core.content.ContextCompat
import com.filestech.agenda_tech.core.time.DeviceZone
import com.filestech.agenda_tech.core.time.TimeZones
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.ZoneId
import javax.inject.Inject
import javax.inject.Singleton

/**
 * [DeviceZone] fed by the system's `TIMEZONE_CHANGED` broadcast, registered once for the life of the
 * process on the application context. The broadcast is protected — only the system sends it — so the
 * receiver opens nothing to other apps; `RECEIVER_NOT_EXPORTED` says so as well.
 *
 * This keeps the screens of a running process up to date. Reminders, which must follow the zone even
 * when no process runs, are re-armed by the manifest receiver (`BootReceiver`) on the same broadcast.
 */
@Singleton
class SystemDeviceZone @Inject constructor(@ApplicationContext context: Context) : DeviceZone {

    private val current = MutableStateFlow(ZoneId.systemDefault())
    override val zone: StateFlow<ZoneId> = current.asStateFlow()

    init {
        ContextCompat.registerReceiver(
            context,
            object : BroadcastReceiver() {
                override fun onReceive(context: Context, intent: Intent) {
                    // The intent names the new zone. The system resets the process's default zone before
                    // the broadcast goes out, so the fallback gives the same answer; reading the extra
                    // first keeps it from depending on that order.
                    current.value = TimeZones.resolveOrNull(intent.getStringExtra(EXTRA_TIME_ZONE))
                        ?: ZoneId.systemDefault()
                }
            },
            IntentFilter(Intent.ACTION_TIMEZONE_CHANGED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    private companion object {
        /** `Intent.EXTRA_TIMEZONE`, sent by the system on every version but only named from API 30. */
        const val EXTRA_TIME_ZONE = "time-zone"
    }
}
