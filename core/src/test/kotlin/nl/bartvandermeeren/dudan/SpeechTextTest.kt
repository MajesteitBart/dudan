package nl.bartvandermeeren.dudan

import nl.bartvandermeeren.dudan.voice.SpeechText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class SpeechTextTest {
    @Test
    fun listItemsAndHeadingsGetAPause() {
        val text = SpeechText.withPauses(SpeechText.fromMarkdown("## Today\n\n- Standup at 9\n- Lunch with Anna"))
        assertEquals("Today.\nStandup at 9.\nLunch with Anna.", text)
    }

    @Test
    fun eachSentenceStreamsAloneAndShortOnesJoinTheNext() {
        val text = "Hoi Bart, je volgende afspraak begint om half drie. Wil je de punten horen? Ik heb er drie. En een vraag."
        assertEquals(
            listOf("Hoi Bart, je volgende afspraak begint om half drie.", "Wil je de punten horen? Ik heb er drie.", "En een vraag."),
            SpeechText.streamingChunks(text, 200),
        )
    }

    @Test
    fun aVeryShortFirstSentenceTakesTheNextAlong() {
        assertEquals(listOf("Oké. Ik zet het in je agenda.", "Nog iets?"), SpeechText.streamingChunks("Oké. Ik zet het in je agenda. Nog iets?", 30))
    }

    @Test
    fun aShortFirstSentenceStaysAloneWhenTheNextIsLong() {
        val second = "Dit is een testantwoord van de mock-server op een vraag over de trein van vanmiddag om kwart over drie."
        assertEquals(listOf("Goede vraag.", second), SpeechText.streamingChunks("Goede vraag. $second", 200))
    }

    @Test
    fun longSentencesAreCutAfterACommaWithinTheLimit() {
        val chunks = SpeechText.streamingChunks("Dit is een lange zin met veel woorden, en daarna komt er nog een stuk tekst achteraan.", 50)
        assertEquals(listOf("Dit is een lange zin met veel woorden,", "en daarna komt er nog een stuk tekst achteraan."), chunks)
    }

    @Test
    fun japaneseSplitsAfterItsFullStopsWithoutSpaces() {
        val chunks = listOf(
            "明日の会議は午前十時から始まります。場所はいつもの会議室ではなく、三階の大きな部屋に変わりました。",
            "資料は昨日の夜にメールで送ってありますので、出かける前に一度目を通しておいてください。",
            "終わったあとは、みんなで近くのお店に昼ご飯を食べに行く予定です。",
            "何か質問があれば、いつでも聞いてください。午後から雨が降るそうなので、傘を忘れずに持ってきてください。",
            "明後日の予定は、今夜もう一度まとめて送ります。よろしくお願いします。",
        )
        val text = chunks.joinToString("")
        assertTrue(text.length > 200)
        assertEquals(chunks, SpeechText.streamingChunks(text, 200))
    }

    @Test
    fun aClosingQuoteStaysWithItsJapaneseSentence() {
        val text = "「明日の会議は午前十時から始まりますので、遅れないでください。」場所は三階の大きな部屋に変わりました。"
        assertEquals(
            listOf("「明日の会議は午前十時から始まりますので、遅れないでください。」", "場所は三階の大きな部屋に変わりました。"),
            SpeechText.streamingChunks(text, 200),
        )
        // Nor are two marks in a row split apart.
        assertEquals(
            listOf("えっ、本当に明日の会議は午前十時から始まるのですか！？", "それなら少し早めに家を出ます。"),
            SpeechText.streamingChunks("えっ、本当に明日の会議は午前十時から始まるのですか！？それなら少し早めに家を出ます。", 200),
        )
    }

    @Test
    fun aLongJapaneseSentenceIsCutAfterAComma() {
        val clause = "このアプリは話しかけた言葉をスマートフォンの中で文字に変え、"
        val chunks = SpeechText.streamingChunks(clause.repeat(8) + "最後に返事を読み上げます。", 200)
        assertEquals(listOf(clause.repeat(6), clause.repeat(2) + "最後に返事を読み上げます。"), chunks)
    }

    @Test
    fun linesThatAlreadyEndASentenceStayAsTheyAre() {
        val text = "Here's your agenda:\nIs that right?\nShe said \"fine.\"\n(see above)"
        assertEquals("Here's your agenda:\nIs that right?\nShe said \"fine.\"\n(see above).", SpeechText.withPauses(text))
        assertEquals("今日の予定です。\n「わかりました。」\n明日の予定.", SpeechText.withPauses("今日の予定です。\n「わかりました。」\n明日の予定"))
    }
}
