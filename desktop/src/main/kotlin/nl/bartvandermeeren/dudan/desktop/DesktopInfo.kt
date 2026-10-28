package nl.bartvandermeeren.dudan.desktop

object DesktopInfo {
    /** The installer's version (jpackage sets it); "dev" when run from Gradle. */
    val version: String = System.getProperty("jpackage.app-version")?.takeIf { it.isNotBlank() } ?: "dev"
}
