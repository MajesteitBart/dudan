package nl.bartvandermeeren.aight.voice

/**
 * Writes out what a Dutch voice would otherwise misread: times, amounts, percentages, dates, ordinals,
 * phone numbers, other numbers and common abbreviations. Supertonic reads "1250" and "14:35" wrongly,
 * but reads the same numbers correctly once they are spelled out the standard way.
 */
object DutchText {
    fun normalize(text: String): String {
        var s = abbreviations(text)
        s = s.replace(phone) { m -> m.value.filter { it.isDigit() || it == '+' }.map { if (it == '+') "plus" else number(it.digitToInt().toLong()) }.joinToString(" ") }
        s = s.replace(isoDate) { date(it.groupValues[3], it.groupValues[2], it.groupValues[1]) ?: it.value }
        s = s.replace(date) { date(it.groupValues[1], it.groupValues[2], it.groupValues[3]) ?: it.value }
        s = s.replace(clock) { time(it.groupValues[1], it.groupValues[2]) }
        s = s.replace(clockWithUur) { time(it.groupValues[1], it.groupValues[2]) }
        s = s.replace(euroScaled) { "${decimal(it.groupValues[1])} ${it.groupValues[2]} euro" }
        s = s.replace(euroSign) { money(it.groupValues[1], it.groupValues[3], "euro") }
        s = s.replace(euroWord) { money(it.groupValues[1], it.groupValues[2], "euro") }
        s = s.replace(dollarSign) { money(it.groupValues[1], it.groupValues[2], "dollar") }
        s = s.replace(percent) { "${decimal(it.groupValues[1])} procent" }
        units.forEach { (pattern, word) -> s = s.replace(pattern, "$1 $word") }
        s = s.replace(ordinalNumber) { ordinal(it.groupValues[1].toLong()) }
        s = s.replace(range, " tot ")
        s = s.replace(decimalNumber) { decimal(it.value) }
        s = s.replace(thousands) { number(it.value.replace(".", "").toLong()) }
        s = s.replace(integer) { m -> if (m.value.length > 1 && m.value.startsWith('0') || m.value.length > 12) digits(m.value) else number(m.value.toLong()) }
        symbols.forEach { (pattern, word) -> s = s.replace(pattern, word) }
        return s
    }

    /** A whole number in words, spelled the standard way: "tweeëntwintig", "twaalfhonderdvijftig", "tweeduizend zesentwintig". */
    fun number(n: Long): String = when {
        n < 0 -> "min " + number(-n)
        n < 20 -> UNITS[n.toInt()]
        n < 100 -> {
            val unit = n % 10
            val tens = TENS[(n / 10).toInt()]
            if (unit == 0L) tens else UNITS[unit.toInt()].let { if (it.endsWith("e")) "${it}ën$tens" else "${it}en$tens" }
        }
        n < 1000 -> hundreds(n / 100) + below(n % 100)
        // Dutch says "twaalfhonderdvijftig" rather than "duizend tweehonderdvijftig".
        n in 1100..1999 && (n / 100) % 10 != 0L -> number(n / 100) + "honderd" + below(n % 100)
        n < 1_000_000 -> (if (n / 1000 == 1L) "duizend" else number(n / 1000) + "duizend") + after(n % 1000)
        n < 1_000_000_000 -> number(n / 1_000_000) + " miljoen" + after(n % 1_000_000)
        n < 1_000_000_000_000 -> number(n / 1_000_000_000) + " miljard" + after(n % 1_000_000_000)
        else -> digits(n.toString())
    }

    /** "eerste", "derde", "achtste", "twintigste", "honderdeerste". */
    fun ordinal(n: Long): String {
        val word = number(n)
        return when {
            word.endsWith("een") -> word.dropLast(3) + "eerste"
            word.endsWith("drie") -> word.dropLast(4) + "derde"
            word.endsWith("acht") || word.endsWith("tig") || word.endsWith("honderd") ||
                word.endsWith("duizend") || word.endsWith("miljoen") || word.endsWith("miljard") -> word + "ste"
            else -> word + "de"
        }
    }

    private fun hundreds(h: Long) = if (h == 1L) "honderd" else UNITS[h.toInt()] + "honderd"

    private fun below(rest: Long) = if (rest == 0L) "" else number(rest)

    // A space follows duizend, miljoen and miljard: "tweeduizend zesentwintig".
    private fun after(rest: Long) = if (rest == 0L) "" else " " + number(rest)

    private fun digits(value: String) = value.filter { it.isDigit() }.map { number(it.digitToInt().toLong()) }.joinToString(" ")

    /** "12,5" as "twaalf komma vijf"; a fraction with a leading zero digit by digit: "nul komma nul vijf". */
    private fun decimal(value: String): String {
        val (whole, fraction) = value.split(',', limit = 2).let { it[0] to it.getOrNull(1) }
        val wholeWords = number(whole.replace(".", "").toLong())
        if (fraction.isNullOrEmpty()) return wholeWords
        val fractionWords = if (fraction.startsWith('0') || fraction.length > 2) digits(fraction) else number(fraction.toLong())
        return "$wholeWords komma $fractionWords"
    }

    private fun money(whole: String, cents: String, currency: String): String {
        val amount = whole.replace(".", "").toLong()
        val centValue = cents.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }?.toLong() ?: 0L
        return when {
            amount == 0L && centValue > 0 -> "${number(centValue)} cent"
            centValue > 0 -> "${number(amount)} $currency ${number(centValue)}"
            else -> "${number(amount)} $currency"
        }
    }

    private fun time(hours: String, minutes: String): String {
        val m = minutes.toLong()
        return number(hours.toLong()) + " uur" + if (m == 0L) "" else " " + number(m)
    }

    private fun date(day: String, month: String, year: String): String? {
        val monthName = MONTHS.getOrNull(month.toInt() - 1) ?: return null
        if (day.toInt() !in 1..31) return null
        return "${number(day.toLong())} $monthName ${number(year.toLong())}"
    }

    private fun abbreviations(text: String): String {
        var s = text
        ABBREVIATIONS.forEach { (short, long) ->
            val pattern = Regex("(?<![\\p{L}.])" + Regex.escape(short) + if (short.endsWith(".")) "" else "(?![\\p{L}])", RegexOption.IGNORE_CASE)
            s = pattern.replace(s) { m ->
                val expansion = if (m.value.first().isUpperCase()) long.replaceFirstChar { it.uppercase() } else long
                // An abbreviation's full stop can also end the sentence; keep that one.
                val rest = s.substring(m.range.last + 1)
                val endsSentence = short.endsWith(".") && (rest.isBlank() || rest.startsWith("\n") || rest.matches(Regex("^\\s+\\p{Lu}[\\s\\S]*")))
                if (endsSentence) "$expansion." else expansion
            }
        }
        return s
    }

    private const val AMOUNT = "(\\d{1,3}(?:\\.\\d{3})+|\\d+)"

    private val phone = Regex("(?<![\\d\\p{L}])(?:\\+31[ -]?\\d{1,3}|0\\d{1,3})[ -]?\\d{6,8}(?!\\d)")
    private val isoDate = Regex("(?<!\\d)(\\d{4})-(\\d{2})-(\\d{2})(?!\\d)")
    private val date = Regex("(?<!\\d)(\\d{1,2})[-/](\\d{1,2})[-/](\\d{4})(?!\\d)")
    private val clock = Regex("(?<!\\d)([01]?\\d|2[0-3]):([0-5]\\d)(?![\\d:])")
    private val clockWithUur = Regex("(?<!\\d)([01]?\\d|2[0-3])\\.([0-5]\\d) ?uur\\b")
    private val euroScaled = Regex("€ ?(\\d+(?:,\\d+)?) ?(duizend|miljoen|miljard)\\b")
    private val euroSign = Regex("€ ?$AMOUNT(?:([.,])(\\d{2}|--?))?(?!\\d)")
    private val euroWord = Regex("(?<![\\d.])$AMOUNT(?:,(\\d{2}|--?))? ?(?:euro|EUR)\\b")
    private val dollarSign = Regex("\\$ ?$AMOUNT(?:\\.(\\d{2}))?(?!\\d)")
    private val percent = Regex("(\\d+(?:,\\d+)?) ?%")
    private val ordinalNumber = Regex("(?<![\\d\\p{L}])(\\d+)(?:ste|de|e)(?![\\p{L}])")
    private val range = Regex("(?<=\\d) ?[–-] ?(?=\\d)")
    private val decimalNumber = Regex("(?<![\\d\\p{L}])\\d+,\\d+(?![\\d\\p{L}])")
    private val thousands = Regex("(?<![\\d\\p{L}.])\\d{1,3}(?:\\.\\d{3})+(?![\\d\\p{L}])")
    private val integer = Regex("(?<![\\d\\p{L}])\\d+(?![\\d\\p{L}])")

    private val units = listOf(
        Regex("(\\d) ?km/[uh]\\b") to "kilometer per uur",
        Regex("(\\d) ?km\\b") to "kilometer",
        Regex("(\\d) ?kg\\b") to "kilo",
        Regex("(\\d) ?cm\\b") to "centimeter",
        Regex("(\\d) ?mm\\b") to "millimeter",
        Regex("(\\d) ?m[²2]\\b") to "vierkante meter",
        Regex("(\\d) ?°C?") to "graden",
    )

    private val symbols = listOf(
        Regex(" & ") to " en ",
        Regex("±") to "ongeveer ",
        Regex("~(?=\\s?\\p{N}|\\s?[a-z])") to "ongeveer ",
        Regex(" ?(→|->) ?") to ", ",
    )

    private val ABBREVIATIONS = listOf(
        "bijv." to "bijvoorbeeld", "bv." to "bijvoorbeeld", "o.a." to "onder andere", "d.w.z." to "dat wil zeggen",
        "i.p.v." to "in plaats van", "i.v.m." to "in verband met", "m.b.t." to "met betrekking tot", "t.o.v." to "ten opzichte van",
        "n.a.v." to "naar aanleiding van", "o.b.v." to "op basis van", "m.u.v." to "met uitzondering van", "z.s.m." to "zo snel mogelijk",
        "a.u.b." to "alsjeblieft", "p.p." to "per persoon", "e.d." to "en dergelijke", "enz." to "enzovoort", "etc." to "etcetera",
        "incl." to "inclusief", "excl." to "exclusief", "ca." to "circa", "evt." to "eventueel", "resp." to "respectievelijk",
        "nr." to "nummer", "a.s." to "aanstaande", "jl." to "jongstleden", "t/m" to "tot en met", "mln" to "miljoen", "mld" to "miljard",
    )

    private val UNITS = listOf(
        "nul", "een", "twee", "drie", "vier", "vijf", "zes", "zeven", "acht", "negen", "tien",
        "elf", "twaalf", "dertien", "veertien", "vijftien", "zestien", "zeventien", "achttien", "negentien",
    )
    private val TENS = listOf("", "", "twintig", "dertig", "veertig", "vijftig", "zestig", "zeventig", "tachtig", "negentig")
    private val MONTHS = listOf(
        "januari", "februari", "maart", "april", "mei", "juni", "juli", "augustus", "september", "oktober", "november", "december",
    )
}
