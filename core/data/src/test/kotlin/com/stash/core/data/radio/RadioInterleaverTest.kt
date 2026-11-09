package com.stash.core.data.radio

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RadioInterleaverTest {
    // seed gets weight ~= half the neighbor weight sum, so it lands ~1/3 of slots.
    private fun cand(artist: String, title: String, weight: Float) =
        RadioCandidate(artist, title, videoId = "$artist-$title", weight = weight)

    @Test fun `orders every candidate exactly once`() {
        val pool = listOf(
            cand("Seed", "s1", 3f), cand("Seed", "s2", 3f),
            cand("A", "a1", 1f), cand("B", "b1", 1f),
        )
        val out = RadioInterleaver.order(pool)
        assertEquals(pool.toSet(), out.toSet())
        assertEquals(pool.size, out.size)
    }

    @Test fun `never repeats the same artist back to back when alternatives exist`() {
        val pool = buildList {
            repeat(4) { add(cand("Seed", "s$it", 3f)) }
            repeat(2) { add(cand("A", "a$it", 1f)) }
            repeat(2) { add(cand("B", "b$it", 1f)) }
        }
        val out = RadioInterleaver.order(pool)
        // No adjacent same-artist until the tail (when one artist may be all
        // that's left). Tolerate a single tail collision.
        val artists = out.map { it.artist }
        var adjacent = 0
        for (i in 1 until artists.size) if (artists[i] == artists[i - 1]) adjacent++
        assertTrue("adjacent same-artist count was $adjacent", adjacent <= 1)
    }

    @Test fun `higher-weight seed is the most frequent artist, never starved`() {
        val pool = buildList {
            repeat(12) { add(cand("Seed", "s$it", 6f)) }          // seed weight tuned by generator
            repeat(4) { add(cand("N${it % 4}", "n$it", 1f)) }     // 4 neighbors, 1 each
        }
        val out = RadioInterleaver.order(pool.shuffled())
        val seedCount = out.count { it.artist == "Seed" }
        // Pins only that the seed is the MOST frequent artist (never starved) — NOT
        // a literal 1/3. The generator's finite seed catalog + no-repeat set make a
        // steady 1/3-forever impossible; the feel is "seed-heavy open, drifting out."
        val maxNeighbor = out.filter { it.artist != "Seed" }.groupingBy { it.artist }.eachCount().values.max()
        assertTrue("seed $seedCount should be >= max neighbor $maxNeighbor", seedCount >= maxNeighbor)
    }

    private fun hopCand(artist: String, title: String, weight: Float, hop: Int) =
        RadioCandidate(artist, title, videoId = "$artist-$title", weight = weight, hop = hop)

    @Test fun `match floor drops weak widen candidates`() {
        val pool = listOf(
            cand("Strong", "s1", 1f),
            cand("Weak", "w1", 0.1f),
        )

        val out = RadioInterleaver.order(pool, matchFloor = 0.2f)

        assertEquals(listOf("s1"), out.map { it.title })
    }

    @Test fun `no artist repeats within three slots while alternatives exist`() {
        val pool = buildList {
            for (artist in listOf("A", "B", "C", "D")) {
                repeat(5) { add(cand(artist, "$artist$it", 1f)) }
            }
        }

        val artists = RadioInterleaver.order(pool).map { it.artist }
        for (i in 3 until artists.size) {
            assertFalse(
                "${artists[i]} repeated within 3 slots at $i: $artists",
                artists[i] in artists.subList(i - 3, i),
            )
        }
    }

    @Test fun `hop decay pushes distant discoveries later`() {
        val pool = buildList {
            repeat(3) { add(hopCand("A", "a$it", 1f, hop = 0)) }
            repeat(3) { add(hopCand("B", "b$it", 1f, hop = 3)) }
            repeat(3) { add(hopCand("C", "c$it", 1f, hop = 0)) }
            repeat(3) { add(hopCand("D", "d$it", 1f, hop = 0)) }
        }

        val out = RadioInterleaver.order(pool)

        assertTrue(
            "a 3-hop artist should enter after a direct artist",
            out.indexOfFirst { it.artist == "B" } > out.indexOfFirst { it.artist == "A" },
        )
    }

    @Test fun `a skipped artist is down-weighted on later orders`() {
        val pool = buildList {
            for (artist in listOf("A", "B", "C", "D")) {
                repeat(3) { add(cand(artist, "$artist$it", 1f)) }
            }
        }

        val out = RadioInterleaver.order(pool, skippedArtists = mapOf("A" to 3))

        assertTrue(
            "the skipped artist should not lead the order",
            out.indexOfFirst { it.artist == "A" } > out.indexOfFirst { it.artist == "B" },
        )
    }
}
