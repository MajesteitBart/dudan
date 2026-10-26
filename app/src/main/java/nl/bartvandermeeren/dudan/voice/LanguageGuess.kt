package nl.bartvandermeeren.dudan.voice

/**
 * Guesses a reply's language well enough to pick a voice. Scripts settle Korean, Japanese, Arabic,
 * Hindi, Greek and the Cyrillic languages. Latin-script languages are told apart by their common
 * words and by letters only one of them uses, such as ß or ł.
 *
 * A word several languages use counts for each of them, so it can't tip the balance. When two languages
 * tie, the first of the phone's languages among them wins; with none of them in the tie, it's no guess.
 */
object LanguageGuess {
    /** The language code (such as "nl"), or null when the text gives nothing to go on. */
    fun guess(text: String, preferred: List<String> = emptyList()): String? {
        script(text)?.let { return it }
        val scores = mutableMapOf<String, Int>()
        val lower = text.lowercase()
        lower.split(nonLetters).take(MAX_WORDS).forEach { word ->
            if (word.isEmpty()) return@forEach
            words[word]?.forEach { language -> scores.merge(language, 1, Int::plus) }
        }
        lower.forEach { char ->
            letters[char]?.let { language -> scores.merge(language, 1, Int::plus) }
            if (char in VIETNAMESE) scores.merge("vi", 1, Int::plus)
        }
        val best = scores.values.maxOrNull() ?: return null
        val top = scores.filterValues { it == best }.keys
        return top.singleOrNull() ?: preferred.firstOrNull { it in top }
    }

    /** The language a non-Latin script gives away, or null for Latin text. */
    private fun script(text: String): String? {
        val counts = mutableMapOf<Character.UnicodeScript, Int>()
        var ukrainian = false
        var russian = false
        var bulgarian = false
        text.codePoints().limit(MAX_CHARS.toLong()).forEach { point ->
            if (!Character.isLetter(point)) return@forEach
            var script = Character.UnicodeScript.of(point)
            // Japanese mixes kana with Chinese characters; any kana makes the text Japanese.
            if (script == Character.UnicodeScript.KATAKANA) script = Character.UnicodeScript.HIRAGANA
            // A Chinese character or Hangul syllable carries about as much as a short Latin word.
            val weight = if (script in syllabic) 3 else 1
            counts.merge(script, weight, Int::plus)
            when (Character.toLowerCase(point).toChar()) {
                'і', 'ї', 'є', 'ґ' -> ukrainian = true
                'ы', 'э', 'ё' -> russian = true
                'ъ' -> bulgarian = true
            }
        }
        if ((counts[Character.UnicodeScript.HIRAGANA] ?: 0) > 0) {
            counts.merge(Character.UnicodeScript.HIRAGANA, counts.remove(Character.UnicodeScript.HAN) ?: 0, Int::plus)
        }
        // Only Japanese writes kana and only Korean Hangul, so a third of the text settles it, even
        // when code names or English terms make up the rest.
        val total = counts.values.sum()
        val own = listOf(Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.HANGUL).firstOrNull { 3 * (counts[it] ?: 0) >= total && total > 0 }
        return when (own ?: counts.maxByOrNull { it.value }?.key) {
            Character.UnicodeScript.HANGUL -> "ko"
            Character.UnicodeScript.HIRAGANA -> "ja"
            Character.UnicodeScript.HAN -> "zh"
            Character.UnicodeScript.ARABIC -> "ar"
            Character.UnicodeScript.DEVANAGARI -> "hi"
            Character.UnicodeScript.GREEK -> "el"
            Character.UnicodeScript.HEBREW -> "he"
            Character.UnicodeScript.THAI -> "th"
            Character.UnicodeScript.CYRILLIC -> when {
                ukrainian -> "uk"
                russian -> "ru"
                // Bulgarian writes ъ as a vowel all the time; Russian hardly uses it.
                bulgarian -> "bg"
                else -> "ru"
            }
            else -> null
        }
    }

    private val syllabic = setOf(Character.UnicodeScript.HAN, Character.UnicodeScript.HIRAGANA, Character.UnicodeScript.HANGUL)

    private val nonLetters = Regex("[^\\p{L}]+")
    private const val MAX_WORDS = 400
    private const val MAX_CHARS = 2000

    /** Vowels with tone marks only Vietnamese writes. Not ă (Romanian has it too) or đ (Croatian). */
    private val VIETNAMESE = ("ơư" + (0x1EA0..0x1EF9).map { it.toChar() }.joinToString("")).toSet()

    /** Letters a single one of these languages uses. */
    private val letters: Map<Char, String> = buildMap {
        fun put(chars: String, language: String) = chars.forEach { put(it, language) }
        put("ß", "de")
        put("łąęśźżń", "pl")
        put("řěů", "cs")
        put("ľĺŕô", "sk")
        put("őű", "hu")
        put("șțţ", "ro")
        put("ğış", "tr")
        put("ñ¿¡", "es")
        put("ã", "pt")
        put("œ", "fr")
        put("æø", "da")
        put("ķļņģ", "lv")
        put("ėįų", "lt")
    }

    // Function words, greetings and short answers. Words both of two languages use often ("de" in
    // Dutch, French and Spanish) are listed for each, so they count for all of them. Words that are
    // just as common in English ("is", "in", "was", "we") stay out entirely, so they don't tip a short reply.
    private val lists = mapOf(
        "en" to "the and not you to that for on with are this it be but also what can as have will from your an or by at " +
            "hello hi thanks thank yes no sure okay great good morning evening night today tomorrow yesterday done please " +
            "just all there here would could should",
        "nl" to "de het een en niet je ik van dat die voor op met zijn er maar ook wat kan naar als dit jij wij heb wordt nog " +
            "bij uit goedemorgen goedemiddag goedenavond welterusten hoi hallo doei dag bedankt dank graag prima oké gedaan " +
            "klopt zeker natuurlijk nee ja goed geen wel veel vandaag morgen gisteren straks misschien alleen altijd komt staat",
        "de" to "der die das und ist nicht ich du sie ihr es ein eine einen mit auf für zu von dem den sich auch wenn aber " +
            "oder noch nur wie wird werden sind hast habe hat mir mich dir dich gibt schon heute morgen gestern danke bitte ja " +
            "nein gut hallo guten tag jetzt sehr über bei nach aus im zum zur dass weil diese dieser kein keine",
        "fr" to "le la les un une des et est pas je tu il elle nous vous ils que qui pour dans sur avec ce cette mais ou " +
            "très bien merci bonjour salut oui non du au aux sont été être avoir fait de en aujourd demain",
        "es" to "el la los las un una y es no que de del en por para con se lo su al como más pero está están son muy hoy " +
            "mañana ayer gracias hola sí bien también este esta eso hay yo usted",
        "it" to "il lo la gli le un una è non che di da per con mi ti si ci sono sei del della dei al alla ma anche più " +
            "molto oggi domani ieri grazie ciao sì bene questo questa ho hai ha abbiamo perché",
        "pt" to "os as um uma é não que de do da dos das em no na para com por se mais muito mas como você eu ele ela nós " +
            "está são hoje amanhã ontem obrigado obrigada olá sim bem também isso isto tem",
        "sv" to "och att det som en är inte jag du vi ni de den på för med till av om men har kan ska vill var hur vad här där " +
            "också mycket idag imorgon igår tack hej ja nej bra eller så nu från efter mig dig",
        "da" to "og at det som en er ikke jeg du vi de den på for med til af om men har kan skal vil var hvordan hvad der " +
            "også meget dag tak hej ja nej godt eller så nu fra efter mig dig",
        "pl" to "na nie się to jest że jak ale co po tak od za mnie ja ty czy jestem są był była dla przez już bardzo " +
            "dzisiaj jutro wczoraj dziękuję cześć dzień dobry proszę mam masz może tylko też",
        "cs" to "je se na že to jak ale co po tak od za mě já ty jsem jsou byl pro už velmi dnes zítra včera děkuji " +
            "ahoj dobrý den prosím mám máš může jen také není ne",
        "sk" to "je sa na že to ako ale čo po tak od za ma ja ty som sú bol pre už veľmi dnes zajtra včera ďakujem " +
            "ahoj dobrý deň prosím mám máš môže len tiež nie",
        "sl" to "je se na da to za kot ali kaj po tako od sem si smo ni ne pa tudi zelo danes jutri včeraj hvala " +
            "živjo dober dan prosim imam lahko samo že bo bi",
        "hr" to "je se na da to sa za kao ali što po tako od sam si smo su nije ne pa također vrlo danas sutra jučer hvala " +
            "bok dobar dan molim imam može samo već će bi",
        "hu" to "az és hogy nem egy van meg de ez azt ki mi el csak már még mint vagy ha nagyon ma holnap tegnap " +
            "köszönöm szia igen jó itt ott kell lehet után minden volt lesz nincs én te ön",
        "ro" to "și de la în nu că cu pe un este sunt se ce mai pentru din care dar foarte azi astăzi mâine ieri mulțumesc " +
            "salut da bine acest această ai are eu tu el ea noi voi fi poate",
        "tr" to "ve bir bu da de için ile ne çok ama gibi daha var yok ben sen biz siz değil mi mı mu mü evet hayır merhaba " +
            "teşekkürler teşekkür bugün yarın dün iyi şimdi sonra olarak olan kadar",
        "fi" to "ja on ei se että oli olen ovat minä sinä hän te mutta tai kun jos niin myös vain nyt tänään huomenna " +
            "eilen kiitos hei kyllä hyvä erittäin tämä tuo mitä miten kanssa jo vielä",
        "et" to "ja on ei et oli olen nad mina sina tema meie teie aga või kui nii ka ainult nüüd täna homme eile aitäh " +
            "tänan tere jah hea väga mis kuidas koos juba veel seda",
        "lv" to "un ir ar uz no par kas ka bet vai ne es tu viņš viņa mēs jūs tas tā šis šī arī ļoti šodien rīt vakar " +
            "paldies sveiki labi jā nē kā kad tikai jau vēl bija būs var",
        "lt" to "ir yra kad su į iš ant bet ar ne aš tu jis ji mes jūs tai šis ši taip labai šiandien rytoj vakar ačiū " +
            "labas gerai kaip kai tik jau dar buvo bus gali tačiau apie nuo po",
        "id" to "dan yang di ke dari ini itu untuk dengan tidak ada saya kamu anda kami kita mereka akan sudah belum bisa " +
            "juga sangat hari besok kemarin terima kasih halo ya baik apa bagaimana karena atau tetapi tapi dalam pada",
        "vi" to "và là của có không tôi bạn một những các được cho với này đó rất hôm nay ngày mai cảm ơn xin chào",
    )

    /** Each word and the languages it belongs to. */
    private val words: Map<String, List<String>> = buildMap<String, MutableList<String>> {
        lists.forEach { (language, list) -> list.split(' ').forEach { getOrPut(it) { mutableListOf() } += language } }
    }
}
