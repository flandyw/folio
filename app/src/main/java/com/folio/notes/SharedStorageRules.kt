package com.folio.notes

import java.io.File

/** Callers pass canonical paths, preventing prefix collisions and symlink escapes. */
internal object SharedStorageRules {
    fun contains(roots: List<File>, file: File): Boolean = roots.any { root ->
        file.toPath().startsWith(root.toPath())
    }
    fun validateName(name: String) {
        require(name.isNotBlank() && name != "." && name != ".." && '/' !in name && '\\' !in name && '\u0000' !in name) {
            "Use a file name without slashes"
        }
    }
    /** Reserve the target before streaming so even two copies never overwrite an existing file. */
    fun createCopy(parent: File, name: String): File {
        validateName(name)
        val dot = name.lastIndexOf('.').takeIf { it > 0 } ?: name.length
        val stem = name.substring(0, dot)
        val extension = name.substring(dot)
        for (number in 0..9999) {
            val target = File(parent, if (number == 0) name else "$stem ($number)$extension")
            if (target.createNewFile()) return target
        }
        error("Too many copies with this name")
    }
}
