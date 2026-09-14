plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

allprojects {
    apply(plugin = rootProject.libs.plugins.detekt.get().pluginId)
    apply(plugin = rootProject.libs.plugins.ktlint.get().pluginId)

    detekt {
        config.setFrom(files("$rootDir/config/detekt/detekt.yml"))
        buildUponDefaultConfig = true
        // androidTest AJOUTE explicitement (audit, point E.2) : les sources par defaut du plugin
        // sont src/{main,test}, donc MigrationsTest, DatabaseEncryptionTest et
        // TransientKeyFailureTest — les tests qui couvrent la crypto et les migrations — ne
        // passaient sous AUCUNE analyse statique.
        source.setFrom(
            files("src/main/java", "src/test/java", "src/androidTest/java"),
        )
        autoCorrect = false
        parallel = true
    }

    dependencies {
        add("detektPlugins", rootProject.libs.detekt.formatting)
    }

    configure<org.jlleitschuh.gradle.ktlint.KtlintExtension> {
        version.set("1.3.1")
        android.set(true)
        // NON BLOQUANT, et c'est un choix tenu — non plus une posture de demarrage (audit).
        //
        // Ce commentaire disait « repasser a false une fois ktlintFormat passe et commite ».
        // Ca n'a jamais ete fait, et la mesure dit pourquoi : 1 956 violations, dont l'ecrasante
        // majorite porte sur des regles que ce projet contredit DELIBEREMENT —
        // `standard:package-name` (153) refuse le underscore d'`agenda_tech`, qui est
        // l'applicationId publie et ne peut pas changer ; `standard:function-naming` (81) refuse
        // la PascalCase des @Composable, qui est la convention de Compose ;
        // `standard:multiline-expression-wrapping` (418) impose une mise en page que detekt ne
        // demande pas. Les rendre bloquantes reviendrait a reformater tout le depot pour
        // satisfaire des regles auxquelles on ne souscrit pas.
        //
        // La couverture reelle n'est pas nulle pour autant : detekt-formatting embarque les memes
        // regles ktlint, et LUI bloque (maxIssues: 0) sur celles que config/detekt/detekt.yml
        // retient. ktlint reste ici comme rapport consultable (`./gradlew ktlintCheck`), pas
        // comme garde-fou — et c'est maintenant ce que le commentaire dit.
        ignoreFailures.set(true)
        reporters {
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.PLAIN)
            reporter(org.jlleitschuh.gradle.ktlint.reporter.ReporterType.HTML)
        }
        filter {
            exclude("**/generated/**", "**/build/**")
        }
    }
}

tasks.register("clean", Delete::class) {
    delete(rootProject.layout.buildDirectory)
}
