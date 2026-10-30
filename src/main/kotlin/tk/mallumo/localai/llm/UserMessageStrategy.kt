package tk.mallumo.localai.llm

import tk.mallumo.localai.settings.LocalAiCommitSettingsService

object UserMessageStrategy {

    const val USER_MESSAGE_SYSTEM_PROMPT = """You are a professional product marketing specialist and copywriter.
Your task is to write a clear, engaging "What's New" announcement tailored specifically for regular, non-technical END USERS.

Strict Guidelines:
1. No Technical Jargon: Do NOT mention source code, class names, file paths, pull requests, git commits/hashes, databases, refactoring, dependencies, or architectural internal details.
2. Focus on User Value: Translate technical changes into user benefits — what can they do now, how is their experience improved, what issues are resolved, and how is the app faster, more reliable, or more pleasant to use.
3. Structure:
   - A welcoming headline: '# What's New in [Version/Release]'
   - A warm 1-2 sentence introduction.
   - '### Highlights' with bullet points of key new features/improvements.
   - '### Improvements & Fixes' with simplified, friendly bullets for bug fixes.
4. Formatting: Use clean Markdown. Do NOT wrap output in triple backtick code blocks."""

    suspend fun generateUserMessage(
        settingsService: LocalAiCommitSettingsService,
        branchName: String,
        branchNotes: String,
        onToken: suspend (String) -> Unit
    ): Pair<String, Boolean> {
        val serverUrl = settingsService.state.serverUrl
        val modelId = settingsService.state.modelName.ifBlank { "default" }

        val maxChars = (settingsService.state.maxDiffTokens.coerceAtLeast(10000)) * 4
        val safeNotes = if (branchNotes.length > maxChars) {
            branchNotes.take(maxChars) + "\n\n... [TRUNCATED] ..."
        } else {
            branchNotes
        }

        val userPrompt = """Here are the release notes and commit details from branch '$branchName':

$safeNotes

Please generate the end-user "What's New" announcement based on the above information."""

        val (rawResult, loopDetected) = KoogLlmClient.generateStreaming(
            baseUrlInput = serverUrl,
            modelId = modelId,
            systemPrompt = USER_MESSAGE_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            onToken = onToken
        )

        val clean = KoogLlmClient.sanitizeOutput(rawResult).trim()
        return Pair(clean, loopDetected)
    }
}
