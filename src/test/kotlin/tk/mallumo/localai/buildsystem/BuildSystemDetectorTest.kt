package tk.mallumo.localai.buildsystem

import org.junit.Assert.*
import org.junit.Test

class BuildSystemDetectorTest {

    @Test
    fun testParseGradleVersionDirect() {
        val buildKts = """
            plugins {
                kotlin("jvm") version "2.0.0"
            }
            version = "1.4.2"
            group = "tk.mallumo"
        """.trimIndent()
        val ver = BuildSystemDetector.parseGradleVersion(buildKts)
        assertEquals("1.4.2", ver)
    }

    @Test
    fun testParseGradleVersionGroovy() {
        val buildGroovy = """
            group 'org.example'
            version '2.5.0-SNAPSHOT'
        """.trimIndent()
        val ver = BuildSystemDetector.parseGradleVersion(buildGroovy)
        assertEquals("2.5.0-SNAPSHOT", ver)
    }

    @Test
    fun testParseMavenVersion() {
        val pom = """
            <project>
                <modelVersion>4.0.0</modelVersion>
                <groupId>com.example</groupId>
                <artifactId>my-app</artifactId>
                <version>3.1.0</version>
            </project>
        """.trimIndent()
        val ver = BuildSystemDetector.parseMavenVersion(pom)
        assertEquals("3.1.0", ver)
    }

    @Test
    fun testParseNpmVersion() {
        val pkgJson = """
            {
              "name": "my-frontend",
              "version": "0.9.5",
              "scripts": {}
            }
        """.trimIndent()
        val ver = BuildSystemDetector.parseNpmVersion(pkgJson)
        assertEquals("0.9.5", ver)
    }

    @Test
    fun testEnrichModulesWithCatalogVersions() {
        val modules = listOf(
            DetectedModule(
                name = "core",
                relativePath = "core",
                buildSystem = BuildSystemType.GRADLE,
                descriptorFile = "core/build.gradle.kts",
                version = null
            ),
            DetectedModule(
                name = "api",
                relativePath = "api",
                buildSystem = BuildSystemType.GRADLE,
                descriptorFile = "api/build.gradle.kts",
                version = "1.0.0" // already set
            )
        )

        val candidateFiles = mapOf(
            "gradle.properties" to "version=2.1.0\norg.gradle.jvmargs=-Xmx2048m"
        )

        val enriched = BuildSystemDetector.enrichModulesWithCatalogVersions(modules, candidateFiles)
        assertEquals(2, enriched.size)
        assertEquals("2.1.0", enriched[0].version)
        assertEquals("gradle.properties", enriched[0].versionReference)
        assertEquals("1.0.0", enriched[1].version)
    }

    @Test
    fun testEnrichModulesWithTomlCatalog() {
        val modules = listOf(
            DetectedModule(
                name = "shared",
                relativePath = "shared",
                buildSystem = BuildSystemType.GRADLE,
                descriptorFile = "shared/build.gradle.kts",
                version = null
            )
        )

        val candidateFiles = mapOf(
            "gradle/libs.versions.toml" to """
                [versions]
                appVersion = "3.4.1"
                kotlin = "2.0.0"
            """.trimIndent()
        )

        val enriched = BuildSystemDetector.enrichModulesWithCatalogVersions(modules, candidateFiles)
        assertEquals("3.4.1", enriched[0].version)
        assertEquals("gradle/libs.versions.toml", enriched[0].versionReference)
    }

    @Test
    fun testIsModuleChanged() {
        val changedFiles = setOf(
            "app/src/main/kotlin/Main.kt",
            "README.md"
        )

        assertTrue(BuildSystemDetector.isModuleChanged("app", changedFiles))
        assertFalse(BuildSystemDetector.isModuleChanged("core", changedFiles))
        assertTrue(BuildSystemDetector.isModuleChanged("", changedFiles)) // root module contains README.md
    }
}
