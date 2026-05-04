"""Sample colors from the hold-methods source to figure out why alpha-key missed it."""
from pathlib import Path
import numpy as np
from PIL import Image

src = Path("C:/Users/jtfow/Downloads/Gemini_Generated_Image_ag7m1kag7m1kag7m(5).png")
arr = np.array(Image.open(src).convert("RGB"))
h, w, _ = arr.shape

# Sample a 4x4 grid of points across the image, including 4 corners.
sample_pts = []
for ry in [0.02, 0.25, 0.5, 0.75, 0.98]:
    for rx in [0.02, 0.25, 0.5, 0.75, 0.98]:
        sample_pts.append((int(ry * h), int(rx * w)))

print(f"image {w}x{h}")
for y, x in sample_pts:
    r, g, b = arr[y, x]
    print(f"  ({x:5d},{y:5d}) rgb=({r:3d},{g:3d},{b:3d})")

# Histogram of brightness across the whole image (max channel value).
mx = arr.max(axis=2)
bins = [0, 100, 150, 175, 200, 225, 240, 256]
hist, edges = np.histogram(mx, bins=bins)
print("\nbrightness (max-channel) histogram:")
for h_, lo, hi in zip(hist, edges[:-1], edges[1:]):
    print(f"  [{lo:3d},{hi:3d}): {h_/mx.size:6.2%}")
