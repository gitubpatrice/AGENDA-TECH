# Lot 4 — tri de la relecture externe des lots C, D et E

> Mon tri, pas le rapport brut. Le rapport est dans
> `audits/ia-externe/rapport-gpt-lot-CDE-2026-08-08.md`.
>
> Relecteur : **GPT-5.2**. **Gemini 3.1 Pro : indisponible** (503 persistant, aucun modèle Pro de
> repli — voir lot 2 §Gemini). Ces trois lots n'ont donc **qu'un relecteur externe sur deux**, et
> c'est un manque, pas un satisfecit.

---

## Ce que la relecture a trouvé et que j'avais manqué

| # | Constat | Sévérité | Statut après vérification |
|---|---|---|---|
| **13** | Budget épuisé ⇒ `computeNextFire` rend `null` ⇒ le planificateur **annule** les alarmes | **MAJEUR** | ✅ CONFIRMÉ — **défaut introduit par mon lot D** |
| **1** | Un fuseau à offset fixe (`+02:00`) casse mon propre analyseur | **MAJEUR** | ✅ CONFIRMÉ |
| **9** | Premier `SUMMARY` vide ⇒ événement jeté | MINEUR | ✅ CONFIRMÉ |
| **4** | `read()` rendant 0 ⇒ boucle sans fin | MINEUR | ✅ CONFIRMÉ (théorique, corrigé quand même) |
| **5** | Le KDoc de `BoundedRead` promet un plafond mémoire qu'il ne tient pas | MINEUR | ✅ CONFIRMÉ — commentaire menteur |
| **8** | Le KDoc de la migration v6 décrit un filtre que le code n'applique pas | MINEUR | ✅ CONFIRMÉ — commentaire menteur |

### #13 — le plus grave, et il est de moi

`schedule()` traitait `fire == null` comme « la série est terminée » et **annulait** l'alarme. Avant le
lot D, `null` n'avait qu'un sens et cette lecture était juste. Le budget lui en a donné un second —
« on a cessé de chercher » — sans apprendre à l'appelant à les distinguer. Comme `rescheduleAll()`
partage **un** budget sur toute la passe, l'épuiser sur une série pathologique faisait paraître
terminés **tous les rappels suivants**, chacun étant alors annulé.

C'est pire que le mal que le budget devait soigner : ne pas réarmer un rappel après un redémarrage le
laisse au prochain lancement de l'application ; l'**annuler** signifie que plus rien ne le réarme
jusqu'à ce que l'utilisateur rouvre l'événement. Mon propre message de commit du lot D disait
« tronquer une queue pathologique est la moindre perte » — tronquer, c'est laisser ces alarmes
tranquilles, pas les détruire. Motif « le repli échoue du mauvais côté », écrit par moi, dans le lot
qui corrigeait un défaut.

### #1 — mon exportateur écrivait ce que mon importateur ne sait pas lire

`TimeZones` accepte les offsets fixes **volontairement**, et mon propre test le documentait. Exporté
sans guillemets, `+02:00` produit `DTSTART;TZID=+02:00:20250601T100000`, et `parsePropertyLine`
coupait au **premier** `:` : paramètre `TZID=+02`, valeur `00:20250601T100000`, qui ne s'analyse pas.
L'événement était jeté — par notre lecteur, depuis notre export.

Corrigé des deux côtés (guillemets à l'écriture, analyseur conscient des guillemets à la lecture), et
le découpage des paramètres sur `;` l'est aussi : n'en traiter qu'un des deux aurait recréé
l'asymétrie que cet audit passe son temps à trouver.

---

## Ce que j'ai refusé, avec la raison

| # | Constat | Verdict | Pourquoi |
|---|---|---|---|
| **2** | `lowercase()` casse en locale turque (I sans point) | ❌ **RÉFUTÉ** | `String.lowercase()` de Kotlin est **indépendant de la locale** (`Locale.ROOT`) — c'est précisément ce qui le distingue de `toLowerCase()`. Le piège est réel, il est déjà évité. **Épinglé par un test** sous `tr-TR` pour qu'un futur « correctif » vers `lowercase(Locale.getDefault())` échoue bruyamment. |
| **11** | `@ApplicationScope` pourrait être sur `Dispatchers.Main` | ❌ **RÉFUTÉ** | `CoroutineModule.kt:26-28` : construit sur `@DefaultDispatcher` = `Dispatchers.Default`. Le relecteur n'avait pas le fichier et l'a dit — constat honnête, simplement faux. |
| **3** | Alias « backward » (`Asia/Calcutta`, `Europe/Kiev`…) absents de certaines tzdb | ⚠️ **MESURÉ, non retenu** | Les ids modernes courent le risque **inverse et pire** : `Europe/Kyiv` n'existe qu'à partir de tzdb 2022b, donc **absent** des Android anciens que l'app vise, là où `Europe/Kiev` est présent partout. CLDR les nomme à l'ancienne pour cette raison. Tranché par la mesure : `TimeZonesDeviceTest` vérifie les 140 entrées sur la tzdb **réelle** du Galaxy S9 (API 29, le plus vieil appareil disponible) — **toutes résolvent**. |
| **6** | Coût de la migration v6 (N balayages de table) | ⚠️ **QUANTIFIÉ, non retenu** | Un balayage pour le `DISTINCT`, puis un `UPDATE` par **valeur distincte inutilisable** — pas par ligne. Un agenda personnel porte une poignée de fuseaux quel que soit son nombre d'événements, et le pire cas réaliste (un calendrier entier importé d'un export Outlook) est **une** valeur, donc deux passes. Raisonnement écrit dans le KDoc. |
| **7** | Le repli sur `systemDefault()` peut réparer vers la mauvaise zone si l'utilisateur a voyagé | ⚠️ **ASSUMÉ** | Vrai, et déjà écrit dans le KDoc. L'alternative — laisser brut — signifie que **tout** lecteur replie sur UTC pour toujours, ce qui est sûrement faux ; la zone de l'appareil est celle avec laquelle l'instant voisin a été calculé, donc probablement juste. On remplace une erreur certaine par une approximation. |
| **10** | `EXDATE.distinct()` perd les doublons | ❌ **REJETÉ** | L'ensemble des instants exclus est ce qui a un sens ; exclure deux fois le même instant, c'est la même exclusion. Ce codec n'a jamais promis de restituer un fichier étranger octet pour octet. |

---

## Ce que la relecture n'a pas vu, et que le sabotage a vu

Deux de mes **tests** étaient faux. Aucun relecteur ne l'a signalé ; c'est le sabotage qui l'a dit.

1. Le test des emoji (F14) affirmait sur la `String` de l'encodeur. Le substitut orphelin y est encore
   présent — rien n'est cassé tant qu'on n'écrit pas les octets. Il fixait de surcroît **un seul
   offset**, alors que la coupure exige que la frontière tombe exactement entre deux unités d'un
   emoji. Corrigé : l'assertion traverse `toByteArray(UTF_8)` et l'offset est balayé.
2. Le test du `;` entre guillemets restait **vert avec le correctif retiré** : le découpage naïf
   produisait des paramètres parasites qui n'influençaient rien. Refait autour du vrai risque — un
   `VALUE=DATE` **passager** glissé dans un paramètre `X-` sans rapport, qui fait analyser un
   événement horaire comme une date et le fait jeter.

C'est la leçon la plus utile de ce lot : un rapport externe lit le code, le sabotage teste les tests.
Les deux sont nécessaires, et ni l'un ni l'autre ne remplace l'autre.

---

## État après correction

Sabotages, **six**, tous confirmés :

| Sabotage | Échecs |
|---|---|
| `TZID` exporté sans guillemets | 1 |
| `indexOf(':')` aveugle aux guillemets | 1 |
| `split(';')` aveugle aux guillemets | 1 |
| Premier `SUMMARY` et non premier utilisable | 1 |
| Garde de budget retirée du planificateur | 1 |
| `MAX_EMPTY_READS = 0` (sémantique « consécutif ») | 1 |

Gate : `assembleDebug` OK · **293 tests JVM** (+8), 0 échec · lint 0 · detekt 0.
Appareil : **10 tests instrumentés** verts sur Galaxy S9 (API 29), ciblés par série.

**Reste dû** : la relecture Gemini de ces trois lots, dès que le modèle redevient joignable.
