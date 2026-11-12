# Folio brand

The mark is a stack of three note pages: a teal back sheet, a cream middle sheet and a paper front page with a terracotta corner tab and three handwriting lines, on a powder-blue tile.

| File | Use |
| --- | --- |
| `folio-icon.svg` | Master artwork (tile included). Edit this first. |
| `app/src/main/res/drawable/ic_folio_mark.xml` | The same shapes without the tile: launcher foreground. |
| `app/src/main/java/com/folio/notes/ui/FolioLogo.kt` | The in-app logo, drawn in Compose so the splash can animate it. |
| `app/src/main/res/drawable/ic_folio_mono.xml` | Themed-icon (monochrome) layer. |
| `app/src/main/res/mipmap-anydpi-v26/ic_launcher.xml` | Adaptive launcher icon. |

The artwork is drawn in Android's 108-unit adaptive-icon space with everything inside the central 72, so one set of coordinates serves the SVG, the launcher and the app. Change a shape in all four files.

## Colours

| Role | Hex |
| --- | --- |
| Tile | `#B6D0DE` |
| Back sheet | `#467D8B` |
| Middle sheet | `#F1E0CD` |
| Paper | `#FBF8F3` (the app's light background) |
| Tab | `#AD4B2D` (the app's default accent) |
| Lines | `#8BA8B0` |
| Shadow | `#1F3A44` at 14% |

## Rules

- The logo keeps these colours everywhere; it never takes the theme or the user's accent. In the app use `FolioLogo` (`ui/FolioLogo.kt`), not a tinted icon.
- The wordmark is "Folio" in the platform serif, semi-bold, as on the splash.
- Keep clear space of a quarter of the tile's width around it, and don't rotate, recolour or outline it.
