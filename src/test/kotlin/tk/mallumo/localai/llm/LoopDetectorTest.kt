package tk.mallumo.localai.llm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class LoopDetectorTest {

    @Test
    fun testNoLoop() {
        val normalText = """
            feat(auth): add login endpoint
            
            - Implement token verification
            - Handle session refresh
            - Add tests
        """.trimIndent()
        assertFalse(LoopDetector.detectRepetitionLoop(normalText))
    }

    @Test
    fun testLineLevelLoop() {
        val loopText = """
            feat(core): update logic
            - Fix bug in processor
            - Fix bug in processor
            - Fix bug in processor
        """.trimIndent()
        assertTrue(LoopDetector.detectRepetitionLoop(loopText, maxRepeats = 3))
    }

    @Test
    fun testSubstringLoop() {
        val pattern = "repeating sentence pattern. "
        val loopText = pattern + pattern + pattern
        assertTrue(LoopDetector.detectRepetitionLoop(loopText, minLen = 8, maxRepeats = 3))
    }

    @Test
    fun testDeduplicateConsecutiveLines() {
        val loopingLines = """
            feat: sample
            - line 1
            - line 2
            - line 2
            - line 2
            - line 3
        """.trimIndent()
        val expected = """
            feat: sample
            - line 1
            - line 2
            - line 3
        """.trimIndent()
        assertEquals(expected, LoopDetector.deduplicateConsecutiveLines(loopingLines))
    }
}
