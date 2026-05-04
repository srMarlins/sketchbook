# asset-pipeline

One-shot processor that turns the raw Gemini Imagen outputs in `~/Downloads/` into the production assets the Sketchbook web UI consumes.

## Run

```pwsh
uv run --project tools/asset-pipeline python tools/asset-pipeline/process.py
```

Idempotent — safe to re-run after replacing inputs.

## Inputs (8 files)

| Source filename in `~/Downloads/` | Asset role | See `docs/design-language.md` appendix |
|---|---|---|
| `Gemini_Generated_Image_ag7m1kag7m1kag7m.png`   | A1 paper-grain texture | A1 |
| `Gemini_Generated_Image_ag7m1kag7m1kag7m(1).png` | A2 wood-grain texture | A2 |
| `Gemini_Generated_Image_y3uuswy3uuswy3uu.png`   | A3 brand wordmark | A3 |
| `Gemini_Generated_Image_ag7m1kag7m1kag7m(2).png` | A4 24-doodle library | A4 |
| `Gemini_Generated_Image_ag7m1kag7m1kag7m(3).png` | A5 5 field icons | A5 |
| `Gemini_Generated_Image_ag7m1kag7m1kag7m(4).png` | A6 6 washi tape strips | A6 |
| `Gemini_Generated_Image_ag7m1kag7m1kag7m(5).png` | A7 staples + paperclip + pushpins | A7 |
| `Gemini_Generated_Image_ag7m1kag7m1kag7m(6).png` | A8 4 torn-edge strips | A8 |

## Outputs

```
web/public/
  textures/
    paper-grain.webp           # 512x512, ~3 KB
    wood-grain.webp            # 1024x1024, ~108 KB
    *.tile-preview.png         # 2x2 tile previews (visual seam check; safe to delete)
  brand/
    sketchbook.png             # 1600x791, ~417 KB, true alpha
  raw/
    washi-tape-6.png           # alpha-keyed, awaiting per-strip slicing or vectorization
    hold-methods.png           # alpha-keyed, awaiting per-item slicing or vectorization
    torn-edges-4.png           # alpha-keyed, awaiting clipPath extraction
    icons/
      doodles/                 # 24 individual sprites, alpha background, ~60-140 KB each
      field/                   # 5 individual sprites
```

## What the script does

- **Watermark removal** — crops 10 % off the bottom-right corner of paper/wood textures (where Gemini drops a small four-pointed-star watermark) and re-squares.
- **Seamless tiling** — offsets the texture by half-width/half-height and linearly blends the resulting visible center seam against a mirrored copy of the seam region. Result tiles cleanly without a hard edge.
- **WebP export** — quality 80 (wood) / 88 (paper).
- **Alpha-keying Gemini's faux-transparent checkerboard** —
  1. Sample 4 corner patches and learn the actual background luminance band (adaptive — handles checkerboards of any grey value).
  2. Mark pixels that are near-grey AND in the learned luminance band.
  3. Connected-component label with 8-connectivity so adjacent dark and light squares merge.
  4. Keep only components that touch the image border. This preserves white/grey *inside* artwork (polka dots inside a tape, metal highlights inside a staple, white letter cores inside the wordmark).
- **Brand wordmark** — alpha-key + downscale to 1600 px on the long edge.
- **Sheet slicing** — splits the 24-doodle and 5-field-icon sheets into individual sprite PNGs by grid; each cell is alpha-keyed against the off-white paper background and bbox-cropped with 8 px padding.

## Next steps (not done by this script — manual or future tooling)

1. **Vectorize doodles** — run `potrace` (or `vtracer`) on each `web/public/raw/icons/doodles/*.png`, hand-clean in Inkscape, embed as `<symbol>` elements in `web/public/sprites.svg`. Strokes should use `currentColor` so theme tokens drive doodle color.
2. **Extract individual washi-tape, hold-method, and torn-edge sprites** — these are positioned-not-gridded layouts; either crop manually or use `scipy.ndimage.label` on the alpha channel to find each connected opaque region and crop them out automatically. Add a `slice_components(...)` function to `process.py` for this.
3. **Convert torn-edge strips into SVG `<clipPath>` masks** — the four torn-edge styles need to be vectorized as path data, not raster, since they get applied via `mask-image` to arbitrary-sized strip components.
4. **Vectorize the brand wordmark** — currently shipped as a 417 KB PNG with alpha. For zoom crispness, vectorize the letter shapes in Illustrator/Inkscape, preserving fixed letter colors. Save as `sprites.svg#brand-sketchbook`.

## File layout

```
tools/asset-pipeline/
  pyproject.toml      # uv-managed Python project (Pillow + numpy + scipy)
  process.py          # entry point
  verify.py           # quick sanity check on alpha channel coverage
  debug_holds.py      # ad-hoc: prints corner samples + brightness histogram
  README.md           # this file
```
