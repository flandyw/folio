# Writing follow

Writing follow uses transient handwriting geometry; it does not change saved ink or notebook storage.
Printed rules anchor a page's baseline and spacing. Blank pages and infinite canvases infer the body
from recent strokes, keep descenders out of the baseline estimate, and confirm ambiguous line changes.
A clear cursive word on a new line can provide enough evidence in a single stroke.

New-line placement stays pending across pen-down interruptions. Glides stop as soon as the pen
touches down and replan from the actual view after the next accepted stroke. Returns aim at an
absolute writing height, so stopping halfway and resuming cannot apply a second full vertical step.
Actual consumed pan determines whether a return arrived; horizontal movement alone cannot advance
the tracked baseline when vertical scrolling is clamped. Back records only movement actually applied.

Sideways glides use half the configured return pause, extended by learned pen-up gaps. Near the
visible edge, adaptive following uses a shorter letter-gap wait and a glide of at most 180 ms.
New-line placement uses the same urgency; automatic returns keep the longer pause and configured
glide duration. Dots and crossbars near the final word can resume an interrupted request after pen-up;
corrections farther back hold the view. Rejected palm contacts do not cancel a pending glide.

On a canvas, the current viewport width defines a line's length. Its start stays fixed in canvas
coordinates during follow pans, even when that start moves off screen. Deliberate navigation or a
viewport resize resets it. An answer area is optional. Pages use the configured minimum zoom;
canvases use the handwriting's actual height on screen.

## Checks

Run the usual Android build/lint checks, then the pure regression smoke check using the same JDK:

```sh
./gradlew :app:assembleDebug :app:lintDebug
node tools/writing-follow-smoke.cjs
node tools/katex-smoke.cjs
```

The follow smoke check compiles the actual pure Kotlin helpers with Gradle's cached compiler and
runs deterministic handwriting and animation traces. It needs Node, JDK 17, and the compiler cache
populated by the Android build; it downloads nothing and leaves no generated files in the repo.

## Device check

Use a stylus on a ruled page, a blank page, an imported PDF with several separate answer blocks,
and an infinite canvas. Repeat at a comfortable writing zoom and with automatic return enabled.

1. Write words containing `f`, `g`, `j`, `p`, and `y`, including as the first and final letter.
   Check sideways following and the next-line return. Repeat with small letters and joined-up words.
2. Finish near the line's edge, then dot an `i` or cross a `t`. The return should restart its pause.
   Correct an earlier word instead: the view should hold. Rest a palm during the pause: rejected
   palm contacts should leave the request intact.
3. Start a natural next line with a tall capital, then a short letter or a descender. Continue
   writing during the placement pause and touch down midway through a glide. The next pen-up
   should replan placement without losing the line or moving an extra line down.
4. Use Next line, stop its glide halfway, then either continue the old line or write the new one.
   Check that the resumed movement targets the actual position. At a scroll limit, sideways
   movement must not falsely report a successful vertical return. Check Back after each case.
5. On the canvas, write long enough for several sideways pans. Its end should remain reachable
   and return to the original start. Then pinch/pan, rotate or resize the window, and write again:
   following should use a fresh lane. Repeat in RTL and with the other pen hand selected.
6. Write on the final printed rule with descenders below it. Sideways following should continue;
   automatic return must not spill into the next question or an adjacent answer column.
7. In Maths mode, grow a fraction/equation downward and use Next line. Horizontal follow should
   remain off. Check Pause/Resume, both follow-axis toggles, fixed/adaptive timing, and shape tidy.

These traces exercise geometry and state transitions, but they do not substitute for real stylus,
palm rejection, frame timing, and page-scroll checks on a device.
