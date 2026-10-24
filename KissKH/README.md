# KissKH per CloudStream

Estensione CloudStream nativa per consultare il catalogo KissKH, cercare titoli
e riprodurre episodi senza richiedere un ID TMDB o IMDb.

## Funzionalita

- Catalogo KissKH con righe per drama e film coreani, giapponesi e thailandesi,
  oltre agli anime.
- Ricerca nativa e schede aperte tramite gli ID interni KissKH.
- Risoluzione di video ed episodi tramite gli endpoint KissKH.
- Sottotitoli nativi ITA/ENG, decifratura dei vecchi sottotitoli e traduzione
  DeepL opzionale. La chiave DeepL viene inserita nelle impostazioni e resta
  salvata sul dispositivo.
- Il matching TMDB e mantenuto solo come percorso alternativo quando il
  provider risolve un titolo proveniente da un catalogo esterno.

## API e disponibilita

Il provider usa gli endpoint JSON interni consumati dal sito, ad esempio
`/api/DramaList/List`, `/api/DramaList/Search` e
`/api/DramaList/Drama/{id}`. Non e stata trovata documentazione pubblica che
confermi un'API ufficiale per sviluppatori: gli endpoint possono cambiare o
non essere accessibili ai client esterni.

KissKH e protetto da Cloudflare. Se una richiesta riceve una challenge HTTP
403, il catalogo puo risultare vuoto e la riproduzione non essere disponibile.
In alcuni test la rete diretta ha ricevuto `cf-mitigated: challenge`, mentre
la stessa richiesta tramite Proton VPN ha restituito JSON valido. **In base
alla rete e alle regole Cloudflare, potrebbe quindi essere necessaria una
VPN.** La VPN instrada normalmente anche i dati video, non solo il catalogo,
quindi considera l'eventuale limite di traffico del servizio VPN.

Il campo relay nelle impostazioni e opzionale: configurarlo richiede un relay
fidato compatibile con il template. Non e un servizio KissKH ufficiale e non
garantisce l'accesso in presenza di una challenge.

## Build

Dalla root del repository, con Android SDK installato e `ANDROID_HOME`
configurato:

```powershell
.\gradlew.bat :KissKH:make
```

Il pacchetto CloudStream viene generato in `KissKH/build/KissKH.cs3`.

## Installazione di test sulla LAN

Per servire il pacchetto da un PC sulla rete locale, esporre una directory
contenente `manifest.json`, `plugins.json` e `KissKH.cs3` via HTTP e aggiungere
in CloudStream l'URL del manifest LAN. Il telefono e il PC devono essere
raggiungibili sulla stessa rete e il firewall deve consentire la porta scelta.

Il `.cs3` presente in `build` e l'artefatto compilato per il test locale; dopo
modifiche ai sorgenti, rigenerarlo con il comando Gradle qui sopra.
