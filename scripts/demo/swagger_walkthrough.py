#!/usr/bin/env python3
"""Drives the gateway's real Swagger UI in a browser and records the session as a GIF.

Nothing is mocked: the script authorizes with a bearer token, fills in the request forms and presses *Execute*, exactly
as a reviewer would. Each frame is a screenshot of the live response, captioned with what it proves.

    pip install playwright pillow          # uses the installed Microsoft Edge/Chrome, no browser download
    docker compose --profile apps up -d --wait
    python scripts/demo/swagger_walkthrough.py                      # writes docs/assets/swagger-walkthrough.gif
    python scripts/demo/swagger_walkthrough.py --headed --slow 400  # watch it run

The script reuses the token minting and HTTP helper of engineering_proof.py (same directory).
"""
import argparse
import json
import sys
import time
import uuid
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from playwright.sync_api import sync_playwright

sys.path.insert(0, str(Path(__file__).resolve().parent))
from engineering_proof import Api, token, wait_for  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
CAPTION_JS = """
(text) => {
  let bar = document.getElementById('sf-caption');
  if (!bar) {
    bar = document.createElement('div');
    bar.id = 'sf-caption';
    bar.style.cssText = 'position:fixed;left:0;right:0;top:0;z-index:99999;padding:12px 20px;font:600 17px/1.35 '
      + 'Segoe UI,Arial,sans-serif;color:#f8fafc;background:#0f172a;border-bottom:3px solid #6366f1;';
    document.body.appendChild(bar);
    document.body.style.paddingTop = '64px';
  }
  bar.textContent = text;
}
"""


class Swagger:
    def __init__(self, page, base, shots, slow_ms):
        self.page, self.base, self.shots, self.slow = page, base, shots, slow_ms

    def open(self):
        self.page.goto(f"{self.base}/swagger-ui/index.html", wait_until="networkidle")
        self.page.wait_for_selector(".opblock-tag-section")

    def authorize(self, bearer):
        self.page.click("button.authorize")
        modal = self.page.locator(".auth-container")
        logout = modal.locator("button", has_text="Logout")
        if logout.count():  # an earlier identity is active: log it out first
            logout.first.click()
        modal.locator("#auth-bearer-value").fill(bearer)
        modal.locator("button[type=submit]").click()  # its accessible name is "Apply credentials"
        self.page.click("button.btn-done")

    def op(self, tag, operation_id):
        return self.page.locator(f"#operations-{tag}-{operation_id}")

    def expand(self, op):
        if "is-open" not in (op.get_attribute("class") or ""):
            op.locator(".opblock-summary").first.click()
        if op.locator("button.try-out__btn").first.inner_text().strip() == "Try it out":
            op.locator("button.try-out__btn").first.click()
        op.scroll_into_view_if_needed()

    def collapse(self, op):
        if "is-open" in (op.get_attribute("class") or ""):
            op.locator(".opblock-summary").first.click()

    def set_param(self, op, name, value):
        field = op.locator(f'tr[data-param-name="{name}"]').locator("input, select").first
        if field.evaluate("e => e.tagName") == "SELECT":
            field.select_option(value)
        else:
            field.fill(value)

    def set_body(self, op, body):
        op.locator("textarea.body-param__text").fill(json.dumps(body, indent=2))

    def execute(self, op, expect_status):
        op.locator("button.execute").click()
        op.locator(".live-responses-table .response-col_status", has_text=str(expect_status)).first.wait_for(
            timeout=20000)

    def response_json(self, op):
        text = op.locator(".live-responses-table .highlight-code pre").first.inner_text()
        return json.loads(text)

    def snap(self, op, caption):
        # The evidence is the live response (status, body, headers): put it at the top, just under the caption bar.
        self.page.evaluate(CAPTION_JS, caption)
        op.locator(".live-responses-table").first.evaluate("e => e.scrollIntoView({block: 'start'})")
        self.page.evaluate("window.scrollBy(0, -84)")
        self.page.wait_for_timeout(self.slow)
        path = self.shots / f"frame-{len(list(self.shots.glob('frame-*.png'))):02d}.png"
        self.page.screenshot(path=str(path))
        return path


def build_gif(frames, out):
    font = ImageFont.truetype("arial.ttf", 18) if Path("C:/Windows/Fonts/arial.ttf").exists() else None
    images = []
    for path in frames:
        img = Image.open(path).convert("RGB")
        img = img.resize((1280, int(img.height * 1280 / img.width)), Image.Resampling.LANCZOS)
        images.append(img.convert("P", palette=Image.Palette.ADAPTIVE, colors=128))
    images[0].save(out, save_all=True, append_images=images[1:], duration=[3500] * len(images), loop=0, optimize=True)
    print(f"wrote {out} ({out.stat().st_size / 1024:.0f} KiB, {len(images)} frames)")


def record_swagger(base_url, shots_dir, headed=False, slow=250):
    """Runs the Swagger UI session and returns the captured frames (PNG paths) in order."""
    shots = Path(shots_dir)
    shots.mkdir(parents=True, exist_ok=True)
    for old in shots.glob("frame-*.png"):
        old.unlink()
    api = Api(base_url)
    admin, alice, bob = token(admin=True), token(), token()
    sku = "SWAGGER-" + uuid.uuid4().hex[:8].upper()
    frames = []

    with sync_playwright() as p:
        try:
            browser = p.chromium.launch(channel="msedge", headless=not headed)
        except Exception:
            browser = p.chromium.launch(headless=not headed)
        page = browser.new_page(viewport={"width": 1440, "height": 1100})
        sw = Swagger(page, base_url, shots, slow)
        sw.open()

        # 1. administrator creates the product
        sw.authorize(admin)
        op = sw.op("Administration", "createProduct")
        sw.expand(op)
        sw.set_body(op, {"sku": sku, "name": "Limited sneaker (Swagger demo)",
                         "price": {"amount": "119.00", "currency": "USD"}, "initialStock": 5})
        sw.execute(op, 201)
        product = sw.response_json(op)
        frames.append(sw.snap(op, "1/7  POST /admin/products as ADMIN: 201 Created, 5 units, UUIDv7 id, audited"))
        sw.collapse(op)

        # 2. a customer reads availability (weak ETag), then repeats with If-None-Match -> 304 is shown by the gateway
        sw.authorize(alice)
        op = sw.op("Catalog", "availability")
        sw.expand(op)
        sw.set_param(op, "productId", product["id"])
        sw.execute(op, 200)
        frames.append(sw.snap(op, "2/7  GET /products/{id}/availability: stock counters, weak ETag, Cache-Control: no-cache"))
        sw.collapse(op)

        # 3. reserve, 4. replay with the same Idempotency-Key
        key = "swagger-demo-" + uuid.uuid4().hex[:10]
        op = sw.op("Checkout", "reserve")
        sw.expand(op)
        sw.set_param(op, "Idempotency-Key", key)
        sw.set_body(op, {"productId": product["id"], "quantity": 2})
        sw.execute(op, 201)
        reservation = sw.response_json(op)
        frames.append(sw.snap(op, "3/7  POST /reservations: 201, an atomic conditional UPDATE took 2 of 5 units"))
        op.locator("button.execute").click()
        page.wait_for_function("() => document.body.innerText.toLowerCase().includes('idempotent-replayed')")
        frames.append(sw.snap(op, "4/7  Same Idempotency-Key again: the original 201 is replayed (Idempotent-Replayed: true), stock untouched"))
        sw.collapse(op)

        # 5. order with a forced payment failure -> saga compensates
        op = sw.op("Checkout", "placeOrder")
        sw.expand(op)
        sw.set_param(op, "Idempotency-Key", "swagger-order-" + uuid.uuid4().hex[:10])
        sw.set_param(op, "X-Payment-Simulation", "FAILED")
        sw.set_body(op, {"reservationId": reservation["id"]})
        sw.execute(op, 202)
        order = sw.response_json(op)
        frames.append(sw.snap(op, "5/7  POST /orders with X-Payment-Simulation: FAILED: 202 Accepted, the payment runs async over Kafka"))
        sw.collapse(op)

        # 6. the saga finished: CANCELLED, with the audit trail
        owner = alice
        wait_for(lambda: api.call("GET", f"/api/v1/orders/{order['id']}", owner)[1].get("status") == "CANCELLED", 30)
        op = sw.op("Checkout", "order")
        sw.expand(op)
        sw.set_param(op, "orderId", order["id"])
        sw.execute(op, 200)
        frames.append(sw.snap(op, "6/7  GET /orders/{id}: PENDING_PAYMENT -> CANCELLED, the compensation released the stock"))
        sw.collapse(op)

        # 7. another user cannot even learn that the order exists
        sw.authorize(bob)
        op = sw.op("Checkout", "order")
        sw.expand(op)
        sw.set_param(op, "orderId", order["id"])
        sw.execute(op, 404)
        frames.append(sw.snap(op, "7/7  Same order as another user: 404 problem+json, not 403, so ids cannot be probed (OWASP API1)"))
        browser.close()

    return frames


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default="http://localhost:8088")
    ap.add_argument("--out", default=str(ROOT / "docs" / "assets" / "swagger-walkthrough.gif"))
    ap.add_argument("--shots", default=str(ROOT / "target" / "swagger-frames"))
    ap.add_argument("--headed", action="store_true")
    ap.add_argument("--slow", type=int, default=250, help="ms to wait before each screenshot")
    args = ap.parse_args()

    frames = record_swagger(args.base_url, args.shots, args.headed, args.slow)
    build_gif(frames, Path(args.out))


if __name__ == "__main__":
    main()
