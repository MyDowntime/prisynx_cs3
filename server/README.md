# PriSynx Sync Server (Zero-Knowledge Vault)

Server di sincronizzazione e backup crittografato end-to-end (E2EE) per l'estensione **PriSynx** su CloudStream.

## 🔒 Sicurezza e Privacy (Zero-Knowledge)
- **Nessun dato in chiaro**: Il server riceve un payload già cifrato con **AES-256-GCM** direttamente dal dispositivo Android.
- **Nessuna chiave sul server**: La chiave di cifratura viene generata sul dispositivo dal PIN a 6 cifre tramite `PBKDF2WithHmacSHA256` (100.000 iterazioni) e non lascia mai il client.
- **Protezione Anti-Brute Force**: Il server blocca l'account e l'IP per 60 minuti dopo 5 tentativi consecutivi di recupero con PIN errato.
- **Nessun tracciamento**: Nemmeno chi gestisce la VPS o possiede i file di log/database può decifrare la cronologia, i preferiti o le chiavi debrid salvate.

## 🚀 Avvio Rapido (Docker)
```bash
docker compose up -d --build
```
Il server ascolta sulla porta `8795` e memorizza il database in `data/sync.db`.
