# Low-latency ink device checks

Manual verification plan for the front-buffer pen overlay. **Status: not yet run.** Keep describing this path as *on by default and not yet device-verified* until the results table is completed and every pass criterion below is met.

Behaviour details live in [Pen feel](pen-feel.md). Pure checks (`pen-smoothing-smoke.cjs`, `handwriting-smoke.cjs`, `HandwritingInkTest`) never create a `SurfaceView` and do not cover this path.

## Scope

`LowLatencyInk` attaches one transparent `SurfaceView` per window backed by `androidx.graphics.lowlatency.CanvasFrontBufferedRenderer` (`graphics-core 1.0.4`). `InkView.startLowLatency` activates on pen-down only when:

- Android 10 (API 29) or newer (the switch is hidden below API 29).
- `AppPrefs.LOW_LATENCY_INK` (`input.lowLatencyInk`) is true (default true on fresh installs).
- Tool is Pen, contact is stylus, landing point is on the page (not a sticky note), opacity ≥ 0.99, style is ballpoint (`!StrokeStyle.isBrush`), not ruler-locked, and view is editable.

The page draws the same stroke underneath and owns all saved samples, pressure, undo, and journal entries. The overlay clears ~48 ms after lift (or immediately on `cancel`) and clips predicted tails to 28 screen pixels. If the API is unsupported, `attach` throws, or the surface is not ready, strokes commit silently through the page path.

## Setup

Test on two ARM devices where possible (the APK ships `arm64-v8a` / `armeabi-v7a` only):
1. A stylus tablet (e.g. OnePlus Pad 3 on ColorOS; record the ColorOS version).
2. A finger-only phone on API 29+ with **Draw with a finger** enabled.

Use `./build.sh -n` (do **not** publish with `-p`). For a release-verified claim, repeat on an R8 build (`./build.sh -n -r`), as `graphics-core` has no dedicated ProGuard keep rule. Confirm **Low-latency ink** is on in the pen tool's **Behaviour** page. Prepare a blank finite notebook, infinite canvas, PDF page, page with a sticky note, and a mistake-review **Paper** question.

"Overlay observed" means a fast ballpoint stroke stays visibly closer to the stylus tip than with the switch off and clears its tail at lift; otherwise record **fallback only**.

## Checklist

Run with the switch on unless noted. After every saved stroke, verify path, pressure, start taper, single Undo, and single Redo (the overlay is never an undo step).

1. **Stroke start and end:** Draw dots, ticks, slow lines, and fast loops in light and dark themes (red, blue, black). Confirm clean endpoints, no spikes, no duplicates, and no gap after the ~48 ms handoff wipe.
2. **Prediction overshoot:** Draw fast loops, small letters (`e`/`a`), and sharp zigzags. Confirm tails stay within ~28 px, reversals clear old tails, and pauses/lifts leave no tail.
3. **Pressure and width:** Compare light vs firm pressure, **Thinner when fast**, and **Soft stroke ends** (dots must not vanish); committed ink must match switch-off output.
4. **Page-path-only tools:** Confirm no extra leading edge or ghost for highlighter, pen below 99% opacity, fountain/pencil/marker/calligraphy brushes, eraser modes, hardware eraser/stylus button, laser, ruler-locked strokes, and strokes starting on sticky notes.
5. **Tool changes and hold gestures:** Switch tools mid-stroke (cancels cleanly), test pen-start borrow (Highlighter/Eraser), hold-to-erase (erases cleanly in one undo step), and hold-to-shape (overlay clears on preview/lift; moving cancels shape).
6. **Immediate Undo/Redo:** Undo immediately after lift and after a pause (toolbar and two-finger tap), then Redo; close and reopen to verify no ghost overlay returns.
7. **Rotation and config changes:** Rotate between strokes and mid-stroke (mid-stroke cancels cleanly without saving). Test resize/foldable transitions; if the surface recreates, the following stroke must recover the overlay.
8. **Split view and multi-window:** Write in both panes (shared layer), drag the divider idle and mid-stroke, and resize split-screen windows. Note whether live ink paints over floating chrome/stickies during the stroke and confirm it clears on wipe.
9. **Zoom, pan, and zoom pane:** Write after pinch/pan, cross paper edges on finite pages (clips cleanly), and write in the lower zoom pane (overview must not show a second front-buffer copy).
10. **Palm rejection:** Test resting palm, retroactive palm withdrawal, palm on zoom divider/overview, `ACTION_CANCEL`, and confirm scrolling Library, toolbar taps, and divider drags still work after writing (the overlay must never eat touches).
11. **Writing follow:** Trigger edge-strip glides, touch down mid-glide (stops before sampling), test natural returns, **Next line**, and infinite-canvas **Write a response** columns.
12. **Background and resume:** Home mid-stroke (cancels cleanly) and after a stroke (ink persists; overlay recovers after surface recreation). Verify Library and Settings are never blocked by the transparent surface.
13. **Live toggle:** Toggle off/on while idle and mid-stroke from both Settings and pen Behaviour; verify clean removal/re-attachment and persistence across force-stop.
14. **Fallback:** With the switch off, repeat checks 1, 4, and 6. On API 26–28 (if available), confirm the switch is hidden and normal ink/undo/palm rejection work.
15. **Other surfaces:** Test Music reader annotations (and performance mode), Mistake **Paper** in light and dark themes (dark filter stays on paper; sharing stays white), and read-only neighbour previews.
16. **Cross-page and sticky boundaries:** Draw across two finite pages (prediction stops on span; clean segments; single undo) and from paper onto a sticky note (clean split; no residual overlay on note).

## Results

| Device | Android / OEM skin | Build (`debug`, `build.sh -n`, or `build.sh -n -r`) and version | Stylus or finger | Overlay observed? | Pass/fail | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| OnePlus Pad 3 (or stylus tablet) | | | Stylus | | | |
| Finger-only phone | | | Finger | | | |
| API 26–28 device (optional) | | | | | | |

## Pass criteria

Drop the *not yet device-verified* note from `pen-feel.md`, `AGENTS.md`, and the wiki only when all of the following hold:

- An ARM API 29+ stylus tablet passes every check with **Overlay observed: yes** on the target build (`./build.sh -n -r` or CI release for release claims).
- An ARM API 29+ finger-only phone passes all applicable checks (finger strokes never trigger the overlay; toggling never eats touches).
- Switch-off fallback (check 14) passes on the stylus tablet.
- Zero open failures for wrong committed ink, post-wipe ghosts, lingering tails, undo/redo ghosts, saved cancelled strokes, eaten touches, or unrecovered overlays.
- Known structural behaviours (live ink over floating chrome/stickies before wipe, first stroke after enable/resume, mid-stroke window resize) are noted in the results table, and the table records device, Android version, build, and date.
