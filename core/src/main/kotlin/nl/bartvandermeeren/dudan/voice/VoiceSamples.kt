package nl.bartvandermeeren.dudan.voice

/** What Preview in Settings says, in the language of the voice it plays. */
object VoiceSamples {
    /** A greeting that takes the user's name, and the rest of the sentence. */
    private class Sample(val greeting: String, val rest: String, val withName: Boolean = true, val comma: String = ",")

    private val samples = mapOf(
        "en" to Sample("Hi", "this is how I sound when I read a reply aloud."),
        "nl" to Sample("Hoi", "zo klink ik als ik een antwoord in het Nederlands voorlees."),
        "de" to Sample("Hallo", "so klinge ich, wenn ich eine Antwort auf Deutsch vorlese."),
        "fr" to Sample("Bonjour", "voici ma voix quand je lis une réponse en français."),
        "es" to Sample("Hola", "así sueno cuando leo una respuesta en español."),
        "it" to Sample("Ciao", "ecco come suono quando leggo una risposta in italiano."),
        "pt" to Sample("Olá", "é assim que eu soo quando leio uma resposta em português."),
        "sv" to Sample("Hej", "så här låter jag när jag läser upp ett svar på svenska."),
        "da" to Sample("Hej", "sådan lyder jeg, når jeg læser et svar højt på dansk."),
        "fi" to Sample("Hei", "tältä kuulostan, kun luen vastauksen ääneen suomeksi."),
        "et" to Sample("Tere", "nii kõlan ma, kui loen vastuse eesti keeles ette."),
        "lv" to Sample("Sveiki", "tā es skanu, kad nolasu atbildi latviešu valodā."),
        "lt" to Sample("Labas", "taip skamba mano balsas, kai garsiai skaitau atsakymą lietuviškai."),
        "pl" to Sample("Cześć", "tak brzmi mój głos, kiedy czytam odpowiedź po polsku."),
        "cs" to Sample("Ahoj", "takhle zní můj hlas, když čtu odpověď v češtině."),
        "sk" to Sample("Ahoj", "takto znie môj hlas, keď čítam odpoveď po slovensky."),
        "sl" to Sample("Živjo", "tako zveni moj glas, ko na glas preberem odgovor v slovenščini."),
        "hr" to Sample("Bok", "ovako zvuči moj glas kad naglas čitam odgovor na hrvatskom."),
        "hu" to Sample("Szia", "így hangzik a hangom, amikor felolvasok egy választ magyarul."),
        "ro" to Sample("Salut", "așa sună vocea mea când citesc un răspuns în română."),
        "bg" to Sample("Здравей", "така звучи гласът ми, когато чета отговор на български."),
        "ru" to Sample("Привет", "так звучит мой голос, когда я читаю ответ по-русски."),
        "uk" to Sample("Привіт", "так звучить мій голос, коли я читаю відповідь українською."),
        "el" to Sample("Γεια σου", "έτσι ακούγεται η φωνή μου όταν διαβάζω μια απάντηση στα ελληνικά."),
        "tr" to Sample("Merhaba", "bir yanıtı Türkçe sesli okuduğumda sesim böyle çıkıyor."),
        "id" to Sample("Halo", "beginilah suara saya saat membacakan jawaban dalam bahasa Indonesia."),
        "vi" to Sample("Xin chào", "đây là giọng của tôi khi đọc to một câu trả lời bằng tiếng Việt."),
        "ar" to Sample("مرحبًا", "هكذا يبدو صوتي عندما أقرأ إجابة بالعربية.", withName = false, comma = "،"),
        "hi" to Sample("नमस्ते", "हिंदी में जवाब पढ़ते समय मेरी आवाज़ ऐसी सुनाई देती है।", withName = false),
        "ja" to Sample("こんにちは", "日本語で返事を読み上げると、こんな声になります。", withName = false, comma = "。"),
        "ko" to Sample("안녕하세요", "한국어로 답변을 읽어 드릴 때 제 목소리는 이렇습니다.", withName = false, comma = "."),
    )

    /** The languages Preview can speak. */
    val languages: Set<String> get() = samples.keys

    /** The sentence for [language], greeting [name] when there is one; English when the language has none. */
    fun text(language: String, name: String): String {
        val sample = samples[language] ?: samples.getValue("en")
        val greeting = if (sample.withName && name.isNotBlank()) "${sample.greeting} ${name.trim()}" else sample.greeting
        // Japanese runs on after its full stop; the rest put a space after the comma.
        val separator = if (sample.comma == "。") sample.comma else "${sample.comma} "
        return "$greeting$separator${sample.rest}"
    }
}
