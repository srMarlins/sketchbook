"""3x3 tile preview to make seam quality unambiguous."""
from pathlib import Path
from PIL import Image
REPO_ROOT = Path(__file__).resolve().parents[2]
for name in ("paper-grain", "wood-grain"):
    src = REPO_ROOT / f"web/public/textures/{name}.webp"
    img = Image.open(src)
    w, h = img.size
    canvas = Image.new("RGB", (w * 3, h * 3))
    for ry in range(3):
        for rx in range(3):
            canvas.paste(img, (rx * w, ry * h))
    out = REPO_ROOT / f"web/public/textures/{name}.tile-3x3.png"
    canvas.thumbnail((1200, 1200))
    canvas.save(out)
    print(out)
