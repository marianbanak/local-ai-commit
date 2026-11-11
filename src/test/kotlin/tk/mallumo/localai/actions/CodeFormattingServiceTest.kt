package tk.mallumo.localai.actions

import tk.mallumo.localai.settings.LocalAiCommitState
import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class CodeFormattingServiceTest {

    @Test
    fun testIsXmlFile() {
        assertTrue(CodeFormattingService.isXmlFile("AndroidManifest.xml"))
        assertTrue(CodeFormattingService.isXmlFile("strings.XML"))
        assertTrue(CodeFormattingService.isXmlFile("path/to/layout.xml"))
        assertTrue(CodeFormattingService.isXmlFile("pom.xml"))

        assertFalse(CodeFormattingService.isXmlFile("MainActivity.kt"))
        assertFalse(CodeFormattingService.isXmlFile("styles.css"))
        assertFalse(CodeFormattingService.isXmlFile("build.gradle.kts"))
        assertFalse(CodeFormattingService.isXmlFile("xml_helper.kt"))
    }

    @Test
    fun testDefaultReformatXmlIsUnchecked() {
        val state = LocalAiCommitState()
        // Default must be unchecked (false)
        assertFalse(state.reformatXml)
    }
}
