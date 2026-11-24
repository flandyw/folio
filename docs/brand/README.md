# Folio brand

The mark is a stack of three note pages (a teal back sheet, a cream middle sheet, and a paper front sheet with a terracotta corner tab and three handwriting lines) set on a powder-blue tile.

| File | Use |
| --- | --- |
| `folio-icon.svg` | Master artwork (including tile). Edit this first. |
| `app/src/main/res/drawable/ic_folio_mark.xml` | Same shapes without the tile (launcher foreground). |
| `app/src/main/java/com/folio/notes/ui/FolioLogo.kt` | In-app Compose logo, enabling splash animation. |
| `app/src/main/res/drawable/ic_folio_mono.xml` | Monochrome themed-icon layer. |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive launcher icon. |

The artwork uses Android's 108-unit adaptive-icon grid with all shapes inside the central 72 units, so one coordinate set drives the SVG, launcher, and app. Update all four files whenever a shape changes.

## Colours

| Role | Hex |
| --- | --- |
| Tile | `#B6D0DE` |
| Back sheet | `#467D8B` |
| Middle sheet | `#F1E0CD` |
| Paper | `#FBF8F3` (app light background) |
| Tab | `#AD4B2D` (app default accent) |
| Lines | `#8BA8B0` |
| Shadow | `#1F3A44` at 14% |

## Rules

- Keep these fixed colours everywhere; the logo never follows the theme or user accent. Inside the app, render `FolioLogo` (`ui/FolioLogo.kt`) rather than a tinted icon.
- Set the wordmark "Folio" in the platform serif, semi-bold, matching the splash screen.
- Leave clear space of at least a quarter of the tile's width around the mark, and never rotate, recolour, or outline it.
