package com.filestech.agenda_tech.di

import android.app.AlarmManager
import android.content.Context
import com.filestech.agenda_tech.core.time.DeviceZone
import com.filestech.agenda_tech.system.time.SystemDeviceZone
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SystemServiceModule {

    @Provides
    @Singleton
    fun alarmManager(@ApplicationContext context: Context): AlarmManager =
        context.getSystemService(AlarmManager::class.java)

    @Provides
    @Singleton
    fun deviceZone(impl: SystemDeviceZone): DeviceZone = impl
}
