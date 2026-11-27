package com.interop.ridepowerble

import android.speech.tts.TextToSpeech
import java.util.Locale

/**
 * Voice guidance voices. Google's speech engine names its voices by accent + letters; we look
 * for a few known ones per choice and, if the phone doesn't have them, fall back to that accent
 * with a lower (male) or higher (female) pitch so the choice still sounds different.
 */
object Voices {
    data class Choice(val key: String, val label: String, val locale: Locale, val male: Boolean?, val names: List<String>)

    val ALL = listOf(
        Choice("default", "Phone's default voice", Locale.UK, null, emptyList()),
        Choice("gb_f", "British — female", Locale.UK, false, listOf("en-gb-x-gba", "en-gb-x-gbc", "en-gb-x-gbg")),
        Choice("gb_m", "British — male", Locale.UK, true, listOf("en-gb-x-gbb", "en-gb-x-gbd", "en-gb-x-rjs")),
        Choice("us_f", "American — female", Locale.US, false, listOf("en-us-x-tpf", "en-us-x-sfg", "en-us-x-tpc")),
        Choice("au_m", "Australian — male", Locale("en", "AU"), true, listOf("en-au-x-aub", "en-au-x-aud")),
    )

    fun of(key: String?) = ALL.firstOrNull { it.key == key } ?: ALL[0]

    /** Sets [tts] to the chosen voice. Returns a short note if the phone lacks that exact voice. */
    fun apply(tts: TextToSpeech, key: String?): String? {
        val c = of(key)
        tts.setPitch(1f); tts.setSpeechRate(1f)
        if (c.male == null) { tts.language = Locale.UK; return null }
        val voices = try { tts.voices ?: emptySet() } catch (_: Exception) { emptySet() }
        // prefer the on-phone ("local") copy so guidance works with no signal
        val found = c.names.firstNotNullOfOrNull { n ->
            voices.filter { it.name.startsWith(n) && !it.isNetworkConnectionRequired }.minByOrNull { it.name.length }
                ?: voices.firstOrNull { it.name.startsWith(n) }
        }
        if (found != null) { tts.voice = found; return null }
        tts.language = c.locale
        tts.setPitch(if (c.male) 0.82f else 1.15f)
        return "This phone doesn't have that exact voice, so it's using the ${c.label.substringBefore(" ")} accent with a " +
            (if (c.male) "deeper" else "higher") + " pitch. Google's “Speech Services” app adds more voices."
    }
}
