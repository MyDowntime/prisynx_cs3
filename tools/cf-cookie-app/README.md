# CF Cookie (APK)

App Android minuscola per rinnovare `cf_clearance` **senza PC**: apre kisskh.co
in una WebView, tu superi la verifica di Cloudflare, e l'app manda subito cookie
e User-Agent al relay (`POST /renew?token=...`).

Serve perche `cf_clearance` e `HttpOnly` (JavaScript non lo legge) ed e legato a
**IP + User-Agent esatto**: leggerlo a mano da telefono e impossibile senza
estensioni, e sbagliare lo UA invalida il cookie. Qui cookie e UA vengono presi
dallo stesso browser che ha superato il challenge.

## Uso

1. Installa `CFCookie.apk` (sideload; serve l'installazione da origini sconosciute).
2. Apri l'app, tocca *Impostazioni relay* e inserisci:
   - URL del relay, es. `http://192.168.1.50:8191`;
   - il `RELAY_TOKEN` del compose.
3. Salva: l'app apre kisskh.co. Se compare la casella "verifica", cliccala.
4. Appena il cookie compare, l'app lo invia da sola e mostra
   `OK: ...` oppure il motivo del rifiuto.

Telefono e tvbox devono uscire con lo **stesso IP pubblico** (stessa Wi-Fi). Se
il telefono e in 4G il cookie non vale: il relay rispondera `challenge`.

## Build (senza Android Studio ne Gradle)

Serve un JDK e un Android SDK con `platforms/android-35` e delle build-tools
(`aapt2`, `zipalign`, `apksigner`), anche incomplete: lo script usa `aapt2.exe`
come cartello per capire quali build-tools sono utilizzabili.

```powershell
cd tools\cf-cookie-app
powershell -ExecutionPolicy Bypass -File .\build.ps1 -Sdk "C:\percorso\android-sdk"
```

Output: `build-local/CFCookie.apk`, firmato con una keystore di debug generata
al primo build in `.tools/debug.keystore` (quindi gli aggiornamenti successivi
si installano sopra senza disinstallare).

Note:

- il `d8` delle build-tools 34 (R8 8.2.2) va in `NullPointerException` con i
  class file di JDK recenti; lo script scarica una volta R8 aggiornato
  (`9.5.33`, ~20 MB) da Maven in `.tools/` e usa quello;
- `.tools/` e `build-local/` non vanno committati (vedi `.gitignore`).
