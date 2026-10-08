# Palm rejection

Folio uses Android's public stylus input contract on every device, including the OnePlus Pad 3
and Stylo 2. There is no device-name switch, private OnePlus API, contact-width cutoff or guessed
screen DPI. The system identifies palms; Folio also keeps touch out of an active pen stream and
provides proximity protection when hover or recent pen contact is available.

`PalmRejection.kt` holds the pure pointer lifetime rules. `StylusInputRouter.kt` applies them before
`MainActivity` dispatches to Compose, and before a standalone `InkView` dispatches to any tool.
Pointers are tracked by ID, never by their current index. Public `MotionEvent.obtain`/`addBatch`
calls preserve pressure, tool types and all historical axes while translating the tip's POINTER_DOWN/UP into DOWN/UP when
a palm arrived first or lifts last. Clean single-pointer moves use the original event without a copy.

- ACTION_CANCEL abandons the whole logical gesture on every supported Android version.
- On Android 13+, ACTION_POINTER_UP with FLAG_CANCELED abandons that pointer. A rejected palm
  cannot cancel an admitted pen. A canceled touch in a pinch/chord cancels the touch gesture,
  and a canceled tip never commits its stroke.
- Pen arrival cancels an earlier touch preview and starts a complete, isolated pen gesture. The
  same rule covers ink, eraser, lasso, text/image moves, sticky notes and answer/mark rectangles.
- With proximity protection enabled, a hovering or touching pen blocks touchscreen contacts.
  HOVER_EXIT retains the configured grace interval: Android also sends EXIT before tip DOWN.
  Rejected contacts stay rejected until lifted, even if the interval expires or the pen leaves.
- The existing Palm rejection slider controls that additional protection throughout the main
  window, reference panes, toolbar and guarded popup controls. Zero means system-only protection;
  Android cancellation and tip isolation remain enabled. Mouse input remains available near a pen.
- A shared cancellation serial lets Compose navigation distinguish cancellation from release,
  preventing a rejected gesture from flinging, committing a bottom pull, or turning a music sheet.
  Activity pause clears proximity; detaching, rebinding or blocking an ink surface cancels its preview.

Input data changes neither the saved page format nor journal/undo invariants. Actions already
completed before a pen enters range cannot be classified retroactively. With no detected pen,
finger drawing/navigation remains available; only Android can identify a palm at that point.

Run `node tools/palm-rejection-smoke.cjs` for pointer-lifetime regression traces, then the canonical
`./build.sh -p` signed build. The traces need the Kotlin compiler already cached by an Android build.

## OnePlus Pad 3 device check

Use a paired Stylo 2 on the tablet's current OxygenOS build. Record that build when reporting input
problems, because hover and system palm classification come from its firmware. These checks
require hardware; desktop traces do not certify the tablet's digitizer behavior.

1. On a finite page and an infinite canvas, rest the palm first, then write short and long strokes.
   Repeat with the palm lifting first and with the tip lifting first. Only intentional pen ink commits;
   a resting contact never resumes drawing/panning when the grace interval expires.
2. Hover the tip before writing, and move it out of range without touching. Touch is blocked in
   range and for the configured interval afterwards. After lifting the palm and waiting, one-finger
   pan, two-finger pinch and two/three-finger undo/redo work again.
3. Repeat with eraser, lasso/handles, text move, image move/crop, sticky creation/move/ink/erase,
   answer-area selection and mark-area selection. A palm cannot commit a preview or delete ink.
4. While writing, rest the hand on toolbar controls, the page scrollbar and an open menu. There
   are no accidental tool changes, undo, page additions or jumps. Genuine pen taps still work.
5. In the music reader, palm contacts cannot turn a sheet or double-tap to fit it; deliberate finger
   taps work after the pen leaves range. Verify reference and peek panes as well.
6. Repeat at Palm rejection = 0 and at a longer interval. Zero still isolates simultaneous tip input
   and honors system cancellation; it allows touch near a hovering pen. Background/restore the
   app and switch notebooks during input; no preview or blocked state survives the switch.

References: [Android input compatibility and palm rejection](https://developer.android.com/develop/ui/views/touch-and-input/input-compatibility-on-large-screens),
[MotionEvent](https://developer.android.com/reference/android/view/MotionEvent),
[OnePlus Pad 3 and Stylo 2](https://www.oneplus.com/us/press/press-release/oneplus-launches-its-flagship-android-tablet-oneplus-pad3-in-the-united-states-and-canada).
