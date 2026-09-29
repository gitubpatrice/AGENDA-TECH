# Datenschutzerklärung — Agenda Tech

_Übersetzung der englischen Fassung vom 26. August 2026._ · 🇫🇷 [Français](PRIVACY.fr.md) · 🇬🇧 [English](PRIVACY.md) · 🇮🇹 [Italiano](PRIVACY.it.md) · 🇪🇸 [Español](PRIVACY.es.md)

> Diese Übersetzung wurde vom Entwickler maschinengestützt erstellt und noch nicht von einer
> Muttersprachlerin oder einem Muttersprachler geprüft. **Bei Abweichungen gilt die
> [französische Fassung](PRIVACY.fr.md).**

Agenda Tech (`com.filestech.agenda_tech`) ist eine **vollständig lokale** Kalender-App, gebaut auf
einem Grundsatz: **Ihre Daten verlassen nie Ihr Gerät.**

## Kurz gesagt

- **Keine Datenerhebung, keine Datenübertragung.** Die App fordert **keine Internetberechtigung**
  an (`INTERNET`, `ACCESS_NETWORK_STATE`) — sie ist technisch nicht in der Lage, irgendetwas über
  ein Netzwerk zu senden.
- **Kein Konto, keine Registrierung, keine Kennung.**
- **Keine Werbung, keine Tracker, keine Analyse** (kein Firebase Analytics, kein Crashlytics, kein
  Drittanbieter-SDK zur Datenerhebung).
- **Keine Cloud-Sicherung**: `allowBackup=false` — Ihre Daten sind von den automatischen
  Sicherungen von Android und von der Übertragung zwischen Geräten ausgeschlossen.

## Welche Daten, und wo

Alles, was Sie eingeben (Termine, Titel, Orte, Notizen, Erinnerungen, Kalender), wird **nur auf
Ihrem Gerät** gespeichert, in einer **verschlüsselten** Datenbank (SQLCipher, AES-256; der
Schlüssel wird vom Android KeyStore geschützt — Hardware/TEE auf unterstützten Geräten).

Der Entwickler hat **keinen Zugriff** auf diese Daten und erhält **keine Kopie** davon.

## Angeforderte Berechtigungen, und wozu

Diese Liste ist **vollständig**: Es sind die elf Berechtigungen, die das veröffentlichte APK trägt,
gelesen aus seinem **zusammengeführten** Manifest. Dazu gehören auch die, die keine Zeile unseres
eigenen Codes anfordert, sondern die eine Bibliothek mitgebracht hat — sie wegzulassen wäre
schmeichelhafter und weniger wahr gewesen. Eine automatische Prüfung lässt jede Build fehlschlagen,
deren zusammengeführtes Manifest von dieser Liste abweicht (`tools/check-manifest-permissions.py`,
bei jeder Build der kontinuierlichen Integration ausgeführt).

### Von der App angefordert

| Berechtigung | Zweck | Netzwerk? |
|---|---|---|
| `READ_CALENDAR` | Auf Ihren Wunsch die Termine importieren, die **bereits** auf dem Gerät sind (vom System synchronisierter Google-/Exchange-/lokaler Kalender). **Nur lesend.** | Nein |
| `POST_NOTIFICATIONS` | Terminerinnerungen anzeigen. | Nein |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | Erinnerungen zur exakten Uhrzeit auslösen. | Nein |
| `RECEIVE_BOOT_COMPLETED` | Erinnerungen nach einem Neustart wieder einplanen. | Nein |
| `VIBRATE` | Vibration bei Erinnerungen. | Nein |

### Von den verwendeten Bibliotheken mitgebracht

| Berechtigung | Stammt aus | Was sie **hier** tatsächlich tut | Netzwerk? |
|---|---|---|---|
| `USE_BIOMETRIC` | `androidx.biometric` | Die App per Fingerabdruck oder Gesicht entsperren, wenn Sie die App-Sperre aktivieren. | Nein |
| `USE_FINGERPRINT` | `androidx.biometric` | Dasselbe unter Android 9 und älter, wo es die moderne Biometrie-API nicht gibt. | Nein |
| `WAKE_LOCK` | `androidx.work`, über Glance (die Widgets) | Wird kurz gehalten, während eine Hintergrundaufgabe läuft. Es gibt zwei: das Neuzeichnen eines Widgets, das Glance als WorkManager-Aufgabe ausführt, und — wenn Sie sie einschalten — die wöchentliche automatische Sicherung, die die App selbst einplant. WorkManager nimmt für jede Aufgabe einen partiellen Wake Lock. | Nein |
| `FOREGROUND_SERVICE` | `androidx.work`, über Glance (die Widgets) | **Nichts.** Von `androidx.work` deklariert, das sie nur für eine beschleunigte Aufgabe bräuchte — weder das Neuzeichnen der Widgets noch die automatische Sicherung ist eine. | Nein |
| `com.filestech.agenda_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core` | Eine **selbst erteilte** Berechtigung auf Signaturebene: Nur eine mit unserem Schlüssel signierte App kann sie besitzen. Sie hindert andere Apps daran, unsere internen Empfänger zu erreichen. | Nein |

Korrigiert am 26. August 2026. Dieser Absatz sagte früher, dass beide Berechtigungen nichts abdecken,
was die App tut. Das stimmte für den eigenen Code der App und war **falsch für die App, die Sie
installieren**: Glance zeichnet ein Widget neu, indem es eine WorkManager-Aufgabe einplant, also wird
`WAKE_LOCK` bei jeder Aktualisierung eines Widgets tatsächlich kurz genommen. Sie zu entfernen, würde
die Widget-Aktualisierung scheitern lassen. `FOREGROUND_SERVICE` ist wirklich ungenutzt, stammt aber
aus derselben Bibliothek und bleibt mit ihr, statt allein entfernt zu werden.

Keine der beiden gewährt Netzwerkzugriff, und keine kann dazu benutzt werden, etwas zu lesen. Der
Wake Lock hält den Prozessor nur für den Bruchteil einer Sekunde wach, den das Neuzeichnen eines
Widgets dauert.

Die App fordert **nie** Zugriff auf Ihren Standort, Ihre Kontakte, das Mikrofon, die Kamera oder
das Internet an.

Der Import aus dem Gerätekalender (`READ_CALENDAR`) liest nur, was die System-Apps **bereits lokal
synchronisiert** haben; Agenda Tech verbindet sich weder mit Ihrem Google-Konto noch mit einem
entfernten Dienst. Die Berechtigung wird **zur Laufzeit** angefordert, erst wenn Sie den
Importbildschirm öffnen, und kann verweigert werden.

## Weitergabe an Dritte

**Keine.** Es werden keine Daten an irgendjemanden weitergegeben, verkauft oder übertragen — die
App hat dazu keine technischen Mittel (keine Internetberechtigung).

Möglich sind nur die Übergaben, die **Sie** ausdrücklich auslösen, und sie bleiben auf Ihrem Gerät,
von einer App zur anderen:

- **`.ics`-Export**: an den Ort Ihrer Wahl, über die Dateiauswahl des Systems.
- **Verschlüsselte `.atbak`-Sicherung**: Dies ist die umfassendste Übergabe, daher verdient sie eine
  genaue Beschreibung. Die Datei enthält **Ihren gesamten Kalender** (Kalender, Termine,
  Beschreibungen, Orte, Adressen, GPS-Koordinaten, Erinnerungen), und Sie wählen, wohin sie
  geschrieben wird — auch in einen mit einer Cloud synchronisierten Ordner, wenn Sie das wollen.
  **Die App sendet sie nie selbst irgendwohin**: Sie schreibt an den Ort, den Sie in der
  Dateiauswahl des Systems angeben, und kann ohnehin kein Netzwerk erreichen. Der Inhalt ist
  verschlüsselt (AES-256), mit einem Schlüssel, der **allein aus Ihrem Passwort** abgeleitet wird:
  Weder wir noch ein Dienst, der diese Datei vielleicht speichert, können sie lesen. Bei einer
  Sicherung, die Sie von Hand exportieren, wird dieses Passwort nirgends gespeichert — wenn Sie es
  vergessen, ist die Datei für immer unlesbar, **auch für uns**. Was mit der Datei geschieht,
  nachdem sie die App verlassen hat, liegt ganz bei Ihnen.
- **Automatische Sicherung** (optional, standardmäßig aus): Sobald Sie sie einschalten, schreibt die
  App einmal pro Woche dieselbe verschlüsselte `.atbak`-Datei in einen von Ihnen gewählten Ordner
  und behält die vier neuesten. Auch sie sendet nichts irgendwohin — sie schreibt in diesen Ordner
  und hat keinen Netzwerkzugriff. Weil sie ohne Sie läuft, ist sie die einzige Stelle, an der **Ihr
  Passwort auf dem Telefon aufbewahrt wird**: verschlüsselt mit einem Schlüssel in der sicheren
  Hardware des Geräts, der sie nie verlässt. Das Ausschalten der Option löscht das Passwort und
  diesen Schlüssel. Der Kompromiss ist gewollt — eine Sicherung, die nur Sie öffnen können, und die
  an dem Tag nutzbar bleibt, an dem das Telefon weg ist. `SECURITY.md` beschreibt es vollständig.
- **Einen Ort auf der Karte öffnen**: Wenn Sie bei einem Termin GPS-Koordinaten eingeben und auf die
  Markierung tippen, übergibt die App **diese Koordinaten und die Bezeichnung des Termins** an die
  Karten-App Ihres Telefons. Sonst wird nichts übergeben, und nichts verlässt die App, wenn Sie
  nicht auf die Markierung tippen. Was diese Karten-App dann mit den Informationen macht, regelt
  ihre eigene Datenschutzerklärung.
- **Erinnerungston**: Sie wählen über die Klingeltonauswahl des Systems einen der Klingeltöne, die
  bereits auf dem Gerät registriert sind. Die App speichert die Kennung des gewählten Klingeltons
  und nimmt **keine dauerhafte Zugriffsberechtigung** auf Ihre Dateien — sie fordert weder
  `READ_MEDIA_AUDIO` noch `READ_EXTERNAL_STORAGE` an und könnte daher keine halten. Wenn der gewählte
  Ton einmal unlesbar wird, wird der Standardton des Systems verwendet statt Stille.

  *(Dieser Absatz sagte früher, die App „behalte die Berechtigung, eine gewählte Audiodatei zu
  lesen“. Das war in beiden Hälften falsch — die Auswahl ist die Klingeltonauswahl des Systems,
  keine Dateiauswahl, und `takePersistableUriPermission` kommt in der App nirgends vor; der einzige
  zugehörige Code gibt eine Berechtigung frei, die eine ältere Version genommen haben könnte. Eine
  Datenschutzerklärung, die Vollständigkeit beansprucht, muss laut korrigiert werden, wenn sie es
  nicht ist.)*

## Ihre Rechte (DSGVO)

Da die App keine personenbezogenen Daten außerhalb Ihres Geräts verarbeitet, gibt es keine
entfernte Verarbeitung, auf die man zugreifen, die man berichtigen oder löschen könnte. Sie behalten
die volle Kontrolle: Das Löschen eines Termins, eines Kalenders oder die Deinstallation der App
entfernt die entsprechenden Daten vom Gerät. Die Deinstallation löscht die verschlüsselte Datenbank.

## Kinder

Die App erhebt keine Daten und ist für alle Altersgruppen geeignet.

## Änderungen

Diese Erklärung kann sich mit der App weiterentwickeln; das Datum oben gibt die letzte Überarbeitung
an, und die Historie ist in diesem Repository öffentlich.

## Herausgeber und Kontakt

Agenda Tech wird von **Patrice Haltaya** (Frankreich) herausgegeben, dem Verantwortlichen im Sinne der DSGVO — auch wenn, wie oben erklärt, nie Daten bei ihm ankommen. Kontakt: **contact@files-tech.com**.

Fragen oder Meldungen: Eröffnen Sie ein [Issue](https://github.com/gitubpatrice/AGENDA-TECH/issues)
im Repository, oder erreichen Sie uns über [files-tech.com](https://files-tech.com). Zur Sicherheit
siehe [SECURITY.md](SECURITY.md).
