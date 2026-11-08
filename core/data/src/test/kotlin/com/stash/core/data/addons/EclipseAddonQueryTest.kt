package com.stash.core.data.addons

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class EclipseAddonQueryTest {
    @Test
    fun `a version suffix is stripped, remix and live are not`() {
        assertThat(EclipseAddonQuery.cleanedTitle("Stay - Ely Oaks Version")).isEqualTo("Stay")
        assertThat(EclipseAddonQuery.cleanedTitle("Stay (Ely Oaks Version)")).isEqualTo("Stay")
        assertThat(EclipseAddonQuery.cleanedTitle("Song - Remix")).isEqualTo("Song - Remix")
        assertThat(EclipseAddonQuery.cleanedTitle("Song (Live)")).isEqualTo("Song (Live)")
        assertThat(EclipseAddonQuery.cleanedTitle("Song - Extended Version")).isEqualTo("Song")
        assertThat(EclipseAddonQuery.cleanedTitle("Song")).isEqualTo("Song")
    }

    @Test
    fun `the primary artist stops at the usual credit separators`() {
        assertThat(EclipseAddonQuery.primaryArtist("Ely Oaks, Misdemeanor")).isEqualTo("Ely Oaks")
        assertThat(EclipseAddonQuery.primaryArtist("Ely Oaks & Misdemeanor")).isEqualTo("Ely Oaks")
        assertThat(EclipseAddonQuery.primaryArtist("A feat. B")).isEqualTo("A")
        assertThat(EclipseAddonQuery.primaryArtist("A ft. B")).isEqualTo("A")
        assertThat(EclipseAddonQuery.primaryArtist("A x B")).isEqualTo("A")
        assertThat(EclipseAddonQuery.primaryArtist("Ely Oaks")).isEqualTo("Ely Oaks")
    }

    @Test
    fun `variants put the full query first and deduplicate`() {
        val variants = EclipseAddonQuery.variants("Stay - Ely Oaks Version", "Ely Oaks, Misdemeanor")

        assertThat(variants).containsExactly(
            "Stay - Ely Oaks Version" to "Ely Oaks, Misdemeanor",
            "Stay" to "Ely Oaks",
            "Stay - Ely Oaks Version" to "Ely Oaks",
        ).inOrder()

        assertThat(EclipseAddonQuery.variants("Song", "Artist")).containsExactly("Song" to "Artist")
    }
}
