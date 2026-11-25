# Display refresh rate

`MainActivity.requestHighRefreshRate` requests the fastest display mode advertised at the current physical resolution via both `preferredDisplayModeId` and `preferredRefreshRate`. There is no 60/120 Hz cap: a 144 Hz panel is requested at 144 Hz across the entire activity, including the Library and editor.

## Caching and invalidation

The selected mode is cached across focus changes to avoid repeated mode enumeration and window relayout whenever a dialog or the software keyboard closes. Returning to the app, display-change notifications, moving to another display, or changing resolution invalidate the cache. Window attributes are only updated when the requested mode actually changes, preventing mode-change relayout loops.

Android treats these values as preferences: system display settings, OEM per-app policies, battery saver, and thermal throttling can still cap the actual refresh rate. Requesting 144 Hz also does not guarantee that every frame meets a 6.94 ms budget.

## Tablet verification

1. Enable the tablet's high refresh rate setting (including any per-app override for Folio) and turn on **Developer options → Show refresh rate**.
2. Open Folio and watch the overlay while scrolling the Library and writing, panning, and zooming in a notebook. On a 144 Hz tablet, confirm 144 Hz during interaction.
3. Open and close dialogs and the keyboard, switch away and back, and rotate the device. Repeat scrolling and writing and confirm the high rate returns without flicker or mode loops.
4. Change the system refresh setting while Folio is open and while backgrounded; confirm Folio tracks the newly exposed modes.
5. On an external display, move Folio between screens and confirm each display uses its own fastest mode at its current resolution.

Use frame profiling separately for dropped frames; the system overlay reports display Hz rather than app FPS.
