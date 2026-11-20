"""Diagnostica del relay: da quale IP esce, e il cookie viene accettato?

    docker compose exec kisskh-relay python check.py

Usa RELAY_COOKIES / RELAY_USER_AGENT / RELAY_IMPERSONATE dall'ambiente del
container: esattamente gli stessi valori del fast path. Mostra l'IP pubblico
(quello che vede Cloudflare) e la risposta del sito con e senza cookie.
Non stampa mai il valore dei cookie, solo nome e lunghezza.
"""

from __future__ import annotations

import asyncio
import os

from curl_cffi.requests import AsyncSession

API = os.getenv("RELAY_PROBE_URL", "").strip() or (
    "https://kisskh.co/api/DramaList/List?page=1&type=0&sub=0&country=0&status=0&order=1"
)
IMPERSONATE = os.getenv("RELAY_IMPERSONATE", "chrome").strip() or "chrome"
UA = os.getenv("RELAY_USER_AGENT", "").strip()
COOKIES = " ".join(os.getenv("RELAY_COOKIES", "").split()).strip().strip(";")


def classify(status: int, body: bytes, cf_mitigated: str) -> str:
    if status == 200:
        return "ok"
    head = body[:8192].lower()
    if cf_mitigated.lower() == "challenge" or b"just a moment" in head:
        return "challenge"
    if status in (403, 429) and (
        b"you have been blocked" in head
        or b"attention required" in head
        or b"error 10" in head
    ):
        return "blocked"
    return "other"


async def probe(session: AsyncSession, url: str, with_cookie: bool) -> None:
    headers = {
        "Accept": "application/json, text/plain, */*",
        "Accept-Language": "en-US,en;q=0.9,it;q=0.8",
        "Referer": "https://kisskh.co/",
    }
    if UA:
        headers["User-Agent"] = UA
    if with_cookie and COOKIES:
        headers["Cookie"] = COOKIES

    label = "con cookie " if with_cookie else "senza cookie"
    try:
        response = await session.get(url, headers=headers, timeout=25)
    except Exception as exc:  # rete/DNS/TLS
        print(f"[test] {label}: errore {exc.__class__.__name__}: {str(exc)[:120]}")
        return

    body = response.content or b""
    cf_mitigated = response.headers.get("cf-mitigated", "") or ""
    reason = classify(response.status_code, body, cf_mitigated)
    print(
        f"[test] {label}: {response.status_code} {reason} "
        f"(bytes={len(body)}, cf-mitigated={cf_mitigated or '-'})"
    )
    if reason == "ok":
        print(f"       primi byte: {body[:120]!r}")


async def public_ip(session: AsyncSession, url: str) -> str:
    try:
        response = await session.get(url, timeout=15)
        if response.status_code == 200:
            return response.text.strip()[:80]
    except Exception:
        pass
    return "assente"


async def main() -> None:
    print(f"[cfg] impersonate={IMPERSONATE} ua={'set' if UA else 'MISSING'}")
    if UA:
        print(f"[cfg] ua={UA[:120]}")
    names = [p.split("=", 1)[0].strip() for p in COOKIES.split(";") if "=" in p]
    print(f"[cfg] cookie(s)={names or 'nessuno'} len={len(COOKIES)}")
    print(f"[cfg] probe={API}")

    async with AsyncSession(impersonate=IMPERSONATE, timeout=25) as session:
        print(
            "[net] ipv4=" + await public_ip(session, "https://api.ipify.org?format=json")
            + " ipv6=" + await public_ip(session, "https://api6.ipify.org?format=json")
        )
        print(
            "[net] confronta con https://api.ipify.org aperto NEL BROWSER che ha "
            "prodotto il cookie: deve essere lo stesso IP"
        )
        await probe(session, API, with_cookie=False)
        if COOKIES:
            await probe(session, API, with_cookie=True)
        else:
            print("[test] nessun RELAY_COOKIES: niente da testare")


if __name__ == "__main__":
    asyncio.run(main())
