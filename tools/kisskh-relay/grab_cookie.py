"""Prende un cf_clearance fresco da Chrome vero e lo manda al relay.

Da eseguire sul PC (non sul box), con Chrome installato:

    pip install playwright
    python grab_cookie.py http://192.168.1.50:8191 IL_TOKEN

Apre Chrome su kisskh.co, supera la verifica Cloudflare, poi chiama
`POST /renew` sul relay con il cookie e lo **User-Agent esatto** di quel browser.
Il valore del cookie non viene mai stampato: viaggia solo verso il relay, che
risponde se Cloudflare lo accetta (`verdict`).

Perche serve: cf_clearance e legato allo UA del browser che l'ha ottenuto, quindi
copiare il cookie e sbagliare lo UA (o usare un altro browser) produce 403.
Qui i due valori sono per forza coerenti.
"""

from __future__ import annotations

import argparse
import json
import sys
import time
import urllib.request

from playwright.sync_api import sync_playwright

SITE = "https://kisskh.co"
CLEARANCE = "cf_clearance"


def _relay_url(base: str) -> str:
    base = base.strip().rstrip("/")
    if not base.startswith(("http://", "https://")):
        base = "http://" + base
    return base


def _ask_cloudflare(page, context) -> None:
    """Clicca il Turnstile (spesso e una challenge interattiva) finche non passa."""
    for attempt in range(1, 6):
        cookies = {cookie["name"] for cookie in context.cookies()}
        if CLEARANCE in cookies:
            return
        if attempt > 1:
            page.wait_for_timeout(4000)
        box = None
        for handle in page.query_selector_all("iframe"):
            src = handle.get_attribute("src") or ""
            if "challenges.cloudflare.com" in src:
                box = handle.bounding_box()
                break
        if box:
            x, y = box["x"] + 30, box["y"] + box["height"] / 2
        else:
            x, y = 216, 335  # posizione del widget vista su kisskh.co
        page.mouse.move(x - 260, y + 120, steps=12)
        page.mouse.move(x, y, steps=18)
        page.wait_for_timeout(300)
        page.mouse.click(x, y)
        page.wait_for_timeout(5000)


def grab(timeout_s: int) -> tuple[str, str, int]:
    with sync_playwright() as playwright:
        browser = playwright.chromium.launch(
            channel="chrome",
            headless=False,
            args=["--disable-blink-features=AutomationControlled"],
        )
        context = browser.new_context(viewport={"width": 1280, "height": 720})
        context.add_init_script(
            "Object.defineProperty(navigator, 'webdriver', {get: () => undefined});"
        )
        page = context.new_page()
        page.goto(SITE + "/", wait_until="domcontentloaded", timeout=timeout_s * 1000)
        page.wait_for_timeout(4000)
        _ask_cloudflare(page, context)

        cookies = [c for c in context.cookies(SITE) if c["name"] in (CLEARANCE, "__cf_bm")]
        user_agent = page.evaluate("() => navigator.userAgent")
        browser.close()

    clearance = next((c for c in cookies if c["name"] == CLEARANCE), None)
    if clearance is None:
        raise SystemExit(
            "Cloudflare non ha rilasciato cf_clearance: riprova, o clicca tu la casella "
            "nella finestra che si apre."
        )

    # Scadenza vera decisa da Cloudflare (per kisskh.co: circa un anno).
    ttl = 0
    expires = clearance.get("expires") or 0
    if expires > 0:
        ttl = max(60, int(expires - time.time()) - 60)
    return "; ".join(f"{c['name']}={c['value']}" for c in cookies), user_agent, ttl


def renew(base: str, token: str, cookies: str, user_agent: str, ttl: int = 0) -> dict:
    url = f"{_relay_url(base)}/renew?token={token}"
    payload: dict[str, object] = {"cookies": cookies, "user_agent": user_agent}
    if ttl > 0:
        payload["ttl"] = ttl
    body = json.dumps(payload).encode()
    request = urllib.request.Request(
        url, data=body, headers={"Content-Type": "application/json"}, method="POST"
    )
    with urllib.request.urlopen(request, timeout=60) as response:
        return json.loads(response.read().decode())


def main() -> int:
    parser = argparse.ArgumentParser(description="Rinnova il cf_clearance del relay")
    parser.add_argument("relay", help="base del relay, es. http://192.168.1.50:8191")
    parser.add_argument("token", help="RELAY_TOKEN configurato sul relay")
    parser.add_argument("--timeout", type=int, default=60, help="timeout apertura pagina")
    parser.add_argument("--dry-run", action="store_true", help="non invia, mostra solo cosa ha preso")
    args = parser.parse_args()

    cookies, user_agent, ttl = grab(args.timeout)
    names = [p.split("=", 1)[0] for p in cookies.split(";")]
    print(f"cookie: {names} len={len(cookies)}")
    print(f"ua: {user_agent}")
    if ttl:
        print(f"scadenza Cloudflare: ~{ttl // 60} min ({ttl // 86400} giorni)")

    if args.dry_run:
        return 0

    result = renew(args.relay, args.token.strip(), cookies, user_agent, ttl)
    print("relay:", json.dumps(result, indent=2))

    if result.get("status") == 200:
        print("OK: il relay ha un clearance accettato (nessun copia-incolla).")
        return 0
    if "status" not in result and result.get("error"):
        print(
            "Cookie inviato e salvato, ma il relay non e riuscito a verificarlo "
            "(DNS/rete). Non e un rifiuto di Cloudflare: controlla 'probe'."
        )
        return 2
    print("Cloudflare ha rifiutato il cookie appena preso: guarda 'verdict'/'hint'.")
    return 1


if __name__ == "__main__":
    sys.exit(main())
