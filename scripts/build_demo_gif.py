from PIL import Image, ImageDraw, ImageFont
import os

assets_dir = r"c:\Users\Madhawa\Desktop\Dev\StockForge\docs\assets"

slides = [
    ("demo-dashboard-active.png", "1. Interactive Live Dashboard & Saga State Machine (localhost:8088)"),
    ("grafana-system-overview.png", "2. Grafana System Overview: RED Metrics, Virtual Threads & Latency SLOs"),
    ("grafana-business-invariants.png", "3. Grafana Business Flow: Real-Time Stock Invariants & Saga Completion"),
    ("kafka-ui-topics.png", "4. Apache Kafka UI: Event-Driven Outbox/Inbox Topics & Partition Lag"),
    ("swagger-ui.png", "5. OpenAPI 3.0 Interactive REST Explorer & RFC 9457 Validation Schemas")
]

target_w, target_h = 1280, 780
frames = []

for filename, caption in slides:
    path = os.path.join(assets_dir, filename)
    if not os.path.exists(path):
        print(f"Skipping missing: {path}")
        continue
    img = Image.open(path).convert("RGBA")
    
    # Resize preserving aspect ratio or fill target area
    img_resized = img.resize((target_w, target_h - 44), Image.Resampling.LANCZOS)
    
    # Create canvas with caption banner at bottom
    canvas = Image.new("RGBA", (target_w, target_h), (15, 23, 42, 255)) # slate-900
    canvas.paste(img_resized, (0, 0))
    
    draw = ImageDraw.Draw(canvas)
    
    # Top subtle border
    draw.line([(0, target_h - 44), (target_w, target_h - 44)], fill=(51, 65, 85, 255), width=2)
    
    # Caption bar background
    draw.rectangle([(0, target_h - 42), (target_w, target_h)], fill=(15, 23, 42, 255))
    
    # Text
    try:
        font = ImageFont.truetype("arial.ttf", 20)
    except Exception:
        font = ImageFont.load_default()
        
    draw.text((24, target_h - 34), caption, fill=(248, 250, 252, 255), font=font)
    
    # Convert back to RGB for gif palette
    frames.append(canvas.convert("RGB"))

if frames:
    gif_path = os.path.join(assets_dir, "stockforge-demo.gif")
    # 3200ms per frame = 3.2 seconds display
    frames[0].save(
        gif_path,
        save_all=True,
        append_images=frames[1:],
        duration=3200,
        loop=0,
        optimize=True
    )
    print(f"Successfully generated animated demo GIF at {gif_path} (size: {os.path.getsize(gif_path)} bytes)")
else:
    print("No frames found to generate GIF!")

