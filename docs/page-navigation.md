# Page navigation

## Explicit navigation

Editor arrows, the page picker, search hits, PDF outlines, and new-page actions all route through one `jumpTo` path. Relative arrows read live ViewModel state so rapid taps between compositions each step once.

`PageNavigation` separates explicit jumps from the continuous column's most-visible-page tracking: a request waits for layout and holds the destination selected until the viewport moves (essential for short pages and the last page). New requests supersede older ones without spawning competing scroll coroutines. Corner arrows use a single `combinedClickable`: holding jumps to the first/last page without also firing a single-page tap on release, with accessibility labels for both actions.

## Single-page swipe (`SinglePageSheet`)

`SinglePageSheet` uses a keyed lazy `HorizontalPager` with strict gesture gating:

- Horizontal touches claim a page turn only after travelling 2× touch slop and moving at least 2× as horizontally as vertically, leaving vertical scrolling, pen input, finger writing, pinching, and editing untouched.
- A zoomed page can still swipe to turn, but only from a fresh drag that *started* flush against that edge (`InkView.onPageEdges`), with a stricter claim distance, flatter angle, 40% width threshold (vs 30% fitted), and faster flick, so panning into an edge never accidentally turns the page.
- Each gesture turns at most one page. Distance and density-scaled velocity decide release, a reversing flick retracts, edges show bounded resistance, fresh drags catch settling sheets, and new destinations cancel settling animations. Adjacent and moving sheets are non-editable and hidden from accessibility; each newly selected page opens fitted.
- Sticky notes work identically to the continuous column: fitted bounds include notes extending past the paper (`InkView.workspace()`, shared with incoming neighbours), zoomed panning stops at the notes' outer edge, and touches on a resize grip or on a note with the sticky/text tool never turn the page.

## Cross-page object carrying

In the continuous column, photos (hand tool), text boxes (text tool), and lasso selections carry smoothly across pages: hold near the top or bottom edge to auto-scroll, then drop onto another page (or its nearest gap). The grab offset stays intact, the destination writes on its active layer before clearing the source, each page undoes its own half, and cancelled drags leave originals untouched. Resize/rotate handles stay local, and single-page, music, and infinite-canvas layouts keep local dragging.

## Verification

- **Automated:** `node tools/page-navigation-smoke.cjs` and `./build.sh -p`.
- **On device:**
  - Drag a photo, text box, and mixed lasso selection across multiple pages via edge auto-scroll (zoomed, panned, mixed PDF sizes, and dropping back on source); test cancellation, locked/hidden layers, per-page undo/redo, and reopening.
  - In one-page view, create/drag/resize/type/draw on sticky notes, extend one past the paper edge to confirm refitting, and verify swipes on bare space still turn pages.
  - Rapidly alternate arrows, hold to jump to ends, toggle continuous/one-page layouts, and add/insert/duplicate/delete pages.
  - Test slow drags, flicks, reversed drags, edge resistance, catching a settling swipe, rotation mid-drag, zoomed edge-pan vs deliberate edge-swipe, and TalkBack focus isolation.

See [Continuous document editor](continuous-editor.md) for the separate continuous camera, page-ID anchors, and page browser.
