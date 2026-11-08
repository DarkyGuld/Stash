package com.stash.data.spotify

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpotifyIsrcBatchParserTest {
    @Test
    fun `batching parses every track that has an ISRC`() {
        val body = """
            {"tracks":[
              {"id":"a","external_ids":{"isrc":"ISRC-A"}},
              {"id":"b","external_ids":{}},
              {"id":"c","external_ids":{"isrc":"ISRC-C"}},
              null
            ]}
        """.trimIndent()

        val map = parseIsrcBatch(body)

        assertEquals(mapOf("a" to "ISRC-A", "c" to "ISRC-C"), map)
    }

    @Test
    fun `an error body parses as empty`() {
        assertTrue(parseIsrcBatch("""{"error":{"status":429}}""").isEmpty())
        assertTrue(parseIsrcBatch("<html>nope</html>").isEmpty())
    }
}
