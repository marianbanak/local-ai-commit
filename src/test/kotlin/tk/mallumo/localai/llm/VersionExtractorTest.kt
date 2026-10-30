package tk.mallumo.localai.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class VersionExtractorTest {

    @Test
    fun testExtractFromGradleProperties() {
        val content = """
            org.gradle.jvmargs=-Xmx2048m
            version=1.4.2-SNAPSHOT
            group=com.example
        """.trimIndent()

        val version = VersionExtractor.extractVersionFromText("gradle.properties", content)
        assertEquals("1.4.2-SNAPSHOT", version)
    }

    @Test
    fun testExtractFromBuildGradleKts() {
        val content = """
            plugins {
                kotlin("jvm") version "2.1.0"
            }
            group = "tk.mallumo"
            version = "2.0.5"
        """.trimIndent()

        val version = VersionExtractor.extractVersionFromText("build.gradle.kts", content)
        assertEquals("2.0.5", version)
    }

    @Test
    fun testExtractFromLibsVersionsToml() {
        val tomlContent = """
            [versions]
            agp = "8.2.0"
            kotlin = "2.1.0"
            appVersion = "3.1.0-RC1"
            
            [libraries]
        """.trimIndent()

        val version = VersionExtractor.extractVersionFromText("libs.versions.toml", tomlContent)
        assertEquals("3.1.0-RC1", version)

        val tomlGeneric = """
            [package]
            name = "my-crate"
            version = "0.8.4"
        """.trimIndent()

        val ver2 = VersionExtractor.extractVersionFromText("Cargo.toml", tomlGeneric)
        assertEquals("0.8.4", ver2)
    }

    @Test
    fun testExtractFromPackageJson() {
        val json = """
            {
              "name": "my-app",
              "version": "1.12.0",
              "private": true
            }
        """.trimIndent()

        val version = VersionExtractor.extractVersionFromText("package.json", json)
        assertEquals("1.12.0", version)
    }

    @Test
    fun testExtractFromPomXml() {
        val xml = """
            <project>
                <groupId>com.mycompany.app</groupId>
                <artifactId>my-app</artifactId>
                <version>4.0.0-beta</version>
            </project>
        """.trimIndent()

        val version = VersionExtractor.extractVersionFromText("pom.xml", xml)
        assertEquals("4.0.0-beta", version)
    }

    @Test
    fun testExtractFromVersionTxt() {
        val text = "v1.2.3\nSome extra info"
        val version = VersionExtractor.extractVersionFromText("version.txt", text)
        assertEquals("1.2.3", version)
    }

    @Test
    fun testExtractFromUnknownFile() {
        val text = "some random file content"
        val version = VersionExtractor.extractVersionFromText("readme.md", text)
        assertNull(version)
    }
}
