# site/

The Sketchbook landing page (`index.html`). Single static file, no build step.

- Tailwind via Play CDN (`cdn.tailwindcss.com`).
- Fonts via Google Fonts (Permanent Marker + Gloria Hallelujah + Space Mono + Inter).
- Tokens mirror `docs/design-language.md` — wood-grain desk, lined paper, colored construction-paper wordmark, song-strip mockup with washi tape.
- OS-detected primary CTA, with explicit per-OS links as a fallback.
- Live version tag fetched from `metadata.properties` on the bucket.

## Where it ships

GitHub Pages, deployed from `main` by `.github/workflows/pages.yml`. Live at:
<https://srmarlins.github.io/sketchbook/>

Binaries continue to live in `gs://sketchbook-releases/` for Conveyor's anonymous auto-update fetch. The landing page links into the bucket for downloads. Conveyor's own `download.html` is preserved at the bucket too as the OS-detect fallback.

Why not the bucket itself? GCS only honors `MainPageSuffix` when the bucket is fronted by a custom domain (CNAME to `c.storage.googleapis.com`); raw `storage.googleapis.com/<bucket>/` URLs always serve an XML listing.

## Iteration

```powershell
start chrome site/index.html  # or your browser of choice
```

No dev server needed. Edit, save, refresh.
