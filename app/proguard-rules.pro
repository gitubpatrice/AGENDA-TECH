# Agenda Tech — règles R8/ProGuard (release).
#
# Politique : garder les surfaces réfléchies par les libs (Room, SQLCipher natif) et
# laisser R8 optimiser le reste. Additif — chaque garde documente pourquoi elle existe.

# --- SQLCipher (net.zetetic) --------------------------------------------------
# La lib charge son .so via System.loadLibrary("sqlcipher") + JNI. Les classes du
# package sont référencées par nom côté natif : ne pas les renommer/supprimer.
-keep class net.zetetic.database.** { *; }
-keep class net.sqlcipher.** { *; }

# --- Room ---------------------------------------------------------------------
# Room génère des implémentations _Impl référencées par réflexion à l'ouverture.
-keep class * extends androidx.room.RoomDatabase { *; }
-dontwarn androidx.room.paging.**

# --- Kotlin coroutines --------------------------------------------------------
-dontwarn kotlinx.coroutines.**

# --- Modèles de domaine -------------------------------------------------------
# On conserve les membres des data class du domaine pour éviter toute surprise à
# l'introspection. L'export ICS existe (IcsCodec) et écrit ces modèles.
-keep class com.filestech.agenda_tech.domain.model.** { *; }

# --- Format de sauvegarde .atbak ----------------------------------------------
# domain.backup est le SEUL package réellement sérialisé (kotlinx.serialization).
# kotlinx.serialization embarque ses propres règles R8, donc aucun -keep explicite
# n'est ajouté ici : une règle redondante masquerait le jour où elles cesseraient de
# suffire. Ce qui garantit le format, c'est le test d'un export/restauration réel sur
# l'APK release minifié — un .atbak illisible ne se découvrirait sinon que le jour où
# quelqu'un en a besoin.
