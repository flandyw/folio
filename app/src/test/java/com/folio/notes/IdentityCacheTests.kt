package com.folio.notes

import java.util.Collections
import java.util.IdentityHashMap
import org.junit.Assert.*
import org.junit.Test

class IdentityCacheTests {
    @Test fun exceedingCapacityEvictsOneEntryAndUsesIdentity() {
        val cache = IdentityCache<List<Int>, Any>(2)
        val a = listOf(1)
        val equalA = listOf(1)
        val b = listOf(2)
        val first = cache.getOrPut(a) { Any() }
        val second = cache.getOrPut(equalA) { Any() }
        assertNotSame(first, second)
        // LRU: touching `a` makes it most-recent, so `equalA` (least-recent) is evicted next.
        assertSame(first, cache.getOrPut(a) { error("cache miss") })
        cache.getOrPut(b) { Any() }
        assertSame(first, cache.getOrPut(a) { error("MRU entry lost") })
        assertNotSame(second, cache.getOrPut(equalA) { Any() })
    }

    @Test fun leastRecentlyUsedStaysResidentWhilePanning() {
        val cache = IdentityCache<Any, Any>(3)
        val a = Any(); val b = Any(); val c = Any(); val d = Any()
        val keptA = cache.getOrPut(a) { Any() }
        cache.getOrPut(b) { Any() }
        cache.getOrPut(c) { Any() }
        // Visible working set keeps hitting `a`; a new stroke must evict `b`, not `a`.
        assertSame(keptA, cache.getOrPut(a) { error("hit lost") })
        cache.getOrPut(d) { Any() }
        assertSame(keptA, cache.getOrPut(a) { error("visible stroke evicted") })
        assertEquals(3, cache.size)
    }

    @Test fun pruningAndClearingReleaseOldEntries() {
        val cache = IdentityCache<Any, Any>(2)
        val a = Any(); val b = Any(); val c = Any()
        val value = cache.getOrPut(a) { Any() }
        cache.getOrPut(b) { Any() }
        cache.retainAll(Collections.newSetFromMap(IdentityHashMap<Any, Boolean>()).apply { add(a) })
        cache.getOrPut(c) { Any() }
        assertSame(value, cache.getOrPut(a) { error("retained entry lost") })
        cache.clear()
        assertNotSame(value, cache.getOrPut(a) { Any() })
    }
}
