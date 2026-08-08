## A. La classification est-elle juste ?

### KeystoreInvalidated (`dataIsUnrecoverable = true`)
Aucun défaut concret établi dans le code fourni.

- **DatabaseKeyManager.kt:71-75** · **MINEUR** · La valeur est cohérente avec “alias perdu/invalidé = plus de déchiffrement possible”. · **Confiance : CERTAIN**

**UserNotAuthenticatedException → KeystoreInvalidated (destructif)** : je ne vois **pas** de scénario *dérivable de ce code seul* où cette exception surviendrait “transitoirement” puisque la clé est générée avec `userAuthRequired=false`. Donc rien de recevable ici sans historique de versions (ancienne clé créée avec auth) ou bug OEM documenté.

- **DatabaseKeyManager.kt:138-142** · **Aucun défaut recevable** (pas de scénario reproductible à partir des fichiers fournis) · **Confiance : CERTAIN**

### KeystoreUnavailable (`dataIsUnrecoverable = false`)
Aucun défaut concret établi (et même si permanent, un “reset” ne rendrait pas le Keystore plus disponible).

- **DatabaseKeyManager.kt:106-109** · **Aucun défaut** · **Confiance : CERTAIN**

### Io (`dataIsUnrecoverable = false`)
Je ne vois pas de cas *concret* où `Failure.Io` signifierait “clé perdue à coup sûr” **dans vos invariants** (stockage interne app, pas d’accès utilisateur). En cas de FS réellement mort, reset ne garantit pas de pouvoir recréer la clé non plus.

- **DatabaseKeyManager.kt:93-95** · **Aucun défaut** · **Confiance : CERTAIN**

### WrapCorrupted (`dataIsUnrecoverable = true`) — point dangereux
Il y a **un chemin concret** où `WrapCorrupted` peut être levée alors que les données sont **potentiellement récupérables** : si l’échec de `aead.decrypt(...)` vient **d’une panne transitoire du provider/Keystore pendant l’opération crypto**, et non d’un blob réellement corrompu.

Aujourd’hui, **toute** `Outcome.Failure` de `aead.decrypt` devient `Failure.WrapCorrupted()` (destructif), sans inspection de la cause.

- **DatabaseKeyManager.kt:188-198** · **CRITIQUE** · **Scénario** :
  1) État initial : DB + `master.key` valides.
  2) `DatabaseFactory.build()` → `getOrCreatePassphrase()` → `unwrap()` ; `wrappingKey()` **réussit** (vous avez un `SecretKey` handle).
  3) Pendant `Cipher.init/doFinal` dans `AeadCipher.decrypt`, le provider AndroidKeyStore/keystore2 renvoie une erreur **transitoire** (typiquement `ProviderException` / `KeyStoreException` / erreurs “operation failed” côté keystore2) : `aead.decrypt` → `Outcome.Failure`.
  4) `unwrap()` mappe ça en `WrapCorrupted` (sans cause), `DatabaseFactory` voit `dataIsUnrecoverable=true` et exécute `resetAfterUnrecoverableKey()` → suppression de la DB alors qu’une nouvelle tentative plus tard aurait pu déchiffrer.
  
  Le cœur du problème : “`getOrCreateKey` a rendu une clé normalement” **n’implique pas** “le Keystore est sain pour exécuter l’opération GCM”. Le handle peut être obtenu et l’opération échouer ensuite.
  
  · **Confiance : HYPOTHÈSE** (dépend du comportement réel keystore2/OEM, mais le chemin de code et la perte de discrimination cause→classe sont établis)

**Correctif possible (avec risque)** : passer la cause (`AppError.Crypto.cause`) dans `WrapCorrupted(cause)` et surtout **reclassifier** les échecs de `decrypt/encrypt` :  
- AEADBadTag/IllegalArgumentException(version/blob) ⇒ `WrapCorrupted`  
- erreurs Keystore/provider ⇒ `KeystoreUnavailable`  
**Risque** : si vous classez trop large en “transitoire”, un `master.key` vraiment corrompu pourrait mener à un refus d’ouverture au lieu d’un reset (brick jusqu’à UI de reset).

---

## B. `wrappingKey()` attrape `Throwable`. Est-ce trop large ?

Je n’ai pas de scénario concret où cela casse quelque chose **dans le sens “efface à tort”** : le pire ici est d’enrober un `Error` (OOM, etc.) en `KeystoreUnavailable`, ce qui mène à “refuser d’ouvrir” puis exception, sans wipe.

- **DatabaseKeyManager.kt:142-144** · **Aucun défaut concret** (plutôt un choix de surface d’erreur) · **Confiance : CERTAIN**

---

## C. La nouvelle tentative unique est-elle utile ou trompeuse ?

Je ne peux pas prouver que c’est du théâtre **ni** que ça rattrape réellement keystore2 sans observation terrain. Techniquement, une seconde tentative immédiate peut réussir si le premier échec venait d’un état d’initialisation/IPC “premier appel” (chargement provider/KeyStore lazy, daemon qui se relève). Mais c’est non démontrable ici.

- **DatabaseFactory.kt:87-99** · **Aucun défaut recevable** (pas de scénario reproductible montrant un effet négatif certain) · **Confiance : CERTAIN**

---

## D. Les tests prouvent-ils ce qu’ils prétendent ?

### `TransientKeyFailureTest` — répertoire `master.key` → `Failure.Io`
Oui, le montage prend bien le chemin visé :
- `File.exists()` renvoie `true` pour un répertoire
- `readBytes()` sur un répertoire lève typiquement une `FileNotFoundException("Is a directory")` → catch `Throwable` → `Failure.Io`

- **TransientKeyFailureTest.kt:57-103** · **Aucun défaut** · **Confiance : CERTAIN**

### “preuve de récupérabilité” en restaurant les octets
Oui, **dans le cadre de ce test** c’est concluant : vous restaurez le blob exact de `master.key` et vous relisez une ligne de la DB. Ça démontre bien “les données n’étaient pas perdues, seulement l’accès transitoirement impossible”.

- **TransientKeyFailureTest.kt:89-103** · **Aucun défaut** · **Confiance : CERTAIN**

### `anUnrecoverableKeyStillResets...` : est-ce *bien* WrapCorrupted ?
Le test **ne l’asserte pas** ; il vérifie que “ça reset et ça repart”. Donc il ne peut pas “passer pour la mauvaise raison” au sens “il prouve WrapCorrupted” (il ne le prouve pas). En pratique, supprimer l’alias rend très probable le chemin “nouvelle clé Keystore + decrypt refuse → WrapCorrupted”, mais ce n’est pas verrouillé par assertion.

- **TransientKeyFailureTest.kt:112-142** · **MINEUR** · Le test ne prouve pas le type d’échec intermédiaire (WrapCorrupted vs autre), seulement l’issue (reset+flag). · **Confiance : CERTAIN**

### `DatabaseKeyFailureTest` — tautologique / verrouille-t-il vraiment ?
Le dernier test se présente comme un garde-fou “ajout d’un subtype ⇒ compilation cassée”, mais en réalité il liste **en dur** 4 instances ; ajouter un 5ᵉ subtype ne casse pas la compilation et ne change pas ce test.

- **DatabaseKeyFailureTest.kt:67-77** · **MAJEUR** · **Scénario** : un dev ajoute `Failure.NewKind(…)` avec `dataIsUnrecoverable=true` (ou change la politique), oublie de mettre à jour ce test ; suite verte, et la politique “combien de classes peuvent effacer” n’est pas détectée. · **Confiance : CERTAIN**  
  **Correctif possible (avec risque)** : dériver la liste via réflexion (`Failure::class.sealedSubclasses` et instanciation si possible, ou un `when` exhaustif) ; **risque** : tests plus fragiles (constructeurs avec params, sealedSubclasses parfois vide selon tooling), à cadrer.

Autre point : le test “every failure carries its cause” est vrai pour les **constructeurs**, mais en production vous jetez souvent `WrapCorrupted()` **sans cause** (voir E ci-dessous), donc le test ne verrouille pas la propriété qu’il sous-entend.

- **DatabaseKeyManager.kt:158-159 et 197-198** + **DatabaseKeyFailureTest.kt:50-56** · **MINEUR** · **Scénario** : incident réel (provider/keystore) pendant decrypt/encrypt → vous perdez la cause au moment où elle est la plus utile. · **Confiance : CERTAIN**

---

## E. Autre chose de cassé ?

### Ordre des opérations dans le reset : fenêtre de brick
`resetAfterUnrecoverableKey()` supprime **d’abord** `master.key`, puis supprime la DB. Si le process est tué / power-loss **entre les deux**, vous laissez un état “DB chiffrée existante + plus de master.key”. Au prochain lancement :
- `getOrCreatePassphrase()` voit `master.key` absent ⇒ génère une nouvelle passphrase
- l’ouverture Room/SQLCipher échoue (mauvaise clé) avec une exception **non-`Failure`**
- pas de reset automatique ⇒ boucle d’échec, et le flag reset peut ne jamais être posé.

- **DatabaseFactory.kt:111-115** · **MAJEUR** · **Scénario** :
  1) DB + master.key existent.
  2) Un cas “unrecoverable” déclenche `resetAfterUnrecoverableKey`.
  3) Crash/kill après `destroyKeyFile()` mais avant `deleteDatabase()`.
  4) Redémarrage : nouvelle clé générée, mais DB existante illisible ⇒ l’app ne peut plus repartir proprement.
  
  · **Confiance : CERTAIN**

**Correctif possible (avec risque)** : inverser l’ordre (`deleteDatabase` puis `destroyKeyFile`) pour éviter l’état “DB sans clé”.  
**Risque** : si crash après `deleteDatabase` mais avant `destroyKeyFile`, vous laissez un `master.key` (potentiellement corrompu/ancien) ; mais au lancement suivant, `unwrap()` re-déclenchera l’unrecoverable et finira le nettoyage. C’est un risque nettement moins bloquant que “DB sans clé”.

---