package tk.mallumo.localai.llm

import kotlin.test.Test
import kotlin.test.assertEquals

class CommitStrategyTest {

    @Test
    fun testBuildChunksWithinBudget() {
        val files = listOf(
            "core/fileA.kt",
            "core/fileB.kt",
            "utils/fileC.kt"
        )
        val fileTokens = mapOf(
            "core/fileA.kt" to 500,
            "core/fileB.kt" to 600,
            "utils/fileC.kt" to 400
        )
        val maxTokens = 2000
        val chunks = CommitStrategy.buildChunks(files, fileTokens, maxTokens)
        // Since total tokens is 1500 <= 2000, files under the directory structure are chunked cleanly
        val allChunkFiles = chunks.flatten()
        assertEquals(3, allChunkFiles.size)
    }

    @Test
    fun testBuildChunksExceedingBudgetSplits() {
        val files = listOf(
            "module/A.kt",
            "module/B.kt"
        )
        val fileTokens = mapOf(
            "module/A.kt" to 1500,
            "module/B.kt" to 1500
        )
        val maxTokens = 2000
        val chunks = CommitStrategy.buildChunks(files, fileTokens, maxTokens)
        // Each file is 1500 tokens, adding both exceeds 2000, so they must be split into 2 chunks
        assertEquals(2, chunks.size)
        assertEquals(listOf("module/A.kt"), chunks[0])
        assertEquals(listOf("module/B.kt"), chunks[1])
    }

    @Test
    fun testCalculateSelectionStatsSingleCall() {
        val files = listOf("core/A.kt", "core/B.kt")
        val fileTokens = mapOf("core/A.kt" to 300, "core/B.kt" to 400)
        val stats =
            CommitStrategy.calculateSelectionStats(files, fileTokens, maxDiffTokens = 2000, contextWindowTokens = 4000)

        assertEquals(2, stats.totalFiles)
        assertEquals(2, stats.normalFiles)
        assertEquals(0, stats.svgFiles)
        assertEquals(700, stats.totalDiffTokens)
        assertEquals(1, stats.estimatedCalls)
    }

    @Test
    fun testCalculateSelectionStatsMultiChunk() {
        val files = listOf("module1/A.kt", "module2/B.kt")
        val fileTokens = mapOf("module1/A.kt" to 1500, "module2/B.kt" to 1500)
        val stats =
            CommitStrategy.calculateSelectionStats(files, fileTokens, maxDiffTokens = 2000, contextWindowTokens = 4000)

        assertEquals(2, stats.totalFiles)
        assertEquals(3000, stats.totalDiffTokens)
        assertEquals(2, stats.numChunks)
        // 2 chunk summaries + 1 final consolidation = 3 calls
        assertEquals(3, stats.estimatedCalls)
    }

    @Test
    fun testCalculateSelectionStatsSvgOnly() {
        val files = listOf("icons/logo.svg", "icons/badge.svg")
        val fileTokens = emptyMap<String, Int>()
        val stats =
            CommitStrategy.calculateSelectionStats(files, fileTokens, maxDiffTokens = 2000, contextWindowTokens = 4000)

        assertEquals(2, stats.totalFiles)
        assertEquals(0, stats.normalFiles)
        assertEquals(2, stats.svgFiles)
        assertEquals(0, stats.totalDiffTokens)
        // 0 LLM calls for SVG-only changes
        assertEquals(0, stats.estimatedCalls)
    }

    @Test
    fun testCalculateSelectionStatsWithBinaryFiles() {
        val files = listOf(
            "src/Main.kt",
            "docs/manual.pdf",
            "assets/logo.png",
            "media/demo.mp4"
        )
        val fileTokens = mapOf("src/Main.kt" to 350)
        val stats =
            CommitStrategy.calculateSelectionStats(files, fileTokens, maxDiffTokens = 2000, contextWindowTokens = 4000)

        assertEquals(4, stats.totalFiles)
        assertEquals(1, stats.normalFiles)
        assertEquals(3, stats.binaryFiles)
        assertEquals(0, stats.svgFiles)
        assertEquals(350, stats.totalDiffTokens)
        assertEquals(1, stats.estimatedCalls)
    }

    @Test
    fun testCalculateSelectionStatsBinaryOnly() {
        val files = listOf("assets/logo.png", "docs/manual.pdf")
        val fileTokens = emptyMap<String, Int>()
        val stats =
            CommitStrategy.calculateSelectionStats(files, fileTokens, maxDiffTokens = 2000, contextWindowTokens = 4000)

        assertEquals(2, stats.totalFiles)
        assertEquals(0, stats.normalFiles)
        assertEquals(2, stats.binaryFiles)
        assertEquals(0, stats.totalDiffTokens)
        // 1 LLM call using asset metadata prompt without binary diff
        assertEquals(1, stats.estimatedCalls)
    }
}
