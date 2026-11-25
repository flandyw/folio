package com.folio.notes.library.files

import com.folio.notes.storage.NotebookTags

private val fileQueryWhitespace = Regex("[\\s\\p{Z}]+")

internal fun matchesFileName(name: String, query: String): Boolean =
    query.trim().split(fileQueryWhitespace).filter { it.isNotBlank() }.all { name.contains(it, ignoreCase = true) }

/*
 * The tag panel's draft. Tags are one tag whatever their case (NotebookTags.normalize), so every
 * membership check here ignores case too; [count] is how many of the [total] notebooks carry the tag.
 */
private fun List<String>.hasTag(label: String) = any { it.equals(label, true) }
private fun List<String>.withoutTag(label: String) = filterNot { it.equals(label, true) }

internal fun tagChipActive(label: String, count: Int, added: List<String>, removed: List<String>): Boolean =
    added.hasTag(label) || (count > 0 && !removed.hasTag(label))

/** Untouched tags on only some notebooks show as mixed. */
internal fun tagChipMixed(label: String, count: Int, total: Int, added: List<String>, removed: List<String>): Boolean =
    count in 1 until total && !added.hasTag(label) && !removed.hasTag(label)

/** The (added, removed) draft after tapping [label]'s chip. */
internal fun toggleTagDraft(label: String, count: Int, total: Int, added: List<String>, removed: List<String>): Pair<List<String>, List<String>> = when {
    added.hasTag(label) -> added.withoutTag(label) to if (count > 0) removed.withoutTag(label) + label else removed
    removed.hasTag(label) -> added + label to removed.withoutTag(label)
    count == total -> added to removed + label
    else -> added + label to removed
}

/** Whether saving would change any notebook's tags, so closing the panel would lose something. */
internal fun tagDraftChanges(tags: List<List<String>>, pending: List<String>, removed: List<String>): Boolean =
    tags.any { NotebookTags.edit(it, pending, removed) != NotebookTags.normalize(it) }
