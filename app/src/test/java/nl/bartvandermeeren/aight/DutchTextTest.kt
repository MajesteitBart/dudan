package nl.bartvandermeeren.aight

import nl.bartvandermeeren.aight.voice.DutchText
import org.junit.Assert.assertEquals
import org.junit.Test

class DutchTextTest {
    @Test
    fun numbersAreSpelledTheStandardWay() {
        val expected = mapOf(
            0L to "nul", 8L to "acht", 13L to "dertien", 21L to "eenentwintig", 22L to "tweeëntwintig", 33L to "drieëndertig",
            80L to "tachtig", 99L to "negenennegentig", 100L to "honderd", 101L to "honderdeen", 186L to "honderdzesentachtig",
            1000L to "duizend", 1005L to "duizend vijf", 1250L to "twaalfhonderdvijftig", 1995L to "negentienhonderdvijfennegentig",
            2026L to "tweeduizend zesentwintig", 22_000L to "tweeëntwintigduizend", 400_000L to "vierhonderdduizend",
            1_000_000L to "een miljoen", 3_400_000L to "drie miljoen vierhonderdduizend",
        )
        expected.forEach { (n, words) -> assertEquals("$n", words, DutchText.number(n)) }
    }

    @Test
    fun ordinals() {
        val expected = mapOf(
            1L to "eerste", 2L to "tweede", 3L to "derde", 8L to "achtste", 11L to "elfde", 18L to "achttiende",
            20L to "twintigste", 21L to "eenentwintigste", 100L to "honderdste", 101L to "honderdeerste", 1000L to "duizendste",
        )
        expected.forEach { (n, words) -> assertEquals("$n", words, DutchText.ordinal(n)) }
    }

    @Test
    fun timesAmountsAndDatesAreWrittenOut() {
        assertEquals(
            "De trein naar Utrecht vertrekt om veertien uur vijfendertig vanaf spoor acht. Het kaartje kost twaalf euro vijftig. " +
                "Op drie oktober spreken we af in 's-Hertogenbosch.",
            DutchText.normalize(
                "De trein naar Utrecht vertrekt om 14:35 vanaf spoor 8. Het kaartje kost € 12,50. Op 3 oktober spreken we af in 's-Hertogenbosch.",
            ),
        )
        assertEquals("Om negen uur bel ik, of om half vier.", DutchText.normalize("Om 9:00 bel ik, of om half vier."))
        assertEquals("Vertrek om veertien uur vijf.", DutchText.normalize("Vertrek om 14.05 uur."))
        assertEquals(
            "Het kost twaalfhonderdvijftig euro, drie komma vijf miljoen euro of vijftig cent.",
            DutchText.normalize("Het kost €1.250, € 3,5 miljoen of 0,50 euro."),
        )
        assertEquals("Dat is twaalf euro.", DutchText.normalize("Dat is € 12,-."))
        assertEquals(
            "Op vijfentwintig september tweeduizend zesentwintig of vijfentwintig september tweeduizend zesentwintig.",
            DutchText.normalize("Op 25-09-2026 of 2026-09-25."),
        )
    }

    @Test
    fun percentagesOrdinalsRangesAndPhoneNumbers() {
        assertEquals("vijftien procent korting, of twaalf komma vijf procent extra.", DutchText.normalize("15% korting, of 12,5% extra."))
        assertEquals("De derde week, met tien tot twaalf mensen.", DutchText.normalize("De 3e week, met 10-12 mensen."))
        assertEquals("Bel nul zes een twee drie vier vijf zes zeven acht.", DutchText.normalize("Bel 06-12345678."))
        assertEquals("Nog vijf kilometer bij twintig graden.", DutchText.normalize("Nog 5 km bij 20°C."))
    }

    @Test
    fun abbreviationsAreExpandedAndKeepTheirSentenceEnd() {
        assertEquals(
            "Bijvoorbeeld morgen, onder andere met Anna enzovoort. Daarna lunch.",
            DutchText.normalize("Bijv. morgen, o.a. met Anna enz. Daarna lunch."),
        )
        assertEquals("Kamer nummer vier, tot en met vrijdag.", DutchText.normalize("Kamer nr. 4, t/m vrijdag."))
    }

    @Test
    fun numbersInsideWordsStayAsTheyAre() {
        assertEquals("Een mp3, 5G en COVID-negentien.", DutchText.normalize("Een mp3, 5G en COVID-19."))
    }
}
