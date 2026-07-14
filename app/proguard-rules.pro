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
# Les data class du domaine peuvent être sérialisées (export ICS à venir) — on
# conserve leurs membres pour éviter toute surprise à l'introspection.
-keep class com.filestech.agenda_tech.domain.model.** { *; }
