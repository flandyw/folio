package com.folio.notes.model

import kotlin.math.*

/** Printed page backgrounds. Stored by name and read back through [safeValueOf], so an unknown name falls back to dots. */
enum class Paper { PLAIN, RULED, DOTS, GRID, MATH_GRID, GRAPH, MC_SHEET, TIAN_GRID, MI_GRID, SPLIT_RULED;
    /** Spacing used for paper rendering and for snap-to-grid when that paper is active. */
    val gridSpacing: Float get() = when (this) {
        MATH_GRID, GRAPH -> 20f
        GRID -> 28f
        TIAN_GRID, MI_GRID -> HANZI_CELL
        else -> 20f
    }
    val isGrid: Boolean get() = this == GRID || this == MATH_GRID || this == GRAPH
    /** Character-practice papers tile large squares with printed guides, like exercise books. */
    val isHanzi: Boolean get() = this == TIAN_GRID || this == MI_GRID
    val hasPrintedAxes: Boolean get() = this == GRAPH
    companion object {
        /** Side of one hanzi practice cell, in page units; divides the 840 pt page width evenly. */
        const val HANZI_CELL = 84f
        fun safeValueOf(name: String): Paper = try { valueOf(name) } catch (_: Exception) { DOTS }
    }
}
