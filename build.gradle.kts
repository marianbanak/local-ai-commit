import java.util.zip.ZipOutputStream

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "tk.mallumo"
version = "1.0.0"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

configurations.runtimeClasspath {
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-core-jvm")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-swing")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk8")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-jdk9")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-slf4j")
    exclude(group = "org.jetbrains.kotlinx", module = "kotlinx-coroutines-reactive")
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2024.2.4")
        bundledPlugin("Git4Idea")
        bundledPlugin("com.intellij.java")
    }

    implementation("ai.koog:koog-agents:1.3.0")
    implementation("ai.koog:koog-agents-additions:1.3.0-beta")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    compileOnly("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.8.0")

    testImplementation(kotlin("test"))
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        id = "tk.mallumo.local.ai.commit"
        name = "Local AI Commit"
        version = project.version.toString()
        vendor {
            name = "mallumo"
            url = "https://github.com/mallumo"
        }
        ideaVersion {
            sinceBuild = "242"
            untilBuild = provider { null }
        }
    }
}

val dummySearchableOptions = tasks.register("dummySearchableOptions") {
    val file = layout.buildDirectory.file("libs/${project.name}-${project.version}-searchableOptions.jar")
    outputs.file(file)
    doLast {
        val target = file.get().asFile
        if (!target.exists()) {
            target.parentFile.mkdirs()
            ZipOutputStream(target.outputStream()).close()
        }
    }
}

tasks.named("buildPlugin") {
    dependsOn(dummySearchableOptions)
}

kotlin {
    jvmToolchain(21)
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        freeCompilerArgs.add("-Xskip-metadata-version-check")
    }
}
