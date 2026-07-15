package com.filestech.agenda_tech.di

import android.content.Context
import com.filestech.agenda_tech.data.local.db.AppDatabase
import com.filestech.agenda_tech.data.local.db.DatabaseFactory
import com.filestech.agenda_tech.data.local.db.dao.BackupDao
import com.filestech.agenda_tech.data.local.db.dao.CalendarDao
import com.filestech.agenda_tech.data.local.db.dao.EventDao
import com.filestech.agenda_tech.data.local.db.dao.ReminderDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context, factory: DatabaseFactory): AppDatabase =
        factory.build(context)

    @Provides fun calendarDao(db: AppDatabase): CalendarDao = db.calendarDao()
    @Provides fun eventDao(db: AppDatabase): EventDao = db.eventDao()
    @Provides fun reminderDao(db: AppDatabase): ReminderDao = db.reminderDao()
    @Provides fun backupDao(db: AppDatabase): BackupDao = db.backupDao()
}
