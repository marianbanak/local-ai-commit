package tk.mallumo.localai.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommitMessageSizeTest {

    @Test
    fun testCommitMessageSizeFromString() {
        assertEquals(CommitMessageSize.SMALL, CommitMessageSize.fromString("small"))
        assertEquals(CommitMessageSize.SMALL, CommitMessageSize.fromString("Small"))
        assertEquals(CommitMessageSize.MEDIUM, CommitMessageSize.fromString("medium"))
        assertEquals(CommitMessageSize.MEDIUM, CommitMessageSize.fromString("Medium"))
        assertEquals(CommitMessageSize.LARGE, CommitMessageSize.fromString("large"))
        assertEquals(CommitMessageSize.LARGE, CommitMessageSize.fromString("Large"))
        // Fallback default
        assertEquals(CommitMessageSize.MEDIUM, CommitMessageSize.fromString("unknown"))
        assertEquals(CommitMessageSize.MEDIUM, CommitMessageSize.fromString(null))
    }

    @Test
    fun testPromptScalingByCommitSize() {
        val state = LocalAiCommitState()
        // Test Medium
        state.messageSize = CommitMessageSize.MEDIUM
        assertEquals(CommitMessageSize.MEDIUM, state.messageSize)
        assertTrue(state.getEffectiveSystemPrompt().contains("moderate detail"))
        assertTrue(state.getEffectiveSystemPrompt().contains("dynamically covering all important changes"))
        assertTrue(state.getEffectiveChunkSystemPrompt().contains("balanced detail"))
        assertTrue(state.getEffectiveFinalSystemPrompt().contains("moderate detail"))

        // Set to Small
        state.messageSize = CommitMessageSize.SMALL
        assertEquals(CommitMessageSize.SMALL, state.messageSize)
        assertTrue(
            state.getEffectiveSystemPrompt().contains("single-line summary of what changed without deep elaboration")
        )
        assertTrue(state.getEffectiveSystemPrompt().contains("dynamically covering all important changes"))
        assertTrue(
            state.getEffectiveChunkSystemPrompt().contains("brief, compact, and high-level without deep elaboration")
        )
        assertTrue(
            state.getEffectiveFinalSystemPrompt().contains("brief, compact, and high-level without deep elaboration")
        )

        // Set to Large
        state.messageSize = CommitMessageSize.LARGE
        assertEquals(CommitMessageSize.LARGE, state.messageSize)
        assertTrue(state.getEffectiveSystemPrompt().contains("in-depth, thorough explanations"))
        assertTrue(state.getEffectiveSystemPrompt().contains("dynamically covering all important changes"))
        assertTrue(state.getEffectiveChunkSystemPrompt().contains("exhaustive technical detail"))
        assertTrue(state.getEffectiveFinalSystemPrompt().contains("in-depth, thorough explanations"))

        // Reset to Medium
        state.messageSize = CommitMessageSize.MEDIUM
    }
}
