# Informativa sulla privacy — Agenda Tech

_Traduzione della versione inglese del 26 agosto 2026._ · 🇫🇷 [Français](PRIVACY.fr.md) · 🇬🇧 [English](PRIVACY.md) · 🇩🇪 [Deutsch](PRIVACY.de.md) · 🇪🇸 [Español](PRIVACY.es.md)

> Questa traduzione è stata prodotta dallo sviluppatore con l'aiuto di strumenti automatici e non è
> ancora stata rivista da un madrelingua. **In caso di discordanza prevale la
> [versione francese](PRIVACY.fr.md).**

Agenda Tech (`com.filestech.agenda_tech`) è un'app di calendario **interamente locale**, costruita su
un principio: **i suoi dati non lasciano mai il suo dispositivo.**

## In breve

- **Nessun dato raccolto, nessun dato trasmesso.** L'app non dichiara **alcuna autorizzazione
  Internet** (`INTERNET`, `ACCESS_NETWORK_STATE`): è tecnicamente incapace di inviare qualsiasi
  cosa attraverso una rete.
- **Nessun account, nessuna registrazione, nessun identificativo.**
- **Nessuna pubblicità, nessun tracker, nessuna analisi** (niente Firebase Analytics, niente
  Crashlytics, nessun SDK di raccolta di terze parti).
- **Nessun backup nel cloud**: `allowBackup=false` — i suoi dati sono esclusi dai backup automatici
  di Android e dai trasferimenti da dispositivo a dispositivo.

## Quali dati, e dove

Tutto ciò che inserisce (eventi, titoli, luoghi, note, promemoria, calendari) è memorizzato
**unicamente sul suo dispositivo**, in un database **cifrato** (SQLCipher, AES-256; la chiave è
protetta dall'Android KeyStore — hardware/TEE sui dispositivi supportati).

Lo sviluppatore **non ha accesso** a questi dati e **non ne riceve alcuna copia**.

## Autorizzazioni richieste, e perché

Questo elenco è **completo**: sono le undici autorizzazioni che porta l'APK pubblicato, lette dal suo
manifest **unito**. Comprende anche quelle che nessuna riga del nostro codice richiede, portate da
una libreria — ometterle sarebbe stato più lusinghiero e meno vero. Un controllo automatico fa
fallire ogni build il cui manifest unito si discosta da questo elenco
(`tools/check-manifest-permissions.py`, eseguito a ogni build di integrazione continua).

### Dichiarate dall'app

| Autorizzazione | Scopo | Rete? |
|---|---|---|
| `READ_CALENDAR` | Importare, su sua richiesta, gli eventi **già presenti** sul dispositivo (calendario Google/Exchange/locale sincronizzato dal sistema). **Sola lettura.** | No |
| `POST_NOTIFICATIONS` | Mostrare i promemoria degli eventi. | No |
| `USE_EXACT_ALARM` / `SCHEDULE_EXACT_ALARM` | Far scattare i promemoria all'ora esatta. | No |
| `RECEIVE_BOOT_COMPLETED` | Riprogrammare i promemoria dopo un riavvio. | No |
| `VIBRATE` | Vibrazione dei promemoria. | No |

### Portate dalle librerie utilizzate

| Autorizzazione | Proviene da | Cosa fa realmente **qui** | Rete? |
|---|---|---|---|
| `USE_BIOMETRIC` | `androidx.biometric` | Sbloccare l'app con l'impronta o il volto, se attiva il blocco dell'app. | No |
| `USE_FINGERPRINT` | `androidx.biometric` | Lo stesso, su Android 9 e precedenti, dove l'API biometrica moderna non esiste. | No |
| `WAKE_LOCK` | `androidx.work`, portata da Glance (i widget) | Mantenuta per un istante mentre gira un'attività in background. Sono due: il ridisegno di un widget, che Glance esegue come attività WorkManager, e — se la attiva — il backup automatico settimanale, che l'app programma da sé. WorkManager prende un wake lock parziale per eseguire qualsiasi attività. | No |
| `FOREGROUND_SERVICE` | `androidx.work`, portata da Glance (i widget) | **Nulla.** Dichiarata da `androidx.work`, che ne avrebbe bisogno solo per un'attività accelerata — né il ridisegno dei widget né il backup automatico lo sono. | No |
| `com.filestech.agenda_tech.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION` | `androidx.core` | Un'autorizzazione **auto-concessa**, a livello di firma: solo un'app firmata con la nostra chiave può detenerla. Impedisce alle altre app di raggiungere i nostri ricevitori interni. | No |

Corretto il 26 agosto 2026. Questo paragrafo diceva che entrambe le autorizzazioni non coprivano
nulla di ciò che l'app fa. Era vero per il codice dell'app ed era **falso per l'app che installa**:
Glance ridisegna un widget programmando un'attività WorkManager, quindi `WAKE_LOCK` viene
effettivamente preso, brevemente, a ogni aggiornamento di un widget. Rimuoverla farebbe fallire
l'aggiornamento dei widget. `FOREGROUND_SERVICE` è davvero inutilizzata, ma proviene dalla stessa
libreria e resta con essa invece di essere rimossa da sola.

Nessuna delle due concede l'accesso alla rete, e nessuna può essere usata per leggere alcunché. Il
wake lock tiene sveglio il processore solo per la frazione di secondo che serve a ridisegnare un
widget.

L'app non richiede **mai** l'accesso alla sua posizione, ai contatti, al microfono, alla fotocamera
o a Internet.

L'importazione dal calendario del dispositivo (`READ_CALENDAR`) legge solo ciò che le app di sistema
hanno **già sincronizzato in locale**; Agenda Tech non si collega al suo account Google né ad alcun
servizio remoto. L'autorizzazione viene richiesta **durante l'uso**, solo quando apre la schermata di
importazione, e può essere negata.

## Condivisione con terzi

**Nessuna.** Nessun dato viene condiviso, venduto o trasmesso a nessuno — l'app non ha alcun mezzo
tecnico per farlo (nessuna autorizzazione Internet).

Gli unici scambi possibili sono quelli che **lei** avvia esplicitamente, e restano sul suo
dispositivo, da un'app all'altra:

- **Esportazione `.ics`**: nel percorso di sua scelta, tramite il selettore di file del sistema.
- **Backup cifrato `.atbak`**: è lo scambio più ampio, e merita quindi una descrizione precisa. Il
  file contiene **l'intero calendario** (calendari, eventi, descrizioni, luoghi, indirizzi,
  coordinate GPS, promemoria), e lei sceglie dove viene scritto — anche in una cartella
  sincronizzata con un cloud, se così desidera. **L'app non lo invia mai da nessuna parte da sé**:
  scrive nel percorso indicato nel selettore di file del sistema, e comunque non può raggiungere
  alcuna rete. Il contenuto è cifrato (AES-256) con una chiave derivata **unicamente dalla sua
  password**: né noi né il servizio che eventualmente ospita quel file possiamo leggerlo. Per un
  backup esportato a mano quella password non è memorizzata da nessuna parte — se la dimentica, il
  file resta illeggibile per sempre, **anche per noi**. Ciò che accade al file una volta uscito
  dall'app dipende interamente da lei.
- **Backup automatico** (facoltativo, disattivato per impostazione predefinita): una volta attivato,
  l'app scrive lo stesso `.atbak` cifrato una volta alla settimana in una cartella scelta da lei,
  conservando i quattro più recenti. Anche così non invia nulla da nessuna parte — scrive in quella
  cartella e non ha accesso alla rete. Poiché funziona senza di lei, è l'unico caso in cui **la sua
  password è conservata sul telefono**: cifrata da una chiave custodita nell'hardware sicuro del
  dispositivo, che non ne esce mai. Disattivare l'opzione cancella la password e quella chiave. Il
  compromesso è voluto — un backup che solo lei può aprire, e che resta utilizzabile il giorno in cui
  il telefono non c'è più. `SECURITY.md` lo descrive per intero.
- **Aprire un luogo sulla mappa**: se inserisce coordinate GPS in un evento e tocca il segnaposto,
  l'app passa **quelle coordinate e il titolo dell'evento** all'app di mappe del suo telefono. Non
  viene inviato nient'altro, e nulla esce se non tocca il segnaposto. Ciò che l'app di mappe fa poi
  con queste informazioni è regolato dalla sua informativa sulla privacy.
- **Suono del promemoria**: sceglie una suoneria tra quelle già registrate sul dispositivo, tramite
  il selettore di suonerie del sistema. L'app memorizza l'identificativo della suoneria scelta e
  **non prende alcuna autorizzazione di accesso permanente** ai suoi file — non dichiara né
  `READ_MEDIA_AUDIO` né `READ_EXTERNAL_STORAGE`, quindi non potrebbe detenerne una. Se il suono
  scelto diventa illeggibile, viene usato il suono predefinito del sistema anziché il silenzio.

  *(Questo paragrafo diceva in precedenza che l'app «conserva l'autorizzazione a leggere» un file
  audio scelto. Era sbagliato in entrambe le parti — il selettore è quello delle suonerie di sistema,
  non un selettore di file, e `takePersistableUriPermission` non compare da nessuna parte nell'app;
  l'unico codice collegato rilascia un'autorizzazione che una versione precedente potrebbe aver
  preso. Un'informativa che si dichiara completa deve correggersi apertamente quando non lo è.)*

## I suoi diritti (GDPR)

Poiché l'app non tratta dati personali al di fuori del suo dispositivo, non esiste alcun trattamento
remoto a cui accedere, da rettificare o cancellare. Lei mantiene il pieno controllo: eliminare un
evento, un calendario o disinstallare l'app rimuove i dati corrispondenti dal dispositivo. La
disinstallazione elimina il database cifrato.

## Minori

L'app non raccoglie dati ed è adatta a tutti.

## Modifiche

Questa informativa può evolvere insieme all'app; la data in alto indica l'ultima revisione, e la
cronologia è pubblica in questo repository.

## Editore e contatti

Agenda Tech è pubblicata da **Patrice Haltaya** (Francia), titolare del trattamento ai sensi del GDPR — anche se, come spiegato sopra, nessun dato gli arriva mai. Contatto: **contact@files-tech.com**.

Domande o segnalazioni: apra una [issue](https://github.com/gitubpatrice/AGENDA-TECH/issues) sul
repository, oppure ci contatti tramite [files-tech.com](https://files-tech.com). Per la sicurezza,
veda [SECURITY.md](SECURITY.md).
