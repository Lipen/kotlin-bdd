import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

group = "com.github.Lipen"

plugins {
    kotlin("jvm") version "1.6.0"
    application
    id("com.github.johnrengelman.shadow") version "7.1.0" apply false
    id("fr.brouillard.oss.gradle.jgitver") version "0.9.1"
    id("com.github.ben-manes.versions") version "0.39.0"
    `maven-publish`
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(platform(kotlin("bom")))
    implementation(kotlin("stdlib-jdk8"))

    implementation("io.github.microutils:kotlin-logging:2.1.15")
    runtimeOnly("ch.qos.logback:logback-classic:1.2.7")

    testImplementation(kotlin("test"))
    testImplementation("com.soywiz.korlibs.klock:klock-jvm:2.4.8")
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
    }
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}

application {
    mainClass.set("com.github.lipen.bdd.BDDKt")
    applicationDefaultJvmArgs = listOf("-Dfile.encoding=UTF-8", "-Dsun.stdout.encoding=UTF-8")
}

java {
    withSourcesJar()
    withJavadocJar()
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
}

jgitver {
    strategy("MAVEN")
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            from(components["java"])
        }
    }
    repositories {
        maven(url = "$buildDir/repository")
    }
}

tasks.wrapper {
    gradleVersion = "7.3"
    distributionType = Wrapper.DistributionType.ALL
}

defaultTasks("clean", "build")
