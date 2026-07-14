# Agenda Tech

Agenda / calendrier **100 % local, chiffré, zéro réseau** — la brique agenda de l'écosystème
Files Tech, pensée pour sortir de Google Agenda sans rien confier au cloud.

> État : **fondations** (scaffold). L'architecture, la base chiffrée et le graphe d'injection
> sont posés et compilent ; les fonctionnalités (vues calendrier, récurrence, rappels, ICS)
> arrivent en phase 2.

## Principes

- **Aucune permission Internet.** L'interopérabilité passe par l'import/export de fichiers
  `.ics` (RFC 5545) via le sélecteur système — jamais par synchronisation réseau.
- **Chiffré au repos.** Base Room adossée à SQLCipher ; clé maître enveloppée par
  l'AndroidKeyStore (matériel sur les appareils avec TEE). Cf. [SECURITY.md](SECURITY.md).
- **Confidentialité par défaut.** `FLAG_SECURE` posé (pas d'aperçu dans les Récents, pas de
  capture d'écran), backups cloud exclus.

## Stack

Kotlin natif · Jetpack Compose (Material 3) · Hilt · Room + SQLCipher · Coroutines/Flow ·
`Outcome`/`AppError` typés · Timber · tests JUnit5 + Truth + MockK. Version affichée lue
dynamiquement via `PackageInfo` (pas de constante à bumper).

`applicationId` : `com.filestech.agenda_tech` — `minSdk 26` (java.time natif) / `compileSdk 36`.

## Architecture (couches)

```
core/      Outcome, AppError, crypto (AeadCipher, KeystoreManager), logging
domain/    modèles purs (Calendar, Event, RecurrenceRule, Reminder + enums),
           interfaces de repository, use cases      ← aucune dépendance Android/Room
data/      entités Room, DAOs, AppDatabase (SQLCipher), mappers, repository impls
di/        modules Hilt (Coroutine, Database, Repository)
ui/        thème, navigation, écrans (HomeScreen placeholder)
```

Le domaine est **100 % pur** ; le seul point de traversée domaine↔Room est
`data/repository/EntityMappers.kt`. La récurrence est stockée en colonnes structurées
(`rrule_*`) sur l'événement — modèle iCalendar (RRULE = propriété du VEVENT).

## Roadmap (phase 2+)

1. **Expansion de récurrence** (`RecurrenceExpander`) — occurrences concrètes, fuseaux/DST, `EXDATE`.
2. **Vues** Mois / Semaine / Jour / Agenda + éditeur d'événement.
3. **Rappels** — `AlarmManager` exact + `SCHEDULE_EXACT_ALARM` (Android 13+) avec dégradation.
4. **Interop ICS** — import/export RFC 5545.
5. **Widget écran d'accueil** (Glance).
6. **Réglages** (thème, calendrier par défaut) + verrou biométrique optionnel.

## Build

```
./gradlew :app:assembleDebug        # APK debug
./gradlew test                      # tests unitaires (JUnit5)
./gradlew detekt ktlintCheck        # qualité
```

## Licence

[Apache 2.0](LICENSE).
