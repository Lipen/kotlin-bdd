import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm")
    id("com.github.johnrengelman.shadow")
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(kotlin("bom")))
    implementation(kotlin("stdlib-jdk8"))

    implementation(rootProject)
    implementation("io.github.microutils:kotlin-logging:2.1.15")
    runtimeOnly("ch.qos.logback:logback-classic:1.2.7")
    implementation("com.soywiz.korlibs.klock:klock-jvm:2.4.8")
    implementation("com.squareup.okio:okio:3.0.0")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

tasks.withType<ShadowJar> {
    archiveBaseName.set(project.name)
    archiveClassifier.set("")
    archiveVersion.set("")
}
