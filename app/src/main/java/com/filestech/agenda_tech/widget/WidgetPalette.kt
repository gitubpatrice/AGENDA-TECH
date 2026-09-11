package com.filestech.agenda_tech.widget

import androidx.compose.ui.graphics.Color
import androidx.glance.unit.ColorProvider

/**
 * La carte de marque des widgets, definie **une fois**.
 *
 * ## Pourquoi ce fichier existe
 *
 * Les trois couleurs etaient ecrites en dur dans [AgendaWidget] **et** dans [AgendaIconWidget], a
 * l'identique, sous deux jeux de noms differents (`WidgetBackground`/`IconBackground`, etc.). Elles
 * n'etaient fausses ni d'un cote ni de l'autre — et c'est ce qui rend ce genre de doublon traitre :
 * il ne se voit pas le jour ou on l'ecrit, il se voit le jour ou l'un des deux widgets change de
 * teinte et pas l'autre. Poser le fond arrondi en aurait fait une **troisieme** copie, cette fois en
 * XML, c'est-a-dire hors de portee du compilateur.
 *
 * C'est le motif dominant de l'audit du 2026-09-11 — le chemin jumeau — applique a des couleurs.
 *
 * ## Pourquoi des litteraux Kotlin et non des ressources
 *
 * Tente d'abord, refuse par lint, et la raison vaut d'etre consignee : la surcharge
 * `ColorProvider(@ColorRes resId)` de Glance porte `@RestrictTo(LIBRARY_GROUP)` — elle compile
 * parfaitement mais n'est **pas de l'API publique**, et `lintDebug` l'arrete. Seul
 * `ColorProvider(Color)` est utilisable depuis une application.
 *
 * La couleur du FOND, elle, reste dans `colors.xml` : c'est le drawable qui la lit, et rien en
 * Kotlin n'en a besoin. Chaque valeur n'est donc ecrite qu'une fois, des deux cotes.
 *
 * ## Pourquoi ces couleurs ne suivent pas le theme
 *
 * Delibere, et documente dans [AgendaWidget] : un widget vit sur un fond d'ecran quelconque, pas dans
 * l'application. Une carte de teinte fixe reste lisible partout ; une carte qui suivrait le theme
 * clair deviendrait blanche sur blanc chez la moitie des utilisateurs.
 *
 * ## Pourquoi le FOND n'est pas ici
 *
 * Les deux widgets posent leur fond par le drawable `widget_background.xml`, qui seul sait porter les
 * angles arrondis sous Android 12. L'exposer AUSSI ici serait du code que personne n'appelle, et il
 * donnerait a croire qu'il existe deux facons de poser ce fond.
 */
internal object WidgetPalette {
    /**
     * La seule couleur de texte des widgets, et c'est une mesure qui l'a reduite a une.
     *
     * Il y avait un accent bleu clair `#A9C7FF` pour les libelles secondaires. Sur l'ancien fond
     * indigo il rendait 8,05:1 ; sur le bleu du damier il tombe a **3,52:1** et sur le rouge a
     * **3,07:1** — au-dessus du seuil de 3:1 des gros caracteres, mais **sous** les 4,5:1 qu'exige
     * l'heure d'un rendez-vous en 12 sp. Le blanc tient les deux fonds : 6,01:1 et 5,24:1.
     *
     * La hierarchie visuelle passe donc par la taille et la graisse. C'est plus robuste : elle survit
     * a un changement de fond, ce que l'accent n'a pas fait.
     */
    val OnCard = ColorProvider(Color(0xFFFFFFFF))
}
