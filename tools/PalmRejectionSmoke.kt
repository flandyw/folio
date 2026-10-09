package com.folio.notes

import com.folio.notes.PalmRejection.Action.*

private fun mask(vararg ids: Int) = ids.fold(0) { bits, id -> bits or (1 shl id) }

private class Trace(val stylus: StylusActivity = StylusActivity()) {
    val policy = PalmRejection(stylus)
    fun event(action: PalmRejection.Action, ids: Int, pen: Int = 0, touch: Int = ids and pen.inv(),
              id: Int = Integer.numberOfTrailingZeros(ids), time: Long = 0L, canceled: Boolean = false,
              sent: Int = ids, cancel: Boolean = false, navigation: Boolean = false) {
        policy.route(action, ids, pen, touch, id, time, canceled, navigation)
        check(policy.dispatchMask == sent && policy.cancel == cancel) {
            "$action id=$id: expected mask=$sent cancel=$cancel, got mask=${policy.dispatchMask} cancel=${policy.cancel}"
        }
    }
}

fun main() {
    var checks = 0
    fun scenario(name: String, run: () -> Unit) {
        try { run() } catch (failure: Throwable) { throw AssertionError(name, failure) }
        checks++
        println("PASS $name")
    }
    scenario("Finger navigation before any pen, including multi-touch and reordered IDs") {
        Trace().apply {
            event(DOWN, mask(7))
            event(POINTER_DOWN, mask(7, 2), id = 2)
            event(MOVE, mask(2, 7))
            event(POINTER_UP, mask(2, 7), id = 7)
            event(MOVE, mask(2))
            event(UP, mask(2))
        }
    }
    scenario("Palm first, pen takeover cancels the old gesture and keeps pen pressure samples routable") {
        Trace().apply {
            event(DOWN, mask(4))
            event(MOVE, mask(4))
            stylus.contact(20, true)
            event(POINTER_DOWN, mask(4, 12), pen = mask(12), id = 12, time = 20, sent = mask(12), cancel = true)
            event(MOVE, mask(12, 4), pen = mask(12), time = 30, sent = mask(12))
            // Android rejects the palm without canceling the writing tip.
            event(POINTER_UP, mask(12, 4), pen = mask(12), id = 4, time = 40, canceled = true, sent = mask(12))
            event(MOVE, mask(12), pen = mask(12), time = 50)
            stylus.contact(60, false)
            event(UP, mask(12), pen = mask(12), time = 60)
        }
    }
    scenario("Pen lifts first: resting palm cannot return even after the grace period expires") {
        Trace().apply {
            stylus.contact(0, true)
            event(DOWN, mask(9), pen = mask(9))
            event(POINTER_DOWN, mask(9, 3), pen = mask(9), id = 3, sent = mask(9))
            stylus.contact(100, false)
            event(POINTER_UP, mask(3, 9), pen = mask(9), id = 9, time = 100, sent = mask(9))
            event(MOVE, mask(3), time = 2000, sent = 0)
            event(UP, mask(3), time = 2100, sent = 0)
            event(DOWN, mask(3), time = 2200)
        }
    }
    scenario("Hover cancels a started palm and preserves its rejection after EXIT") {
        Trace().apply {
            event(DOWN, mask(5))
            stylus.hover(10, true)
            check(policy.rejectTouches())
            stylus.hover(20, false)
            check(stylus.isRecent(21))
            event(MOVE, mask(5), time = 1000, sent = 0)
            event(UP, mask(5), time = 1100, sent = 0)
            event(DOWN, mask(5), time = 1200)
        }
    }
    scenario("A stationary hovering or touching pen stays protected beyond the grace interval") {
        val stylus = StylusActivity()
        stylus.hover(0, true)
        check(stylus.isRecent(10000))
        stylus.hover(10000, false)
        check(stylus.isRecent(10499) && !stylus.isRecent(10500))
        stylus.contact(11000, true)
        check(stylus.isRecent(20000))
        stylus.contact(20000, false)
        check(stylus.isRecent(20499) && !stylus.isRecent(20500))
    }
    scenario("Proximity applies on DOWN and stays latched across late MOVE and UP") {
        Trace().apply {
            stylus.record(100)
            event(DOWN, mask(1), time = 110, sent = 0)
            event(MOVE, mask(1), time = 1000, sent = 0)
            event(UP, mask(1), time = 1100, sent = 0)
            event(DOWN, mask(1), time = 1200)
        }
    }
    scenario("A newly landed finger can start after grace while an old palm remains quarantined") {
        Trace().apply {
            stylus.record(100)
            event(DOWN, mask(1), time = 110, sent = 0)
            event(POINTER_DOWN, mask(1, 6), id = 6, time = 1000, sent = mask(6))
            event(POINTER_UP, mask(1, 6), id = 1, time = 1100, sent = mask(6))
            event(UP, mask(6), time = 1200)
        }
    }
    scenario("Canceled touch in a pinch cancels the whole gesture, with no surviving release") {
        Trace().apply {
            event(DOWN, mask(0))
            event(POINTER_DOWN, mask(0, 1), id = 1)
            event(POINTER_UP, mask(0, 1), id = 1, canceled = true, sent = 0, cancel = true)
            event(MOVE, mask(0), time = 1000, sent = 0)
            event(UP, mask(0), time = 1100, sent = 0)
        }
        val chord = TouchChord(10f)
        chord.down(0, 0f, 0f, 0)
        chord.join(1, 0f, 0f, 10)
        chord.reset()
        check(chord.finish(100) == 0)
    }
    scenario("Canceled stylus never emits a committing UP") {
        Trace().apply {
            event(DOWN, mask(8), pen = mask(8))
            event(POINTER_DOWN, mask(8, 1), pen = mask(8), id = 1, sent = mask(8))
            event(POINTER_UP, mask(8, 1), pen = mask(8), id = 8, canceled = true, sent = 0, cancel = true)
            event(UP, mask(1), time = 1000, sent = 0)
        }
    }
    scenario("Legacy ACTION_CANCEL works without FLAG_CANCELED and permits the next gesture") {
        Trace().apply {
            event(DOWN, mask(0))
            event(CANCEL, mask(0), sent = 0, cancel = true)
            event(MOVE, mask(0), sent = 0)
            event(DOWN, mask(2))
            event(UP, mask(2))
        }
    }
    scenario("Normal pen UP in a mixed stream commits exactly once") {
        Trace().apply {
            event(DOWN, mask(2), pen = mask(2))
            event(POINTER_DOWN, mask(2, 0), pen = mask(2), id = 0, sent = mask(2))
            event(POINTER_UP, mask(2, 0), pen = mask(2), id = 2, sent = mask(2))
            event(UP, mask(0), sent = 0)
        }
    }
    scenario("System-only mode still isolates the tip and honors Android cancellation") {
        Trace(StylusActivity().apply { graceMs = 0 }).apply {
            stylus.hover(0, true)
            event(DOWN, mask(0))
            event(POINTER_DOWN, mask(0, 31), pen = mask(31), id = 31, sent = mask(31), cancel = true)
            event(POINTER_UP, mask(0, 31), pen = mask(31), id = 0, canceled = true, sent = mask(31))
            event(UP, mask(31), pen = mask(31), canceled = true, sent = 0, cancel = true)
            event(DOWN, mask(0))
        }
    }
    scenario("Mouse remains usable near a pen; hover never cancels a mouse gesture") {
        Trace().apply {
            stylus.hover(0, true)
            event(DOWN, mask(0), touch = 0)
            check(!policy.rejectTouches())
            event(MOVE, mask(0), touch = 0)
            event(UP, mask(0), touch = 0)
        }
    }
    scenario("Lifecycle clears proximity and stale ownership; a new DOWN replaces a missing UP") {
        Trace().apply {
            event(DOWN, mask(1))
            event(DOWN, mask(7), cancel = true)
            policy.reset()
            stylus.hover(0, true)
            stylus.clear()
            check(!stylus.isRecent(1))
            event(DOWN, mask(3))
        }
    }
    scenario("Tip contact leaves hover range even if the device omitted HOVER_EXIT") {
        val stylus = StylusActivity()
        stylus.hover(0, true)
        stylus.contact(10, true)
        stylus.contact(20, false)
        check(!stylus.isRecent(1000))
    }
    scenario("Filtering rewrites actions and reindexes the changing admitted pointer") {
        check(filteredPointerAction(5, 1, 0) == 0)
        check(filteredPointerAction(6, 1, 0) == 1)
        check(filteredPointerAction(5, 1, -1) == 2)
        check(filteredPointerAction(6, 1, -1) == 2)
        check(filteredPointerAction(5, 2, 1) == 5 or (1 shl 8))
        check(filteredPointerAction(6, 2, 0) == 6)
        check(filteredPointerAction(2, 2, 0) == 2)
    }
    scenario("A pinch already underway survives hover, grace and a remaining finger") {
        Trace().apply {
            event(DOWN, mask(2))
            event(POINTER_DOWN, mask(2, 7), id = 7)
            stylus.hover(100, true)
            check(!policy.rejectTouches())
            event(MOVE, mask(2, 7), time = 110)
            event(POINTER_UP, mask(2, 7), id = 2, time = 120)
            event(MOVE, mask(7), time = 130)
            event(UP, mask(7), time = 140)
        }
    }
    scenario("Two fresh fingers can pinch during pen hover after deliberate span movement") {
        Trace().apply {
            stylus.hover(0, true)
            event(DOWN, mask(3), time = 10, sent = 0)
            event(POINTER_DOWN, mask(3, 12), id = 12, time = 30, sent = 0)
            val pinch = PinchNavigation(8f)
            pinch.down(3, 0f, 0f, 10)
            pinch.down(12, 100f, 0f, 30)
            pinch.move(3, -5f, 0f); pinch.move(12, 105f, 0f)
            check(pinch.intent() == 0)
            pinch.move(3, -12f, 0f); pinch.move(12, 112f, 0f)
            check(pinch.intent() == mask(3, 12))
            check(policy.startNavigation(pinch.intent()))
            event(MOVE, mask(3, 12), time = 100, navigation = true)
            check(!policy.rejectTouches())
            event(POINTER_UP, mask(3, 12), id = 12, time = 110, navigation = true)
            event(UP, mask(3), time = 120, navigation = true)
        }
    }
    scenario("Pinching immediately after pen-up needs no grace timeout or retry") {
        Trace().apply {
            stylus.contact(0, true)
            event(DOWN, mask(9), pen = mask(9))
            stylus.contact(100, false)
            event(UP, mask(9), pen = mask(9), time = 100)
            event(DOWN, mask(2), time = 110, sent = 0)
            event(POINTER_DOWN, mask(2, 4), id = 4, time = 120, sent = 0)
            check(policy.startNavigation(mask(2, 4)))
            event(MOVE, mask(2, 4), time = 150, navigation = true)
            event(POINTER_UP, mask(2, 4), id = 4, time = 160, navigation = true)
            event(UP, mask(2), time = 170, navigation = true)
        }
    }
    scenario("A stationary pair, small drift and two-finger pan do not bypass proximity") {
        val pinch = PinchNavigation(8f)
        pinch.down(0, 0f, 0f, 0)
        pinch.down(1, 100f, 0f, 20)
        check(pinch.intent() == 0)
        pinch.move(0, -5f, 0f)
        check(pinch.intent() == 0)
        pinch.move(0, 30f, 0f); pinch.move(1, 130f, 0f)
        check(pinch.intent() == 0)
        pinch.reset()
        pinch.down(0, 0f, 0f, 0)
        pinch.down(1, 100f, 0f, 1000)
        pinch.move(0, -30f, 0f); pinch.move(1, 130f, 0f)
        check(pinch.intent() == 0)
    }
    scenario("An anchored-finger pinch works in either zoom direction") {
        val pinch = PinchNavigation(8f)
        pinch.down(0, 0f, 0f, 0)
        pinch.down(1, 100f, 0f, 20)
        pinch.move(1, 125f, 0f)
        check(pinch.intent() == mask(0, 1))
        pinch.move(1, 75f, 0f)
        check(pinch.intent() == mask(0, 1))
    }
    scenario("A rejected writing palm cannot join a fresh finger to become a pinch") {
        Trace().apply {
            stylus.contact(0, true)
            event(DOWN, mask(9), pen = mask(9))
            event(POINTER_DOWN, mask(9, 1), pen = mask(9), id = 1, sent = mask(9))
            stylus.contact(100, false)
            event(POINTER_UP, mask(9, 1), pen = mask(9), id = 9, time = 100, sent = mask(9))
            event(POINTER_DOWN, mask(1, 2), id = 2, time = 110, sent = 0)
            check(!policy.startNavigation(mask(1, 2)))
            event(MOVE, mask(1, 2), time = 130, sent = 0)
        }
    }
    scenario("Android cancellation and pen takeover still stop an admitted pinch") {
        Trace().apply {
            stylus.hover(0, true)
            event(DOWN, mask(1), time = 10, sent = 0)
            event(POINTER_DOWN, mask(1, 2), id = 2, time = 20, sent = 0)
            check(policy.startNavigation(mask(1, 2)))
            event(MOVE, mask(1, 2), time = 30, navigation = true)
            event(POINTER_UP, mask(1, 2), id = 2, time = 40, canceled = true, sent = 0, cancel = true, navigation = true)
            event(UP, mask(1), time = 50, sent = 0)
            event(DOWN, mask(1), time = 100, sent = 0)
            event(POINTER_DOWN, mask(1, 2), id = 2, time = 110, sent = 0)
            check(policy.startNavigation(mask(1, 2)))
            event(MOVE, mask(1, 2), time = 120, navigation = true)
            stylus.contact(130, true)
            event(POINTER_DOWN, mask(1, 2, 9), pen = mask(9), id = 9, time = 130, sent = mask(9), cancel = true)
        }
    }
    scenario("Navigation permission passes both routers and ends with the physical stream") {
        val stylus = StylusActivity()
        stylus.hover(0, true)
        stylus.attachNavigationSurface()
        check(stylus.canPinchNavigate)
        stylus.beginNavigation(7, 100)
        val child = Trace(stylus)
        child.event(DOWN, mask(1), time = 100, navigation = stylus.isNavigation(7, 100))
        child.event(POINTER_DOWN, mask(1, 2), id = 2, time = 100, navigation = stylus.isNavigation(7, 100))
        child.event(MOVE, mask(1, 2), time = 110, navigation = stylus.isNavigation(7, 100))
        check(!child.policy.rejectTouches())
        check(!stylus.isNavigation(8, 100) && !stylus.isNavigation(7, 101))
        stylus.endNavigation()
        check(!stylus.isNavigation(7, 100))
        stylus.detachNavigationSurface()
        check(!stylus.canPinchNavigate)
    }
    scenario("Child AndroidView handoff does not cancel the parent Compose pinch") {
        val stylus = StylusActivity()
        val captured = stylus.cancellationSerial
        stylus.canceled(window = false)
        check(stylus.cancellationSerial == captured)
        stylus.canceled(window = true)
        check(stylus.cancellationSerial != captured)
    }
    scenario("Hover between the first and second fresh fingers can still recognize a pinch") {
        Trace().apply {
            event(DOWN, mask(1), time = 0)
            stylus.hover(10, true)
            check(policy.rejectTouches())
            event(POINTER_DOWN, mask(1, 2), id = 2, time = 20, sent = 0)
            check(policy.startNavigation(mask(1, 2)))
            event(MOVE, mask(1, 2), time = 30, navigation = true)
        }
    }
    scenario("Touch in another window cannot navigate while the tip is still down") {
        Trace().apply {
            stylus.contact(0, true)
            event(DOWN, mask(1), time = 10, sent = 0)
            event(POINTER_DOWN, mask(1, 2), id = 2, time = 20, sent = 0)
            check(!policy.startNavigation(mask(1, 2)))
            event(MOVE, mask(1, 2), time = 30, sent = 0)
        }
    }
    println("Palm rejection: $checks scenarios passed.")
}
