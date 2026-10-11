package com.folio.notes

import kotlin.math.abs

fun main() {
    val ids = (0..9).map { "page-$it" }
    val nav = PageNavigation()
    val oldLayout = Any()
    fun observe(first: Int, offset: Int = 0, current: Int = first, forward: Boolean = true,
                visible: Set<Int> = setOf(first), layout: Any = Any()) =
        nav.observe(ids, PageNavigation.Viewport(PageNavigation.Position(first, offset), visible, forward, current, layout))

    nav.request(ids[4], oldLayout)
    check(observe(4, 55, current = 5, forward = false, visible = setOf(4, 5), layout = oldLayout) == null) {
        "A stale layout already containing the destination was acknowledged before the jump"
    }
    check(observe(4, current = 5, visible = setOf(4, 5)) == 4)
    nav.request(ids[3], oldLayout)
    check(observe(1) == null) { "Old layout undid a button turn" }
    nav.request(ids[4], oldLayout)
    check(observe(3) == null) { "Earlier button request won over the latest tap" }
    check(observe(4, current = 5, visible = setOf(4, 5)) == 4) { "A short target was skipped for its larger neighbour" }
    repeat(5) { check(observe(4, current = 5, visible = setOf(4, 5)) == 4) }
    check(observe(4, 15, current = 5) == 5) { "User scrolling did not resume normal selection" }

    nav.request(ids.last(), oldLayout)
    check(observe(8, 30, current = 8, forward = false, visible = setOf(8, 9)) == 9)
    check(observe(8, 30, current = 8, forward = false, visible = setOf(8, 9)) == 9)
    check(observe(8, 25, current = 8) == 8)

    nav.request(ids[4], oldLayout)
    val reordered = ids.toMutableList().apply { add(2, removeAt(4)) }
    check(nav.observe(reordered, PageNavigation.Viewport(PageNavigation.Position(2, 0), setOf(2), true, 3, Any())) == 2)
    val deleted = reordered.filterNot { it == ids[4] }
    check(nav.observe(deleted, PageNavigation.Viewport(PageNavigation.Position(2, 0), setOf(2), true, 2, Any())) == 2)

    // Distance, quick flicks, deliberate reversals, and identical physical gestures across densities.
    check(PageSwipe.target(4, 10, -300f, 0f, 1000f, 1f) == 5)
    check(PageSwipe.target(4, 10, 300f, 0f, 1000f, 1f) == 3)
    check(PageSwipe.target(4, 10, -100f, -1000f, 1000f, 1f) == 5)
    check(PageSwipe.target(4, 10, -10f, -2000f, 1000f, 1f) == 4)
    check(PageSwipe.target(4, 10, -100f, -100f, 1000f, 1f) == 4)
    check(PageSwipe.target(4, 10, -400f, 1000f, 1000f, 1f) == 4) { "Reversing flick must retract the turn" }
    check(PageSwipe.target(4, 10, -200f, -2000f, 2000f, 2f) == 5)
    check(PageSwipe.target(0, 10, 500f, 1000f, 1000f, 1f) == 0)
    check(PageSwipe.target(9, 10, -500f, -1000f, 1000f, 1f) == 9)
    check(PageSwipe.target(0, 1, -500f, -1000f, 1000f, 1f) == 0)
    check(PageSwipe.target(4, 10, -5000f, -10000f, 1000f, 1f) == 5) { "One gesture skipped pages" }
    check(PageSwipe.offset(-5000f, 1000f, 4, 10) == -1000f)
    check(PageSwipe.offset(0f, 1000f, 0, 10) == 0f)
    val edge = PageSwipe.offset(500f, 1000f, 0, 10)
    check(edge > 0f && edge < 120f)
    check(abs(PageSwipe.edgeDistance(edge, 1000f) - 500f) < .01f) { "Catching an edge bounce jumps the sheet" }
    check(abs(PageSwipe.offset(-500f, 1000f, 9, 10) + edge) < .01f)
    check(PageSwipe.offset(50000f, 1000f, 0, 10) < 120f)
    println("Page navigation smoke passed: request races, short pages, ends, reorder/delete, flicks, reversals, density and bounded edge resistance.")
}
