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
after directional touch slop; vertical movement, pen input, finger writing, zooming and
editing retain their page interactions. Each gesture can move only one page. Distance and
density-scaled velocity decide release; a reversing flick retracts the turn. The edges have
bounded resistance. A fresh drag can catch a settling sheet. A changed destination cancels
the old animation, and swipe completion checks its original page before selecting anything.
Adjacent and moving sheets are blocked from editing and hidden from accessibility. Each
newly selected page starts fitted, including pages kept alive by pager prefetch.

Run `node tools/page-navigation-smoke.cjs` for request races, stale layouts already showing
the destination, short pages, document ends, reorder/delete and gesture threshold checks.
Run `./build.sh -p` for the signed experimental build.

## Device check

- In continuous view, rapidly alternate arrows, tap Next repeatedly, and turn during a fling.
  Each tap advances one page from the latest selection. Repeat with mixed short/tall PDF pages.
- Hold each arrow, then release; it stays at the end. The next tap responds immediately.
- Toggle layouts on a middle page and at the document end. Add, insert, duplicate and delete
  pages in one-page mode; search, outline and thumbnails land on the requested page.
- Slowly drag both ways, release below/above a quarter width, flick, reverse a long drag,
  and drag beyond the first/last page. Sheets follow continuously; one gesture never skips pages.
- Catch a settling swipe and reverse it. Tap an arrow or pick a different page during settling;
  the new destination wins. Resize/rotate during a drag; the sheet does not remain displaced.
- Pinch a fitted page, pan while zoomed, then use an arrow and return. The new page is fitted.
  Check pen writing, palms, finger writing, selections, text and image editing produce no turns.
- With TalkBack, adjacent sheets are absent from focus; arrows expose disabled states and the
  first/last-page long-click actions. Music scores and infinite canvases retain their own layouts.
