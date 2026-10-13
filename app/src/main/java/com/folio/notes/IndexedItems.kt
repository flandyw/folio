package com.folio.notes

/** Ordered immutable values with shared ID lookup and O(log n) replacement. */
internal class IndexedItems<T> private constructor(
    private val tree: Node<T>?, private val positions: Map<String, Int>,
    private val identity: (T) -> String,
    internal val previous: IndexedItems<T>? = null, internal val changedIndex: Int? = null,
) : AbstractList<T>() {
    private class Node<T>(val size: Int, val value: T? = null, val left: Node<T>? = null, val right: Node<T>? = null)
    override val size: Int get() = tree?.size ?: 0
    /** Stable across replacements; contains IDs/positions only, so caches never retain old content. */
    internal val membershipIdentity: Any get() = positions
    override fun get(index: Int): T {
        if (index !in indices) throw IndexOutOfBoundsException("Index: $index, size: $size")
        var node = tree!!; var offset = index
        while (node.size > 1) {
            val left = node.left!!
            if (offset < left.size) node = left else { offset -= left.size; node = node.right!! }
        }
        @Suppress("UNCHECKED_CAST") return node.value as T
    }
    // AbstractList's iterator calls get for every item, turning full scans into O(n log n).
    // Walk shared branches directly, keeping only O(log n) pending nodes per iterator.
    override fun iterator(): Iterator<T> = object : Iterator<T> {
        private val pending = java.util.ArrayDeque<Node<T>>().apply { tree?.let(::addLast) }
        override fun hasNext(): Boolean = pending.isNotEmpty()
        override fun next(): T {
            if (!hasNext()) throw NoSuchElementException()
            var node = pending.removeLast()
            while (node.size > 1) {
                pending.addLast(node.right!!)
                node = node.left!!
            }
            @Suppress("UNCHECKED_CAST") return node.value as T
        }
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
    fun indexOfId(id: String): Int = positions[id] ?: -1
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

    /** Changed values in document order, or null when membership/order must be rebuilt. */
    internal fun changedItemsFrom(old: List<T>): List<T>? {
        if (old !is IndexedItems<T> || positions !== old.positions) return null
        if (tree === old.tree) return emptyList()
        changedFrom(old)?.let { return listOf(get(it)) }
        val changed = ArrayList<T>()
        fun visit(current: Node<T>?, before: Node<T>?) {
            if (current === before) return
            if (current!!.size == 1) {
                @Suppress("UNCHECKED_CAST") changed.add(current.value as T)
            } else {
                visit(current.left, before!!.left)
                visit(current.right, before.right)
            }
        }
        visit(tree, old.tree)
        return changed
    }

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
    if (this is IndexedItems<Notebook>) byId(id) else firstOrNull { it.id == id }
internal fun List<NotePage>.page(id: String?): NotePage? = if (id == null) null else
    if (this is IndexedItems<NotePage>) byId(id) else firstOrNull { it.id == id }

internal fun List<NotePage>.pageIndex(id: String): Int =
    if (this is IndexedItems<NotePage>) indexOfId(id) else indexOfFirst { it.id == id }
