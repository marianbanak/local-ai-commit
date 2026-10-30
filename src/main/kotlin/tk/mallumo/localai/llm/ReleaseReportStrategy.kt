package tk.mallumo.localai.llm

import tk.mallumo.localai.settings.LocalAiCommitSettingsService

object ReleaseReportStrategy {

    const val RELEASE_SYSTEM_PROMPT = """You are an expert developer and technical lead.
Generate a comprehensive, clear, and professional Git Commit Report summarizing the provided commit logs.

The report must include:
1. Executive Summary: A high-level overview of the main features, bug fixes, and goals achieved during this period.
2. Grouped Changes: Organize individual changes into logical categories (e.g., Features, Bug Fixes, Refactoring, Documentation) with detailed bullet points. Include brief explanations.
3. Impact Analysis: Explain any overall architecture impacts, codebase health changes, or key dependencies introduced.

Use clean Markdown formatting. Do not wrap the output in markdown code blocks. Do not repeat or include the '# RELEASE' title header as it is already added. Start directly with '1. Executive Summary:'."""

    suspend fun generateReleaseReport(
        settingsService: LocalAiCommitSettingsService,
        version: String,
        commitLogs: String,
        onToken: suspend (String) -> Unit
    ): Pair<String, Boolean> {
        val titleHeader = if (version.isNotBlank()) "# RELEASE v$version\n\n\n" else "# RELEASE\n\n\n"
        onToken(titleHeader)

        val serverUrl = settingsService.state.serverUrl
        val modelId = settingsService.state.modelName.ifBlank { "default" }

        // Truncate logs if too large (roughly 30,000 tokens * 4 chars = 120,000 chars)
        val maxLogChars = (settingsService.state.maxDiffTokens.coerceAtLeast(10000)) * 4
        val safeLogs = if (commitLogs.length > maxLogChars) {
            commitLogs.take(maxLogChars) + "\n\n... [LOGS TRUNCATED DUE TO CONTEXT LIMITS] ..."
        } else {
            commitLogs
        }

        val userPrompt = "Here are the git commit logs:\n\n$safeLogs"

        val (rawResult, loopDetected) = KoogLlmClient.generateStreaming(
            baseUrlInput = serverUrl,
            modelId = modelId,
            systemPrompt = RELEASE_SYSTEM_PROMPT,
            userPrompt = userPrompt,
            onToken = onToken
        )

        if (loopDetected) {
            onToken("\n\n... [Repetition loop detected and halted] ...\n")
        }

        val footer = "\n\n***AI release commit***\n"
        onToken(footer)

        val cleanBody = KoogLlmClient.sanitizeOutput(rawResult)
            .removePrefix("# RELEASE")
            .removePrefix("# RELEASE v$version")
            .trimStart('\r', '\n')
        val finalReport =
            titleHeader + cleanBody + (if (loopDetected) "\n\n... [Repetition loop detected and halted] ...\n" else "") + footer

        return Pair(finalReport, loopDetected)
    }
}

