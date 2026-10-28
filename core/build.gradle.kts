import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// The parts of dudan that don't need Android: the Hermes client, the chat engine, OpenUI and the
// schedule logic. The Android app (:app) and the Windows app (:desktop) both build on it.
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
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

dependencies {
    // Their types are part of this module's API: flows, JSON and the HTTP client.
    api(libs.kotlinx.coroutines.core)
    api(libs.kotlinx.serialization.json)
    api(libs.okhttp)
    implementation(libs.commonmark)
    implementation(libs.commonmark.ext.gfm.tables)
    implementation(libs.commonmark.ext.gfm.strikethrough)
    implementation(libs.commonmark.ext.autolink)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}

// `./gradlew testDebugUnitTest`, as the docs and tools/release-apk.mjs run it, also runs these tests.
tasks.register("testDebugUnitTest") { dependsOn(tasks.test) }
