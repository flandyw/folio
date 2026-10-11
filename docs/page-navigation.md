# Page navigation

The editor's arrows, page picker, search results, outline and new-page actions use one
`jumpTo` path. Relative arrows read the live ViewModel position, so multiple taps between
compositions each advance once. `PageNavigation` keeps explicit selection separate from
the continuous column's most-visible-page selection. A request waits for a new layout,
then keeps the destination selected until the viewport moves. This matters for short pages
and the last page, which may never occupy most of the viewport. Requests replace earlier
requests; they do not launch competing scroll coroutines or wait for an absent column.

The corner arrows use a single `combinedClickable` for tap and hold. Holding jumps to an
end, and releasing does not also turn a page. Both actions have accessibility labels.

`SinglePageSheet` uses a keyed, lazy `HorizontalPager`. A horizontal touch claims the drag
only once it has travelled twice the touch slop and is at least twice as horizontal as vertical;
vertical movement, pen input, finger writing, pinching and editing retain their page
interactions. A zoomed page can be swiped too, but only by a fresh drag that *began* with the page
already flush against the side being dragged past (`InkView.onPageEdges`), so panning to an edge
and carrying on never turns it; zoomed drags also need a longer claim, a more horizontal angle,
40% of the width (30% fitted) and a faster, longer flick. Each gesture can move only one page.
Distance and density-scaled velocity decide release; a reversing flick retracts the turn. The edges have
bounded resistance. A fresh drag can catch a settling sheet. A changed destination cancels
the old animation, and swipe completion checks its original page before selecting anything.
Adjacent and moving sheets are blocked from editing and hidden from accessibility. Each
newly selected page starts fitted, including pages kept alive by pager prefetch.

Sticky notes work in the one-page view like the scrolling column: the sticky tool, typing, the note menu
(its Draw action switches to the pen) and ink written on a note. The fitted view covers the paper plus any
note hanging past it (`InkView.workspace()`, shared by the neighbour sliding in during a swipe so it never refits on landing), so a note placed beside the
paper stays visible and reachable, and zoomed panning stops at the notes' edge. A finger that lands on a focused note's resize grip (any tool), or on a note with the sticky or text tool,
is resizing, moving or typing in it and never turns the page.

Run `node tools/page-navigation-smoke.cjs` for request races, stale layouts already showing
the destination, short pages, document ends, reorder/delete and gesture threshold checks.
Run `./build.sh -p` for the signed experimental build.

## Device check

- Sticky tool in one-page view: tap and drag to make notes, drag one by finger and by pen without the page
  turning, resize from the grip, type, change paper, Draw from the menu. Drag a note past the paper edge: the page
  refits so it stays in view; delete it and the fit returns. Swipes on bare space still turn the page.
- In continuous view, rapidly alternate arrows, tap Next repeatedly, and turn during a fling.
  Each tap advances one page from the latest selection. Repeat with mixed short/tall PDF pages.
- Hold each arrow, then release; it stays at the end. The next tap responds immediately.
- Toggle layouts on a middle page and at the document end. Add, insert, duplicate and delete
  pages in one-page mode; search, outline and thumbnails land on the requested page.
- Slowly drag both ways, release below/above a quarter width, flick, reverse a long drag,
  and drag beyond the first/last page. Sheets follow continuously; one gesture never skips pages.
- Catch a settling swipe and reverse it. Tap an arrow or pick a different page during settling;
  the new destination wins. Resize/rotate during a drag; the sheet does not remain displaced.
- Zoom in and pan sideways until the page stops: a further deliberate swipe turns it, but keep
  panning in one stroke past the edge and nothing turns; a diagonal or short drag at an edge pans.
  Dragging fitted pages diagonally or with small flicks does not turn them.
- Pinch a fitted page, pan while zoomed, then use an arrow and return. The new page is fitted.
  Check pen writing, palms, finger writing, selections, text and image editing produce no turns.
- With TalkBack, adjacent sheets are absent from focus; arrows expose disabled states and the
  first/last-page long-click actions. Music scores and infinite canvases retain their own layouts.
