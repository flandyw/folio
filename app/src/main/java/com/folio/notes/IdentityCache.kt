package com.folio.notes

import java.util.ArrayDeque
import java.util.IdentityHashMap

/** Bounded FIFO cache for immutable objects, without deep hashing or whole-cache flushes. */
internal class IdentityCache<K : Any, V : Any>(private val capacity: Int) {
    init { require(capacity > 0) }
    private val values = IdentityHashMap<K, V>()
    private val order = ArrayDeque<K>()

    fun getOrPut(key: K, create: () -> V): V {
        values[key]?.let { return it }
        val value = create()
        if (order.size == capacity) values.remove(order.removeFirst())
        values[key] = value
        order.addLast(key)
        return value
    }

    fun retainAll(keys: Set<K>) {
        val iterator = order.iterator()
        while (iterator.hasNext()) {
            val key = iterator.next()
            if (key !in keys) { values.remove(key); iterator.remove() }
        }
    }

    fun clear() { values.clear(); order.clear() }
}
