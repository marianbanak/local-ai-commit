package tk.mallumo.localai.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

const val DEFAULT_SYSTEM_PROMPT =
    """You are an expert software engineer. Generate a highly detailed git commit message using Conventional Commits format.

Structure:
1. Header: <type>(<scope>): <short description>
2. Body: A detailed bulleted list explaining the logic changes, the motivation (why), and any architectural impacts. Contrast old vs new behavior.
3. Footer: Note any BREAKING CHANGES. Do NOT include issue IDs, JIRA references, or 'Related Issues' sections.

Rules:
- Use imperative mood.
- Be technical and precise.
- Do not use conversational filler.
- Do not include any Jira/Issue tracking information.
- Output ONLY the raw commit message. Do NOT wrap it in markdown code blocks (e.g., no ``` or ```conventional_commit)."""

const val DEFAULT_CHUNK_SYSTEM_PROMPT = """You are an expert software engineer.
Analyze the git diff for this subset of files and generate a detailed bulleted list of changes.
Explain the logic changes, the motivation (why), and contrast old vs new behavior.
Output ONLY the raw bulleted list of changes. Do NOT include header, footer, or any conversational filler. Do NOT wrap in markdown code blocks."""

const val DEFAULT_FINAL_SYSTEM_PROMPT = """You are an expert software engineer.
Generate a cohesive Conventional Commit message based on the provided summaries of changes.

Structure:
1. Header: <type>(<scope>): <short description>
2. Body: A detailed, consolidated bulleted list explaining the logic changes, the motivation (why), and any architectural impacts.
3. Footer: Note any BREAKING CHANGES. Do NOT include issue IDs, JIRA references, or 'Related Issues' sections.

Rules:
- Use imperative mood.
- Be technical and precise.
- Do not use conversational filler.
- Do not include any Jira/Issue tracking information.
- Output ONLY the raw commit message. Do NOT wrap it in markdown code blocks."""

class LocalAiCommitState {
    var serverUrl: String = "http://localhost:11434"
    var modelName: String = ""
    var reformatCode: Boolean = true
    var optimizeImports: Boolean = true
    var rearrangeCode: Boolean = false
    var systemPrompt: String = DEFAULT_SYSTEM_PROMPT
    var chunkSystemPrompt: String = DEFAULT_CHUNK_SYSTEM_PROMPT
    var finalSystemPrompt: String = DEFAULT_FINAL_SYSTEM_PROMPT

    // Dynamic runtime context window (updated from LLM connection)
    @com.intellij.util.xmlb.annotations.Transient
    var contextWindowTokens: Int = 32768

    val maxDiffTokens: Int
        get() = (contextWindowTokens * 0.85).toInt()
}

@Service(Service.Level.PROJECT)
@State(
    name = "LocalAiCommitSettings",
    storages = [Storage("localAiCommitSettings.xml")]
)
class LocalAiCommitSettingsService : PersistentStateComponent<LocalAiCommitState> {
    private var state = LocalAiCommitState()

    override fun getState(): LocalAiCommitState = state

    override fun loadState(state: LocalAiCommitState) {
        this.state = state
    }

    companion object {
        fun getInstance(project: Project): LocalAiCommitSettingsService =
            project.service()
    }
}
