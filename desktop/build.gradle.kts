import java.net.URI
import java.security.MessageDigest
import org.jetbrains.compose.desktop.application.dsl.TargetFormat
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// dudan for Windows: the same Hermes client and chat engine as the Android app (:core), with a
// Compose Desktop window, a tray icon and a global shortcut for quick questions.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

// The interface strings are the Android app's, so both apps say the same things in English and
// Dutch. src/main/composeResources adds what only the Windows app has.
val sharedResources by tasks.registering(Sync::class) {
    from(rootProject.file("app/src/main/res")) {
        include(
            "values/strings.xml", "values-nl/strings.xml", "values/agent_screen.xml", "values-nl/agent_screen.xml",
            "values/outbound_attachments.xml", "values-nl/outbound_attachments.xml",
        )
    }
    // Compose resources have no nodpi qualifier; these PNGs are drawn at their own size anyway.
    from(rootProject.file("app/src/main/res/drawable-nodpi")) {
        include("dudan_mark.png", "dudan_mark_halo.png")
        into("drawable")
    }
    // The app icon for the window, the taskbar and the tray.
    from(rootProject.file("assets")) {
        include("dudan-app-icon.png")
        rename { "dudan_app_icon.png" }
        into("drawable")
    }
    from("src/main/composeResources")
    // Android strings escape ' and " with a backslash; Compose resources would show the backslash.
    // Gradle can't see into the filter, so the property makes a change to it rerun the task.
    inputs.property("stringFilter", "unescape-quotes-1")
    filesMatching("**/*.xml") {
        filter { line -> line.replace("\\'", "'").replace("\\\"", "\"") }
    }
    into(layout.buildDirectory.dir("sharedResources"))
}

// The Android app's Google Sans files, on the classpath for Compose Desktop's Font(resource).
tasks.processResources {
    from(rootProject.file("app/src/main/res/font")) { into("fonts") }
}

// sherpa-onnx reads replies aloud with the phone's voices (Kokoro, Supertonic). Its JVM jars are only
// published as GitHub release assets, as the phone's AAR is, so fetch the pinned version once. The native
// jar holds the Windows DLLs, which sherpa unpacks and loads at the first use.
val sherpaVersion = "1.13.8"
val sherpaJars = mapOf(
    "sherpa-onnx-jvm-$sherpaVersion.jar" to "77b7b047fade4eadada96b568eb92615049aaf1dc317c7244e46c1ea38b9a63b",
    "sherpa-onnx-native-lib-win-x64-$sherpaVersion.jar" to "33fbdbd5410e9ba9bdda94aa164ec8f7825bb49246420d8ce9bdd88219d97039",
)
val sherpaDir = layout.projectDirectory.dir("libs")

val fetchSherpa by tasks.registering {
    // Only locals in the actions: the configuration cache can't serialize references to the build script.
    val targets = sherpaJars.map { (name, sha) -> Triple(sherpaDir.file(name).asFile, sha, "https://github.com/k2-fsa/sherpa-onnx/releases/download/v$sherpaVersion/$name") }
    outputs.files(targets.map { it.first })
    onlyIf { targets.any { !it.first.exists() } }
    doLast {
        targets.forEach { (target, expected, url) ->
            if (target.exists()) return@forEach
            target.parentFile.mkdirs()
            val bytes = URI(url).toURL().openStream().use { it.readBytes() }
            val digest = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }
            check(digest == expected) { "Checksum mismatch for ${target.name}: $digest" }
            target.writeBytes(bytes)
        }
    }
}

compose.resources {
    packageOfResClass = "nl.bartvandermeeren.dudan.desktop.res"
    generateResClass = always
    customDirectory("main", layout.dir(sharedResources.map { it.destinationDir }))
}

dependencies {
    implementation(project(":core"))
    implementation(compose.desktop.currentOs)
    implementation(libs.desktop.material3)
    implementation(libs.desktop.icons.extended)
    implementation(libs.desktop.resources)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.haze)
    implementation(libs.jna.platform)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)
    implementation(libs.commons.compress)
    implementation(files(sherpaJars.keys.map { sherpaDir.file(it) }).builtBy(fetchSherpa))

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.desktop.ui.test)
}

tasks.test {
    // DesktopJourneyTest walks through the app against tools/mock-hermes and only runs when it's given:
    //   MOCK_LANG=en node tools/mock-hermes/server.mjs 8650
    //   ./gradlew :desktop:test -Pdudan.mock=http://127.0.0.1:8650
    providers.gradleProperty("dudan.mock").orNull?.let { systemProperty("dudan.mock", it) }
    // LiveServerTest asks a real server one question through a saved profile; see its comment.
    providers.gradleProperty("dudan.live").orNull?.let { systemProperty("dudan.live", it) }
    // VoiceLiveTest downloads the voices into this folder and reads aloud through the sound output.
    providers.gradleProperty("dudan.voices").orNull?.let { systemProperty("dudan.voices", it) }
    systemProperty("dudan.shots", layout.buildDirectory.dir("ui-shots").get().asFile.path)
}

compose.desktop {
    application {
        mainClass = "nl.bartvandermeeren.dudan.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi)
            packageName = "dudan"
            packageVersion = "0.1.0"
            description = "Your Hermes agent, one shortcut away"
            vendor = "Bart van der Meeren"
            copyright = "MIT License"
            licenseFile.set(rootProject.file("LICENSE"))
            // What suggestRuntimeModules finds, plus jdk.crypto.ec: TLS loads it as a service, so jdeps can't
            // see it, and HTTPS to a server with an elliptic-curve certificate fails without it.
            modules("java.instrument", "jdk.unsupported", "jdk.crypto.ec")

            windows {
                // Made from assets/dudan-app-icon.png at 16 to 256 px.
                iconFile.set(project.file("packaging/dudan.ico"))
                menuGroup = "dudan"
                shortcut = true
                perUserInstall = true
                // Keep it: Windows Installer recognizes updates by this id.
                upgradeUuid = "6f0d3f4e-2a51-4c3b-9d38-7b1e5a9c2d14"
            }
        }
    }
}
