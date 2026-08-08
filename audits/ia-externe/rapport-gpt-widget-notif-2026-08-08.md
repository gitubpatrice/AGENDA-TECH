## 1) Fuite de contenu d’agenda hors coquille protégée (widget + notifications)

### 1.A — Widget “AgendaWidget” : ce qui est affiché (et ce qui fuit)
- **AgendaWidget.kt:75-86**
  - **Constat (CONFIRMÉ)** : le widget affiche **l’heure** de chaque occurrence (`row.time`) et, si `hideTitles == false`, **le titre** (`occurrence.event.title`). Il **n’affiche pas** le lieu, les notes, ni d’autres champs.
  - **Scénario** : téléphone déverrouillé ou écran d’accueil visible “par-dessus l’épaule” / screenshot du launcher → on lit les horaires (et potentiellement les titres).
  - **Sévérité** : **ÉLEVÉE** si titres visibles (leak direct de contenu), **MOYENNE** même si titres masqués (leak de planning via horaires + “no events”/densité).

- **AgendaWidget.kt:71-72 + 85**
  - **Constat (CONFIRMÉ)** : si un verrou applicatif est “activé” (`lockRepository().isLockEnabled()`), alors `hideTitles=true` et le widget met `title=""`.
  - **Scénario** : utilisateur active le PIN/biométrie → le widget cesse d’afficher les titres (à la prochaine mise à jour effective du widget).
  - **Sévérité** : **BON POINT** sur le principe (réduction de fuite), mais voir le point “stale UI” plus bas.

- **AgendaWidget.kt:67-72**
  - **Constat (CONFIRMÉ)** : **même quand les titres sont masqués**, le widget **lit quand même la base** (via `observeOccurrences().invoke(...).first()`), donc il force une ouverture/lecture de la chaîne données (SQLCipher + dépendances) **en dehors de toute “gate UI”**.
  - **Scénario** : app verrouillée (au sens UX) mais le système rafraîchit le widget → lecture des occurrences quand même (les titres sont ensuite jetés côté UI).
  - **Sévérité** : **MOYENNE** (surface d’exécution et d’I/O hors app, pas une exfiltration directe à elle seule mais augmente l’exposition aux bugs/DoS/états transitoires).

### 1.B — Widget : respect du verrou “en pratique” (risque de contenu stale)
- **AgendaWidget.kt:68-72 (commentaire LOCK-3) + mécanisme Glance**
  - **Constat (PROBABLE)** : le commentaire affirme que “l’activation du lock ne laisse jamais les titres lisibles”, mais **ce fichier ne garantit pas** qu’un changement de `isLockEnabled()` déclenche immédiatement un `update()` du widget.
  - **Scénario concret** :
    1) l’utilisateur avait le lock désactivé et `widgetHideTitles=false` → le widget affiche des titres ;
    2) l’utilisateur active le lock dans l’app ;
    3) **si aucun rafraîchissement widget n’est forcé immédiatement**, le launcher peut continuer à afficher l’ancien rendu (RemoteViews/Glance snapshot) avec titres **jusqu’au prochain update système**.
  - **Ce que voit l’attaquant** : toujours les titres sur l’écran d’accueil après activation du PIN, pendant une fenêtre indéterminée.
  - **Sévérité** : **ÉLEVÉE** (contredit l’objectif privacy associé au lock).
  - **Statut** : **PROBABLE** (il faudrait vérifier les appelants : existe-t-il un `GlanceAppWidget.update(...)` déclenché lors des changements de lock/settings ? pas visible dans les fichiers fournis).

### 1.C — Notification de rappel : contenu réellement visible (lockscreen + shade)
- **ReminderNotifier.kt:171-195 + 223-234**
  - **Constat (CONFIRMÉ)** : la notification “privée” contient **`event.title`** en titre et **`timeText · location`** si `location` non vide.
  - **Scénario** : une personne avec accès au téléphone **déverrouillé au niveau OS** (mais app encore verrouillée par PIN) ouvre le tiroir de notifications → voit les titres/lieux sans jamais passer par le lock applicatif.
  - **Sévérité** : **ÉLEVÉE** (c’est une fuite de contenu d’agenda hors coquille protégée, par design).

- **ReminderNotifier.kt:154-168 + 193-196**
  - **Constat (CONFIRMÉ)** : `setVisibility(NotificationCompat.VISIBILITY_PRIVATE)` **et** `setPublicVersion(publicVersion)` sont présents ; `publicVersion` est générique (pas de titre/lieu utilisateur).
  - **Conséquence** :
    - si l’OS est configuré pour **masquer le contenu sensible sur écran verrouillé**, Android affichera la `publicVersion` (générique) → **bon** ;
    - si l’OS est configuré pour **afficher tout le contenu sur écran verrouillé**, une notif `PRIVATE` peut quand même montrer le contenu complet sur lockscreen (comportement dépendant des réglages utilisateur/OEM) → **le code ne peut pas “forcer” le masquage**.
  - **Sévérité** : **MOYENNE à ÉLEVÉE** selon la promesse produit autour du lock applicatif (au minimum, le commentaire “off a locked screen” n’est pas une garantie).

- **ReminderNotifier.kt:128-133 (channel locksreenVisibility)**
  - **Constat (CONFIRMÉ)** : le channel met `lockscreenVisibility` à `PRIVATE` si `settings.notifLockScreen=true`, sinon `SECRET`.
  - **Bon point** : pas de `PUBLIC`.
  - **Limite** : le **système** (et l’utilisateur via réglages notifications) peut **modifier/override** la visibilité effective d’un channel.
  - **Sévérité** : **MOYENNE** (attendu sur Android, mais ça invalide toute “garantie absolue”).

---

## 2) PendingIntent (immutabilité, collisions, contenu)

### 2.A — Notifications : PendingIntent immutables (bon point)
- **ReminderNotifier.kt:141-152** (`getActivity(... FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT)`)
- **ReminderNotifier.kt:176-187** (`getBroadcast(... FLAG_IMMUTABLE | FLAG_UPDATE_CURRENT)`)
  - **Constat (CONFIRMÉ)** : `FLAG_IMMUTABLE` est présent sur les deux.
  - **Rien à signaler** sur la mutabilité ici.

### 2.B — Collisions de requestCode / notificationId (risque de mélange d’événements)
- **ReminderNotifier.kt:239-241** (`notificationId(...) = (eventId * 31 + occurrenceStartUtcMillis).hashCode()`)
  - **Constat (PROBABLE)** : conversion en `Int` via `Long.hashCode()` ⇒ **collisions théoriquement possibles**.
  - **Scénario** : deux occurrences différentes produisent le même `notificationId` :
    - une notification peut **écraser** l’autre (`notify(id, ...)`),
    - le “Snooze” peut agir sur le mauvais couple (puisque requestCode identique pour l’action snooze).
  - **Sévérité** : **FAIBLE à MOYENNE** (peu probable en usage normal, mais c’est une classe de bug “silencieux” et difficile à diagnostiquer).

### 2.C — Alarmes : requestCode basé sur `reminderId.toInt()` (overflow)
- **ReminderScheduler.kt:213-235** (`PendingIntent.getBroadcast(..., reminderId.toInt(), ...)`)
  - **Constat (PROBABLE)** : si `reminderId` dépasse la plage `Int`, `toInt()` **wrap** ⇒ collisions possibles ⇒ une alarme peut **remplacer/annuler** une autre.
  - **Scénario** : base restaurée/importée avec ids élevés (ou corruption) → collisions → rappels qui disparaissent, snooze/annulation incohérents.
  - **Sévérité** : **MOYENNE** (fiabilité + perte de rappels ; pas une fuite directe mais impact utilisateur important).

### 2.D — Widgets : PendingIntent caché par Glance (immutabilité non vérifiable ici)
- **AgendaWidget.kt:125-131** (`clickable(actionStartActivity(Intent(...)))`)
- **AgendaIconWidget.kt:66-72** (idem)
  - **Constat (PROBABLE)** : `actionStartActivity` construit un PendingIntent côté Glance ; **ce code ne montre pas** les flags (`IMMUTABLE` vs mutable).
  - **Scénario** : launcher malveillant (widget host) qui envoie le PendingIntent avec un `fillInIntent` si mutable → injection d’extras vers `MainActivity` (impact dépend entièrement de ce que `MainActivity` accepte comme extras/deeplinks).
  - **Sévérité** : **MOYENNE** (dépend des versions Glance et de `MainActivity`).
  - **Statut** : **PROBABLE** (à confirmer en inspectant la version de Glance et/ou en instrumentation).

---

## 3) Receivers exportés du widget (déclenchement externe / boucle de travail)

### 3.A — Exported=true : déclenchement par intent explicite (DoS / I/O forcée)
- **AndroidManifest.xml: sections `<receiver ... AgendaWidgetReceiver ... exported="true">` et `AgendaIconWidgetReceiver ... exported="true">`**
- **AgendaWidgetReceiver.kt:6-9**, **AgendaIconWidgetReceiver.kt:6-9**
  - **Constat (CONFIRMÉ)** : receivers exportés (nécessaire pour l’hôte de widget).
  - **Constat (PROBABLE)** : une app tierce peut envoyer un intent **explicite** à ces receivers (même sans être un launcher) et tenter de provoquer des updates → donc exécutions + I/O (et pour `AgendaWidget`, ouverture DB).
  - **Scénario** : app malveillante boucle `sendBroadcast(Intent().setClassName(..., AgendaWidgetReceiver))` avec `APPWIDGET_UPDATE` → réveils/CPU/WorkManager jobs → consommation batterie, spam d’accès DB/keystore.
  - **Sévérité** : **MOYENNE** (DoS/batterie, surface d’exécution hors UI).

---

## 4) Robustesse (widget peut démarrer sans l’app, DB indisponible, exceptions)

### 4.A — Absence de gestion d’erreur autour du chargement DB
- **AgendaWidget.kt:54-57 + 59-98**
  - **Constat (CONFIRMÉ)** : aucun `try/catch` autour de `loadData()` ni autour de `.first()`.
  - **Scénario** : keystore/DB indisponible temporairement (post-boot, état transitoire, corruption, ressource manquante) → exception → update widget qui échoue.
  - **Effet** :
    - crash du worker Glance/WorkManager (selon implémentation),
    - widget peut **rester affiché avec l’ancien rendu** (potentiellement avec titres) si l’update échoue.
  - **Sévérité** : **MOYENNE** (fiabilité) avec **impact privacy** possible via contenu stale (voir 1.B).

### 4.B — `.first()` sur un Flow : risque de blocage si aucune émission
- **AgendaWidget.kt:67**
  - **Constat (PROBABLE)** : si le Flow n’émet jamais (erreur aval “swallow”, deadlock, DB bloquée), `first()` peut suspendre longtemps → worker qui traîne / timeouts.
  - **Sévérité** : **MOYENNE** (fiabilité/ANR worker).

### 4.C — I/O sur thread principal
- Sur ces fichiers **seuls**, je ne peux pas confirmer une I/O sur main thread : `provideGlance` est généralement exécuté hors UI (Glance/WorkManager).
  - **Statut** : **Rien à signaler (non confirmé)** *dans les fichiers fournis* ; dépend des implémentations de `SettingsRepository.current()` / `ObserveOccurrencesInRangeUseCase`.

---

## 5) Commentaires/KDoc qui promettent plus que le code ne garantit

### 5.A — Widget LOCK-3 “ne laisse jamais les titres lisibles”
- **AgendaWidget.kt:68-70**
  - **Promesse** : activer le lock “ne laisse jamais” des titres lisibles.
  - **Problème (PROBABLE)** : absence de garantie d’update immédiat + possibilité de rendu stale (RemoteViews).
  - **Statut** : **PROBABLE** (dépend des appelants non fournis).

### 5.B — Widget SEC-W1 “respect le hide titles”
- **AgendaWidget.kt:84-85**
  - **Promesse** : respect du réglage privacy.
  - **Problème (PROBABLE)** : le rendu existant peut survivre au changement de réglage tant qu’aucun refresh n’a eu lieu.
  - **Statut** : **PROBABLE**.

### 5.C — Notification “keep user-entered title/location off a locked screen”
- **ReminderNotifier.kt:153-154**
  - **Promesse** : titre/lieu hors écran verrouillé.
  - **Problèmes** :
    1) (CONFIRMÉ) la notification privée contient bien titre/lieu ;
    2) (PROBABLE) l’affichage effectif sur lockscreen dépend des réglages OS (“show all content”) + overrides channel/OEM.
  - **Statut** : **CONFIRMÉ** (le code ne “force” pas l’absence de contenu sur lockscreen dans tous les cas).

---

## Points explicitement sains (dans les fichiers fournis)

- **AgendaIconWidget** : **rien à signaler** côté fuite d’agenda (pas d’accès DB, uniquement date locale). (*AgendaIconWidget.kt:24-41*)
- **Notifications** : **bon point** : `VISIBILITY_PRIVATE` + `publicVersion` générique est correctement mis en place. (*ReminderNotifier.kt:154-196*)
- **PendingIntent des rappels** : **bon point** : `FLAG_IMMUTABLE` est utilisé. (*ReminderNotifier.kt:141-152, 176-187 ; ReminderScheduler.kt:221-233*)

Si tu veux, je peux faire une passe “confirmations” ciblée (à partir des appelants non fournis) sur : (a) déclenchement d’update widget lors d’activation du lock / changement settings, (b) version Glance et flags effectifs des PendingIntent générés, (c) code de `ReminderReceiver` pour voir si un contenu d’event est relu en base au moment du post et si ça bypass un état “verrouillé”.