package com.folio.notes.music

import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Test

class MusicSetTest {
    @Test fun duplicateKeepsRunningOrderAndLeavesOriginalAlone() {
        val source = MusicSet("source", "Recital", listOf("second", "first", "encore"))
        val original = MusicLibrary(sets = listOf(source))
        val duplicate = MusicSet("copy", "Recital (copy)", source.scores)

        val copied = original.withDuplicatedSet(source.id, "copy")

        assertEquals(listOf(source, duplicate), copied.sets)
        assertEquals(listOf(source), original.sets)
        assertEquals(copied, MusicCodec.decode(MusicCodec.encode(copied)))
    }

    @Test fun repeatedCopiesSkipNamesAlreadyInUseIgnoringCase() {
        val source = MusicSet("source", "Recital")
        val existing = MusicSet("existing", "RECITAL (COPY)")
        val library = MusicLibrary(sets = listOf(source, existing))

        val first = library.withDuplicatedSet(source.id, "first")
        val second = first.withDuplicatedSet(source.id, "second")

        assertEquals(MusicSet("first", "Recital (copy 2)"), first.sets.last())
        assertEquals(MusicSet("second", "Recital (copy 3)"), second.sets.last())
    }

    @Test fun missingSourceDoesNotCreateACopy() {
        val library = MusicLibrary(sets = listOf(MusicSet("source", "Recital")))
        assertSame(library, library.withDuplicatedSet("missing", "copy"))
    }
}
