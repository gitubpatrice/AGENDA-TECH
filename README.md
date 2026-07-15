# Agenda Tech

Agenda / calendrier Android **100 % local, chiffré, zéro réseau** — la brique agenda de
l'écosystème **Files Tech**, pensée pour sortir de Google Agenda sans rien confier au cloud.

[![Licence](https://img.shields.io/badge/licence-Apache%202.0-blue.svg)](LICENSE)
[![Zéro réseau](https://img.shields.io/badge/r%C3%A9seau-z%C3%A9ro-success.svg)](#confidentialité)
[![Plateforme](https://img.shields.io/badge/Android-8.0%2B%20(API%2026)-brightgreen.svg)](#installation)

> **Version de test** `v0.3.0`. Fonctionnelle de bout en bout ; en cours de finition avant une
> première publication stable. Vos retours de bug sont les bienvenus dans les
> [issues](https://github.com/gitubpatrice/AGENDA-TECH/issues).

## Installation

1. Téléchargez l'APK **universel** (`agenda-tech-…-universal.apk`) depuis la
   [dernière release](https://github.com/gitubpatrice/AGENDA-TECH/releases/latest).
2. Ouvrez-le sur votre téléphone Android (8.0 ou plus). Autorisez si besoin « installer des
   applications inconnues ».
3. C'est tout — aucune inscription, aucun compte, aucune connexion.

L'APK est **universel** (fonctionne sur tous les appareils, pas de variante à choisir) et **signé**.

## Fonctionnalités

- **Vues** Mois (changement de mois par glissement fluide), Semaine, Jour et Agenda (liste).
- **Événements** : création/édition, journée entière, description, couleur par événement.
- **Lieu** : libellé, adresse postale (adresse, code postal, ville) et coordonnées GPS — un appui
  ouvre le point dans votre application de cartes (sans aucune permission de localisation).
- **Récurrences** (RFC 5545) : quotidienne / hebdo (jours choisis) / mensuelle / annuelle, avec
  intervalle, fin après N occurrences ou à une date, et **modification/suppression d'une seule
  occurrence** (modèle iCalendar `RECURRENCE-ID`).
- **Rappels** par alarmes exactes, avec re-programmation après redémarrage : délais prédéfinis ou
  valeur libre, et son au choix (sonnerie système ou votre propre fichier audio).
- **Sauvegarde chiffrée `.atbak`** : export/restauration de **tout** l'agenda (calendriers,
  événements, récurrences, lieux, rappels) dans un fichier protégé par mot de passe
  (PBKDF2 600 000 itérations + AES-256-GCM) — à ranger où vous voulez, y compris un cloud.
- **Import / export `.ics`** (RFC 5545) via le sélecteur de fichiers système — format d'échange,
  qui ne remplace pas la sauvegarde (il perd rappels, couleurs et structure des calendriers).
- **Import depuis le calendrier de l'appareil** (Google, Exchange, calendriers locaux) en
  **lecture seule** — l'app copie ce qui est déjà synchronisé sur le téléphone, **sans réseau**.
- **Verrou optionnel** par code PIN et/ou biométrie.
- **Thème sombre** (style GitHub), **widgets** écran d'accueil, français / anglais.

## Confidentialité

- **Aucune permission Internet.** Aucune donnée ne quitte l'appareil : ni cloud, ni analytics,
  ni publicité, ni SDK tiers de traçage.
- **Chiffré au repos.** Base Room adossée à **SQLCipher** (AES-256) ; clé maître enveloppée par
  l'**AndroidKeyStore** (matériel/TEE sur les appareils compatibles).
- **Sauvegardes chiffrées de bout en bout.** Le fichier `.atbak` est chiffré par votre mot de passe
  seul : il reste illisible même posé sur un cloud. Format documenté dans [SECURITY.md](SECURITY.md).
- **Confidentialité à l'écran.** `FLAG_SECURE` (pas d'aperçu dans les Récents, capture d'écran
  bloquée), sauvegardes cloud exclues (`allowBackup=false`).

Détails : [SECURITY.md](SECURITY.md) · [PRIVACY.md](PRIVACY.md).

## Stack technique

Kotlin natif · Jetpack Compose (Material 3) · Hilt · Room + SQLCipher · Coroutines/Flow ·
`Outcome`/`AppError` typés · Timber · tests JUnit5 + Truth + MockK.

`applicationId` : `com.filestech.agenda_tech` — `minSdk 26` (java.time natif) / `compileSdk 36`.

### Architecture (couches)

```
core/      Outcome, AppError, crypto (AeadCipher, KeystoreManager, PinHasher), texte, logging
domain/    modèles purs (Calendar, Event, RecurrenceRule, Reminder, DeviceCalendar/DeviceEvent
           + enums), interfaces de repository, use cases, moteur de récurrence (DST-correct),
           device/ mapper pur ligne calendrier → Event      ← aucune dépendance Android
data/      entités Room, DAOs, AppDatabase (SQLCipher), mappers, repository impls — dont
           l'accès au Calendar Provider (import appareil), caché derrière une interface domaine
di/        modules Hilt
ui/        thème, navigation, écrans (Mois/Semaine/Jour/Agenda, éditeur, réglages, verrou…)
system/    alarmes exactes des rappels · notifications
widget/    widgets Glance (agenda + icône date)
```

Le domaine est **100 % pur** ; le seul point de traversée domaine↔Room est
`data/repository/EntityMappers.kt`. La récurrence est stockée en colonnes structurées
(`rrule_*`) sur l'événement — modèle iCalendar (RRULE = propriété du VEVENT).

## Compiler depuis les sources

```bash
git clone https://github.com/gitubpatrice/AGENDA-TECH.git
cd AGENDA-TECH
./gradlew :app:assembleDebug          # APK de debug (splits + universel)
./gradlew :app:testDebugUnitTest      # tests unitaires
```

La signature de release attend un fichier `keystore.properties` (non versionné) ; le build
de debug n'en a pas besoin.

### Versions

`version.properties`, à la racine, est la **source unique** de `versionCode` / `versionName` :

```properties
versionCode=44
versionName=0.3.0
```

Le `versionCode` doit **toujours augmenter** : Android refuse d'installer un APK dont le
`versionCode` est inférieur à celui déjà installé, et l'utilisateur ne voit qu'un échec sans
explication. Il se bumpe **à la main**, avant de builder l'APK d'une release — jamais après.

Le build **échoue** si le fichier est absent ou illisible : mieux vaut ne rien produire qu'un APK
portant une version silencieusement fausse.

## Sécurité & vie privée

- Modèle de menace, chiffrement, verrou : [SECURITY.md](SECURITY.md).
- Politique de confidentialité (permissions, données, contact) : [PRIVACY.md](PRIVACY.md).
- Signalement de vulnérabilité : voir [SECURITY.md](SECURITY.md#signalement).

## Licence

[Apache License 2.0](LICENSE) — © 2026 Patrice Haltaya / Files Tech.
