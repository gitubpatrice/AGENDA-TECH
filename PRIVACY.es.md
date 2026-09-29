# Política de privacidad — Agenda Tech

_Traducción de la versión inglesa del 26 de agosto de 2026._ · 🇫🇷 [Français](PRIVACY.fr.md) · 🇬🇧 [English](PRIVACY.md) · 🇩🇪 [Deutsch](PRIVACY.de.md) · 🇮🇹 [Italiano](PRIVACY.it.md)

> Esta traducción la ha realizado el desarrollador con ayuda de herramientas automáticas y todavía
> no la ha revisado un hablante nativo. **En caso de discrepancia, prevalece la
> [versión francesa](PRIVACY.fr.md).**

Agenda Tech (`com.filestech.agenda_tech`) es una app de calendario **totalmente local**, construida
sobre un principio: **sus datos nunca salen de su dispositivo.**

## En resumen

- **Ningún dato recogido, ningún dato transmitido.** La app no declara **ningún permiso de
  Internet** (`INTERNET`, `ACCESS_NETWORK_STATE`): es técnicamente incapaz de enviar nada a través
  de una red.
- **Sin cuenta, sin registro, sin identificador.**
- **Sin anuncios, sin rastreadores, sin analítica** (ni Firebase Analytics, ni Crashlytics, ni
  ningún SDK de recogida de datos de terceros).
- **Sin copia de seguridad en la nube**: `allowBackup=false` — sus datos quedan excluidos de las
  copias de seguridad automáticas de Android y de las transferencias entre dispositivos.

## Qué datos, y dónde

Todo lo que introduce (eventos, títulos, lugares, notas, recordatorios, calendarios) se guarda
**únicamente en su dispositivo**, en una base de datos **cifrada** (SQLCipher, AES-256; la clave está
protegida por el Android KeyStore — hardware/TEE en los dispositivos compatibles).

El desarrollador **no tiene acceso** a estos datos y **no recibe ninguna copia** de ellos.

## Permisos solicitados, y para qué

Esta lista es **exhaustiva**: son los once permisos que lleva el APK publicado, leídos de su
manifiesto **fusionado**. Incluye los que ninguna línea de nuestro propio código solicita, sino que
trajo una biblioteca — omitirlos habría sido más favorecedor y menos cierto. Una comprobación
automática hace fallar cualquier compilación cuyo manifiesto fusionado se aparte de esta lista
(`tools/check-manifest-permissions.py`, ejecutado en cada compilación de integración continua).

### Declarados por la app

| Permiso | Finalidad | ¿Red? |
|---|---|---|
| `READ_CALENDAR` | Importar, cuando usted lo pide, los eventos **ya presentes** en el dispositivo (calendario de Google/Exchange/local sincronizado por el sistema). **Solo lectura.** | No |
| `POST_NOTIFICATIONS` | Mostrar los recordatorios de eventos. | No |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | Lanzar los recordatorios a la hora exacta. | No |
| `RECEIVE_BOOT_COMPLETED` | Volver a programar los recordatorios tras un reinicio. | No |
| `VIBRATE` | Vibración de los recordatorios. | No |

### Traídos por las bibliotecas utilizadas

| Permiso | Procede de | Lo que hace realmente **aquí** | ¿Red? |
|---|---|---|---|
| `USE_BIOMETRIC` | `androidx.biometric` | Desbloquear la app con la huella o el rostro, si activa el bloqueo de la app. | No |
| `USE_FINGERPRINT` | `androidx.biometric` | Lo mismo, en Android 9 y anteriores, donde la API biométrica moderna no existe. | No |
| `WAKE_LOCK` | `androidx.work`, traído por Glance (los widgets) | Se mantiene un instante mientras se ejecuta una tarea en segundo plano. Son dos: redibujar un widget, que Glance ejecuta como tarea de WorkManager, y — si la activa — la copia de seguridad automática semanal, que programa la propia app. WorkManager toma un wake lock parcial para ejecutar cualquier tarea. | No |
| `FOREGROUND_SERVICE` | `androidx.work`, traído por Glance (los widgets) | **Nada.** Lo declara `androidx.work`, que solo lo necesitaría para una tarea acelerada — ni el redibujado de los widgets ni la copia automática lo son. | No |
| `com.filestech.agenda_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core` | Un permiso **autoconcedido**, de nivel de firma: solo una app firmada con nuestra clave puede tenerlo. Impide que otras apps lleguen a nuestros receptores internos. | No |

Corregido el 26 de agosto de 2026. Este párrafo decía antes que ninguno de los dos permisos cubría
nada de lo que hace la app. Era cierto para el código de la app y **falso para la app que usted
instala**: Glance redibuja un widget programando una tarea de WorkManager, así que `WAKE_LOCK` se
toma de verdad, brevemente, cada vez que se actualiza un widget. Quitarlo haría fallar la
actualización de los widgets. `FOREGROUND_SERVICE` no se usa realmente, pero procede de la misma
biblioteca y se mantiene con ella en lugar de eliminarse por separado.

Ninguno de los dos concede acceso a la red, y ninguno puede usarse para leer nada. El wake lock solo
impide que el procesador se duerma durante la fracción de segundo que tarda un widget en redibujarse.

La app **nunca** solicita acceso a su ubicación, sus contactos, el micrófono, la cámara ni a
Internet.

La importación desde el calendario del dispositivo (`READ_CALENDAR`) solo lee lo que las apps del
sistema **ya han sincronizado localmente**; Agenda Tech no se conecta a su cuenta de Google ni a
ningún servicio remoto. El permiso se solicita **durante el uso**, solo cuando abre la pantalla de
importación, y puede denegarse.

## Comunicación a terceros

**Ninguna.** Ningún dato se comparte, se vende ni se transmite a nadie — la app no tiene ningún
medio técnico para hacerlo (ningún permiso de Internet).

Los únicos intercambios posibles son los que **usted** inicia expresamente, y permanecen en su
dispositivo, de una app a otra:

- **Exportación `.ics`**: a la ubicación que usted elija, mediante el selector de archivos del
  sistema.
- **Copia de seguridad cifrada `.atbak`**: es el intercambio más amplio, así que merece una
  descripción precisa. El archivo contiene **todo su calendario** (calendarios, eventos,
  descripciones, lugares, direcciones, coordenadas GPS, recordatorios), y usted elige dónde se
  escribe — incluso en una carpeta sincronizada con una nube, si así lo decide. **La app nunca lo
  envía a ninguna parte por sí misma**: escribe en la ubicación que usted indica en el selector de
  archivos del sistema, y de todos modos no puede llegar a ninguna red. El contenido está cifrado
  (AES-256) con una clave derivada **únicamente de su contraseña**: ni nosotros ni el servicio que
  pudiera alojar ese archivo podemos leerlo. En una copia que exporta a mano, esa contraseña no se
  guarda en ninguna parte — si la olvida, el archivo queda ilegible para siempre, **también para
  nosotros**. Lo que ocurra con el archivo una vez fuera de la app depende por completo de usted.
- **Copia de seguridad automática** (opcional, desactivada por defecto): una vez activada, la app
  escribe el mismo `.atbak` cifrado una vez por semana en la carpeta que usted elija, y conserva los
  cuatro más recientes. Tampoco envía nada a ninguna parte — escribe en esa carpeta y no tiene
  acceso a la red. Como funciona sin usted, es el único caso en que **su contraseña se guarda en el
  teléfono**: cifrada con una clave custodiada en el hardware seguro del dispositivo, que nunca sale
  de él. Desactivar la opción borra la contraseña y esa clave. El compromiso es deliberado — una
  copia que solo usted puede abrir, y que sigue siendo utilizable el día en que el teléfono ya no
  está. `SECURITY.md` lo describe por completo.
- **Abrir un lugar en el mapa**: si introduce coordenadas GPS en un evento y toca el marcador, la app
  pasa **esas coordenadas y el título del evento** a la app de mapas de su teléfono. No se envía
  nada más, y nada sale si no toca el marcador. Lo que esa app de mapas haga después con la
  información se rige por su propia política de privacidad.
- **Sonido del recordatorio**: usted elige un tono entre los ya registrados en el dispositivo,
  mediante el selector de tonos del sistema. La app guarda el identificador del tono elegido y **no
  toma ningún permiso de acceso persistente** a sus archivos — no declara ni `READ_MEDIA_AUDIO` ni
  `READ_EXTERNAL_STORAGE`, así que no podría tener ninguno. Si el sonido elegido deja de poder
  leerse, se usa el sonido predeterminado del sistema en lugar del silencio.

  *(Este párrafo decía antes que la app «conserva el permiso para leer» un archivo de audio elegido.
  Era erróneo en sus dos mitades — el selector es el de tonos del sistema, no un selector de
  archivos, y `takePersistableUriPermission` no aparece en ninguna parte de la app; el único código
  relacionado libera un permiso que una versión anterior pudo haber tomado. Una política de
  privacidad que se dice exhaustiva tiene que corregirse en voz alta cuando no lo es.)*

## Sus derechos (RGPD)

Como la app no trata ningún dato personal fuera de su dispositivo, no existe ningún tratamiento
remoto al que acceder, que rectificar o que suprimir. Usted conserva el control total: eliminar un
evento, un calendario o desinstalar la app borra los datos correspondientes del dispositivo.
Desinstalar elimina la base de datos cifrada.

## Menores

La app no recoge datos y es apta para todos los públicos.

## Cambios

Esta política puede evolucionar junto con la app; la fecha de arriba indica la última revisión, y el
historial es público en este repositorio.

## Editor y contacto

Agenda Tech la edita **Patrice Haltaya** (Francia), responsable del tratamiento en el sentido del RGPD — aunque, como se explica más arriba, ningún dato le llega nunca. Contacto: **contact@files-tech.com**.

Preguntas o avisos: abra una [issue](https://github.com/gitubpatrice/AGENDA-TECH/issues) en el
repositorio, o contáctenos a través de [files-tech.com](https://files-tech.com). Sobre seguridad,
consulte [SECURITY.md](SECURITY.md).
