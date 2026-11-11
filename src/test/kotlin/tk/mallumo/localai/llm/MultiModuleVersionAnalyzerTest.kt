package tk.mallumo.localai.llm

import org.junit.Assert.*
import org.junit.Test
import tk.mallumo.localai.buildsystem.BuildSystemType
import tk.mallumo.localai.buildsystem.DetectedModule

class MultiModuleVersionAnalyzerTest {

    @Test
    fun testBuildTreeOutline() {
        val paths = listOf(
            "settings.gradle.kts",
            "build.gradle.kts",
            "app/build.gradle.kts",
            "app/src/main/kotlin/App.kt",
            "gradle/libs.versions.toml",
            "package.json",
            "module.yaml",
            "images/banner.png"
        )

        val outline = MultiModuleVersionAnalyzer.buildTreeOutline(paths)
        assertTrue(outline.contains("settings.gradle.kts"))
        assertTrue(outline.contains("build.gradle.kts"))
        assertTrue(outline.contains("app/build.gradle.kts"))
        assertTrue(outline.contains("gradle/libs.versions.toml"))
        assertTrue(outline.contains("package.json"))
        assertTrue(outline.contains("module.yaml"))
        assertFalse(outline.contains("app/src/main/kotlin/App.kt")) // deeply nested non-config file excluded
    }

    @Test
    fun testExtractPrimaryVersion() {
        val aiResponse = """
            Based on the Gradle catalog and Amper templates, here is the version analysis:

            Primary Version: 2.3.1

            Modules:
            - :core: 2.3.1 (from libs.versions.toml)
            - :feature-a: 1.0.0 (from feature-a/module.yaml)
        """.trimIndent()

        val primary = MultiModuleVersionAnalyzer.extractPrimaryVersion(aiResponse)
        assertEquals("2.3.1", primary)
    }

    @Test
    fun testExtractPrimaryVersionWithPrefix() {
        val aiResponse = """
            Primary Version: v1.0.4-rc1
        """.trimIndent()

        val primary = MultiModuleVersionAnalyzer.extractPrimaryVersion(aiResponse)
        assertEquals("1.0.4-rc1", primary)
    }

    @Test
    fun testMergeAiModuleVersions() {
        val initialModules = listOf(
            DetectedModule("core", "core", BuildSystemType.GRADLE, "core/build.gradle.kts", version = "unknown"),
            DetectedModule("feature-a", "feature-a", BuildSystemType.AMPER, "feature-a/module.yaml", version = null)
        )

        val aiResponse = """
            Modules:
            - :core: 2.3.1 (from libs.versions.toml)
            - feature-a: 1.2.0 (from template.yaml)
        """.trimIndent()

        val merged = MultiModuleVersionAnalyzer.mergeAiModuleVersions(initialModules, aiResponse)
        assertEquals("2.3.1", merged.first { it.name == "core" }.version)
        assertEquals("1.2.0", merged.first { it.name == "feature-a" }.version)
    }
}
