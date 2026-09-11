package com.filestech.agenda_tech.core.time

/**
 * La longueur d'un jour **civil nominal** en millisecondes.
 *
 * ## Pourquoi une seule definition (audit de coherence C6)
 *
 * `24L * 60 * 60 * 1000` etait reecrit quatre fois en source principale — `DeviceEventMapper`,
 * `IcsCodec`, `RfcDuration`, `MonthViewModel` — sous deux noms differents (`DAY_MILLIS` et
 * `MILLIS_PER_DAY`). Aucune des quatre n'etait fausse, et c'est justement ce qui rend la duplication
 * traitre : elle ne se manifeste pas par un defaut, elle se manifeste le jour ou l'une des quatre
 * change. C'est le meme geste que `TimeZones` a fait pour la resolution des fuseaux (audit F3, cinq
 * jumeaux), et il a sa place au meme endroit.
 *
 * ## ⚠️ Ce que cette constante n'est PAS
 *
 * Elle ne mesure pas « un jour » au sens du calendrier. Une nuit de changement d'heure dure 23 ou 25
 * heures, et `start + DAY_MILLIS` tombe alors a cote d'un minuit d'une heure entiere. Ce defaut a ete
 * commis et rattrape pendant l'audit du 2026-09-11, sur la fin des evenements « toute la journee ».
 *
 * Pour franchir une frontiere de jour, passer par le calendrier :
 * `date.plusDays(n).atStartOfDay(zone)`. Cette constante-ci sert a ce qui est nominal par definition
 * — une duree RFC 5545 `P1D`, un plafond, un pas de fenetre — jamais a situer un minuit.
 */
internal const val DAY_MILLIS: Long = 24L * 60 * 60 * 1000
