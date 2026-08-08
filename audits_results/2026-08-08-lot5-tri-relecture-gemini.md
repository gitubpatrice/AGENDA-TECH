# Lot 5 — tri de la relecture Gemini (enfin obtenue)

> Mon tri, pas le rapport brut. Le rapport est dans
> `audits/ia-externe/rapport-gemini-lot-CDE-2026-08-08.md`.
>
> **Gemini 3.1 Pro a fini par répondre**, après ~30 tentatives réparties sur la journée et trois
> relances. Les lots C, D et E ont donc bien eu **deux relecteurs externes**, comme prévu au lot 2.

---

## Ce qu'il a trouvé et que j'ai retenu

| # | Constat | Verdict |
|---|---|---|
| **3** | Un événement dont le fuseau est `"Z"` exporte `TZID=Z` | ✅ **CONFIRMÉ** — corrigé |

`ZoneId.of("Z")` est valide, donc `"Z"` passe `isCanonical` et peut légitimement atteindre
l'exportateur : un `.ics` portant `TZID=Z` en produit exactement un. Ma branche comparait l'identifiant
littéral `"UTC"`, donc elle le manquait, et l'événement ressortait en `TZID=Z` — précisément
l'identifiant illisible que le repli sur UTC avait été choisi pour éviter, atteint par l'autre côté.

Corrigé en comparant les **règles** (`normalized() == ZoneOffset.UTC`) plutôt que la chaîne : toutes
les écritures d'un décalage nul — `UTC`, `Z`, `Etc/UTC`, `GMT` — retombent sur la forme `…Z` que tout
lecteur comprend. Vérifié par sabotage : revenir à la comparaison sur l'identifiant fait tomber le
test.

---

## Ce que j'ai réfuté par la mesure

### #2 — « `escapeText` remplace U+2026 (…) au lieu de U+0085 »

C'était le constat le plus grave du rapport : il impliquait que chaque « … » d'un titre était corrompu
à l'export **et** que l'injection que S8 prétendait fermer restait ouverte.

**Faux.** Mesuré octet par octet : les trois caractères sont bien U+2028, U+2029 et U+0085. Gemini a
lu U+0085, qui est **invisible**, tel que son afficheur l'a rendu.

Mais le constat a produit deux améliorations réelles, parce que l'erreur était prévisible :

1. les trois caractères sont désormais écrits en **échappements** (` `…) dans le source. Une
   ligne qu'aucun lecteur ne peut vérifier est une ligne qui invite à la mauvaise conclusion — et
   c'est un humain qui la lira la prochaine fois ;
2. un test épingle la réfutation : `an ellipsis survives the export untouched`. La seule réponse
   durable à « ce caractère est-il celui que tu crois » est un test, pas une seconde lecture du même
   glyphe invisible.

### #1 — « la migration v6 décale l'événement de 6 heures »

Scénario de Gemini : un utilisateur à New York importe un `.ics` français ; l'instant a été calculé
avec le fuseau de l'appareil, la migration répare l'étiquette sans toucher à l'instant, donc
« l'événement s'affiche à 20h00 ».

**La conclusion est fausse** : l'interface rend **toujours** dans le fuseau de l'appareil
(`ZoneId.systemDefault()`, vérifié sur les 10 points de rendu). L'instant ne bouge pas, donc
l'affichage ne bouge pas. Ce que la migration change, c'est l'ancrage des **occurrences récurrentes**
et l'export — ce qui est exactement son objet.

**Mais le noyau du constat est juste et méritait d'être écrit** : la migration répare l'étiquette, pas
l'instant. Les deux cas :

- utilisateur **dans le fuseau du fichier** (le cas courant : un Français important un export Outlook
  français) — l'instant était déjà juste, la réparation de l'étiquette rend l'ensemble correct ;
- utilisateur **ailleurs au moment de l'import** — l'instant était déjà faux avant la migration, et il
  le reste. La migration ne l'aggrave pas.

Autrement dit : jamais nuisible, souvent pleinement correctrice. Recalculer les instants supposerait de
connaître le fuseau de l'appareil **au moment de l'import**, que rien n'enregistre ; utiliser le
fuseau actuel comme approximation reviendrait à réécrire des instants sur une supposition, de façon
irréversible. C'est écrit dans le KDoc de la migration.

### #4 — « le budget partagé affame les événements suivants ; il doit être par événement »

Le budget **par événement** est précisément le défaut que F5 a corrigé : il ne dit rien du nombre
d'événements, et ce nombre est ce qu'un import contrôle. Y revenir rouvrirait F5.

L'arithmétique, que le rapport ne fait pas : une série normale coûte quelques milliers d'itérations,
le plafond **par événement** est de 100 000, et le budget partagé est de 1 000 000. Il faut donc au
moins dix séries pathologiques pour l'épuiser — c'est-à-dire un import hostile, où la troncature est
le comportement voulu. Et depuis F5-bis (lot H), l'épuisement ne **supprime** plus les alarmes
restantes : il les laisse en place.

### #5 — « `BoundedRead` ne capture pas `IOException`, donc l'app crashe »

`readAtMost` laisse effectivement remonter l'`IOException` — c'est délibéré, une lecture qui échoue
n'est pas une lecture trop grosse. Mais les **deux** appelants la capturent : `IcsViewModel.import`
via `runCatching`, `BackupViewModel.readFile` via `try/catch`. Aucun crash. Réfuté.

### #6 — « le `goAsync()` fuit si `.get()` lève »

`pendingResult.finish()` est dans un `finally` qui couvre le `catch (t: Throwable)` de
`rescheduleRemindersAsync`. Déjà vérifié au lot H par le relecteur GPT. Réfuté.

### #7 — « premier gagne casse les mises à jour Exchange »

Un bloc `VEVENT` ajouté en fin de fichier est un **événement distinct** (chaque `BEGIN:VEVENT` ouvre
une nouvelle table), donc l'ajout de bloc n'est pas affecté ; et les mises à jour passent par
l'idempotence sur l'`UID`. Un second `DTSTART` dans un même bloc est malformé. Le seul cas réel de
cette famille — un premier `SUMMARY` vide — a été corrigé au lot H. Réfuté.

### #8 — « le mutex tenu pendant le PBKDF2 provoque des saccades »

`throttleRemainingMs` **suspend** sur le mutex, elle ne bloque aucun thread : la coroutine du
décompte attend, l'interface continue de dessiner. Le décompte marque une pause d'environ 100 ms, ce
qui est invisible sur un compteur à la seconde. Réfuté.

---

## Ce qu'il a confirmé sans le savoir

Trois de ses réponses aux questions posées valident des points que j'avais établis autrement :
l'idempotence de la v6, l'innocuité du bump de version à `identityHash` constant, et le fait que
`EXDATE.distinct()` ne perd rien puisque les instants sont convertis en millisecondes UTC avant la
déduplication.

Sa remarque sur `ZoneId.SHORT_IDS` « partiellement mort » est **fausse** : `ZoneId.of("EST")` lève
(tzdb ne connaît que `EST5EDT`), donc le troisième palier est bien atteint — ce que `TimeZonesTest`
mesure déjà.

---

## Bilan

**1 constat retenu sur 8**, contre 6 sur 13 pour GPT au lot H. Mais le constat retenu était réel et
personne d'autre ne l'avait vu, et le constat le plus spectaculairement faux (#2) a produit deux
améliorations : des échappements lisibles et un test qui épingle la réfutation.

C'est l'argument pour deux relecteurs plutôt qu'un, et il ne tient pas au taux de justesse : les deux
modèles ont regardé le même code et n'ont pas vu les mêmes choses. Aucun des deux n'a vu ce que le
sabotage a vu, ni ce que les deux plongées internes ont trouvé ensuite.

Gate au terme du lot : 313 tests JVM, 0 échec · lint 0 · detekt 0 · 15 tests instrumentés.
