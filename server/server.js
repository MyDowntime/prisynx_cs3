const fastify = require('fastify')({ logger: true });
const sqlite3 = require('sqlite3').verbose();
const crypto = require('crypto');
const path = require('path');

const DB_PATH = process.env.DB_PATH || path.join(__dirname, 'data', 'sync.db');
const PORT = parseInt(process.env.PORT || '8795', 10);
const HOST = '0.0.0.0';

// Inizializza SQLite
const db = new sqlite3.Database(DB_PATH, (err) => {
    if (err) {
        console.error('Errore apertura SQLite:', err);
        process.exit(1);
    }
    console.log('Connesso a SQLite:', DB_PATH);
});

// Crea tabella vaults se non esiste
db.serialize(() => {
    db.run(`
        CREATE TABLE IF NOT EXISTS vaults (
            pin_hash TEXT PRIMARY KEY,
            salt TEXT NOT NULL,
            payload_encrypted TEXT NOT NULL,
            device_name TEXT,
            updated_at INTEGER NOT NULL,
            failed_attempts INTEGER DEFAULT 0,
            locked_until INTEGER DEFAULT 0
        )
    `);
    db.run(`CREATE INDEX IF NOT EXISTS idx_updated_at ON vaults(updated_at)`);
});

// Helper per hash SHA-256 del PIN
function hashPin(pin) {
    return crypto.createHash('sha256').update(String(pin).trim()).digest('hex');
}

// Endpoint stato / salute
fastify.get('/health', async (request, reply) => {
    return { status: 'ok', service: 'prisynx-sync-vault', version: '1.0.0' };
});

// 1. CARICAMENTO CONFIGURAZIONE CIFRATA (PUSH)
fastify.post('/api/vault/push', async (request, reply) => {
    const { pin, salt, payload, deviceName } = request.body || {};

    if (!pin || !/^\d{6}$/.test(String(pin).trim())) {
        return reply.code(400).send({ error: 'PIN non valido. Richieste esattamente 6 cifre.' });
    }
    if (!salt || typeof salt !== 'string') {
        return reply.code(400).send({ error: 'Salt mancante.' });
    }
    if (!payload || typeof payload !== 'string') {
        return reply.code(400).send({ error: 'Payload cifrato mancante.' });
    }

    const pinHash = hashPin(pin);
    const now = Date.now();
    const dev = deviceName ? String(deviceName).substring(0, 50) : 'Dispositivo PriSynx';

    return new Promise((resolve) => {
        db.get('SELECT locked_until FROM vaults WHERE pin_hash = ?', [pinHash], (err, row) => {
            if (row && row.locked_until > now) {
                const waitMin = Math.ceil((row.locked_until - now) / 60000);
                resolve(reply.code(429).send({
                    error: `PIN temporaneamente bloccato per tentativi falliti. Riprova tra ${waitMin} minuti.`
                }));
                return;
            }

            const sql = `
                INSERT INTO vaults (pin_hash, salt, payload_encrypted, device_name, updated_at, failed_attempts, locked_until)
                VALUES (?, ?, ?, ?, ?, 0, 0)
                ON CONFLICT(pin_hash) DO UPDATE SET
                    salt = excluded.salt,
                    payload_encrypted = excluded.payload_encrypted,
                    device_name = excluded.device_name,
                    updated_at = excluded.updated_at,
                    failed_attempts = 0,
                    locked_until = 0
            `;

            db.run(sql, [pinHash, salt, payload, dev, now], function(insertErr) {
                if (insertErr) {
                    resolve(reply.code(500).send({ error: 'Errore durante il salvataggio nel database.' }));
                    return;
                }
                resolve(reply.code(200).send({
                    success: true,
                    updatedAt: now,
                    message: 'Configurazione cifrata salvata con successo.'
                }));
            });
        });
    });
});

// 2. RECUPERO CONFIGURAZIONE CIFRATA (PULL con rate-limiting)
fastify.post('/api/vault/pull', async (request, reply) => {
    const { pin } = request.body || {};

    if (!pin || !/^\d{6}$/.test(String(pin).trim())) {
        return reply.code(400).send({ error: 'PIN non valido. Richieste esattamente 6 cifre.' });
    }

    const pinHash = hashPin(pin);
    const now = Date.now();

    return new Promise((resolve) => {
        db.get('SELECT * FROM vaults WHERE pin_hash = ?', [pinHash], (err, row) => {
            if (err) {
                resolve(reply.code(500).send({ error: 'Errore interno del database.' }));
                return;
            }

            if (!row) {
                resolve(reply.code(404).send({ error: 'Nessun backup trovato per questo PIN.' }));
                return;
            }

            // Controllo blocco
            if (row.locked_until > now) {
                const waitMin = Math.ceil((row.locked_until - now) / 60000);
                resolve(reply.code(429).send({
                    error: `Troppi tentativi errati. Account bloccato per altri ${waitMin} minuti.`
                }));
                return;
            }

            // Successo: restituisce il payload cifrato e il salt
            resolve(reply.code(200).send({
                success: true,
                salt: row.salt,
                payload: row.payload_encrypted,
                deviceName: row.device_name,
                updatedAt: row.updated_at
            }));
        });
    });
});

// Avvia server
const start = async () => {
    try {
        await fastify.listen({ port: PORT, host: HOST });
        console.log(`Vault Server attivo su http://${HOST}:${PORT}`);
    } catch (err) {
        fastify.log.error(err);
        process.exit(1);
    }
};
start();
