# Relecture contradictoire — Agenda Tech, correctif du CRITIQUE (lot B)

Tu relis **le correctif d'un défaut CRITIQUE écrit par un autre assistant**. Sur ce dépôt précisément,
les correctifs ont produit **8 nouveaux défauts en 2 passes de revue** lors de la v0.5.4, et sur une
application sœur un correctif a introduit un nouveau défaut **9 fois d'affilée**. Ton rôle est de trouver
ce que CE correctif casse ou manque.

**Agenda Tech** : agenda Android natif (Kotlin, Compose, Hilt, Room + SQLCipher), 100 % local, chiffré,
zéro réseau. **`allowBackup=false` : la base est le SEUL exemplaire de l'agenda de l'utilisateur sur
l'appareil.** Fichiers entiers ci-dessous ; tu n'as pas accès au dépôt.

---

## 1. Le défaut corrigé

`DatabaseFactory.build()` attrapait `Exception` et, pour tout échec indistinctement, supprimait
`master.key` ET `agendatech.db` :

```kotlin
val raw = try {
    keyManager.getOrCreatePassphrase()
} catch (e: Exception) {
    keyManager.destroyKeyFile()
    context.deleteDatabase(AppDatabase.DATABASE_NAME)   // le seul exemplaire
    markResetPending(context)
    keyManager.getOrCreatePassphrase()
}
```

Trois faits se combinaient :

1. `DatabaseKeyManager` exposait déjà une hiérarchie typée (`KeystoreInvalidated`, `WrapCorrupted`, `Io`)
   dont le KDoc promet que l'appelant « surfaces a recovery flow instead of auto-wiping the key ».
   L'appelant faisait l'inverse et fondait les trois cas en une branche.
2. `KeystoreManager.getOrCreateKey` n'enveloppe rien : un `KeyStoreException`, un
   `UnrecoverableKeyException`, un `ProviderException` remontaient **nus**, hors hiérarchie, dans le
   `catch (e: Exception)`. Ce sont les erreurs transitoires.
3. Le pire chemin est `BootReceiver`, qui construit la base pour reprogrammer les alarmes : pas d'écran,
   pas d'utilisateur. Un hoquet du Keystore au démarrage effaçait l'agenda entier.

## 2. Le correctif à relire

- `DatabaseKeyManager.Failure` porte `dataIsUnrecoverable`, seul point de décision. `false` par défaut
  pour tout ce qui n'est pas reconnu.
- Nouvelle classe `Failure.KeystoreUnavailable` (transitoire), et un `wrappingKey()` qui mappe **tout**
  ce que le Keystore peut lever.
- `DatabaseFactory` : irrécupérable → réinitialisation ; sinon → **une** nouvelle tentative puis échec
  **sans rien effacer** ; pas un `Failure` → propagation.

---

## 3. Contexte indispensable — ne PAS signaler ceci

- **`WrapCorrupted` classée « irrécupérable » est un arbitrage assumé.** Y arriver signifie que
  `getOrCreateKey` a rendu une clé normalement et que seul `aead.decrypt` a refusé. Si l'on refusait
  aussi d'effacer dans ce cas, un `master.key` réellement corrompu briquerait l'app pour toujours, sans
  aucune issue dans l'UI. **Ne conteste pas ce choix comme préférence — mais DIS-LE si tu vois un chemin
  concret où `WrapCorrupted` est levée alors que les données sont en réalité récupérables.** C'est le
  point que je crains le plus.
- **Ne jamais proposer de changer la clé de signature ni de révoquer le keystore** : cela romprait le
  chemin de mise à jour de toutes les installations existantes. Un tel constat est faux par construction.
- **`versionCode` figé à la main** dans `version.properties`, délibérément.
- Le KDoc de `DatabaseKeyManager` dit que la clé de base **n'est pas** derrière une authentification
  utilisateur (`setUserAuthenticationRequired = false`) : c'est assumé et documenté dans SECURITY.md.
- `rekeyLegacyZeroKeyDatabase` corrige un défaut historique (base chiffrée avec 32 octets nuls) et a été
  vérifié ; ne le re-signale pas, sauf interaction avec le nouveau code.
- Le retrait de `catch (e: Exception)` fait qu'une exception imprévue **plante** l'app au lieu d'effacer.
  C'est délibéré et argumenté. Ne signale pas « ça peut planter » comme un défaut — signale-le seulement
  si tu identifies une exception **fréquente et bénigne** qui deviendrait un plantage en boucle.

---

## 4. Ce sur quoi je veux ton avis

### A. La classification est-elle juste ?

Pour chacune des quatre classes, existe-t-il un chemin concret où la valeur de `dataIsUnrecoverable` est
**fausse** ? En particulier :
- une cause où `Io` est levée alors que la clé est bel et bien perdue (donc l'app refuse d'ouvrir pour
  toujours au lieu de se réinitialiser) ;
- une cause où `WrapCorrupted` ou `KeystoreInvalidated` est levée alors que les données étaient
  récupérables (donc on efface à tort — le scénario grave).
- `UserNotAuthenticatedException` est mappée sur `KeystoreInvalidated`, donc **destructive**. La clé de
  base est créée avec `setUserAuthenticationRequired = false` : cette exception peut-elle malgré tout
  survenir de façon transitoire ? Si oui, c'est un effacement à tort.

### B. `wrappingKey()` attrape `Throwable`. Est-ce trop large ?

Un `OutOfMemoryError` ou un `ThreadDeath` deviendrait un `KeystoreUnavailable`. Est-ce un problème
concret ici, sachant que la conséquence est « ne rien effacer, refuser d'ouvrir » ?

### C. La nouvelle tentative unique est-elle utile ou trompeuse ?

Elle est **immédiate**, sans délai. Est-ce que cela peut réellement rattraper un échec transitoire du
keystore2, ou est-ce que ça ne fait que doubler le coût d'un échec certain ? Dis-le franchement si c'est
du théâtre.

### D. Les tests prouvent-ils ce qu'ils prétendent ?

C'est ma question la plus importante. Ce dépôt a déjà eu un test vacant (un fake qui renvoyait toujours
une liste vide, rendant vacant tout test qui s'y appuyait).

`TransientKeyFailureTest` remplace `master.key` par un **répertoire** du même nom pour provoquer une
`IOException` réelle sans mock.
- Ce montage emprunte-t-il bien le chemin `Failure.Io` ? `keyFile.exists()` sur un répertoire renvoie-t-il
  bien `true`, et `readBytes()` lève-t-il bien ?
- Le test garde les octets de la clé de côté puis les restaure pour prouver que les données étaient
  récupérables. Cette preuve est-elle réellement concluante ?
- `anUnrecoverableKeyStillResetsTheDatabaseAndFlagsIt` supprime l'alias Keystore et attend un
  `WrapCorrupted`. **Est-ce bien `WrapCorrupted` qui sera levée**, et non autre chose ? Si c'est autre
  chose, le test passe pour la mauvaise raison.
- `DatabaseKeyFailureTest` est-il tautologique, ou verrouille-t-il réellement quelque chose ?

### E. Autre chose de cassé ?

Fuites d'état, ordre des opérations, `raw.wipe()` mal placé, la clé qui survit en mémoire, interaction
avec `rekeyLegacyZeroKeyDatabase`.

---

## 5. Règles de recevabilité

1. **Un constat sans scénario d'échec concret et reproductible n'est pas recevable.** État de départ,
   suite d'appels, résultat faux obtenu. Si tu ne peux pas écrire ce scénario, ne rapporte pas le constat.
2. **Interdit** : style, nommage, renommages, découpage de fichiers, mise en forme, préférences
   d'architecture, « il faudrait plus de tests » en général.
3. **« Aucun défaut sur ce point » est une réponse attendue et utile.** Dis-le point par point (A à E).
4. **La précision de chaque relecteur est mesurée au fil des lots.** Un faux positif coûte plus qu'un
   silence. Mesuré sur ce dépôt au lot précédent : GPT a déclaré un `BroadcastReceiver` « OK » sans voir
   que l'injection Hilt précède le corps de `onReceive` ; Gemini a annoncé un ANR sur un chemin qui
   termine par `flowOn(Dispatchers.Default)`. Les deux ont trouvé par ailleurs de vrais défauts.
   Préfère 2 constats solides à 10 hypothèses.
5. Pour chaque constat : `fichier:ligne` · sévérité (CRITIQUE / MAJEUR / MINEUR) · scénario · **confiance**
   (CERTAIN si établi dans le code fourni, HYPOTHÈSE sinon).
6. Si tu proposes un correctif, dis **ce qu'il risque de casser**.
