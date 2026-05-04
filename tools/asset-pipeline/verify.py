"""Verify alpha-keyed outputs actually contain transparent pixels."""
from pathlib import Path
import numpy as np
from PIL import Image

REPO_ROOT = Path(__file__).resolve().parents[2]

targets = [
    REPO_ROOT / "web/public/brand/sketchbook.png",
    REPO_ROOT / "web/public/raw/washi-tape-6.png",
    REPO_ROOT / "web/public/raw/hold-methods.png",
    REPO_ROOT / "web/public/raw/torn-edges-4.png",
]

for p in targets:
    img = Image.open(p)
    if img.mode != "RGBA":
        print(f"{p.name}: mode={img.mode} (not RGBA — alpha key did not run!)")
        continue
    a = np.array(img)[..., 3]
    total = a.size
    transparent = int((a == 0).sum())
    semi = int(((a > 0) & (a < 255)).sum())
    opaque = int((a == 255).sum())
    print(f"{p.name}: {img.size}  alpha=0:{transparent/total:.1%}  semi:{semi/total:.1%}  opaque:{opaque/total:.1%}")
