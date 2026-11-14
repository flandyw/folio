# Interface icons

`app/src/main/java/com/folio/notes/ui/FolioIcons.kt` is the shared Compose catalogue.
It vendors paths from [Lucide 1.73.0](https://github.com/lucide-icons/lucide/tree/1.73.0/icons)
(commit `55782beff3a0133df79c417eb68f96e8f19e2404`); each declaration names its
upstream SVG. The full Lucide/Feather license ships in `assets/licenses/lucide.txt`.
There is no runtime download or additional Android dependency.

SVG primitives are expressed as path data, with explicit arc flags for Compose's
parser. Preserve curves when closing paths. Keep Lucide's 24 × 24 viewport,
2-unit strokes, round caps and round joins; do not rescale glyphs to their bounds.
Existing call sites control displayed size, tint and accessibility descriptions.
Directional icons keep their RTL mirroring. Selected stars, bookmarks and flags
fill the same contour as their outline state.

The marker, laser pointer, handwriting, PDF and stoplight drawings are Folio
custom glyphs on the same grid and stroke settings. Add custom drawings only when
Lucide has no appropriate symbol. The Folio brand mark remains separate.

Run `node tools/icons-smoke.cjs` for catalogue coverage, style, state contours and
RTL checks, followed by the repository's canonical `./build.sh -p` check. Inspect
new glyphs at toolbar size in both themes on a device.
