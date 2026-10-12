package com.folio.notes

/** Ordered immutable values with shared ID lookup and O(log n) replacement. */
internal class IndexedItems<T> private constructor(
    private val tree: Node<T>?, private val positions: Map<String, Int>,
    private val identity: (T) -> String,
    internal val previous: IndexedItems<T>? = null, internal val changedIndex: Int? = null,
) : AbstractList<T>() {
    private class Node<T>(val size: Int, val value: T? = null, val left: Node<T>? = null, val right: Node<T>? = null)
    override val size: Int get() = tree?.size ?: 0
    override fun get(index: Int): T {
        require(index in indices)
        var node = tree!!; var offset = index
        while (node.size > 1) {
            val left = node.left!!
            if (offset < left.size) node = left else { offset -= left.size; node = node.right!! }
        }
        @Suppress("UNCHECKED_CAST") return node.value as T
    }
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is IndexedItems<*>) return super.equals(other)
        fun equal(a: Node<*>?, b: Node<*>?): Boolean = when {
            a === b -> true
            a == null || b == null || a.size != b.size -> false
            a.size == 1 -> a.value == b.value
            else -> equal(a.left, b.left) && equal(a.right, b.right)
        }
        return equal(tree, other.tree)
    }
    override fun hashCode(): Int = super.hashCode()
    fun byId(id: String): T? = positions[id]?.let(::get)
    fun replace(id: String, value: T): IndexedItems<T> {
        val index = positions[id] ?: return this
        require(identity(value) == id)
        if (get(index) === value) return this
        fun rewrite(node: Node<T>, offset: Int): Node<T> {
            if (node.size == 1) return Node(1, value)
            val left = node.left!!
            return if (offset < left.size) Node(node.size, left = rewrite(left, offset), right = node.right)
            else Node(node.size, left = left, right = rewrite(node.right!!, offset - left.size))
        }
        // Keep just one predecessor: a long handwriting session must not retain every old tree.
        return IndexedItems(rewrite(tree!!, index), positions, identity, detached(), index)
    }
    private fun detached() = IndexedItems(tree, positions, identity)
    internal fun changedFrom(old: List<T>): Int? =
        if (old is IndexedItems<T> && previous?.tree === old.tree) changedIndex else null

    companion object {
        fun <T> of(items: List<T>, identity: (T) -> String): IndexedItems<T> {
            if (items is IndexedItems<T>) return items
            fun build(start: Int, end: Int): Node<T>? {
                if (start == end) return null
                if (end - start == 1) return Node(1, items[start])
                val middle = (start + end) / 2
                return Node(end - start, left = build(start, middle), right = build(middle, end))
            }
            val positions = HashMap<String, Int>(items.size)
            items.forEachIndexed { index, value -> check(positions.put(identity(value), index) == null) { "Duplicate document identity" } }
            return IndexedItems(build(0, items.size), positions, identity)
        }
    }
}

internal fun List<Notebook>.replaceNotebook(note: Notebook): List<Notebook> = IndexedItems.of(this) { it.id }.replace(note.id, note)
internal fun List<NotePage>.replacePage(page: NotePage): List<NotePage> = IndexedItems.of(this) { it.id }.replace(page.id, page)
internal fun List<Notebook>.notebook(id: String?): Notebook? = if (id == null) null else
    (this as? IndexedItems<Notebook>)?.byId(id) ?: firstOrNull { it.id == id }
internal fun List<NotePage>.page(id: String?): NotePage? = if (id == null) null else
    (this as? IndexedItems<NotePage>)?.byId(id) ?: firstOrNull { it.id == id }
