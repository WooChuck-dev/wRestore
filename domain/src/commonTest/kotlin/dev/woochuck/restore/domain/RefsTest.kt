package dev.woochuck.restore.domain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class RefsTest {
    private val repo = InMemoryRepo()
    private fun add(id: String, player: String = "p") = repo.insert(snap(player = player).copy(id = id))
    private val listing = Listing("p", mapOf(1 to "aaaaaaaa-1", 2 to "bbbbbbbb-2"))

    @Test fun rowNumbersWithAndWithoutHash() {
        assertEquals("aaaaaaaa-1", Refs.resolve("#1", listing, repo).getOrThrow())
        assertEquals("bbbbbbbb-2", Refs.resolve("2", listing, repo).getOrThrow())
    }

    @Test fun unknownRowAndMissingListingFail() {
        assertTrue(Refs.resolve("#9", listing, repo).isFailure)
        assertTrue(Refs.resolve("1", null, repo).isFailure)
        assertTrue(Refs.resolve("latest", null, repo).isFailure)
    }

    @Test fun latestIsTheNewestDeathOfTheListedPlayer() {
        add("11111111-a"); add("22222222-b"); add("33333333-c", player = "other")
        assertEquals("22222222-b", Refs.resolve("latest", listing, repo).getOrThrow())
    }

    @Test fun uniquePrefixResolvesAndAmbiguousOrShortFails() {
        add("abcdef01-x"); add("abcdef02-y"); add("123456ff-z")
        assertEquals("123456ff-z", Refs.resolve("123456", null, repo).getOrThrow())
        assertEquals("abcdef01-x", Refs.resolve("abcdef01", null, repo).getOrThrow())
        assertTrue(Refs.resolve("abcdef", null, repo).isFailure)   // ambiguous
        assertTrue(Refs.resolve("abcde", null, repo).isFailure)    // shorter than 6
        assertTrue(Refs.resolve("zzzzzzzz", null, repo).isFailure) // not hex
        assertTrue(Refs.resolve("ffffff", null, repo).isFailure)   // no match
    }

    @Test fun exactIdWinsOverLongerIdsSharingItsPrefix() {
        add("abcdef"); add("abcdef-longer")
        assertEquals("abcdef", Refs.resolve("abcdef", null, repo).getOrThrow())
    }
}
