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
    const val LAST_UPDATE_CHECK = "updates.lastCheck"
    const val LIB_SORT = "library.sort"
    const val LIB_KIND = "library.kind"
    const val LIB_LIST = "library.listView"
    const val DEFAULT_TOOL = "editor.defaultTool"
    const val DEFAULT_PAPER = "notebook.defaultPaper"
    const val DEFAULT_COVER = "notebook.defaultCover"
    const val DEFAULT_PAGE_COVER = "notebook.defaultPageCover"
    const val PALM_MS = "input.palmMs"
    const val TIMER_CUSTOM_MIN = "timer.customMinutes"
    const val TIMER_READING_MIN = "timer.readingMinutes"
    const val TIMER_AUTO_START = "timer.autoStart"
    const val TIMER_IDLE_MIN = "timer.idleMinutes"
    const val EXPORT_PNG_SCALE = "export.pngScale"
    const val EXPORT_PDF_MODE = "export.pdfMode"
    const val SPLIT_FRACTION = "workspace.splitFraction"
    const val TEXT_SIZE_KEY = "text.size"
    const val AUTO_BACKUP_TREE_URI = "backup.auto.treeUri"
    const val AUTO_BACKUP_LAST_SUCCESS = "backup.auto.lastSuccess"
    const val AUTO_BACKUP_LAST_ERROR = "backup.auto.lastError"

    const val DEFAULT_FULLSCREEN = true
    const val DEFAULT_KEEP_SCREEN_ON = false
    const val DEFAULT_AUTO_UPDATE = true
    const val DEFAULT_LIST_VIEW = false
    const val DEFAULT_PAGE_COVER_ENABLED = true
    const val DEFAULT_COVER_INDEX = 0
    const val DEFAULT_PALM_MS = 500L
    const val PALM_MIN_MS = 0L
    const val PALM_MAX_MS = 1500L
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
    const val DEFAULT_TEXT_SIZE = 26f
    const val TEXT_SIZE_MIN = 12f
    const val TEXT_SIZE_MAX = 72f

    fun defaultTool(raw: String?): Tool =
        runCatching { Tool.valueOf(raw ?: "") }.getOrDefault(Tool.PEN)

    fun defaultPaper(raw: String?): Paper =
        runCatching { Paper.valueOf(raw ?: "") }.getOrDefault(Paper.MATH_GRID)

    fun librarySort(raw: String?): LibrarySort =
        runCatching { LibrarySort.valueOf(raw ?: "") }.getOrDefault(LibrarySort.RECENT)

    fun libraryKind(raw: String?): LibraryKind =
        runCatching { LibraryKind.valueOf(raw ?: "") }.getOrDefault(LibraryKind.ALL)

    fun defaultCover(index: Int): Int =
        if (CoverColors.isEmpty()) 0 else index.coerceIn(0, CoverColors.lastIndex)

    fun palmMs(value: Long?): Long =
        (value ?: DEFAULT_PALM_MS).coerceIn(PALM_MIN_MS, PALM_MAX_MS)

    fun timerCustomMinutes(value: Int?): Int =
        (value ?: DEFAULT_TIMER_CUSTOM_MIN).coerceIn(TIMER_CUSTOM_MIN_RANGE, TIMER_CUSTOM_MAX)

    fun timerReadingMinutes(value: Int?): Int =
        (value ?: DEFAULT_TIMER_READING_MIN).coerceIn(TIMER_READING_MIN_RANGE, TIMER_READING_MAX)

    /** Minutes of pen idleness before the clock stops itself; 0 keeps it running until stopped. */
    fun timerIdleMinutes(value: Int?): Int =
        (value ?: DEFAULT_TIMER_IDLE_MIN).coerceIn(TIMER_IDLE_MIN_RANGE, TIMER_IDLE_MAX)

    /** The sitting the smart timer starts on a first pen stroke: the Custom timer's own settings. */
    fun autoStartPreset(writingMinutes: Int?, readingMinutes: Int?): ExamTimerPreset {
        val writing = timerCustomMinutes(writingMinutes)
        return ExamTimerPreset("Auto · $writing min", writing * 60, timerReadingMinutes(readingMinutes) * 60)
    }

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
}
