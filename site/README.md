# site/

The Sketchbook landing page (`index.html`). Single static file, no build step.

- Tailwind via Play CDN (`cdn.tailwindcss.com`).
- Fonts via Google Fonts (Permanent Marker + Gloria Hallelujah + Space Mono + Inter).
- Tokens mirror `docs/design-language.md` — wood-grain desk, lined paper, colored construction-paper wordmark, song-strip mockup with washi tape.
- OS-detected primary CTA, with explicit per-OS links as a fallback.
- Live version tag fetched from `metadata.properties` on the bucket.

## Where it ships

The release workflow uploads this file to `gs://sketchbook-releases/index.html` after Conveyor's `output/*` upload, so it becomes the bucket's default page (configured via the bucket's `MainPageSuffix`).

Conveyor's own `download.html` is preserved at the same path; the landing page links to it as a fallback for the OS picker.

## Iteration

```powershell
start chrome site/index.html  # or your browser of choice
```

No dev server needed. Edit, save, refresh.
