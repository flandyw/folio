package com.folio.notes

/**
 * Central catalogue of customisable preferences.
 *
 * Existing keys (theme, finger, stylus, penHaptics, shapeRecognition, follow.*, text.*,
 * toolbar.*, ink-tools) are left untouched for migration safety. Everything new lives
 * here with a documented default, range and pure parsing helper so it stays JVM-testable
 * and every screen reads the same value.
 */
object AppPrefs {
    const val FULLSCREEN = "fullscreen"
    const val KEEP_SCREEN_ON = "editor.keepScreenOn"
    const val AUTO_UPDATE = "updates.auto"
    /** Opt-in VPS builds; stable builds continue to come from GitHub. */
    const val EXPERIMENTAL_UPDATES = "updates.experimental"
    const val LAST_UPDATE_CHECK = "updates.lastCheck"
    const val UPDATE_RETRY_AT = "updates.retryAt"
    const val EXPERIMENTAL_LAST_UPDATE_CHECK = "updates.experimental.lastCheck"
    const val EXPERIMENTAL_UPDATE_RETRY_AT = "updates.experimental.retryAt"
    const val LIB_SORT = "library.sort"
    const val LIB_KIND = "library.kind"
    const val LIB_LIST = "library.listView"
    /** Music shelf shows rows instead of first-page covers; music keeps its own choice. */
    const val MUSIC_LIST = "music.listView"
    const val DEFAULT_TOOL = "editor.defaultTool"
    const val DEFAULT_PAPER = "notebook.defaultPaper"
    /** Paper new mistake-practice pages start on; they are always infinite canvases. */
    const val MISTAKE_PAPER = "mistake.paper"
    const val DEFAULT_COVER = "notebook.defaultCover"
    const val DEFAULT_PAGE_COVER = "notebook.defaultPageCover"
    const val PALM_MS = "input.palmMs"
    const val FAST_PAN = "input.fastPan"
    const val FAST_PAN_MULTIPLIER = "input.fastPanMultiplier"
    const val TIMER_CUSTOM_MIN = "timer.customMinutes"
    const val TIMER_READING_MIN = "timer.readingMinutes"
    const val TIMER_AUTO_START = "timer.autoStart"
    const val TIMER_IDLE_MIN = "timer.idleMinutes"
    const val EXPORT_PNG_SCALE = "export.pngScale"
    const val EXPORT_PDF_MODE = "export.pdfMode"
    const val SPLIT_FRACTION = "workspace.splitFraction"
    /** Which side the editor sits on when a companion pane is open; remembered between sessions. */
    const val EDITOR_ON_RIGHT = "workspace.editorOnRight"
    const val TEXT_SIZE_KEY = "text.size"
    /** The user's own accent colour as `#RRGGBB`; absent means the palette's own colours. */
    const val ACCENT = "theme.accent"
    /** Notebook cover colours the user added, stored after the built-in ones. */
    const val CUSTOM_COVERS = "notebook.customCovers"
    /** Multiplier on the app's sp text, independent of the system font size. */
    const val UI_TEXT_SCALE = "display.textScale"
    const val AUTO_BACKUP_TREE_URI = "backup.auto.treeUri"
    const val AUTO_BACKUP_LAST_SUCCESS = "backup.auto.lastSuccess"
    const val AUTO_BACKUP_LAST_ERROR = "backup.auto.lastError"
    /** Local notebook identities omitted from automatic and portable library backups. */
    const val BACKUP_EXCLUDED_NOTEBOOKS = "backup.excludedNotebooks"
    /** Peek shows the whole current page instead of a pinned view. */
    const val AUTO_PEEK = "peek.auto"

    val DEFAULT_BACKUP_EXCLUDED_NOTEBOOKS: Set<String> = emptySet()

    const val DEFAULT_FULLSCREEN = true
    const val DEFAULT_KEEP_SCREEN_ON = false
    const val DEFAULT_AUTO_UPDATE = true
    const val DEFAULT_EXPERIMENTAL_UPDATES = false
    const val DEFAULT_LIST_VIEW = false
    val DEFAULT_MISTAKE_PAPER = Paper.MATH_GRID
    const val DEFAULT_PAGE_COVER_ENABLED = true
    const val DEFAULT_COVER_INDEX = 0
    const val DEFAULT_PALM_MS = 500L
    const val PALM_MIN_MS = 0L
    const val PALM_MAX_MS = 1500L
    const val DEFAULT_FAST_PAN = false
    const val DEFAULT_FAST_PAN_MULTIPLIER = 2f
    const val FAST_PAN_MIN = 1.25f
    const val FAST_PAN_MAX = 5f
    const val DEFAULT_TIMER_CUSTOM_MIN = 90
    const val TIMER_CUSTOM_MIN_RANGE = 1
    const val TIMER_CUSTOM_MAX = 480
    const val DEFAULT_TIMER_READING_MIN = 15
    const val TIMER_READING_MIN_RANGE = 0
    const val TIMER_READING_MAX = 60
    const val DEFAULT_TIMER_AUTO_START = true
    const val DEFAULT_TIMER_IDLE_MIN = 5
    const val TIMER_IDLE_MIN_RANGE = 0
    const val TIMER_IDLE_MAX = 60
    const val DEFAULT_PNG_SCALE = 2f
    const val PNG_SCALE_MIN = 1f
    const val PNG_SCALE_MAX = 3f
    const val DEFAULT_SPLIT = 0.5f
    const val DEFAULT_EDITOR_ON_RIGHT = false
    const val DEFAULT_TEXT_SIZE = 26f
    const val TEXT_SIZE_MIN = 12f
    const val TEXT_SIZE_MAX = 72f
    const val DEFAULT_UI_TEXT_SCALE = 1f
    const val UI_TEXT_SCALE_MIN = 0.85f
    const val UI_TEXT_SCALE_MAX = 1.4f

    fun lastUpdateCheckKey(experimental: Boolean): String =
        if (experimental) EXPERIMENTAL_LAST_UPDATE_CHECK else LAST_UPDATE_CHECK

    fun updateRetryAtKey(experimental: Boolean): String =
        if (experimental) EXPERIMENTAL_UPDATE_RETRY_AT else UPDATE_RETRY_AT

    fun backupExcludedNotebookIds(raw: Set<String>?): Set<String> =
        (raw ?: DEFAULT_BACKUP_EXCLUDED_NOTEBOOKS).filter { it.length in 1..64 && it.matches(Regex("[a-zA-Z0-9-]+")) }.toSet()

    /** A deliberate single-notebook export includes the chosen notebook even if it is excluded. */
    fun notebooksForBackup(notes: List<Notebook>, excludedIds: Set<String>, includeExcluded: Boolean = false): List<Notebook> =
        if (includeExcluded) notes else notes.filterNot { it.id in excludedIds }

    fun defaultTool(raw: String?): Tool =
        runCatching { Tool.valueOf(raw ?: "") }.getOrDefault(Tool.PEN)

    fun defaultPaper(raw: String?): Paper =
        runCatching { Paper.valueOf(raw ?: "") }.getOrDefault(Paper.MATH_GRID)

    /** Mistake practice pages are infinite canvases, so the paper only picks the printed guide. */
    fun mistakePaper(raw: String?): Paper =
        runCatching { Paper.valueOf(raw ?: "") }.getOrDefault(DEFAULT_MISTAKE_PAPER)

    fun librarySort(raw: String?): LibrarySort =
        runCatching { LibrarySort.valueOf(raw ?: "") }.getOrDefault(LibrarySort.RECENT)

    fun libraryKind(raw: String?): LibraryKind =
        runCatching { LibraryKind.valueOf(raw ?: "") }.getOrDefault(LibraryKind.ALL)

    /** The default cover keeps its design but its colour always points into the built-in covers, never a removable custom one. */
    fun defaultCover(cover: Int): Int =
        CoverStyle.withColor(cover, CoverStyle.colorIndex(cover).coerceIn(0, BuiltInCoverColors.lastIndex))

    fun palmMs(value: Long?): Long =
        (value ?: DEFAULT_PALM_MS).coerceIn(PALM_MIN_MS, PALM_MAX_MS)

    fun fastPanMultiplier(value: Float?): Float =
        (value ?: DEFAULT_FAST_PAN_MULTIPLIER).coerceIn(FAST_PAN_MIN, FAST_PAN_MAX)

    /** The factor applied to finger/two-finger pan distance: 1 while fast pan is off. */
    fun panFactor(enabled: Boolean, multiplier: Float?): Float = if (enabled) fastPanMultiplier(multiplier) else 1f

    fun timerCustomMinutes(value: Int?): Int =
        (value ?: DEFAULT_TIMER_CUSTOM_MIN).coerceIn(TIMER_CUSTOM_MIN_RANGE, TIMER_CUSTOM_MAX)

    fun timerReadingMinutes(value: Int?): Int =
        (value ?: DEFAULT_TIMER_READING_MIN).coerceIn(TIMER_READING_MIN_RANGE, TIMER_READING_MAX)

    /** Minutes of pen idleness before the clock stops itself; 0 keeps it running until stopped. */
    fun timerIdleMinutes(value: Int?): Int =
        (value ?: DEFAULT_TIMER_IDLE_MIN).coerceIn(TIMER_IDLE_MIN_RANGE, TIMER_IDLE_MAX)

    fun pngScale(value: Float?): Float =
        if (value == null || !value.isFinite()) DEFAULT_PNG_SCALE
        else value.coerceIn(PNG_SCALE_MIN, PNG_SCALE_MAX)

    fun pdfExportMode(raw: String?): PdfExportMode = PdfExportMode.safeValueOf(raw)

    fun splitFraction(value: Float?): Float =
        if (value == null || !value.isFinite()) DEFAULT_SPLIT
        else value.coerceIn(SplitPanes.MIN_FRACTION, SplitPanes.MAX_FRACTION)

    fun textSize(value: Float?): Float =
        if (value == null || !value.isFinite()) DEFAULT_TEXT_SIZE
        else value.coerceIn(TEXT_SIZE_MIN, TEXT_SIZE_MAX)

    fun uiTextScale(value: Float?): Float =
        if (value == null || !value.isFinite()) DEFAULT_UI_TEXT_SCALE
        else value.coerceIn(UI_TEXT_SCALE_MIN, UI_TEXT_SCALE_MAX)
}
