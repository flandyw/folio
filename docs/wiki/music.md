# Music

[Wiki home](README.md) · [Writing](writing.md) · [Data and backups](data-and-settings.md)

**Music** is a separate offline library for PDF sheet music. Organise scores into folders and set lists, extract parts, add rehearsal marks and tempos, and read one or two fitted pages at a time with pinch zoom.

## Library and set lists

Choose **Import → Import sheet music PDFs** to add scores. Use **New folder** or **New set list** to organise the shelf, or filter by search, annotated, and unfinished scores. Open a set list to add pieces or start playing through it.

In the reader, **Running order** lists the current set and jumps to any piece. Turning forward (tap, arrow, Space, or pedal) at the end of a piece opens the next piece on page 1 while keeping performance mode active; the final piece stops at its last page.

## Repeats, layouts, and timed turns

- **Repeats and codas:** Add named shortcuts from the current page to another page. The jump button stays visible on the source page (including in performance mode), and **Return** jumps back. Jumps are always manual. Shortcuts save with the score, travel with copies, and carry into extracted parts whenever both pages are included.
- **Landscape layout:** Open **Score options → Reading and timed turns** to choose a saved landscape layout per score. Two-page view can advance one sheet at a time (rolling the previous right sheet to the left) or a full spread at once. Taps, arrows, and pedals follow the same rule; portrait always shows one page.
- **Timed turns:** Pick an interval (5–300 seconds) and tap **Start timed turns** for hands-free reading. Folio enters performance mode with a countdown and **Stop** button. Manual turns, repeat jumps, and rotations reset the countdown. The timer stops on the final page, when leaving performance mode, when backgrounded, or when switching scores; it never auto-starts or advances into another piece. Interval and layout persist per score and carry into copies and extracted parts.

## Metronome

The metronome offers **Wood**, **Click**, and **Soft** voices, a dedicated click-volume slider, subdivisions (2, 3, or 4 per beat), and Off / 1 / 2 / 4-bar count-ins.

- Tap any numbered beat to cycle **Normal → Strong → Silent** (muting a beat also mutes its subdivisions). **Reset beat pattern** restores the default beat-1 accent.
- Tempo, time signature, and beat pattern belong to the score; changing the metre resets the pattern. Voice, volume, subdivisions, and count-in persist across scores. Tap tempo averages the last four taps and resets after a pause.
- Audio is synthesised as PCM on a worker thread using sample-clock tick positions, with the visual beat indicator locked to playback. Count-ins play full beats even when practice beats are muted and only run on explicit start.
- Changing voice, tempo, or pattern while running restarts on beat 1 without repeating the count-in. Losing audio focus or backgrounding stops the metronome. If audio fails to start, an error explains the visual-only fallback; toggle the click to retry.

## Annotations and storage

The music reader runs on the real notebook editor: pencil marks and text are normal editable content with full journal and undo history. Each score creates a hidden PDF-backed notebook on first open that stays out of the regular Library, Study, and workspace pickers. Older index-based annotations migrate automatically on open.

The music collection keeps its own files and index and is **excluded from notebook sync and library backups**. Do not rely on a library backup to preserve sheet music.

## Device checks

- **Repeats and set lists:** Create forward and backward shortcuts, reopen the score, and verify Jump/Return in portrait, two-page landscape, and performance mode. Extract a part with both shortcut pages, then one with only the source. Play a set containing a score left midway through: turning forward from the previous piece should open it on page 1, keep performance mode, ignore held-key repeats, and stop at the last piece. Verify the running-order picker highlights the active piece and switches cleanly.
- **Layouts and timed turns:** Change landscape layout, reopen, and step through odd and even page counts with both advance modes using tap, arrows, and pedal. Test timed turns at the minimum interval: check the countdown, manual Stop, repeat jump, rotation, Home/End, and background/foreground. Confirm reaching the last page or switching pieces stops the timer and re-entering performance mode does not restart it.
- **Metronome:** Listen to all three voices over speakers and wired/Bluetooth audio; compare strong, normal, and subdivision levels, volume, and silent mode. Test 30 and 240 BPM, triplets at 137 BPM, muted beats with subdivisions, and count-in boundaries. Adjust tempo, metre, voice, and volume while running; stop from performance mode, background the app, switch scores, and interrupt audio with another app or call. Reopen Music to confirm saved preferences and legacy count-in migration.

## For maintainers

Start with `music/MusicModels.kt`, `MusicNotebook.kt`, `MusicStage.kt`, and `FolioViewModel.openMusicScore`. Run `node tools/music-smoke.cjs` after `:app:prepareBackupSmoke` (PCM timing and envelopes are smoke-tested; audio latency, underruns, and listening quality require hardware verification). See [AGENTS.md](../../AGENTS.md) for separation and migration rules.
