# Music

[Wiki home](README.md) · [Writing](writing.md) · [Data and backups](data-and-settings.md)

Music is a separate offline collection for PDF sheet music. Its shelf supports importing scores, organizing set lists, extracting parts, rehearsal marks, tempo and reading position. Open a score to read one or two fitted sheets, with pinch zoom.

To start, open Music and choose **Import → Import sheet music PDFs**. Use **New folder** or **New set list** to organize scores; the shelf also offers search and annotated or unfinished filters. Open a set list to add scores or play it.

The reader uses the real notebook editor: pencil marks and text are normal editable page content with journal and undo history. A score is represented by a hidden PDF-backed notebook created on first open. It does not appear in the regular Library, Study or workspace pickers. The music index keeps shelf metadata and per-page annotation counts; older index-based annotations are migrated into the notebook when opened.

The music collection has its own files and index. Music imports and the shelf do not use notebook sync or notebook backups, so do not assume that a library backup is a backup of the music collection. Parts and copies can take their pages from the source score's notebook.

For maintenance, start with `music/MusicModels.kt`, `MusicNotebook.kt`, `MusicStage.kt` and `FolioViewModel.openMusicScore`. Run `node tools/music-smoke.cjs` after preparing the smoke dependencies. See [AGENTS.md](../../AGENTS.md) for the separation and migration rules.
