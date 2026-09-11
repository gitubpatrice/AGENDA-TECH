package com.filestech.agenda_tech.di

import com.filestech.agenda_tech.core.di.ApplicationScope
import com.filestech.agenda_tech.core.di.DefaultDispatcher
import com.filestech.agenda_tech.core.di.IoDispatcher
import com.filestech.agenda_tech.core.di.MainDispatcher
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import javax.inject.Singleton

// Les qualificateurs eux-memes vivent dans `core/di/Qualifiers.kt` depuis l'audit du 2026-09-11 :
// six fichiers de `domain/` les annotaient, ce qui faisait dependre la couche metier du package
// d'injection. Ce module ne garde que ce qui appartient vraiment a l'injection — la FOURNITURE.

@Module
@InstallIn(SingletonComponent::class)
object CoroutineModule {
    @Provides @IoDispatcher fun ioDispatcher(): CoroutineDispatcher = Dispatchers.IO
    @Provides @DefaultDispatcher fun defaultDispatcher(): CoroutineDispatcher = Dispatchers.Default
    @Provides @MainDispatcher fun mainDispatcher(): CoroutineDispatcher = Dispatchers.Main.immediate

    @Provides @Singleton @ApplicationScope
    fun applicationScope(@DefaultDispatcher dispatcher: CoroutineDispatcher): CoroutineScope =
        CoroutineScope(SupervisorJob() + dispatcher)
}
