#!/usr/bin/env python3
"""Builds docs/assets/stockforge-showcase.gif from a live run of the whole platform. Nothing is mocked or canned.

    pip install playwright pillow                 # uses the installed Microsoft Edge/Chrome
    docker compose --profile apps --profile tools up -d --wait
    python scripts/demo/showcase_gif.py

Sequence of the GIF (each frame is a real capture, captioned with what it proves):
    1  Swagger UI, 7 steps   authorize, create product, read availability, reserve, replay, order, saga, BOLA
    2  Terminal, 5 acts      oversell race, idempotency race, saga compensation, Kafka outage, invariant audit
    3  Grafana, 2 dashboards captured right after the load, so they show it
    4  Kafka UI, 2 pages     topics with the outbox-published events, and the order-events messages

The run stops the Kafka container for a minute (act 4). The GIF is written only if every act of the proof passed; the
full transcript is saved next to it as docs/assets/engineering-proof.txt.
"""
import argparse
import os
import re
import subprocess
import sys
import time
from pathlib import Path

from PIL import Image, ImageDraw, ImageFont
from playwright.sync_api import sync_playwright

sys.path.insert(0, str(Path(__file__).resolve().parent))
from swagger_walkthrough import record_swagger  # noqa: E402

ROOT = Path(__file__).resolve().parents[2]
WIDTH = 1280
NAVY, INDIGO, TEXT = (15, 23, 42), (99, 102, 241), (248, 250, 252)
FONTS = Path("C:/Windows/Fonts")


def font(name, size):
    try:
        return ImageFont.truetype(str(FONTS / name), size)
    except OSError:
        return ImageFont.load_default()


def write_caption(d, text):
    for size in range(21, 13, -1):
        f = font("segoeuib.ttf", size)
        if d.textlength(text, font=f) <= WIDTH - 40:
            break
    d.text((20, 15 + (21 - size) // 2), text, fill=TEXT, font=f)


def captioned(img, caption, step):
    """Adds the same navy caption bar the Swagger frames carry."""
    img = img.convert("RGB")
    img = img.resize((WIDTH, int(img.height * WIDTH / img.width)), Image.Resampling.LANCZOS)
    bar = 58
    canvas = Image.new("RGB", (WIDTH, img.height + bar), NAVY)
    canvas.paste(img, (0, bar))
    d = ImageDraw.Draw(canvas)
    d.rectangle([(0, bar - 3), (WIDTH, bar)], fill=INDIGO)
    write_caption(d, f"{step}  {caption}".strip())
    return canvas


def run_proof(out_txt):
    env = {**os.environ, "PYTHONUTF8": "1"}
    proc = subprocess.run([sys.executable, str(ROOT / "scripts" / "demo" / "engineering_proof.py")],
                          cwd=ROOT, capture_output=True, text=True, env=env, encoding="utf-8")
    out_txt.write_text(proc.stdout, encoding="utf-8")
    print(proc.stdout[-900:])
    if proc.returncode != 0:
        sys.exit("the proof failed: refusing to publish a GIF of a failing run (see " + str(out_txt) + ")")
    return proc.stdout


def split_acts(transcript):
    lines = transcript.splitlines()
    heads = [i for i, l in enumerate(lines) if re.match(r"ACT \d  ", l)]
    summary = next(i for i, l in enumerate(lines) if l.startswith("SUMMARY"))
    acts = []
    for n, i in enumerate(heads):
        end = (heads[n + 1] - 1) if n + 1 < len(heads) else summary - 1
        body = [l for l in lines[i + 4:end] if l.strip() and not set(l.strip()) <= {"="}]
        acts.append((lines[i], lines[i + 2], body))
    return acts


def terminal_frame(head, claim, body, step):
    mono, small = font("consola.ttf", 19), font("consola.ttf", 17)
    line_h, pad = 26, 24
    height = 58 + pad * 2 + line_h * (len(body) + 3)
    img = Image.new("RGB", (WIDTH, max(height, 560)), (13, 17, 23))
    d = ImageDraw.Draw(img)
    d.text((pad, 58 + pad), head, fill=(125, 211, 252), font=font("consolab.ttf", 21))
    d.text((pad, 58 + pad + line_h + 4), claim, fill=(148, 163, 184), font=small)
    y = 58 + pad + line_h * 2 + 14
    for line in body:
        colour = (226, 232, 240)
        if "[PASS]" in line:
            colour = (74, 222, 128)
        elif "[FAIL]" in line:
            colour = (248, 113, 113)
        elif line.strip().startswith("$"):
            colour = (250, 204, 21)
        elif "NOTICE" in line:
            colour = (74, 222, 128)
        d.text((pad, y), line[:118], fill=colour, font=mono)
        y += line_h
    return draw_caption(img.crop((0, 0, WIDTH, y + pad)), claim.replace("claim: ", ""), step)


def draw_caption(img, caption, step):
    d = ImageDraw.Draw(img)
    d.rectangle([(0, 0), (WIDTH, 55)], fill=NAVY)
    d.rectangle([(0, 55), (WIDTH, 58)], fill=INDIGO)
    write_caption(d, f"{step}  {caption}")
    return img


DASH_CAPTIONS = {
    "grafana-business": "Grafana: invariant violations 0, dead-lettered 0. Red tile = the Act 4 Kafka outage delaying events",
    "grafana-system": "Grafana: RED metrics, JVM, connection pools and per-service latency during the same run",
    "kafka-topics": "Kafka UI: the saga's topics (commands, events, and a dead-letter topic for each)",
    "kafka-messages": "Kafka UI: order-events, the outbox-published saga events, with partition and offset",
}


def capture_dashboards(page, shots, base_grafana, base_kafka):
    """Grafana and Kafka UI after the load, so their graphs and topics show it."""
    frames = []

    def shoot(url, name, wait_ms, selector=None):
        page.goto(url, wait_until="domcontentloaded")
        if selector:
            page.wait_for_selector(selector, timeout=30000)
        page.wait_for_timeout(wait_ms)
        path = shots / f"{name}.png"
        page.screenshot(path=str(path))
        return path

    frames.append((shoot(f"{base_grafana}/d/stockforge-business/x?orgId=1&from=now-15m&to=now&kiosk", "grafana-business", 9000),
                   DASH_CAPTIONS["grafana-business"]))
    frames.append((shoot(f"{base_grafana}/d/stockforge-system/x?orgId=1&from=now-15m&to=now&kiosk", "grafana-system", 9000),
                   DASH_CAPTIONS["grafana-system"]))
    frames.append((shoot(f"{base_kafka}/ui/clusters/stockforge/all-topics?perPage=25", "kafka-topics", 3500),
                   DASH_CAPTIONS["kafka-topics"]))
    frames.append((shoot(f"{base_kafka}/ui/clusters/stockforge/all-topics/order-events/messages", "kafka-messages", 5000),
                   DASH_CAPTIONS["kafka-messages"]))
    return frames


HEIGHT = 820  # one canvas for every frame: viewers crop later frames to the size of the first one


def fit(frame):
    """Scales to WIDTH, then crops the bottom or pads with the terminal background to exactly WIDTH x HEIGHT."""
    img = frame.convert("RGB")
    img = img.resize((WIDTH, int(img.height * WIDTH / img.width)), Image.Resampling.LANCZOS)
    canvas = Image.new("RGB", (WIDTH, HEIGHT), (13, 17, 23))
    canvas.paste(img.crop((0, 0, WIDTH, min(img.height, HEIGHT))), (0, 0))
    return canvas


def save_gif(frames, durations, out):
    palette = [fit(f).convert("P", palette=Image.Palette.ADAPTIVE, colors=96) for f in frames]
    palette[0].save(out, save_all=True, append_images=palette[1:], duration=durations, loop=0, optimize=True)
    print(f"wrote {out}: {out.stat().st_size / 1024 / 1024:.1f} MiB, {len(palette)} frames")


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--gateway", default="http://localhost:8088")
    ap.add_argument("--grafana", default="http://localhost:3000")
    ap.add_argument("--kafka-ui", default="http://localhost:8090")
    ap.add_argument("--out", default=str(ROOT / "docs" / "assets" / "stockforge-showcase.gif"))
    ap.add_argument("--assemble-only", action="store_true",
                    help="rebuild the GIF from the frames and transcript of the last run (e.g. after editing captions)")
    args = ap.parse_args()
    out = Path(args.out)
    shots = ROOT / "target" / "showcase-frames"
    shots.mkdir(parents=True, exist_ok=True)

    if args.assemble_only:
        swagger = [Image.open(p) for p in sorted((shots / "swagger").glob("frame-*.png"))]
        acts = split_acts(out.with_name("engineering-proof.txt").read_text(encoding="utf-8"))
        dash = [(shots / f"{n}.png", c) for n, c in DASH_CAPTIONS.items()]
    else:
        print("== 1/3 Swagger UI session")
        swagger = [Image.open(p) for p in record_swagger(args.gateway, str(shots / "swagger"), slow=250)]

        print("== 2/3 engineering proof (stops Kafka for about a minute)")
        transcript = run_proof(out.with_name("engineering-proof.txt"))
        acts = split_acts(transcript)

        print("== 3/3 dashboards (waiting for Prometheus to scrape the load)")
        time.sleep(25)
        with sync_playwright() as p:
            try:
                browser = p.chromium.launch(channel="msedge")
            except Exception:
                browser = p.chromium.launch()
            page = browser.new_page(viewport={"width": 1440, "height": 860})
            dash = capture_dashboards(page, shots, args.grafana, args.kafka_ui)
            browser.close()

    frames, durations = [], []
    title = Image.new("RGB", (WIDTH, 560), (13, 17, 23))
    d = ImageDraw.Draw(title)
    d.text((70, 150), "StockForge", fill=TEXT, font=font("segoeuib.ttf", 64))
    d.text((70, 235), "Proof, not claims: a live run of the real stack", fill=(165, 180, 252), font=font("segoeui.ttf", 30))
    for i, t in enumerate(["Swagger UI: real requests, real responses, real headers",
                           "Concurrency, idempotency and saga compensation, verified in PostgreSQL",
                           "A Kafka outage survived through the transactional outbox",
                           "Grafana and Kafka UI captured live during the same run"]):
        d.text((70, 320 + i * 42), "- " + t, fill=(203, 213, 225), font=font("segoeui.ttf", 24))
    frames.append(title)
    durations.append(4000)
    for img in swagger:
        frames.append(img)
        durations.append(4200)
    for i, (head, claim, body) in enumerate(acts):
        frames.append(terminal_frame(head, claim, body, f"{i + 1}/5"))
        durations.append(5500)
    for path, caption in dash:
        frames.append(captioned(Image.open(path), caption, ""))
        durations.append(5000)
    save_gif(frames, durations, out)


if __name__ == "__main__":
    main()
