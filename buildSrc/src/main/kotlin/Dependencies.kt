import org.gradle.kotlin.dsl.apply
import org.gradle.kotlin.dsl.version
import org.gradle.plugin.use.PluginDependenciesSpec

object Versions {
    const val okio = "3.9.0"
    const val jgitver = "0.9.1"
    const val kotlin = "2.0.0"
    const val junit = "5.8.1"
    const val slf4j = "1.7.36"
    const val shadow = "8.1.1"
    const val kotlin_logging = "7.0.0"
}

object Libs {
    fun dep(group: String, name: String, version: String): String = "$group:$name:$version"

    // https://github.com/junit-team/junit5
    const val junit_bom = "org.junit:junit-bom:${Versions.junit}"
    const val junit_jupiter = "org.junit.jupiter:junit-jupiter"

    // https://github.com/oshai/kotlin-logging
    val kotlin_logging = dep(
        group = "io.github.oshai",
        name = "kotlin-logging-jvm",
        version = Versions.kotlin_logging
    )

    // https://github.com/qos-ch/slf4j
    val slf4j_simple = dep(
        group = "org.slf4j",
        name = "slf4j-simple",
        version = Versions.slf4j
    )

    // https://github.com/square/okio
    val okio = dep(
        group = "com.squareup.okio",
        name = "okio",
        version = Versions.okio
    )
}

object Plugins {
    abstract class Plugin(val version: String, val id: String)

    // https://github.com/johnrengelman/shadow
    object Shadow : Plugin(
        version = Versions.shadow,
        id = "com.github.johnrengelman.shadow"
    )

    // https://github.com/jgitver/jgitver
    object JGitver : Plugin(
        version = Versions.jgitver,
        id = "fr.brouillard.oss.gradle.jgitver"
    )
}

fun PluginDependenciesSpec.id(plugin: Plugins.Plugin, apply: Boolean = true) {
    id(plugin.id) version plugin.version apply apply
}
