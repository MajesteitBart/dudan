package nl.bartvandermeeren.aight.device

import java.text.Normalizer

/** An app as the launcher shows it. */
data class LaunchableApp(val label: String, val packageName: String, val activity: String)

/** Finds the app a spoken or typed name means: "Spotify", "maps", "google maps" or a package name. */
object AppMatcher {
    sealed interface Match {
        data class One(val app: LaunchableApp) : Match
        data class Several(val apps: List<LaunchableApp>) : Match
        data object None : Match
    }

    fun find(query: String, apps: List<LaunchableApp>): Match {
        val q = normalize(query)
        if (q.isEmpty()) return Match.None
        val words = q.split(' ')
        // From strict to loose; the first rule that matches anything decides.
        val rules = listOf<(LaunchableApp) -> Boolean>(
            { it.packageName.equals(query.trim(), ignoreCase = true) },
            { normalize(it.label) == q },
            // "maps" means Google Maps as much as Maps.me, so a word start counts as much as the label start.
            { app -> normalize(app.label).let { label -> label.startsWith(q) || label.split(' ').any { it.startsWith(q) } } },
            { app -> normalize(app.label).let { label -> words.all { label.contains(it) } } },
        )
        for (rule in rules) {
            val hits = apps.filter(rule)
            if (hits.isEmpty()) continue
            // Several launcher entries of one app count as that app.
            return if (hits.distinctBy { it.packageName }.size == 1) Match.One(hits.first()) else Match.Several(hits)
        }
        return Match.None
    }

    /** Lowercase, accents dropped, punctuation as spaces: "Café-Bar!" becomes "cafe bar". */
    fun normalize(text: String): String =
        Normalizer.normalize(text, Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .lowercase()
            .replace(Regex("[^\\p{L}\\p{N}]+"), " ")
            .trim()
}

/** What open_link does with a URL, by its scheme. */
object LinkRules {
    enum class Action { View, Dial }

    /** Schemes that reach into files or name arbitrary components; everything else is an ordinary link. */
    private val BLOCKED = setOf("intent", "android-app", "file", "content", "javascript", "data", "jar")

    /** Null when the link is refused. Phone numbers go to the dialer, which never places the call itself. */
    fun actionFor(url: String): Action? {
        val scheme = Regex("^([a-zA-Z][a-zA-Z0-9+.-]*):").find(url.trim())?.groupValues?.get(1)?.lowercase() ?: return null
        return when (scheme) {
            in BLOCKED -> null
            "tel" -> Action.Dial
            else -> Action.View
        }
    }
}
