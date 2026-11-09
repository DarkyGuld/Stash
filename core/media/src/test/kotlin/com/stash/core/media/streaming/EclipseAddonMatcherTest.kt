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

    @Test
    fun `a trailing version suffix accepts the plain title as an alias`() {
        val versioned = TrackEntity(
            title = "Stay - Ely Oaks Version",
            artist = "Ely Oaks, Misdemeanor",
            durationMs = 159_246,
        )
        val best = EclipseAddonMatcher.best(
            versioned,
            listOf(
                EclipseAddonTrack(
                    id = "plain",
                    title = "Stay",
                    artist = "Ely Oaks & Misdemeanor",
                    duration = 159_246,
                ),
            ),
        )

        assertThat(best?.id).isEqualTo("plain")
    }

    @Test
    fun `a bracketed remix with a multi-artist credit matches the same recording`() {
        val track = TrackEntity(
            title = "Dancin (feat. Luvli) - southstar Remix",
            artist = "Aaron Smith, Luvli, southstar",
            durationMs = 173_333,
        )
        val best = EclipseAddonMatcher.best(
            track,
            listOf(
                EclipseAddonTrack(
                    id = "apple",
                    title = "Dancin (feat. Luvli) [southstar Remix]",
                    artist = "Aaron Smith & southstar",
                    duration = 173_333,
                ),
            ),
        )

        assertThat(best?.id).isEqualTo("apple")
    }

    @Test
    fun `remix and live titles never alias to the plain song`() {
        for ((trackTitle, candidateTitle) in listOf(
            "Song - Remix" to "Song",
            "Song (Live)" to "Song",
            "Song - Remix Version" to "Song",
        )) {
            val best = EclipseAddonMatcher.best(
                TrackEntity(title = trackTitle, artist = "Artist", durationMs = 200_000),
                listOf(EclipseAddonTrack(id = "plain", title = candidateTitle, artist = "Artist", duration = 200)),
            )
            assertThat(best).isNull()
        }
    }

    @Test
    fun `a Radio Edit resolves to the catalogue's Edit when the lengths agree within two seconds`() {
        val edit = TrackEntity(
            title = "Intoxicated - Radio Edit",
            artist = "Martin Solveig, Good Times Ahead",
            durationMs = 159_560,
        )
        val best = EclipseAddonMatcher.best(
            edit,
            listOf(
                EclipseAddonTrack(
                    id = "long",
                    title = "Intoxicated",
                    artist = "Martin Solveig & Good Times Ahead",
                    duration = 255_934,
                ),
                EclipseAddonTrack(
                    id = "edit",
                    title = "Intoxicated (Edit)",
                    artist = "Martin Solveig & Good Times Ahead",
                    duration = 159_389,
                ),
            ),
        )

        assertThat(best?.id).isEqualTo("edit")
    }

    @Test
    fun `a differently sized edit is a different file and is rejected`() {
        val edit = TrackEntity(
            title = "Intoxicated - Radio Edit",
            artist = "Martin Solveig",
            durationMs = 159_560,
        )
        val best = EclipseAddonMatcher.best(
            edit,
            listOf(
                EclipseAddonTrack(
                    id = "other-edit",
                    title = "Intoxicated (Edit)",
                    artist = "Martin Solveig",
                    duration = 190_000,
                ),
            ),
        )

        assertThat(best).isNull()
    }

    @Test
    fun `an edit alias still never matches a remix or a live version`() {
        val edit = TrackEntity(title = "Intoxicated - Radio Edit", artist = "Martin Solveig", durationMs = 159_560)
        val best = EclipseAddonMatcher.best(
            edit,
            listOf(
                EclipseAddonTrack(id = "remix", title = "Intoxicated (Edit) [Remix]", artist = "Martin Solveig", duration = 159_500),
                EclipseAddonTrack(id = "live", title = "Intoxicated - Radio Edit (Live)", artist = "Martin Solveig", duration = 159_500),
            ),
        )

        assertThat(best).isNull()
    }

    @Test
    fun `a Radio Edit resolves to the unsuffixed catalogue title at the same length`() {
        val edit = TrackEntity(
            title = "Freed From Desire - Radio Edit",
            artist = "Fun[k]House",
            durationMs = 183_089,
        )
        val best = EclipseAddonMatcher.best(
            edit,
            listOf(
                EclipseAddonTrack(id = "club", title = "Freed From Desire (Club Mix)", artist = "Fun{k}house", duration = 256_383),
                EclipseAddonTrack(id = "plain", title = "Freed From Desire", artist = "Fun{k}house", duration = 183_089),
            ),
        )

        assertThat(best?.id).isEqualTo("plain")
    }

    @Test
    fun `YouTube presentation tags name the same take in both directions`() {
        val taggedTitles = listOf(
            "Blinding Lights (Official Video)",
            "Blinding Lights [Official Music Video]",
            "Blinding Lights (Lyric Video)",
            "Blinding Lights (Audio)",
            "Blinding Lights (Visualizer)",
            "Blinding Lights (HD)",
            "Blinding Lights - Official Video",
            "Blinding Lights - Official Music Video HD",
        )
        for (tagged in taggedTitles) {
            assertThat(EclipseAddonMatcher.best(track.copy(title = tagged), listOf(candidate(title = "Blinding Lights"))))
                .isNotNull()
            assertThat(EclipseAddonMatcher.best(track, listOf(candidate(title = tagged))))
                .isNotNull()
        }
    }

    @Test
    fun `acoustic, re-recorded and live takes are still rejected`() {
        for (other in listOf(
            "Blinding Lights (Acoustic)",
            "Blinding Lights - Acoustic",
            "Blinding Lights (Re-Recorded)",
            "Blinding Lights (Live)",
            "Blinding Lights (Instrumental)",
        )) {
            assertThat(EclipseAddonMatcher.best(track, listOf(candidate(title = other)))).isNull()
        }
    }

    @Test
    fun `artist credits compare as normalized sorted sets, and a subset still matches`() {
        val dancin = TrackEntity(
            title = "Dancin",
            artist = "Aaron Smith, Luvli, southstar",
            durationMs = 173_333,
        )
        fun hit(artist: String) = EclipseAddonTrack(id = "c", title = "Dancin", artist = artist, duration = 173)

        assertThat(EclipseAddonMatcher.best(dancin, listOf(hit("southstar & Aaron Smith")))).isNotNull()
        assertThat(EclipseAddonMatcher.best(dancin, listOf(hit("Aaron Smith, Luvli")))).isNotNull()
        assertThat(EclipseAddonMatcher.best(dancin, listOf(hit("Drake, Future")))).isNull()
    }

    @Test
    fun `a namesake band never matches on a shared prefix`() {
        assertThat(EclipseAddonMatcher.best(track, listOf(candidate(artist = "The Weeknd Tribute Band")))).isNull()
        assertThat(EclipseAddonMatcher.best(track, listOf(candidate(artist = "Weeknd")))).isNotNull()
    }

    @Test
    fun `a video-like row's duration is loose`() {
        val tagged = track.copy(title = "Blinding Lights (Official Video)", durationMs = 200_000)
        assertThat(EclipseAddonMatcher.best(tagged, listOf(candidate(duration = 260)))).isNotNull()

        val omv = track.copy(musicVideoType = "OMV")
        assertThat(EclipseAddonMatcher.best(omv, listOf(candidate(duration = 260)))).isNotNull()

        val prefixed = track.copy(musicVideoType = "MUSIC_VIDEO_TYPE_UGC")
        assertThat(EclipseAddonMatcher.best(prefixed, listOf(candidate(duration = 260)))).isNotNull()

        // ATV is the studio master, so its duration is still the audio length.
        val atv = track.copy(musicVideoType = "ATV")
        assertThat(EclipseAddonMatcher.best(atv, listOf(candidate(duration = 260)))).isNull()
    }

    @Test
    fun `a loose video duration still rejects a live or long edit`() {
        val video = track.copy(title = "Blinding Lights (Official Video)", durationMs = 200_000)
        for (other in listOf("Blinding Lights (Live)", "Blinding Lights (Extended Mix)", "Blinding Lights (Remix)")) {
            assertThat(EclipseAddonMatcher.best(video, listOf(candidate(title = other, duration = 260)))).isNull()
        }

        // A Radio Edit keeps its 2 s rule even when the imported row is a video.
        val edit = TrackEntity(
            title = "Intoxicated - Radio Edit",
            artist = "Martin Solveig",
            durationMs = 159_560,
            musicVideoType = "OMV",
        )
        assertThat(
            EclipseAddonMatcher.best(
                edit,
                listOf(candidate(title = "Intoxicated", artist = "Martin Solveig", duration = 255)),
            ),
        ).isNull()

        // A presentation tag on the edit does not hide its length from the 2 s rule.
        val taggedEdit = edit.copy(title = "Intoxicated - Radio Edit (Official Video)")
        assertThat(
            EclipseAddonMatcher.best(
                taggedEdit,
                listOf(candidate(title = "Intoxicated", artist = "Martin Solveig", duration = 255)),
            ),
        ).isNull()
    }
}
