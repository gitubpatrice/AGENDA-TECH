package com.filestech.agenda_tech.system

import android.content.Context
import androidx.glance.appwidget.updateAll
import com.filestech.agenda_tech.core.di.ApplicationScope
import com.filestech.agenda_tech.core.di.IoDispatcher
import com.filestech.agenda_tech.system.alarm.ReminderScheduler
import com.filestech.agenda_tech.widget.AgendaWidget
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Ce qui doit arriver **après** que l'agenda a changé sur le disque : ré-armer les alarmes et
 * redessiner les widgets.
 *
 * ## Pourquoi une seule couture (audits AG-2, AG-8, AG-9)
 *
 * Ces deux gestes étaient dispersés, et chacun manquait à des endroits différents :
 *
 * - **Le widget** n'était redessiné que depuis deux points de tout le dépôt — après une restauration
 *   et depuis les réglages. Aucune création, modification ou suppression d'événement ne le touchait,
 *   et `updatePeriodMillis` vaut une demi-heure : un rendez-vous créé n'apparaissait pas avant 30
 *   minutes, et surtout un événement **supprimé restait affiché** sur l'écran d'accueil. Le
 *   commentaire `LOCK-3` d'[AgendaWidget] documente exactement ce piège — pour le verrou seulement,
 *   et le corrige là seulement.
 * - **Les alarmes** n'étaient ré-armées que depuis l'éditeur et la restauration. Les deux imports
 *   n'appelaient pas le planificateur : un événement déjà importé, déplacé dans l'agenda source puis
 *   ré-importé, gardait son alarme à l'ANCIENNE heure. Et la suppression d'un calendrier efface ses
 *   événements par cascade de clé étrangère sans rien désarmer.
 *
 * Trois écrans qui doivent penser à deux gestes, c'est six occasions d'en oublier un. Un seul appel
 * en est une.
 *
 * ## Sur [ApplicationScope], jamais sur `viewModelScope`
 *
 * L'écriture qui vient d'avoir lieu est acquittée ; ce qui suit doit aboutir même si l'utilisateur
 * quitte l'écran dans la seconde — c'est précisément ce qu'il fait après un import, dont l'écran se
 * referme tout seul. Une passe abandonnée à mi-chemin laisserait des alarmes désarmées sans que rien
 * ne le signale.
 *
 * Le planificateur arrive en [Provider] et n'est résolu qu'**à l'intérieur** de la coroutine : le
 * résoudre ouvre la base SQLCipher (bibliothèque native, IPC Keystore, déchiffrement AES-GCM), et
 * les ViewModels sont construits sur le thread principal. C'est la règle posée par l'audit F2 pour
 * les receivers, et elle vaut ici pour la même raison.
 *
 * Chaque geste est isolé dans son propre garde : un widget qui refuse de se lier ne doit pas
 * emporter le ré-armement des alarmes avec lui.
 */
@Singleton
class AgendaChangeNotifier @Inject constructor(
    @ApplicationContext private val context: Context,
    private val reminderScheduler: Provider<ReminderScheduler>,
    @ApplicationScope private val appScope: CoroutineScope,
    @IoDispatcher private val io: CoroutineDispatcher,
) {

    /**
     * Sérialise les passes (relecture gpt-5.2 du 2026-09-11).
     *
     * `rescheduleAll()` est lente : elle relit tous les rappels et déplie leurs récurrences. Deux
     * écritures rapprochées — un import suivi d'une suppression — lançaient deux passes concurrentes,
     * et rien ne garantissait leur ordre d'arrivée. La plus ANCIENNE pouvait finir en dernier et
     * armer les alarmes d'après un état périmé, jusqu'au changement suivant.
     *
     * Un `Mutex` plutôt qu'une annulation : la seconde passe doit s'exécuter, pas remplacer la
     * première — c'est elle qui porte l'état le plus récent.
     */
    private val running = Mutex()

    /**
     * À appeler après toute écriture qui change *quels événements existent* ou *quand ils ont lieu*.
     *
     * [rearmReminders] à `false` pour un changement qui ne peut pas déplacer un rappel — un
     * renommage de calendrier, un changement de couleur, une bascule de visibilité : le widget doit
     * suivre, mais relire tous les rappels ne servirait à rien.
     */
    fun onAgendaChanged(rearmReminders: Boolean = true) {
        appScope.launch {
            running.withLock {
                if (rearmReminders) {
                    guarded("re-arming reminders") {
                        withContext(io) { reminderScheduler.get().rescheduleAll() }
                    }
                }
                guarded("widget refresh") { AgendaWidget().updateAll(context) }
            }
        }
    }

    /**
     * Isole un geste : son échec est journalisé, il n'emporte pas le suivant — **mais une annulation
     * passe**.
     *
     * `runCatching`, qui tenait cette place, attrape `Throwable`, donc aussi
     * `CancellationException`. Deux conséquences, toutes deux mauvaises : une passe annulée se
     * journalisait comme une panne du widget, indiscernable d'un vrai défaut dans le log ; et
     * l'annulation avalée ici, le `withLock` rendait la main et le geste suivant démarrait dans une
     * coroutine déjà morte.
     *
     * `SettingsViewModel.refreshWidget()` relançait déjà `CancellationException`, avec cette raison
     * écrite à côté. Le notifier a été créé pour centraliser ce geste **sans reprendre ce
     * durcissement** — le chemin jumeau, encore, et cette fois c'est la copie centrale qui était la
     * plus faible. Relevé en revérifiant l'audit de cohérence du 2026-09-11, avant d'y migrer les
     * deux derniers appelants.
     */
    private suspend fun guarded(what: String, block: suspend () -> Unit) {
        try {
            block()
        } catch (c: CancellationException) {
            throw c
        } catch (t: Throwable) {
            Timber.w(t, "AgendaChangeNotifier: %s failed", what)
        }
    }
}
