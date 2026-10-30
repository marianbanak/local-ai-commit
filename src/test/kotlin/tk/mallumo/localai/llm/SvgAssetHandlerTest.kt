package tk.mallumo.localai.llm

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class SvgAssetHandlerTest {

    @Test
    fun testIsSvgFile() {
        assertTrue(SvgAssetHandler.isSvgFile("src/main/resources/icons/logo.svg"))
        assertTrue(SvgAssetHandler.isSvgFile("src/svg/AppIcon.kt"))
        assertFalse(SvgAssetHandler.isSvgFile("src/main/kotlin/AppIcon.kt"))
        assertFalse(SvgAssetHandler.isSvgFile("src/main/resources/config.json"))
    }

    @Test
    fun testAppendSvgBullets() {
        val baseMsg = "feat(ui): update styling"
        val bullets = listOf("- SVG logo.svg added", "- SVG banner.svg modified")
        val result = SvgAssetHandler.appendSvgBullets(baseMsg, bullets)
        assertTrue(result.contains("- SVG logo.svg added"))
        assertTrue(result.contains("- SVG banner.svg modified"))
        assertTrue(result.startsWith("feat(ui): update styling"))
    }

    @Test
    fun testAppendSvgBulletsWithBreakingChange() {
        val baseMsg = """
            feat(ui): overhaul icons
            
            - Replace legacy vector paths
            
            BREAKING CHANGE: icon IDs have changed
        """.trimIndent()
        val bullets = listOf("- SVG new_icon.svg added")
        val result = SvgAssetHandler.appendSvgBullets(baseMsg, bullets)

        // Assert bullets are inserted before BREAKING CHANGE
        val breakingIdx = result.indexOf("BREAKING CHANGE")
        val bulletIdx = result.indexOf("- SVG new_icon.svg added")
        assertTrue(bulletIdx in 0 until breakingIdx)
    }
}
