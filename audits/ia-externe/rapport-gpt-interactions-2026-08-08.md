## 1) Réglages qui ne s’appliquent pas “au moment où on les change”

### OK (rien à signaler)
- **`SettingsViewModel.kt:~112-156` — `widgetHideTitles` + verrou (PIN) → rafraîchissement widget immédiat**  
  Scénario : l’utilisateur active “masquer les titres dans le widget” ou définit/désactive un PIN ; le widget est rafraîchi via `AgendaWidget().updateAll(context)`.  
  Sévérité : — ; **CONFIRMÉ OK** (c’est explicitement fait dans `updateThenRefreshWidget`, `setPin`, `disableLock`).

- **`SettingsViewModel.kt:~158-171` — réglages de notifications → recréation de channel**  
  Scénario : l’utilisateur change son/vibration/lockscreen ; `ReminderNotifier.rebuildChannel()` est appelé.  
  Sévérité : — ; **CONFIRMÉ OK** (au moins “branché” au bon endroit ; l’effet exact dépendra d’Android mais l’intention “recréation nécessaire” est bien suivie côté app).

### À RISQUE (appelants non visibles dans les fichiers fournis)
- **`SettingsViewModel.kt:~102-111` — `flagSecure` : simple persistance, aucune application visible ici**  
  Scénario : l’utilisateur active “bloquer captures d’écran (`FLAG_SECURE`)” ; si aucune Activity/Window n’observe `settings.flagSecure` pour appliquer/retirer le flag à chaud, l’écran courant reste capturable jusqu’à recréation/redémarrage.  
  Sévérité : **Élevée (privacy)** ; **PROBABLE** (dans les fichiers fournis, on ne voit aucun “applyWindowFlags”).

- **`SettingsViewModel.kt:~102-110` — thème / début de semaine / numéros de semaine : simple persistance**  
  Scénario : l’utilisateur change thème ou week start ; si les écrans (Month/Week/Day/Agenda) lisent ces valeurs “une fois” (init) au lieu de les collecter, l’UI ne reflète pas le changement immédiatement.  
  Sévérité : **Moyenne** ; **PROBABLE** (impossible à confirmer sans les ViewModels/écrans Month/Week/Day/Agenda).

- **`SettingsScreen.kt:~46-51` — disponibilité biométrie mémorisée une seule fois**  
  `val biometricAvailable = remember { StrongBiometrics.isAvailable(context) }`  
  Scénario : l’utilisateur ouvre Réglages, puis ajoute/supprime ses biométries (ou change l’état du device) et revient ; l’écran peut continuer à cacher/montrer le switch biométrique sur un état devenu faux.  
  Sévérité : **Faible à moyenne** ; **PROBABLE**.

---

## 2) Dialogues : double action, état périmé, retour arrière

### Double-tap / action déclenchable 2 fois (écritures/destruction)
- **`BackupScreen.kt:~108-123` + `BackupViewModel.kt:~170-205` — “Restaurer” peut partir deux fois (course sur `pendingRestoreFile`)**  
  Code : `PasswordDialog(... onConfirm = viewModel::restore)` ; dans `restore()`, le 1er appel met `pendingRestoreFile = null` *avant* d’avoir fini toute l’opération.  
  Scénario concret :  
  1) l’utilisateur choisit un `.atbak`, le dialogue mot de passe s’ouvre,  
  2) il double-tape le bouton de confirmation,  
  3) appel #1 : démarre la restauration, met `pendingRestoreFile = null`, passe `busy=RESTORE`,  
  4) appel #2 : voit `pendingRestoreFile == null`, wipe le password et force `_state = Failed` (donc peut **retirer l’overlay** / afficher un échec pendant qu’un restore réel tourne encore).  
  Résultat : UI incohérente + risque d’opérations concurrentes/annulées au pire moment.  
  Sévérité : **Critique (remplacement complet de l’agenda)** ; **CONFIRMÉ** (aucun garde-fou côté UI ni côté VM).

- **`SettingsScreen.kt:~205-238` — Verify PIN : double-tap peut lancer 2 vérifications en parallèle**  
  Code : `checking` est mis à `true` dans `onClick`, mais l’activation réelle du bouton (`enabled = ... && !checking`) ne change qu’après recomposition.  
  Scénario : double tap très rapide sur “Continuer” → 2 coroutines `verify(pin)` peuvent partir ; si PIN faux, ça peut **compter 2 échecs** (donc throttle plus vite) ou créer des états `wrong/checking` incohérents.  
  Sévérité : **Moyenne** ; **PROBABLE** (pattern classique Compose : “disable after click” non atomique).

- **`CalendarsScreen.kt:~82-103` — confirmation suppression calendrier : double-tap possible**  
  Scénario : double-tap sur “Supprimer” → 2 appels `viewModel.delete(target.id)` avant que le dialog disparaisse.  
  Impact dépend de l’idempotence repo/DB.  
  Sévérité : **Moyenne** ; **PROBABLE**.

### Retour arrière / état “pending” correctement nettoyé
- **`BackupViewModel.kt:~156-165` — `cancelRestore()` nettoie correctement**  
  Scénario : l’utilisateur “back”/dismiss le dialogue de mot de passe de restore → `pendingRestoreFile = null` et `awaitingRestorePassword=false`.  
  Sévérité : — ; **CONFIRMÉ OK**.

---

## 3) Rotation / mort de processus / recomposition : état au mauvais endroit

- **`BackupScreen.kt:~38-66` — export : `pendingExportPassword` dans l’UI (remember) → perdu sur rotation, et pas forcément wipé**  
  Scénario :  
  1) l’utilisateur saisit le mot de passe d’export,  
  2) le picker “Créer un document” s’ouvre,  
  3) rotation (ou recréation activité) pendant le picker,  
  4) retour du picker : `pendingExportPassword` est retombé à `null` → **aucun export ne se lance**.  
  En plus, le `CharArray` d’avant rotation ne sera pas explicitement `wipe()` (instance perdue côté UI).  
  Sévérité : **Élevée (fiabilité backup, “seul exemplaire”)** ; **PROBABLE**.

- **`SettingsScreen.kt:~140-190` — PIN et confirmation PIN en `remember` non sauvegardé**  
  Scénario : l’utilisateur tape un PIN (set/verify), rotation → le champ est vidé et le dialogue peut se fermer selon l’état parent (`showPinDialog`, `verifyPurpose`).  
  Côté sécurité c’est plutôt “fail closed”, mais côté UX cela peut surprendre ; pas d’incohérence DB directe.  
  Sévérité : **Faible** ; **CONFIRMÉ**.

---

## 4) Opérations destructives : confirmation et “ce qui va disparaître”

### OK (rien à signaler)
- **`BackupScreen.kt:~98-123` — restauration : confirmation destructive intégrée**  
  Le dialogue de mot de passe inclut un avertissement “remplace tout l’agenda” (`destructiveWarning` + libellé d’action).  
  Sévérité : — ; **CONFIRMÉ OK** (confirmation explicite).

- **`EventEditorScreen.kt:~120-170` — suppression événement non récurrent : confirmation anti-mistap**  
  Sévérité : — ; **CONFIRMÉ OK** (il y a un `AlertDialog` avant `onDelete()`).

### À RISQUE (dialogues sur état périmé)
- **`CalendarsScreen.kt:~36-55` + `~105-135` — dialogues basés sur un objet `Calendar` capturé**  
  Scénario : un calendrier est modifié/supprimé pendant que le dialogue est affiché (sync interne, restore, ou autre) ; la confirmation agit sur `target.id`/`target.name` capturés.  
  Sévérité : **Moyenne** ; **PROBABLE** (dépend des autres flux de modification).

---

## 5) Branchements / navigation

- **`AppRoot.kt:~24-58` — accès Settings/About/Search/Backup branché explicitement depuis `MonthScreen` seulement**  
  Scénario : si `WeekScreen/DayScreen/AgendaScreen` n’exposent pas eux-mêmes de chemin vers Settings/About/Search/Backup (non visible ici), alors selon la vue active l’utilisateur ne peut plus atteindre certaines pages.  
  Sévérité : **Moyenne** ; **PROBABLE** (appelants non fournis).

- **`AppRoot.kt:~86-94` — switchCalendarView : pas d’empilement infini (OK)**  
  `popUpTo(MONTH)` + `launchSingleTop=true` : le backstack reste borné.  
  Sévérité : — ; **CONFIRMÉ OK**.

---

## 6) Accessibilité (uniquement quand ça change le résultat)

- **`SettingsScreen.kt:~300-340` (`ColorRow`) / `EventEditorScreen.kt:~330-375` (`ColorPickerRow`) / `CalendarsScreen.kt:~150-185` (swatches)**  
  Les pastilles de couleur sont des `Box(...).clickable { ... }` **sans libellé/semantics** et en **30dp** environ.  
  Scénario : utilisateur TalkBack/lecteur d’écran → impossible de savoir quelle couleur est sélectionnée / impossible de choisir une couleur de manière fiable ; utilisateur avec motricité réduite → cible <48dp rend l’action difficile voire impraticable.  
  Sévérité : **Moyenne** ; **CONFIRMÉ**.

---

## 2) (Famille “commentaire/KDoc faux ou non garanti”) — points saillants

- **`BackupScreen.kt:~124` KDoc `BusyOverlay`: “Blocks input …”**  
  En pratique `BusyOverlay` est un overlay visuel sans interception d’input (pas de `clickable`/pointerInput), donc le scroll/taps/back (top bar) peuvent encore passer, et surtout la navigation back peut annuler un export/restore en cours.  
  Sévérité : **Élevée (fiabilité backup/restore)** ; **CONFIRMÉ** (et le commentaire est trop fort vs ce que le code garantit).

- **`BackupScreen.kt:~155-170` KDoc `PasswordDialog`: “every consumer wipes”** vs usages visibles  
  Dans les fichiers fournis, **`BackupViewModel.export()` ne wipe jamais `password`** et **`restore()` ne wipe que certains chemins d’erreur** (`file==null`, échec d’énumération alarmes), pas le chemin nominal. Si les usecases ne le font pas eux-mêmes, la promesse “every consumer wipes” est fausse.  
  Sévérité : **Moyenne (secret en mémoire)** ; **PROBABLE** (car dépend de `ExportBackupUseCase/RestoreBackupUseCase`, non fournis) mais **CONFIRMÉ** que *ce fichier* ne le garantit pas.

Si tu fournis les écrans Month/Week/Day/Agenda (ou l’Activity qui applique thème/FLAG_SECURE), je peux confirmer précisément quels réglages “ne prennent effet qu’au redémarrage” vs “réactifs à chaud”.