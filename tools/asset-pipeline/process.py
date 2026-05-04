"""
Sketchbook asset pipeline — one-shot processor.

Inputs:  C:\\Users\\jtfow\\Downloads\\Gemini_Generated_Image_*.png  (8 files)
Outputs: web/public/textures/{paper-grain,wood-grain}.webp
         web/public/brand/sketchbook.png  (true alpha)
         web/public/raw/{doodles-24,field-icons-5,washi-tape-6,hold-methods,torn-edges-4}.png

Run:  uv run --project tools/asset-pipeline python tools/asset-pipeline/process.py
"""

from __future__ import annotations

from pathlib import Path

import numpy as np
from PIL import Image
from scipy import ndimage

REPO_ROOT = Path(__file__).resolve().parents[2]
DOWNLOADS = Path("C:/Users/jtfow/Downloads")
OUT_TEX = REPO_ROOT / "web/public/textures"
OUT_BRAND = REPO_ROOT / "web/public/brand"
OUT_RAW = REPO_ROOT / "web/public/raw"

# (downloads filename) -> (asset role, output path, processor)
ASSETS: dict[str, tuple[str, Path, str]] = {
    "Gemini_Generated_Image_ag7m1kag7m1kag7m.png":   ("A1 paper-grain",      OUT_TEX  / "paper-grain.webp", "raster_texture"),
    "Gemini_Generated_Image_ag7m1kag7m1kag7m(1).png": ("A2 wood-grain",       OUT_TEX  / "wood-grain.webp",  "raster_texture"),
    "Gemini_Generated_Image_y3uuswy3uuswy3uu.png":   ("A3 sketchbook title", OUT_BRAND / "sketchbook.png",  "alpha_key_brand"),
    "Gemini_Generated_Image_ag7m1kag7m1kag7m(2).png": ("A4 doodles-24",       OUT_RAW   / "doodles-24.png",  "passthrough"),
    "Gemini_Generated_Image_ag7m1kag7m1kag7m(3).png": ("A5 field-icons-5",    OUT_RAW   / "field-icons-5.png","passthrough"),
    "Gemini_Generated_Image_ag7m1kag7m1kag7m(4).png": ("A6 washi-tape-6",     OUT_RAW   / "washi-tape-6.png", "alpha_key"),
    "Gemini_Generated_Image_ag7m1kag7m1kag7m(5).png": ("A7 hold-methods",     OUT_RAW   / "hold-methods.png", "alpha_key"),
    "Gemini_Generated_Image_ag7m1kag7m1kag7m(6).png": ("A8 torn-edges-4",     OUT_RAW   / "torn-edges-4.png", "alpha_key"),
}


def remove_gemini_watermark(img: Image.Image, corner_frac: float = 0.10) -> Image.Image:
    """Crop the bottom-right corner where the Gemini diamond watermark sits, then square the image.

    Gemini drops a small 4-pointed star bottom-right. Cropping it off then re-squaring is the
    most reliable cleanup; we sacrifice ~10% of edge pixels but the result is clean and seamless-friendly.
    """
    w, h = img.size
    crop_px = int(min(w, h) * corner_frac)
    cropped = img.crop((0, 0, w - crop_px, h - crop_px))
    cw, ch = cropped.size
    side = min(cw, ch)
    return cropped.crop((0, 0, side, side))


def make_seamless(img: Image.Image, blend_frac: float = 0.18) -> Image.Image:
    """Make a tileable texture from a non-seamless source via offset + linear edge blend.

    Standard offset-paste trick: roll the image by half its width and height so the original
    edges are now in the middle of the canvas. Blend a horizontal and vertical strip across the
    new center seams using a linear alpha gradient, mixed with a mirrored copy of the seam
    region. Result: edges already match (they were the centre, now wrapped) and the new visible
    seams in the middle are smoothed.
    """
    arr = np.array(img.convert("RGB")).astype(np.float32)
    h, w, _ = arr.shape

    rolled = np.roll(arr, shift=(h // 2, w // 2), axis=(0, 1))

    bh = max(2, int(h * blend_frac))
    bw = max(2, int(w * blend_frac))

    # Horizontal seam at y=h/2: blend a band of height bh across that line by mixing rolled with a
    # vertically-flipped copy of itself (mirrored over the seam) using a triangular weight.
    cy = h // 2
    band = rolled[cy - bh // 2 : cy + bh // 2].copy()
    flipped = band[::-1]
    weights = np.linspace(0.0, 1.0, band.shape[0], dtype=np.float32)
    weights = np.minimum(weights, 1.0 - weights) * 2.0  # triangle 0..1..0
    weights = weights[:, None, None]
    rolled[cy - bh // 2 : cy + bh // 2] = band * (1 - weights * 0.5) + flipped * (weights * 0.5)

    # Vertical seam at x=w/2.
    cx = w // 2
    band = rolled[:, cx - bw // 2 : cx + bw // 2].copy()
    flipped = band[:, ::-1]
    weights = np.linspace(0.0, 1.0, band.shape[1], dtype=np.float32)
    weights = np.minimum(weights, 1.0 - weights) * 2.0
    weights = weights[None, :, None]
    rolled[:, cx - bw // 2 : cx + bw // 2] = band * (1 - weights * 0.5) + flipped * (weights * 0.5)

    return Image.fromarray(np.clip(rolled, 0, 255).astype(np.uint8), "RGB")


def alpha_key_checkerboard(img: Image.Image, corner_px: int = 64, color_tol: int = 12) -> Image.Image:
    """Convert Gemini's faux-transparent checkerboard pixels to true alpha.

    Adaptive strategy:
      1. Sample 4 corner patches (definitely background) to learn the actual background colors.
      2. Mark any pixel that is near-grey AND lies within tolerance of the sampled bg luminance range.
      3. Connected-component label with 8-connectivity (so the alternating dark/light squares
         of the checkerboard merge into one component via their shared edges).
      4. Keep only components that touch the image border — this preserves white/grey *inside*
         artwork (polka dots inside a tape, metal highlights inside a staple).
    """
    arr = np.array(img.convert("RGBA"))
    rgb = arr[..., :3].astype(np.int16)
    r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
    h, w = rgb.shape[:2]

    # 1. Sample corners.
    corners = np.concatenate([
        rgb[:corner_px, :corner_px].reshape(-1, 3),
        rgb[:corner_px, -corner_px:].reshape(-1, 3),
        rgb[-corner_px:, :corner_px].reshape(-1, 3),
        rgb[-corner_px:, -corner_px:].reshape(-1, 3),
    ])
    # Reject any corner pixel that is not near-grey (defensive: artwork may bleed into a corner).
    cmax = corners.max(axis=1)
    cmin = corners.min(axis=1)
    grey_corners = corners[(cmax - cmin) < 12]
    if len(grey_corners) == 0:
        # Fall back to the broad heuristic.
        grey_corners = np.array([[200, 200, 200], [255, 255, 255]], dtype=np.int16)

    bg_lum_min = int(grey_corners.mean(axis=1).min()) - color_tol
    bg_lum_max = int(grey_corners.mean(axis=1).max()) + color_tol

    # 2. Build bg_mask.
    near_grey = (np.abs(r - g) < 14) & (np.abs(g - b) < 14) & (np.abs(r - b) < 14)
    lum = (r + g + b) // 3
    in_lum_band = (lum >= bg_lum_min) & (lum <= bg_lum_max)
    bg_mask = near_grey & in_lum_band

    # 3. 8-connectivity so adjacent checker squares merge.
    structure = np.ones((3, 3), dtype=np.int32)
    labels, n = ndimage.label(bg_mask, structure=structure)
    if n == 0:
        return img.convert("RGBA")

    # 4. Border-touching components.
    border = np.concatenate([
        labels[0, :], labels[-1, :], labels[:, 0], labels[:, -1],
    ])
    border_labels = set(np.unique(border).tolist())
    border_labels.discard(0)
    if not border_labels:
        return img.convert("RGBA")

    border_arr = np.array(sorted(border_labels), dtype=labels.dtype)
    is_border = np.isin(labels, border_arr)

    out = arr.copy()
    out[..., 3] = np.where(is_border, 0, 255).astype(np.uint8)
    return Image.fromarray(out, "RGBA")


def make_tile_preview(img: Image.Image, out_path: Path, repeats: int = 2) -> None:
    """Write a 2x2 (or NxN) tiled preview so seam quality is visually verifiable."""
    w, h = img.size
    canvas = Image.new(img.mode, (w * repeats, h * repeats))
    for ry in range(repeats):
        for rx in range(repeats):
            canvas.paste(img, (rx * w, ry * h))
    canvas.save(out_path)


def process_raster_texture(src: Path, dst: Path) -> dict:
    img = Image.open(src).convert("RGB")
    cleaned = remove_gemini_watermark(img, corner_frac=0.10)
    # Resize down to the design-doc target tile size if too large.
    target = 1024 if "wood" in dst.stem else 512
    if cleaned.size[0] > target * 1.05:
        cleaned = cleaned.resize((target, target), Image.LANCZOS)
    # Make seamless via offset + edge blend.
    seamless = make_seamless(cleaned, blend_frac=0.18)
    dst.parent.mkdir(parents=True, exist_ok=True)
    quality = 80 if "wood" in dst.stem else 88
    seamless.save(dst, format="WEBP", quality=quality, method=6)
    # Tile preview for visual seam check.
    preview = dst.with_name(dst.stem + ".tile-preview.png")
    make_tile_preview(seamless, preview, repeats=2)
    return {"size": seamless.size, "bytes": dst.stat().st_size, "preview": str(preview.relative_to(REPO_ROOT))}


def process_alpha_key(src: Path, dst: Path, max_long_edge: int | None = None) -> dict:
    img = Image.open(src)
    keyed = alpha_key_checkerboard(img)
    if max_long_edge and max(keyed.size) > max_long_edge:
        ratio = max_long_edge / max(keyed.size)
        new_size = (int(keyed.size[0] * ratio), int(keyed.size[1] * ratio))
        keyed = keyed.resize(new_size, Image.LANCZOS)
    dst.parent.mkdir(parents=True, exist_ok=True)
    keyed.save(dst, format="PNG", optimize=True)
    return {"size": keyed.size, "bytes": dst.stat().st_size}


def process_alpha_key_brand(src: Path, dst: Path) -> dict:
    """Brand title: alpha-key, then resize to 1600x800 target with hard size budget."""
    return process_alpha_key(src, dst, max_long_edge=1600)


def crop_alpha_bbox(img: Image.Image, padding: int = 4) -> Image.Image:
    """Trim transparent margin around opaque content, preserving a small padding."""
    if img.mode != "RGBA":
        return img
    a = np.array(img)[..., 3]
    rows = np.any(a > 0, axis=1)
    cols = np.any(a > 0, axis=0)
    if not rows.any() or not cols.any():
        return img
    y0, y1 = np.argmax(rows), len(rows) - np.argmax(rows[::-1])
    x0, x1 = np.argmax(cols), len(cols) - np.argmax(cols[::-1])
    y0 = max(0, y0 - padding)
    x0 = max(0, x0 - padding)
    y1 = min(img.size[1], y1 + padding)
    x1 = min(img.size[0], x1 + padding)
    return img.crop((x0, y0, x1, y1))


def slice_grid(src_path: Path, out_dir: Path, cols: int, rows: int, names: list[str], threshold_grey: bool = True) -> list[dict]:
    """Slice a sheet into a grid; alpha-key each cell against an off-white paper background.

    Used for the doodle sheet (cols=6, rows=4) and field-icon sheet (cols=5, rows=1).
    """
    img = Image.open(src_path).convert("RGBA")
    arr = np.array(img)
    if threshold_grey:
        rgb = arr[..., :3].astype(np.int16)
        # Off-white paper background: bright + near-grey + low saturation.
        r, g, b = rgb[..., 0], rgb[..., 1], rgb[..., 2]
        near_grey = (np.abs(r - g) < 20) & (np.abs(g - b) < 20) & (np.abs(r - b) < 20)
        bright = (r > 215) & (g > 215) & (b > 215)
        bg_mask = near_grey & bright
        # Don't connected-component-filter here; the strokes are isolated and small. Just key.
        out = arr.copy()
        out[..., 3] = np.where(bg_mask, 0, 255).astype(np.uint8)
        # Smooth alpha edges by anti-aliasing: where partially-grey but not fully bg, set semi-alpha
        # Simple: leave hard alpha — pencil lines on paper are crisp enough.
        img = Image.fromarray(out, "RGBA")

    out_dir.mkdir(parents=True, exist_ok=True)
    w, h = img.size
    cw, ch = w // cols, h // rows
    results = []
    for ry in range(rows):
        for rx in range(cols):
            idx = ry * cols + rx
            if idx >= len(names):
                continue
            cell = img.crop((rx * cw, ry * ch, (rx + 1) * cw, (ry + 1) * ch))
            trimmed = crop_alpha_bbox(cell, padding=8)
            out_path = out_dir / f"{names[idx]}.png"
            trimmed.save(out_path, format="PNG", optimize=True)
            results.append({"name": names[idx], "size": trimmed.size, "bytes": out_path.stat().st_size})
    return results


def process_passthrough(src: Path, dst: Path) -> dict:
    img = Image.open(src)
    dst.parent.mkdir(parents=True, exist_ok=True)
    img.save(dst, format="PNG", optimize=True)
    return {"size": img.size, "bytes": dst.stat().st_size}


PROCESSORS = {
    "raster_texture": process_raster_texture,
    "alpha_key": process_alpha_key,
    "alpha_key_brand": process_alpha_key_brand,
    "passthrough": process_passthrough,
}

DOODLE_NAMES = [
    "metronome", "piano-keyboard", "microphone", "cassette-tape", "drum-kit", "drumstick",
    "dancer", "star", "moon", "cloud", "rainstorm-cloud", "magnifying-glass",
    "paper-airplane", "pencil-stub", "paperclip", "scissors", "plus", "x-mark",
    "checkmark", "chevron-right", "house", "bookmark", "folder", "cassette-spool",
]
FIELD_ICON_NAMES = ["bpm", "key", "tracks", "length", "time-sig"]


def main() -> int:
    print(f"[asset-pipeline] repo={REPO_ROOT}")
    print(f"[asset-pipeline] source={DOWNLOADS}")
    missing = [name for name in ASSETS if not (DOWNLOADS / name).exists()]
    if missing:
        print(f"[asset-pipeline] MISSING {len(missing)} input(s):")
        for m in missing:
            print(f"  - {m}")
        return 2

    for fname, (role, dst, kind) in ASSETS.items():
        src = DOWNLOADS / fname
        info = PROCESSORS[kind](src, dst)
        rel = dst.relative_to(REPO_ROOT)
        kb = info["bytes"] / 1024
        print(f"[ok] {role:24s} -> {rel}  ({info['size'][0]}x{info['size'][1]}, {kb:.1f} KB, kind={kind})")
        if "preview" in info:
            print(f"     preview: {info['preview']}")

    # Slice doodle sheet into 24 individual PNG sprites.
    doodle_src = DOWNLOADS / "Gemini_Generated_Image_ag7m1kag7m1kag7m(2).png"
    doodle_dir = OUT_RAW / "icons" / "doodles"
    if doodle_src.exists():
        results = slice_grid(doodle_src, doodle_dir, cols=6, rows=4, names=DOODLE_NAMES)
        total_kb = sum(r["bytes"] for r in results) / 1024
        print(f"[ok] doodle sheet -> {len(results)} sprites in {doodle_dir.relative_to(REPO_ROOT)}  ({total_kb:.1f} KB total)")

    # Slice field-icon sheet into 5 individual PNG sprites.
    field_src = DOWNLOADS / "Gemini_Generated_Image_ag7m1kag7m1kag7m(3).png"
    field_dir = OUT_RAW / "icons" / "field"
    if field_src.exists():
        results = slice_grid(field_src, field_dir, cols=5, rows=1, names=FIELD_ICON_NAMES)
        total_kb = sum(r["bytes"] for r in results) / 1024
        print(f"[ok] field-icon sheet -> {len(results)} sprites in {field_dir.relative_to(REPO_ROOT)}  ({total_kb:.1f} KB total)")

    return 0


if __name__ == "__main__":
    raise SystemExit(main())
