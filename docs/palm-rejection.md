# Palm rejection

Folio rejects palms in layers. Each layer catches what the previous one can't. None of them guess from contact size or DPI: Android already uses those signals before an event reaches the app, and our own guesses rejected real fingers on some panels.

| Layer | Where | What it catches |
|---|---|---|
| Tool type | `StylusInputRouter.touch` | Pen and eraser tips own their own stream. Contacts the digitizer labels as a palm (hidden `TOOL_TYPE_PALM` = 5) never dispatch. Unknown or vendor tool types on a touchscreen are treated as hands, not let through like a mouse. |
| Android cancellation | `PalmRejection.route` | `ACTION_CANCEL` and Android 13+ `ACTION_POINTER_UP` + `FLAG_CANCELED` abandon the gesture without a committing UP. A canceled palm never finishes or cancels an unrelated pen. |
| Pen contact | `PalmRejection.route` | While a tip is down, every touch is rejected. A pen that lands during a forwarded touch cancels that touch and takes over. |
| Pen proximity | `StylusActivity.isRecent` | Hover, plus `input.palmMs` (default 500 ms) after the last pen sample, holds fresh touches as *pending*. Hover arriving mid-touch cancels the touch (`rejectTouches`). |
| Latching | `PalmRejection` / `UiTouchGesture` | A rejected or pending contact stays blocked until it lifts, even after the pen leaves. |
| Retroactive withdrawal | `InkView.withdrawPalmInk` | Finger ink that lifted at most `min(palmMs, 400 ms)` before the pen came into range is taken back (`StylusActivity.withdraws`). This catches the brush of a landing hand before hover could see it. |
| Pen-first default | `fingerDrawing(explicit, stylusSeen)` | Until the user sets *Draw with a finger*, the first pen tip on the device turns finger drawing off, so a stray palm can at most pan (behind `panGate`'s slop), never ink. |
| UI controls | `Modifier.guardUiTouches` | The same proximity rule for buttons, sliders and popups, including Compose `PointerType.Unknown`. |

The escape hatch: two *fresh* fingers that change their span by more than touch slop within 280 ms of each other become pinch navigation (`PinchNavigation` → `startNavigation`), even during hover or grace. Rejected palms, palm-labelled contacts and anything present while the tip is down never qualify.

`input.palmMs = 0` ("System only") keeps tool-type isolation, the pen-contact rule and Android cancellation. It turns off proximity, pending touches and retroactive withdrawal.

## Retroactive withdrawal and storage

`FolioViewModel.withdrawStroke` writes one ordinary `PageTransaction`: `forward = Remove([last])`, `undoPop = true`, with no redo push. The palm stroke disappears together with its own undo entry, so neither undo nor redo can bring it back.

It refuses unless:

- the stroke is still the page's last stroke (identity), and
- the top of the page's undo stack is exactly that stroke's removal.

Anything drawn since is therefore never touched.

No format field is new. The replay in `PageJournal` already applies `undoPop` independently of the other flags, but this flag combination is new in practice, so verify it on a device (below).

## Smoke test

`node tools/palm-rejection-smoke.cjs` compiles `PalmRejection.kt` and `TouchChord.kt` and traces the scenarios in `tools/PalmRejectionSmoke.kt`. Add a scenario for every rule change.

## Device checks (OnePlus Pad 3, ColorOS)

Stylus glitches on this device can be OS bugs. Note the ColorOS version before bisecting.

1. **Resting palm.** Finger drawing on. Rest a palm, then write a line. No palm ink appears, and the line is intact.
2. **Hand landing.** Finger drawing on. Brush the palm on the page and lift it, then bring the pen in within a fraction of a second. The brush mark disappears. Undo does not bring it back, and redo is not offered for it.
3. **Deliberate finger ink.** Finger drawing on. Draw with a finger, wait over a second, then pick up the pen. The finger stroke stays.
4. **Withdrawal survives restart.** After check 2, kill the app and reopen the notebook. The mark is still gone, and undo/redo match what was on screen before. Repeat once after enough writing to trigger compaction (over 4 MB of journal).
5. **Pen-first default.** Fresh install, or clear `input.stylusSeen` and `finger`. A finger draws until the first pen tip, then a toast says fingers now scroll. Toggling *Draw with a finger* in Settings sticks across restarts.
6. **Pinch near the pen.** While hovering or right after a lift, a two-finger pinch zooms. A palm plus one finger does not.
7. **Split pane.** Repeat check 2 on the companion pane's page. Only that page changes.
