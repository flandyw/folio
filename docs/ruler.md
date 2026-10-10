# Ruler

A session-only straight-edge for the open page. It is never written to the page, the journal, undo, exports or backups.

- **Show:** page options menu → **Present → Show ruler**. It starts across the middle of a finite page, or as a short horizontal run through the origin on an infinite canvas. Choosing **Hide ruler** removes it. It belongs to one page: it stays on that page and reappears there when you return.
- **Snap:** a stylus stroke with pen or highlighter snaps onto the ruler's edge where it passes within about 22 dp of it, and draws as it goes. Shapes, the eraser, the lasso and text do not snap.
- **Move with a finger:** touching within about 26 dp of an end swings that end, and touching the body moves the whole ruler. Ends keep a minimum length of 40 page units. Fingers that do not touch the ruler still pan and zoom.
- **Pure rules:** `RulerLine.kt` holds the geometry (grab, snap, drag, initial placement) with no Android imports.
- **Ownership:** `InkView.ruler` and `onRulerChanged` carry the ruler. `handleRulerDrag` owns a grabbed finger until lift, and `cancelGesture` releases it.

Check on device: snap a pen stroke onto the edge from both sides and at an angle, swing each end and move the body with a finger while confirming pan still works away from the ruler, check the ends stop at 40 units, confirm the eraser and lasso ignore the ruler, and confirm the ruler comes back on the same page after switching pages.
