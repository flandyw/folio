package com.folio.notes

import java.util.IdentityHashMap

/**
 * Bounded LRU cache for immutable objects, without deep hashing or whole-cache flushes.
 *
 * Identity-keyed: two equal-but-distinct instances occupy two entries, which is what lets
 * untouched strokes keep their geometry while undone fragments are dropped. Hits promote in
 * O(1) so a dense page's visible strokes stay resident while panning instead of churning.
 */
internal class IdentityCache<K : Any, V : Any>(private val capacity: Int) {
    init { require(capacity > 0) }
    private inner class Node(val key: K, var value: V, var prev: Node? = null, var next: Node? = null)
    private val values = IdentityHashMap<K, Node>()
    private var head: Node? = null
    private var tail: Node? = null

    val size: Int get() = values.size

    fun getOrPut(key: K, create: () -> V): V {
        values[key]?.let { node ->
            moveToTail(node)
            return node.value
        }
        val node = Node(key, create())
        appendTail(node)
        values[key] = node
        if (values.size > capacity) {
            head?.let { evict ->
                removeNode(evict)
                values.remove(evict.key)
            }
        }
        return node.value
    }

    private fun moveToTail(node: Node) {
        if (tail === node) return
        removeNode(node)
        appendTail(node)
    }

    private fun appendTail(node: Node) {
        node.prev = tail
        node.next = null
        tail?.next = node
        tail = node
        if (head == null) head = node
    }

    private fun removeNode(node: Node) {
        val p = node.prev
        val n = node.next
        if (p != null) p.next = n else head = n
        if (n != null) n.prev = p else tail = p
        node.prev = null
        node.next = null
    }

    fun retainAll(keys: Set<K>) {
        var cursor = head
        while (cursor != null) {
            val next = cursor.next
            if (!keys.contains(cursor.key)) {
                removeNode(cursor)
                values.remove(cursor.key)
            }
            cursor = next
        }
    }

    fun clear() { values.clear(); head = null; tail = null }
}
