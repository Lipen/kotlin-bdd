import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.6.0"
    application
    id("fr.brouillard.oss.gradle.jgitver") version "0.9.1"
    id("com.github.ben-manes.versions") version "0.39.0"
}

group = "com.github.Lipen"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.github.microutils:kotlin-logging:2.1.0")
    // runtimeOnly("org.slf4j:slf4j-simple:1.7.29")
    runtimeOnly("ch.qos.logback:logback-classic:1.2.3")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("com.github.lipen.bdd.BDDKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
}
