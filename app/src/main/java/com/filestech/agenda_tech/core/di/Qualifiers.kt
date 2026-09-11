package com.filestech.agenda_tech.core.di

import javax.inject.Qualifier

/**
 * Les qualificateurs Hilt, sortis du package `di` (audit — cycle de dépendances).
 *
 * ## Pourquoi ils ne pouvaient pas rester dans `di`
 *
 * Six fichiers de `domain/` les annotaient — les use cases qui prennent un dispatcher, plus
 * `AppLockManager` —, ce qui faisait dépendre la couche métier du package d'injection. `domain/` ne
 * contient aucun import Android, invariant que ce dépôt tient depuis le début ; mais il importait
 * `di`, qui lui dépend de Dagger, de Hilt et du reste de l'application. Le cycle était donc réel :
 * `domain → di → data/system → domain`.
 *
 * Une annotation de qualification n'est pourtant qu'un **nom** — elle ne tire ni Dagger ni Android,
 * seulement `javax.inject`. Sa place est donc dans `core/`, sous toutes les autres couches, là où
 * tout le monde a le droit de la lire. Ce qui reste dans `di/` est ce qui appartient vraiment à
 * l'injection : le module qui FOURNIT les dispatchers.
 *
 * `@Retention(BINARY)` : un qualificateur sert au moment de la compilation du graphe, jamais à
 * l'exécution — le conserver dans les métadonnées d'exécution ne coûterait que de la place.
 */

/** Travail bloquant : disque, base chiffrée, Keystore, SAF. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class IoDispatcher

/** Calcul pur : expansion de récurrences, repliage du corpus de recherche. */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class DefaultDispatcher

@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class MainDispatcher

/**
 * Portée qui survit à l'écran qui l'a lancée.
 *
 * Pour ce qui doit aboutir même si l'utilisateur quitte la vue dans la seconde : le ré-armement des
 * alarmes après une écriture ([com.filestech.agenda_tech.system.AgendaChangeNotifier]), le
 * ré-armement de la sauvegarde hebdomadaire au démarrage.
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class ApplicationScope
