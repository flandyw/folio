# Interface icons

`app/src/main/java/com/folio/notes/ui/FolioIcons.kt` is the shared Compose icon catalogue. It vendors path data from [Lucide 1.73.0](https://github.com/lucide-icons/lucide/tree/1.73.0/icons) (commit `55782beff3a0133df79c417eb68f96e8f19e2404`), with each declaration naming its upstream SVG. The full Lucide/Feather licence ships in `app/src/main/assets/licenses/lucide.txt`; there are no runtime downloads or extra Android dependencies.

## Authoring rules

- Express SVG primitives as path data with explicit arc flags for Compose's parser, and preserve curves when closing paths.
- Keep Lucide's 24 × 24 viewport, 2-unit strokes, round caps, and round joins; never rescale individual glyphs to their visible bounds.
- Call sites control displayed size, tint, and accessibility labels.
- Preserve RTL mirroring on directional icons, and keep filled states (stars, bookmarks, flags) on the exact contour of their outline counterparts.
- Folio custom glyphs (marker, laser pointer, handwriting, PDF, stoplight) follow the same grid and stroke rules. Add a custom glyph only when Lucide has no suitable symbol. The Folio brand mark remains separate.

## Verification

Run `node tools/icons-smoke.cjs` to check catalogue coverage, stroke style, state contours, and RTL rules, then run `./build.sh -p`. Inspect new glyphs at toolbar size in both light and dark themes on a device.
