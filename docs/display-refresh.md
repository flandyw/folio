# Display refresh rate

`MainActivity.requestHighRefreshRate` requests the fastest advertised display mode at the
current physical resolution using both `preferredDisplayModeId` and `preferredRefreshRate`.
There is no 60/120 Hz cap: a display advertising 144 Hz at that resolution is asked for 144 Hz.
This applies throughout the activity, including the library and notebook editor.

The choice is cached across focus changes to avoid repeated mode enumeration and window
relayout when the keyboard or a dialog closes. Returning to the app and display change
notifications invalidate that cache; moving to another display or changing resolution also
invalidates the cached choice. Window attributes are only written when the request changes,
so the mode-change notification does not cause a relayout loop.

Android treats these values as preferences. Display settings, manufacturer app policies,
battery saver and thermal limits can still restrict the actual refresh rate. Requesting
144 Hz also does not guarantee that every application frame meets its 6.94 ms budget.

## Tablet verification

1. Select the tablet's high refresh display setting, including its per-app setting for Folio
   if available, and enable Developer options → Show refresh rate.
2. Open Folio and check the overlay while scrolling the library, writing, panning and zooming
   a notebook. On the 144 Hz tablet, confirm 144 Hz during these interactions.
3. Open/close a dialog and the keyboard, switch away and back, and rotate the tablet. Repeat
   scrolling and writing and confirm the high rate returns without flashing or mode loops.
4. Change the system refresh setting while Folio is open and while it is backgrounded. Confirm
   Folio follows the modes the system exposes when the setting changes or the app resumes.
5. If using an external display, move Folio between displays and confirm it requests each
   display's own fastest mode without changing its resolution.

Use frame timing/profiling separately to investigate dropped app frames; the refresh overlay
reports display Hz rather than application FPS.
