package com.stash.core.media.streaming

import com.google.common.truth.Truth.assertThat
import com.stash.core.data.addons.EclipseAddonTrack
import com.stash.core.data.db.entity.TrackEntity
import org.junit.Test

class EclipseAddonMatcherTest {
    private val track = TrackEntity(
        title = "Blinding Lights",
        artist = "The Weeknd",
        durationMs = 200_000,
    )

    private fun candidate(
        id: String = "id",
        title: String = "Blinding Lights",
        artist: String = "The Weeknd",
        duration: Long = 200,
        format: String? = null,
        isrc: String? = null,
    ) = EclipseAddonTrack(
        id = id,
        title = title,
        artist = artist,
        duration = duration,
        format = format,
        isrc = isrc,
    )

    @Test
    fun `matching ISRC wins immediately, whatever the title says`() {
        val best = EclipseAddonMatcher.best(
            track.copy(isrc = "USUM71923546"),
            listOf(
                candidate(id = "wrong", title = "Something Else", artist = "Other", isrc = null),
                candidate(id = "right", title = "Blinding Lights (Radio Edit)", isrc = "usum71923546"),
            ),
        )

        assertThat(best?.id).isEqualTo("right")
    }

    @Test
    fun `exact normalised title and artist match, with punctuation and diacritics folded`() {
        val accented = TrackEntity(
            title = "Été — C'est la vie!",
            artist = "Beyoncé & Jay-Z",
            durationMs = 180_000,
        )

        val best = EclipseAddonMatcher.best(
            accented,
            listOf(candidate(title = "ete c est la vie", artist = "beyonce and jay z", duration = 180)),
        )

        assertThat(best).isNotNull()
    }

    @Test
    fun `a title that only one side marks live, remix, or instrumental is rejected`() {
        for ((wanted, other) in listOf(
            "Blinding Lights" to "Blinding Lights (Live)",
            "Blinding Lights (Remix)" to "Blinding Lights",
            "Blinding Lights (Instrumental)" to "Blinding Lights",
        )) {
            val best = EclipseAddonMatcher.best(
                track.copy(title = wanted),
                listOf(candidate(title = other)),
            )
            assertThat(best).isNull()
        }
    }

    @Test
    fun `durations beyond 15 seconds apart are rejected, and close ones accepted`() {
        assertThat(EclipseAddonMatcher.best(track, listOf(candidate(duration = 214)))).isNotNull()
        assertThat(EclipseAddonMatcher.best(track, listOf(candidate(duration = 216)))).isNull()
    }

    @Test
    fun `an artist that contains the other matches`() {
        assertThat(EclipseAddonMatcher.best(track, listOf(candidate(artist = "Weeknd")))).isNotNull()
        assertThat(EclipseAddonMatcher.best(track, listOf(candidate(artist = "Drake")))).isNull()
    }

    @Test
    fun `lossless is preferred over lossy among equally matching candidates`() {
        val best = EclipseAddonMatcher.best(
            track,
            listOf(
                candidate(id = "mp3", duration = 200, format = "mp3"),
                candidate(id = "flac", duration = 205, format = "flac"),
            ),
        )

        assertThat(best?.id).isEqualTo("flac")
    }

    @Test
    fun `no candidate at all is a null match`() {
        assertThat(EclipseAddonMatcher.best(track, emptyList())).isNull()
    }
}
