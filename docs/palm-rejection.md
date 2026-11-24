# Palm rejection

Folio rejects palms in layers so each layer catches what the previous one misses. None of them guess from contact size or DPI: Android already processes those signals before dispatch, and custom size thresholds falsely rejected real fingers on some panels.

| Layer | Where | What it catches |
| --- | --- | --- |
| Tool type | `StylusInputRouter.touch` | Pen and eraser tips own their own stream. Digitiser-labelled palms (hidden `TOOL_TYPE_PALM` = 5) never dispatch. Unknown or vendor touchscreen tool types are treated as hands rather than mice. |
| Android cancellation | `PalmRejection.route` | `ACTION_CANCEL` and Android 13+ `ACTION_POINTER_UP` with `FLAG_CANCELED` abandon the gesture without a committing `UP`. A cancelled palm never finishes or cancels an unrelated pen. |
| Pen contact | `PalmRejection.route` | While a pen tip is down, all touch contacts are rejected. A pen landing mid-touch cancels that touch and takes over. |
| Pen proximity | `StylusActivity.isRecent` | Hover, plus `input.palmMs` (default 500 ms) after the last pen sample, holds new touches as *pending*. Hover arriving mid-touch cancels it (`rejectTouches`). |
| Latching | `PalmRejection` / `UiTouchGesture` | Rejected or pending contacts stay blocked until lifted, even after the pen leaves range. |
| Retroactive withdrawal | `InkView.withdrawPalmInk` | Finger ink lifted within `min(palmMs, 400 ms)` before the pen enters range is withdrawn (`StylusActivity.withdraws`), catching a landing hand before hover triggers. |
| Pen-first default | `fingerDrawing(explicit, stylusSeen)` | Until the user explicitly configures *Draw with a finger*, the first pen tip disables finger drawing so stray palms can only pan (behind `panGate` slop), never ink. |
| UI controls | `Modifier.guardUiTouches` | Applies the same proximity gate to buttons, sliders, and popups, including Compose `PointerType.Unknown`. |

**Pinch escape hatch:** Two *fresh* fingers whose span changes beyond touch slop within 280 ms become pinch navigation (`PinchNavigation` → `startNavigation`), even during hover or grace. Rejected palms, palm-labelled contacts, and touches present while the pen tip is down never qualify.

Setting `input.palmMs = 0` (**System only**) keeps tool-type isolation, pen-contact priority, and Android cancellation while turning off proximity holds, pending touches, and retroactive withdrawal.

## Retroactive withdrawal and storage

`FolioViewModel.withdrawStroke` writes one ordinary `PageTransaction` (`forward = Remove([last])`, `undoPop = true`, no redo push), removing both the palm stroke and its undo entry so neither Undo nor Redo can bring it back.

It refuses unless:
- the stroke is still the page's last stroke (by identity), and
- the top of the page's undo stack is exactly that stroke's removal.

Nothing drawn afterward is ever touched. No storage format field is added (`PageJournal` already replays `undoPop` independently), but this flag combination should still be verified on a device.

## Smoke test

`node tools/palm-rejection-smoke.cjs` compiles `PalmRejection.kt` and `TouchChord.kt` and runs `tools/PalmRejectionSmoke.kt`. Add a scenario for every rule change.

## Device checks (OnePlus Pad 3, ColorOS)

Stylus glitches on this hardware can stem from OS bugs; record the ColorOS version before bisecting.

1. **Resting palm:** Enable finger drawing, rest a palm, and write a line. No palm ink should appear and the line should stay intact.
2. **Hand landing:** With finger drawing on, brush your palm on the page, lift, and bring the pen in immediately. The brush mark should disappear with no Undo or Redo entry.
3. **Deliberate finger ink:** With finger drawing on, draw with a finger, wait over a second, then pick up the pen. The finger stroke must stay.
4. **Withdrawal across restart:** After check 2, force-stop and reopen the notebook. Confirm the mark stays gone and undo/redo match. Repeat after writing enough to trigger compaction (>4 MB journal).
5. **Pen-first default:** On a fresh install (or after clearing `input.stylusSeen` and `finger`), confirm a finger draws until the first pen tip, a toast notes fingers now scroll, and toggling *Draw with a finger* in Settings persists across restarts.
6. **Pinch near the pen:** While hovering or right after lift, verify a two-finger pinch zooms while a palm plus one finger does not.
7. **Split pane:** Repeat check 2 on the companion pane and confirm only that page changes.
