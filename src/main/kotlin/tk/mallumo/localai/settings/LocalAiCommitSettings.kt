package tk.mallumo.localai.settings

import com.intellij.openapi.components.*
import com.intellij.openapi.project.Project

enum class CommitMessageSize(val displayName: String) {
    SMALL("Small"),
    MEDIUM("Medium"),
    LARGE("Large");

    override fun toString(): String = displayName

    companion object {
        fun fromString(value: String?): CommitMessageSize {
            return entries.firstOrNull {
                it.name.equals(value, ignoreCase = true) || it.displayName.equals(value, ignoreCase = true)
            } ?: MEDIUM
        }
    }
}

const val SYSTEM_PROMPT_SMALL =
    """You are an expert software engineer. Generate a concise, high-quality git commit message using Conventional Commits format.

Structure:
1. Header: <type>(<scope>): <short description>
2. Body: A concise bulleted list dynamically covering all important changes. Keep each bullet brief and compact (high-level, single-line summary of what changed without deep elaboration).
3. Footer: Note any BREAKING CHANGES only if present. Do NOT include issue IDs, JIRA references, or conversational filler.

Rules:
- Dynamically generate as many bullet points as needed to cover all important modifications.
- Keep each bullet brief, concise, and focused on high-level impact.
- Use imperative mood.
- Do not use conversational filler.
- Do not include any Jira/Issue tracking information.
- Output ONLY the raw commit message. Do NOT wrap it in markdown code blocks."""

const val CHUNK_SYSTEM_PROMPT_SMALL = """You are an expert software engineer.
Analyze the git diff for this subset of files and generate a concise bulleted list of changes.
Dynamically include bullet points for all important modifications, keeping each bullet brief, compact, and high-level without deep elaboration.
Output ONLY the raw bulleted list of changes. Do NOT include header, footer, or any conversational filler. Do NOT wrap in markdown code blocks."""

const val FINAL_SYSTEM_PROMPT_SMALL = """You are an expert software engineer.
Generate a concise, cohesive Conventional Commit message based on the provided summaries of changes.

Structure:
1. Header: <type>(<scope>): <short description>
2. Body: A concise consolidated bulleted list dynamically covering all important changes. Keep each bullet brief, compact, and high-level without deep elaboration.
3. Footer: Note any BREAKING CHANGES only if present. Do NOT include issue IDs, JIRA references, or conversational filler.

Rules:
- Dynamically generate as many bullet points as needed to cover all important modifications.
- Keep each bullet brief, concise, and focused on high-level impact.
- Use imperative mood.
- Do not use conversational filler.
- Do not include any Jira/Issue tracking information.
- Output ONLY the raw commit message. Do NOT wrap it in markdown code blocks."""

const val SYSTEM_PROMPT_MEDIUM =
    """You are an expert software engineer. Generate a clear, well-rounded git commit message using Conventional Commits format.

Structure:
1. Header: <type>(<scope>): <short description>
2. Body: A balanced, informative bulleted list dynamically covering all important changes. For each bullet, provide moderate detail (roughly double the explanation of a brief summary), explaining both what was changed and the motivation or logic behind it.
3. Footer: Note any BREAKING CHANGES only if present. Do NOT include issue IDs, JIRA references, or conversational filler.

Rules:
- Dynamically generate as many bullet points as needed to cover all important modifications.
- Provide balanced detail for each bullet point, explaining the rationale and logic updates clearly.
- Use imperative mood.
- Do not use conversational filler.
- Do not include any Jira/Issue tracking information.
- Output ONLY the raw commit message. Do NOT wrap it in markdown code blocks."""

const val CHUNK_SYSTEM_PROMPT_MEDIUM = """You are an expert software engineer.
Analyze the git diff for this subset of files and generate an informative bulleted list of changes.
Dynamically include bullet points for all important modifications. For each bullet, provide balanced detail, explaining what was modified, function/class logic updates, and context without unnecessary filler.
Output ONLY the raw bulleted list of changes. Do NOT include header, footer, or any conversational filler. Do NOT wrap in markdown code blocks."""

const val FINAL_SYSTEM_PROMPT_MEDIUM = """You are an expert software engineer.
Generate a clear, cohesive Conventional Commit message based on the provided summaries of changes.

Structure:
1. Header: <type>(<scope>): <short description>
2. Body: A balanced, consolidated bulleted list dynamically covering all important changes. For each bullet, provide moderate detail (roughly double the explanation of a brief summary), explaining key modifications, logic updates, and motivations.
3. Footer: Note any BREAKING CHANGES only if present. Do NOT include issue IDs, JIRA references, or conversational filler.

Rules:
- Dynamically generate as many bullet points as needed to cover all important modifications.
- Provide balanced detail for each bullet point, explaining the rationale and logic updates clearly.
- Use imperative mood.
- Do not use conversational filler.
- Do not include any Jira/Issue tracking information.
- Output ONLY the raw commit message. Do NOT wrap it in markdown code blocks."""

const val SYSTEM_PROMPT_LARGE =
    """You are an expert software engineer. Generate an extensive, thorough git commit message using Conventional Commits format.

Structure:
1. Header: <type>(<scope>): <short description>
2. Body: A comprehensive, detailed bulleted list dynamically covering all important changes. For each bullet, provide in-depth, thorough explanations (roughly double the detail of a moderate summary), covering technical rationale, component behavior changes, data flow, and architectural impacts.
3. Footer: Note any BREAKING CHANGES only if present. Do NOT include issue IDs, JIRA references, or conversational filler.

Rules:
- Dynamically generate as many bullet points as needed to cover all important modifications.
- Provide exhaustive, in-depth detail for each bullet, thoroughly documenting technical rationale, component updates, architectural impacts, and edge cases.
- Use imperative mood.
- Do not use conversational filler.
- Do not include any Jira/Issue tracking information.
- Output ONLY the raw commit message. Do NOT wrap it in markdown code blocks."""

const val CHUNK_SYSTEM_PROMPT_LARGE = """You are an expert software engineer.
Analyze the git diff for this subset of files and generate a comprehensive, in-depth bulleted list of changes.
Dynamically include bullet points for all important modifications. For each bullet, provide exhaustive technical detail, thoroughly describing modifications, component behaviors, data structures, and edge cases.
Output ONLY the raw bulleted list of changes. Do NOT include header, footer, or any conversational filler. Do NOT wrap in markdown code blocks."""

const val FINAL_SYSTEM_PROMPT_LARGE = """You are an expert software engineer.
Generate an extensive, comprehensive Conventional Commit message based on the provided summaries of changes.

Structure:
1. Header: <type>(<scope>): <short description>
2. Body: A comprehensive, detailed bulleted list dynamically covering all important changes. For each bullet, provide in-depth, thorough explanations (roughly double the detail of a moderate summary), covering technical rationale, component behavior changes, data flow, and architectural impacts.
3. Footer: Note any BREAKING CHANGES only if present. Do NOT include issue IDs, JIRA references, or conversational filler.

Rules:
- Dynamically generate as many bullet points as needed to cover all important modifications.
- Provide exhaustive, in-depth detail for each bullet, thoroughly documenting technical rationale, component updates, architectural impacts, and edge cases.
- Use imperative mood.
- Do not use conversational filler.
- Do not include any Jira/Issue tracking information.
- Output ONLY the raw commit message. Do NOT wrap it in markdown code blocks."""

fun getSystemPrompt(size: CommitMessageSize): String = when (size) {
    CommitMessageSize.SMALL -> SYSTEM_PROMPT_SMALL
    CommitMessageSize.MEDIUM -> SYSTEM_PROMPT_MEDIUM
    CommitMessageSize.LARGE -> SYSTEM_PROMPT_LARGE
}

fun getChunkSystemPrompt(size: CommitMessageSize): String = when (size) {
    CommitMessageSize.SMALL -> CHUNK_SYSTEM_PROMPT_SMALL
    CommitMessageSize.MEDIUM -> CHUNK_SYSTEM_PROMPT_MEDIUM
    CommitMessageSize.LARGE -> CHUNK_SYSTEM_PROMPT_LARGE
}

fun getFinalSystemPrompt(size: CommitMessageSize): String = when (size) {
    CommitMessageSize.SMALL -> FINAL_SYSTEM_PROMPT_SMALL
    CommitMessageSize.MEDIUM -> FINAL_SYSTEM_PROMPT_MEDIUM
    CommitMessageSize.LARGE -> FINAL_SYSTEM_PROMPT_LARGE
}

const val DEFAULT_SYSTEM_PROMPT = SYSTEM_PROMPT_MEDIUM
const val DEFAULT_CHUNK_SYSTEM_PROMPT = CHUNK_SYSTEM_PROMPT_MEDIUM
const val DEFAULT_FINAL_SYSTEM_PROMPT = FINAL_SYSTEM_PROMPT_MEDIUM

class LocalAiCommitState {
    var serverUrl: String = LocalAiUrlStorage.loadLastUsedUrl("http://localhost:11434")
    var modelName: String = ""
    var reformatCode: Boolean = true
    var optimizeImports: Boolean = true
    var rearrangeCode: Boolean = false
    var reformatXml: Boolean = LocalAiUrlStorage.loadReformatXml(false)
        set(value) {
            field = value
            LocalAiUrlStorage.saveReformatXml(value)
        }
    var commitMessageSize: String = LocalAiUrlStorage.loadCommitSize(CommitMessageSize.MEDIUM.name)
    var systemPrompt: String = DEFAULT_SYSTEM_PROMPT
    var chunkSystemPrompt: String = DEFAULT_CHUNK_SYSTEM_PROMPT
    var finalSystemPrompt: String = DEFAULT_FINAL_SYSTEM_PROMPT

    var messageSize: CommitMessageSize
        get() = CommitMessageSize.fromString(commitMessageSize)
        set(value) {
            commitMessageSize = value.name
            LocalAiUrlStorage.saveCommitSize(value.name)
        }

    fun getEffectiveSystemPrompt(): String = getSystemPrompt(messageSize)
    fun getEffectiveChunkSystemPrompt(): String = getChunkSystemPrompt(messageSize)
    fun getEffectiveFinalSystemPrompt(): String = getFinalSystemPrompt(messageSize)

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
        if (state.serverUrl.isNotBlank()) {
            LocalAiUrlStorage.saveLastUsedUrl(state.serverUrl)
        } else {
            state.serverUrl = LocalAiUrlStorage.loadLastUsedUrl()
        }
        if (state.commitMessageSize.isNotBlank()) {
            LocalAiUrlStorage.saveCommitSize(state.commitMessageSize)
        } else {
            state.commitMessageSize = LocalAiUrlStorage.loadCommitSize()
        }
        LocalAiUrlStorage.saveReformatXml(state.reformatXml)
    }

    companion object {
        fun getInstance(project: Project): LocalAiCommitSettingsService =
            project.service()
    }
}
