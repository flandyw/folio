# Music

[Wiki home](README.md) · [Writing](writing.md) · [Data and backups](data-and-settings.md)

Music is a separate offline collection for PDF sheet music. Its shelf supports importing scores, organizing set lists, extracting parts, rehearsal marks, tempo and reading position. Open a score to read one or two fitted sheets, with pinch zoom.

To start, open Music and choose **Import → Import sheet music PDFs**. Use **New folder** or **New set list** to organize scores; the shelf also offers search and annotated or unfinished filters. Open a set list to add scores or play it.

In the reader, **Running order** lists the current set and lets you jump to any piece. A forward page turn (tap, arrow, Space or pedal) at the end opens the next piece on its first page, keeping performance mode active. The last piece stays at its end.

Use **Repeats and codas** to add a named shortcut from the current page to another page. Its button appears while the source page is visible, including in performance mode; **Return** goes back to the page you left. Jumps are explicit, never taken automatically. Shortcuts are saved with the score, copied with it, and retained in extracted parts only when both pages are included.

Open **Score options → Reading and timed turns** to save a landscape layout for this piece. Two-page view can advance one sheet at a time (keeping the previous right sheet on the left), or advance the whole spread. Taps, arrows and pedals use the same choice; portrait always shows one page.

For hands-free reading without a pedal, choose a turn interval from 5–300 seconds and tap **Start timed turns**. This enters performance mode and shows a countdown with a Stop button. Each real manual turn, repeat jump or rotation restarts the full interval. The timer stops at the final page window, on leaving performance mode, when Folio is backgrounded, or when switching scores. It never starts automatically or advances into another piece. The interval and layout are saved per score and carried into copies and extracted parts.

The **Metronome** offers Wood, Click and Soft percussion sounds, a separate click-volume slider, subdivisions (2, 3 or 4 per beat), and Off / 1 / 2 / 4-bar count-ins. Tap a numbered beat to cycle Normal → Strong → Silent; a silent beat also silences its subdivisions. **Reset beat pattern** restores the default first-beat accent. Tempo, beats per bar and the pattern belong to the score; changing the meter resets the pattern. Sound, volume, subdivisions and count-in are remembered across scores. Tap tempo averages the last four intervals and resets after a pause.

The click is generated as PCM on an audio worker using sample-clock tick positions; the visual indicator follows playback. Count-ins play whole beats even if the practice pattern contains mutes, and are only applied when you explicitly start. The performance-mode beat indicator includes a Stop control. Audio focus loss and backgrounding stop the metronome. If audio cannot start, a visible error explains the visual-only fallback; toggle the click to retry. Sound changes and tempo/pattern edits while running restart at beat 1 without repeating the count-in.

The reader uses the real notebook editor: pencil marks and text are normal editable page content with journal and undo history. A score is represented by a hidden PDF-backed notebook created on first open. It does not appear in the regular Library, Study or workspace pickers. The music index keeps shelf metadata and per-page annotation counts; older index-based annotations are migrated into the notebook when opened.

The music collection has its own files and index. Music imports and the shelf do not use notebook sync or notebook backups, so do not assume that a library backup is a backup of the music collection. Parts and copies can take their pages from the source score's notebook.

Device checks for the reader additions: create forward and backward shortcuts, reopen the score, and check Jump / Return in portrait, two-page landscape and performance mode. Extract a part with both shortcut pages, then one with only the source. Play a set with a score previously left midway through: turn forward at the preceding score's end and confirm it starts on page 1, keeps performance mode, ignores held-key repeats, and stops at the last piece. Check that the running-order picker highlights the current piece and can open a different one.

Device checks for reading controls: reopen a score after changing its landscape layout, turn through odd and even page counts with both advance options, and compare tap, arrow and pedal turns. Start timed turns at the minimum interval; check the countdown, manual Stop, a repeat jump, rotation, Home/End and background/foreground. Verify that reaching the last page or choosing the next piece stops the timer and that returning to performance mode does not restart it.

Metronome device checks: listen to all three voices on the tablet speaker and wired/Bluetooth outputs, compare strong/normal/subdivision levels, and check volume and silent mode. Test 30 and 240 BPM, triplets at 137 BPM, muted beats with subdivisions, and count-in boundaries. Change tempo, meter, voice and volume while running; stop from performance mode, background the app, switch scores, and interrupt audio with a call or another app. Reopen Music to check remembered preferences and legacy count-in migration. PCM timing and envelopes are covered by `music-smoke`; audio-device latency, underruns and listening quality need hardware verification.

For maintenance, start with `music/MusicModels.kt`, `MusicNotebook.kt`, `MusicStage.kt` and `FolioViewModel.openMusicScore`. Run `node tools/music-smoke.cjs` after preparing the smoke dependencies. See [AGENTS.md](../../AGENTS.md) for the separation and migration rules.
