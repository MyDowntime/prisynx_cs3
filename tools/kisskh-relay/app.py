"""kisskh-relay — challenge-aware fetch relay for the KissKH CloudStream plugin.

KissKH sits behind Cloudflare. On some networks every non-browser client gets a
managed challenge, whatever its TLS fingerprint, and only a real browser passes
by running the JS and receiving the `cf_clearance` cookie. This relay therefore
works in two steps:

1. fast path: request with a browser TLS fingerprint (curl_cffi) and the
   clearance cookie obtained earlier — milliseconds, used for most calls;
2. solve path: if the fast path is challenged, ask FlareSolverr (headless
   Chrome) for the page, keep the returned cookies and User-Agent, and serve
   that body. The clearance is then reused by the fast path until it expires.

The plugin only needs to rewrite its API calls:

    Relay template: http://<host>:8191/fetch?token=<token>&url={url}

Generated subtitles (decrypted legacy files, DeepL translations) are published
with POST /store and served back from GET /sub/<id>.srt, so the player always
receives a plain HTTP URL.
"""

from __future__ import annotations

import asyncio
import html
import json
import os
import secrets
import time
from contextlib import asynccontextmanager
from pathlib import Path
from urllib.parse import urlparse

import httpx
from curl_cffi.requests import AsyncSession
from fastapi import FastAPI, Query, Request
from fastapi.responses import HTMLResponse, JSONResponse, Response

TOKEN = os.getenv("RELAY_TOKEN", "").strip()


def _normalize_cookies(raw: str) -> str:
    """Accept a bare cf_clearance value, a real cookie string, or a copied header."""
    value = " ".join(raw.split())
    if not value:
        return ""
    if "=" not in value:
        # Valore incollato senza nome: assumiamo cf_clearance.
        return f"cf_clearance={value}"
    for prefix in ("cookie:", "set-cookie:"):
        if value.lower().startswith(prefix):
            value = value[len(prefix):].strip()
    # Copie da DevTools a volte portano virgolette o il ';' finale.
    return value.strip().strip(";").strip().strip('"').strip("'")

# Comma separated list of allowed hosts. "kisskh.co" also allows "*.kisskh.co".
# L'ordine e quello scritto nella variabile: il primo host e il sito, non il CDN
# (l'ordinamento alfabetico metteva cdnvideo11.shop davanti a kisskh.co).
ALLOWED_HOSTS = list(
    dict.fromkeys(
        h.strip().lower() for h in os.getenv("RELAY_HOSTS", "kisskh.co").split(",") if h.strip()
    )
)
# Host usato per riscaldare/verificare il clearance: il sito, mai un CDN.
PRIMARY_HOST = next((h for h in ALLOWED_HOSTS if h == "kisskh.co"), ALLOWED_HOSTS[0] if ALLOWED_HOSTS else "")

IMPERSONATE = os.getenv("RELAY_IMPERSONATE", "chrome").strip() or "chrome"
TIMEOUT = float(os.getenv("RELAY_TIMEOUT", "25"))
CACHE_TTL = float(os.getenv("RELAY_CACHE_TTL", "30"))
MAX_BYTES = int(os.getenv("RELAY_MAX_BYTES", str(8 * 1024 * 1024)))
MAX_CACHE_ENTRIES = int(os.getenv("RELAY_CACHE_ENTRIES", "256"))
# Log di ogni richiesta (host, path, esito): utile per capire cosa chiede la TV.
VERBOSE = os.getenv("RELAY_VERBOSE", "0").strip() not in ("0", "false", "no")

# FlareSolverr endpoint, e.g. http://flaresolverr:8191/v1 (empty = disabled).
SOLVER_URL = os.getenv("RELAY_SOLVER_URL", "").strip()
# Headless Chrome on ARM boxes needs a lot of time: 120 s is a sane default.
SOLVER_TIMEOUT = float(os.getenv("RELAY_SOLVER_TIMEOUT", "120"))
# Extra seconds FlareSolverr should wait after the page is "ready" (0 = default).
SOLVER_WAIT = float(os.getenv("RELAY_SOLVER_WAIT", "0"))
CLEARANCE_TTL = float(os.getenv("RELAY_CLEARANCE_TTL", "1500"))  # ~25 min
FAST_RETRY_AFTER = float(os.getenv("RELAY_FAST_RETRY", "600"))  # 10 min
# File opzionale dove tenere il clearance rinnovato con /renew: senza file ogni
# riavvio del container torna ai cookie del compose. Richiede un volume.
STATE_FILE = os.getenv("RELAY_STATE_FILE", "").strip()

# URL usata per riscaldare il clearance in background. Meglio la home del sito:
# il challenge sull'endpoint API, navigato da solo, tende a non risolversi mai,
# mentre la home si comporta come un browser vero e lascia i cookie per le API.
WARM_URL = os.getenv("RELAY_WARM_URL", "").strip() or (
    f"https://{PRIMARY_HOST}/" if PRIMARY_HOST else ""
)
WARM_ENABLED = os.getenv("RELAY_WARM", "1").strip() not in ("0", "false", "no")

# URL usata da /renew per dire subito se il cookie appena incollato e accettato.
# Meglio un endpoint API: e quello che serve al plugin, e risponde in millisecondi.
PROBE_URL = os.getenv("RELAY_PROBE_URL", "").strip() or (
    f"https://{PRIMARY_HOST}/api/DramaList/List?page=1&type=0&sub=0&country=0&status=0&order=1"
    if PRIMARY_HOST
    else ""
)

UPSTREAM_HEADERS = {
    "Accept": "application/json, text/plain, */*",
    "Accept-Language": "en-US,en;q=0.9,it;q=0.8",
}


@asynccontextmanager
async def lifespan(_: FastAPI):
    _load_state()
    task: asyncio.Task | None = None
    if SOLVER_URL:
        task = asyncio.create_task(_warm_loop())
    try:
        yield
    finally:
        if task is not None:
            task.cancel()


app = FastAPI(
    title="kisskh-relay",
    docs_url=None,
    redoc_url=None,
    openapi_url=None,
    lifespan=lifespan,
)

# url -> (expires_at, status, body, content_type, source)
_cache: dict[str, tuple[float, int, bytes, str, str]] = {}
# id -> (expires_at, text) per i sottotitoli generati dal plugin (decifrati/DeepL)
_store: dict[str, tuple[float, str]] = {}
STORE_TTL = float(os.getenv("RELAY_STORE_TTL", "21600"))  # 6 ore
MAX_STORE_CHARS = int(os.getenv("RELAY_STORE_MAX", "2000000"))
# cf_clearance state, shared by all requests (bound to this box's public IP).
# RELAY_COOKIES / RELAY_USER_AGENT allow seeding it by hand (no FlareSolverr).
_clearance: dict[str, object] = {
    "cookies": _normalize_cookies(os.getenv("RELAY_COOKIES", "")),
    "ua": os.getenv("RELAY_USER_AGENT", "").strip(),
    "expires": 0.0,
}
if _clearance["cookies"]:
    _clearance["expires"] = time.monotonic() + CLEARANCE_TTL
    print(
        f"[seed] cookie(s)={[p.split('=', 1)[0] for p in str(_clearance['cookies']).split(';')]} "
        f"len={len(str(_clearance['cookies']))} "
        f"ua={'set' if _clearance['ua'] else 'MISSING'}",
        flush=True,
    )
    if not _clearance["ua"]:
        print("[seed] RELAY_USER_AGENT vuoto: il clearance non verra accettato", flush=True)
    for part in str(_clearance["cookies"]).split(";"):
        if "=" in part:
            name, value = part.split("=", 1)
            if " " in value.strip() or len(value.strip()) < 20:
                print(
                    f"[seed] {name.strip()}: valore sospetto (spazi o troppo corto, "
                    f"{len(value.strip())} caratteri) — copia di nuovo il cookie",
                    flush=True,
                )
_fast_disabled_until = 0.0
_warming = False
_stats = {"fast": 0, "solved": 0, "failed": 0}
# Ultimo esito upstream, esposto da /healthz: evita di dover leggere i log.
_last: dict[str, object] = {}


def _lower_headers(headers: object) -> dict[str, str]:
    """Normalizza in minuscolo gli header di curl_cffi o di FlareSolverr."""
    try:
        items = headers.items()  # type: ignore[union-attr]
    except Exception:
        return {}
    return {str(key).lower(): str(value) for key, value in items}


def _classify(status: int, body: bytes, headers: dict[str, str]) -> str:
    """Distingue un clearance rifiutato da un IP bloccato o da un errore qualsiasi."""
    if status == 200:
        return "ok"
    head = body[:8192].lower()
    mitigated = headers.get("cf-mitigated", "").lower()
    if mitigated == "challenge" or b"just a moment" in head or b"challenge-platform" in head:
        return "challenge"
    if status in (403, 429) and (
        b"you have been blocked" in head
        or b"attention required" in head
        or b"error 10" in head  # 1015 / 1020: WAF
    ):
        return "blocked"
    return "other"


def _note(status: int, reason: str, target: str, source: str) -> None:
    _last.update(
        {
            "status": status,
            "reason": reason,
            "source": source,
            "host": urlparse(target).netloc,
            "at": int(time.time()),
        }
    )


def _host_allowed(host: str) -> bool:
    host = (host or "").lower()
    return any(host == allowed or host.endswith("." + allowed) for allowed in ALLOWED_HOSTS)


def _validate(raw: str) -> str:
    try:
        parsed = urlparse(raw)
    except ValueError:
        return ""
    if parsed.scheme not in ("http", "https") or not parsed.netloc:
        return ""
    if not _host_allowed(parsed.hostname or ""):
        return ""
    return raw


def _token_ok(token: str) -> bool:
    if not TOKEN:
        return True
    return secrets.compare_digest(token.strip(), TOKEN)


def _cache_get(url: str) -> tuple[int, bytes, str, str] | None:
    if CACHE_TTL <= 0:
        return None
    entry = _cache.get(url)
    if entry is None:
        return None
    expires_at, status, body, content_type, source = entry
    if expires_at < time.monotonic():
        _cache.pop(url, None)
        return None
    return status, body, content_type, source


def _cache_put(url: str, status: int, body: bytes, content_type: str, source: str) -> None:
    if CACHE_TTL <= 0 or status != 200:
        return
    if len(_cache) >= MAX_CACHE_ENTRIES:
        _cache.clear()
    _cache[url] = (time.monotonic() + CACHE_TTL, status, body, content_type, source)


def _clearance_valid() -> bool:
    return bool(_clearance["cookies"]) and float(_clearance["expires"]) > time.monotonic()


def _store_clearance(cookies: str, ua: str) -> None:
    _clearance["cookies"] = cookies
    _clearance["ua"] = ua
    _clearance["expires"] = time.monotonic() + CLEARANCE_TTL


def _load_state() -> None:
    """Recupera il clearance rinnovato con /renew, se sopravvive al riavvio."""
    if not STATE_FILE:
        return
    try:
        data = json.loads(Path(STATE_FILE).read_text("utf-8"))
    except Exception:
        return
    cookies = _normalize_cookies(str(data.get("cookies") or ""))
    try:
        remaining = float(data.get("expires_at") or 0) - time.time()
    except (TypeError, ValueError):
        return
    if not cookies or remaining <= 60:
        return
    _clearance["cookies"] = cookies
    _clearance["ua"] = str(data.get("user_agent") or "").strip()
    _clearance["expires"] = time.monotonic() + remaining
    print(f"[state] clearance ripreso da {STATE_FILE}: {int(remaining)}s residui", flush=True)


def _save_state() -> None:
    """Salva cookie+UA+scadenza reale, cosi un riavvio non torna al compose."""
    if not STATE_FILE or not _clearance["cookies"]:
        return
    payload = {
        "cookies": _clearance["cookies"],
        "user_agent": _clearance["ua"],
        "expires_at": time.time() + max(0.0, float(_clearance["expires"]) - time.monotonic()),
    }
    try:
        path = Path(STATE_FILE)
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_name(path.name + ".tmp")
        tmp.write_text(json.dumps(payload), "utf-8")
        try:
            os.chmod(tmp, 0o600)
        except OSError:
            pass
        tmp.replace(path)
    except Exception as exc:
        print(f"[state] salvataggio fallito: {exc.__class__.__name__}: {exc}", flush=True)


def _guess_type(body: bytes) -> str:
    head = body.lstrip()[:1]
    if head in (b"{", b"["):
        return "application/json"
    return "text/html"


def _drop_dead_clearance(reason: str) -> None:
    """Un 403 challenge significa che il cookie non e piu accettato: forza un refresh."""
    if reason == "blocked":
        print(
            "[blocked] Cloudflare blocca questo IP (non e il cookie): serve un altro IP",
            flush=True,
        )
        return
    had_cookies = bool(_clearance["cookies"])
    _clearance["expires"] = 0.0
    if had_cookies:
        print("[expired] cf_clearance rifiutato: rinnovalo", flush=True)
    else:
        print("[challenge] nessun cf_clearance valido: serve un cookie o il solver", flush=True)


async def _fast_get(target: str) -> tuple[int, bytes, str, dict[str, str]]:
    headers = dict(UPSTREAM_HEADERS)
    headers["Referer"] = f"{urlparse(target).scheme}://{urlparse(target).netloc}/"
    if _clearance["ua"]:
        headers["User-Agent"] = str(_clearance["ua"])
    if _clearance["cookies"]:
        headers["Cookie"] = str(_clearance["cookies"])

    async with AsyncSession(impersonate=IMPERSONATE, timeout=TIMEOUT) as session:
        response = await session.get(target, headers=headers, allow_redirects=True)

    content_type = (response.headers.get("content-type") or _guess_type(response.content))
    return (
        response.status_code,
        response.content or b"",
        content_type.split(";")[0],
        _lower_headers(response.headers),
    )


async def _solve(target: str) -> tuple[int, bytes, str, dict[str, str]]:
    payload = {
        "cmd": "request.get",
        "url": target,
        "maxTimeout": int(SOLVER_TIMEOUT * 1000),
    }
    if SOLVER_WAIT > 0:
        payload["waitInSeconds"] = SOLVER_WAIT

    async with httpx.AsyncClient(timeout=SOLVER_TIMEOUT + 30) as client:
        response = await client.post(SOLVER_URL, json=payload)
        data = response.json()

    if data.get("status") != "ok":
        raise RuntimeError(str(data.get("message") or "solver error")[:120])

    solution = data.get("solution") or {}
    body = (solution.get("response") or "").encode("utf-8")
    status = int(solution.get("status") or 200)

    cookie_parts = [
        f"{cookie.get('name')}={cookie.get('value')}"
        for cookie in (solution.get("cookies") or [])
        if cookie.get("name") and cookie.get("value")
    ]
    user_agent = str(solution.get("userAgent") or "")
    if cookie_parts:
        _store_clearance("; ".join(cookie_parts), user_agent)

    content_type = _guess_type(body)
    return status, body, content_type, _lower_headers(solution.get("headers") or {})


async def _fetch(target: str) -> tuple[int, bytes, str, str]:
    global _fast_disabled_until

    fast_result: tuple[int, bytes, str] | None = None
    fast_error: str | None = None

    # --- fast path: cookie + fingerprint Chrome ---------------------------
    if (_clearance_valid() or not SOLVER_URL) and time.monotonic() >= _fast_disabled_until:
        try:
            status, body, content_type, headers = await _fast_get(target)
        except Exception as exc:
            fast_error = f"{exc.__class__.__name__}: {exc}"
            print(f"[fast] errore di rete: {fast_error}", flush=True)
        else:
            if status == 200:
                _stats["fast"] += 1
                _note(status, "ok", target, "fast")
                return status, body, content_type, "fast"

            reason = _classify(status, body, headers)
            _note(status, reason, target, "fast")
            fast_result = (status, body, content_type)

            if reason == "challenge":
                _drop_dead_clearance(reason)
                # Cookie rifiutato: inutile riprovare a ogni richiesta per un po'.
                _fast_disabled_until = time.monotonic() + FAST_RETRY_AFTER
            elif reason == "blocked":
                _drop_dead_clearance(reason)
                _fast_disabled_until = time.monotonic() + FAST_RETRY_AFTER
                return status, body, content_type, "fast"

            # Solo una challenge puo essere risolta: un 404/400 va restituito.
            if not SOLVER_URL or reason != "challenge":
                return status, body, content_type, "fast"

    if not SOLVER_URL:
        if fast_error:
            raise RuntimeError(fast_error)
        if fast_result is not None:
            return fast_result[0], fast_result[1], fast_result[2], "fast"
        # Fast path in cooldown senza solver: nessuna scorciatoia, ma risposta vera.
        status, body, content_type, headers = await _fast_get(target)
        _note(status, _classify(status, body, headers), target, "fast")
        return status, body, content_type, "fast"

    # --- solve path -------------------------------------------------------
    solver_error: str | None = None
    try:
        status, body, content_type, headers = await _solve(target)
    except Exception as exc:
        _stats["failed"] += 1
        solver_error = f"{exc.__class__.__name__}: {str(exc)[:160]}"
        _note(0, f"solve: {solver_error}", target, "solved")
        print(f"[solve] fallito: {solver_error}", flush=True)
    else:
        _stats["solved"] += 1
        _note(status, _classify(status, body, headers), target, "solved")
        return status, body, content_type, "solved"

    if fast_error:
        raise RuntimeError(solver_error or fast_error)

    # Solver fallito: meglio l'ultimo body del fast path che un 502 vuoto.
    if fast_result is None:
        status, body, content_type, headers = await _fast_get(target)
        _note(status, _classify(status, body, headers), target, "fast")
        return status, body, content_type, "fast"
    return fast_result[0], fast_result[1], fast_result[2], "fast"


async def _warm_loop() -> None:
    """Keep the cf_clearance fresh so the plugin never waits for a solve."""
    global _warming

    # Give FlareSolverr time to boot on slow boxes (it can take minutes on ARM).
    await asyncio.sleep(20)

    failures = 0
    while True:
        try:
            stale = not _clearance_valid() or (
                float(_clearance["expires"]) - time.monotonic() < CLEARANCE_TTL * 0.3
            )
            if stale and WARM_URL:
                _warming = True
                status, _, _, _ = await _solve(WARM_URL)
                failures = 0
                print(f"[warm] solve status={status} clearance={_clearance_valid()}", flush=True)
        except Exception as exc:
            failures += 1
            # Un solve fallito costa minuti di CPU: non martellare il box.
            delay = min(60 * (2 ** min(failures, 4)), 900)
            print(
                f"[warm] failed ({failures}x): {exc.__class__.__name__} — retry in {delay}s",
                flush=True,
            )
            _warming = False
            await asyncio.sleep(delay)
            continue
        finally:
            _warming = False

        await asyncio.sleep(60)


@app.post("/store")
async def store(request: Request, token: str = Query(""), ext: str = Query("srt")) -> Response:
    """Pubblica un sottotitolo generato dal plugin (decifrato o tradotto)."""
    if not _token_ok(token):
        return JSONResponse({"error": "invalid token"}, status_code=401)

    body_bytes = await request.body()
    if not body_bytes:
        return JSONResponse({"error": "empty body"}, status_code=400)
    if len(body_bytes) > MAX_STORE_CHARS:
        return JSONResponse({"error": "too large"}, status_code=413)

    now = time.monotonic()
    if len(_store) >= 256:
        for key in [k for k, (expiry, _) in _store.items() if expiry < now]:
            _store.pop(key, None)
        while len(_store) >= 256:
            _store.pop(next(iter(_store)), None)

    key = secrets.token_hex(8)
    _store[key] = (now + STORE_TTL, body_bytes.decode("utf-8", "replace"))
    extension = "vtt" if ext.strip().lower() == "vtt" else "srt"

    host = request.headers.get("host") or "127.0.0.1:8191"
    scheme = request.headers.get("x-forwarded-proto") or "http"
    url = f"{scheme}://{host}/sub/{key}.{extension}"
    if VERBOSE:
        print(f"[store] {key}.{extension} {len(body_bytes)} byte", flush=True)
    return JSONResponse({"url": url})


@app.get("/sub/{name}")
async def sub(name: str) -> Response:
    key = name.split(".")[0]
    entry = _store.get(key)
    if entry is None or entry[0] < time.monotonic():
        _store.pop(key, None)
        return JSONResponse({"error": "not found"}, status_code=404)

    media = "text/vtt" if name.endswith(".vtt") else "application/x-subrip"
    if VERBOSE:
        print(f"[sub] {name} {len(entry[1])} byte", flush=True)
    return Response(
        content=entry[1].encode("utf-8"),
        media_type=media,
        headers={"Cache-Control": "no-store"},
    )


@app.get("/healthz")
async def healthz() -> dict[str, object]:
    cookies = str(_clearance["cookies"])
    return {
        "ok": True,
        "impersonate": IMPERSONATE,
        "hosts": ALLOWED_HOSTS,
        "solver": bool(SOLVER_URL),
        "clearance": _clearance_valid(),
        "clearance_expires_in": max(0, int(float(_clearance["expires"]) - time.monotonic())),
        "ua_set": bool(_clearance["ua"]),
        "ua": str(_clearance["ua"])[:120],
        "cookie_names": [part.split("=", 1)[0].strip() for part in cookies.split(";") if "=" in part],
        "cookie_len": len(cookies),
        "state_file": bool(STATE_FILE),
        "fast_disabled_for": max(0, int(_fast_disabled_until - time.monotonic())),
        "last": dict(_last),
        "warming": _warming,
        "stats": _stats,
    }


_RENEW_PAGE = """<!doctype html>
<html lang="it">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<title>kisskh-relay - rinnova clearance</title>
<style>
  :root { color-scheme: dark light; }
  body { font-family: system-ui, -apple-system, Segoe UI, Roboto, sans-serif;
         max-width: 40rem; margin: 0 auto; padding: 1rem 1rem 3rem; line-height: 1.4; }
  h1 { font-size: 1.3rem; margin: 0 0 .25rem; }
  .state { font-size: .85rem; opacity: .8; margin: 0 0 1.25rem; }
  label { display: block; font-weight: 600; margin: 1rem 0 .35rem; }
  small { display: block; font-weight: 400; opacity: .7; margin-top: .2rem; }
  input, textarea { width: 100%; box-sizing: border-box; padding: .6rem;
         font: inherit; font-size: .95rem; border: 1px solid #8888; border-radius: .4rem; }
  textarea { min-height: 5.5rem; word-break: break-all; }
  button { margin-top: 1.2rem; width: 100%; padding: .8rem; font: inherit; font-weight: 600;
         border: 0; border-radius: .4rem; background: #2563eb; color: #fff; }
  button:disabled { opacity: .6; }
  #out { margin-top: 1.2rem; white-space: pre-wrap; word-break: break-all; font-size: .85rem;
         padding: .8rem; border-radius: .4rem; background: #8881; display: none; }
  #out.ok { background: #16a34a33; border: 1px solid #16a34a; }
  #out.ko { background: #dc262633; border: 1px solid #dc2626; }
  code { background: #8882; padding: 0 .25rem; border-radius: .2rem; }
</style>
</head>
<body>
<h1>Rinnova cf_clearance</h1>
<p class="state">__STATE__</p>

<label for="ua">User-Agent di questo browser
  <small>Precompilato: e lo UA che Cloudflare ha visto. Non cambiarlo.</small>
</label>
<input id="ua" autocomplete="off" spellcheck="false">

<label for="cookies">Cookie
  <small>Incolla <code>cf_clearance=...</code> oppure solo il valore.</small>
</label>
<textarea id="cookies" autocomplete="off" spellcheck="false" placeholder="cf_clearance=..."></textarea>

<button id="go">Verifica e salva</button>
<pre id="out"></pre>

<script>
const TOKEN = __TOKEN__;
const out = document.getElementById('out');
document.getElementById('ua').value = navigator.userAgent;

document.getElementById('go').onclick = async () => {
  const cookies = document.getElementById('cookies').value.trim();
  const user_agent = document.getElementById('ua').value.trim();
  out.style.display = 'block';
  out.className = '';
  if (!cookies) { out.textContent = 'Incolla il cookie prima di inviare.'; out.className = 'ko'; return; }
  const button = document.getElementById('go');
  button.disabled = true;
  out.textContent = 'Verifica in corso...';
  try {
    const response = await fetch('/renew?token=' + encodeURIComponent(TOKEN), {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify({ cookies, user_agent })
    });
    const data = await response.json();
    const ok = data.status === 200;
    out.className = ok ? 'ok' : 'ko';
    out.textContent = (ok ? 'OK: ' : 'ATTENZIONE: ')
      + (data.verdict || data.error || ('HTTP ' + response.status))
      + '\\n\\n' + JSON.stringify(data, null, 2);
  } catch (error) {
    out.className = 'ko';
    out.textContent = 'Errore di rete verso il relay: ' + error;
  } finally {
    button.disabled = false;
  }
};
</script>
</body>
</html>
"""


@app.get("/renew-ui", response_class=HTMLResponse)
async def renew_ui(token: str = Query("")) -> Response:
    """Pagina minima per rinnovare il clearance da qualunque browser (anche telefono)."""
    if not _token_ok(token):
        return HTMLResponse(
            "<!doctype html><meta name=viewport content='width=device-width'>"
            "<p style='font-family:system-ui;padding:1rem'>Token mancante o errato.</p>",
            status_code=401,
        )

    remaining = max(0, int(float(_clearance["expires"]) - time.monotonic()))
    if not _clearance["cookies"]:
        state = "Nessun cookie: incollane uno qui sotto."
    elif _clearance_valid():
        state = f"Clearance attivo, scade fra {remaining // 86400}g {remaining % 86400 // 3600}h."
    else:
        state = "Clearance scaduto o rifiutato: incolla un cookie nuovo."
    names = [
        part.split("=", 1)[0].strip() for part in str(_clearance["cookies"]).split(";") if "=" in part
    ]
    if names:
        state += f" Cookie presenti: {', '.join(names)}."

    page = _RENEW_PAGE.replace("__TOKEN__", json.dumps(token)).replace(
        "__STATE__", html.escape(state)
    )
    return HTMLResponse(page, headers={"Cache-Control": "no-store"})


@app.post("/renew")
async def renew(request: Request, token: str = Query(""), verify: int = Query(1)) -> Response:
    """Sostituisce cookie e UA senza ricreare il container, e li prova subito.

    Body JSON: {"cookies": "cf_clearance=...; __cf_bm=...", "user_agent": "Mozilla/5.0 ..."}
    Entrambi opzionali: se manca lo UA si tiene quello gia in uso.
    """
    global _fast_disabled_until

    if not _token_ok(token):
        return JSONResponse({"error": "invalid token"}, status_code=401)

    try:
        payload = await request.json()
    except Exception:
        payload = {}
    if not isinstance(payload, dict):
        payload = {}

    cookies = _normalize_cookies(str(payload.get("cookies") or ""))
    user_agent = str(payload.get("user_agent") or "").strip()
    if cookies:
        _store_clearance(cookies, user_agent or str(_clearance["ua"]))
    elif user_agent:
        _clearance["ua"] = user_agent
        _clearance["expires"] = time.monotonic() + CLEARANCE_TTL
    else:
        return JSONResponse(
            {"error": "body vuoto: serve cookies e/o user_agent"}, status_code=400
        )

    if payload.get("ttl") is not None:
        try:
            _clearance["expires"] = time.monotonic() + max(60.0, float(payload["ttl"]))
        except (TypeError, ValueError):
            pass

    # Un cookie nuovo merita un tentativo immediato, anche se il precedente era
    # stato messo in castigo, e la cache puo contenere i 403 di prima.
    _fast_disabled_until = 0.0
    _cache.clear()
    # Salva subito: se la verifica fallisce per DNS/rete il cookie resta buono.
    _save_state()
    print(
        f"[renew] cookie(s)={[p.split('=', 1)[0].strip() for p in str(_clearance['cookies']).split(';') if '=' in p]} "
        f"len={len(str(_clearance['cookies']))} ua={'set' if _clearance['ua'] else 'MISSING'}",
        flush=True,
    )

    result: dict[str, object] = {
        "clearance": _clearance_valid(),
        "cookie_len": len(str(_clearance["cookies"])),
        "ua_set": bool(_clearance["ua"]),
        "ua": str(_clearance["ua"])[:120],
    }

    if verify and PROBE_URL:
        try:
            status, body, _, headers = await _fast_get(PROBE_URL)
        except Exception as exc:
            # Non e' un rifiuto del cookie: il relay non ha raggiunto il sito.
            result.update(
                {
                    "probe": PROBE_URL,
                    "error": f"{exc.__class__.__name__}: {str(exc)[:200]}",
                    "verdict": "relay non ha raggiunto il sito (DNS/rete), cookie non verificato",
                }
            )
            print(f"[renew] probe non raggiungibile: {result['error']}", flush=True)
            return JSONResponse(result)

        reason = _classify(status, body, headers)
        _note(status, reason, PROBE_URL, "fast")
        result.update(
            {
                "probe": PROBE_URL,
                "status": status,
                "reason": reason,
                "body_bytes": len(body),
                "cf_mitigated": headers.get("cf-mitigated", ""),
            }
        )
        if reason == "challenge":
            _drop_dead_clearance(reason)
            result["clearance"] = False
            result["verdict"] = "cookie rifiutato da Cloudflare"
            result["hint"] = (
                "controlla: UA identico al browser, stesso IP pubblico (non VPN/rete mobile), "
                "cookie completo e non scaduto, browser Chrome (non Firefox) o RELAY_IMPERSONATE coerente"
            )
        elif reason == "blocked":
            result["verdict"] = "IP bloccato da Cloudflare: un altro cookie non serve"
        elif status == 200:
            result["verdict"] = "ok: cookie accettato"
        else:
            result["verdict"] = f"upstream {status}, non e un problema di clearance"

        print(f"[renew] probe {status} {reason} -> {result['verdict']}", flush=True)

    return JSONResponse(result)


@app.get("/fetch")
async def fetch(
    url: str = Query(..., min_length=8),
    token: str = Query(""),
) -> Response:
    if not _token_ok(token):
        return JSONResponse({"error": "invalid token"}, status_code=401)

    target = _validate(url)
    if not target:
        host = (urlparse(url).hostname or "").lower()
        print(f"[deny] host non in allowlist: {host}", flush=True)
        return JSONResponse(
            {"error": "host not allowed", "host": host, "allowed": ALLOWED_HOSTS},
            status_code=403,
        )

    cached = _cache_get(target)
    if cached is not None:
        status, body, content_type, source = cached
        return Response(
            content=body,
            status_code=status,
            media_type=content_type,
            headers={"X-Relay": "cache", "X-Relay-Source": source},
        )

    try:
        status, body, content_type, source = await _fetch(target)
    except Exception as exc:
        return JSONResponse({"error": f"relay: {exc.__class__.__name__}: {str(exc)[:200]}"}, status_code=502)

    if VERBOSE:
        print(f"[req] {status} {source} {target} bytes={len(body)}", flush=True)

    if len(body) > MAX_BYTES:
        return JSONResponse({"error": "response too large"}, status_code=502)

    _cache_put(target, status, body, content_type, source)

    return Response(
        content=body,
        status_code=status,
        media_type=content_type,
        headers={"X-Relay": "miss", "X-Relay-Source": source},
    )


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=int(os.getenv("PORT", "8191")), access_log=False)
