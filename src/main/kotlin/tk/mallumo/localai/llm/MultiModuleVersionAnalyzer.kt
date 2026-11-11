package tk.mallumo.localai.llm

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import tk.mallumo.localai.buildsystem.DetectedModule
import tk.mallumo.localai.buildsystem.ProjectBuildStructure
import tk.mallumo.localai.settings.LocalAiCommitSettingsService
import java.util.regex.Pattern

data class MultiModuleVersionResult(
    val primaryVersion: String?,
    val modules: List<DetectedModule>,
    val rawAnalysis: String
)

object MultiModuleVersionAnalyzer {
    private val LOG = Logger.getInstance(MultiModuleVersionAnalyzer::class.java)

    const val SYSTEM_PROMPT = """You are an expert build-engineer AI.
Analyze the repository project tree, build systems, and configuration files to determine the exact versions for all modules.

Your tasks:
1. Understand includes, version catalogs (libs.versions.toml), templates (e.g., Amper apply: template.yaml), and properties.
2. Determine the unified primary release version for the project if one exists.
3. For each module, identify its exact release version and the source file where it is defined or referenced.
4. If you require additional file contents to resolve a version reference, you may request it by outputting on its own line:
[READ_FILE: relative/path/to/file]

When your analysis is complete, summarize your findings in clean markdown format:
Primary Version: <version or NONE>
Modules:
- <module-name>: <version> (from <source-file>)"""

    suspend fun analyzeVersions(
        repository: GitRepository,
        buildStructure: ProjectBuildStructure,
        settingsService: LocalAiCommitSettingsService,
        onToken: suspend (String) -> Unit = {}
    ): MultiModuleVersionResult {
        val root = repository.root
        val serverUrl = settingsService.state.serverUrl
        val modelId = settingsService.state.modelName.ifBlank { "default" }

        // Build a concise file tree representation (only directories and config/build files)
        val treeOutline = buildTreeOutline(buildStructure.fileTreePaths)

        // Build initial context with detected modules and candidate config files
        val contextBuilder = StringBuilder()
        contextBuilder.append("=== DETECTED BUILD SYSTEM ===\n")
        contextBuilder.append("Primary System: ${buildStructure.primaryBuildSystem.displayName}\n\n")

        contextBuilder.append("=== MODULES FOUND ===\n")
        for (m in buildStructure.modules) {
            val status = if (m.isChangedInReleaseRange) "[MODIFIED IN THIS RELEASE]" else "[UNCHANGED]"
            val initialVer = m.version?.let { " (initial parsed: $it)" } ?: ""
            contextBuilder.append("- ${m.name} (path: '${m.relativePath}', system: ${m.buildSystem.displayName}) $status$initialVer\n")
        }
        contextBuilder.append("\n")

        contextBuilder.append("=== FILE TREE OUTLINE ===\n")
        contextBuilder.append(treeOutline).append("\n\n")

        contextBuilder.append("=== CANDIDATE CONFIGURATION FILES ===\n")
        for ((path, content) in buildStructure.candidateConfigFiles) {
            contextBuilder.append("--- File: $path ---\n")
            contextBuilder.append(content).append("\n\n")
        }

        var currentUserPrompt = "Analyze the project structure and determine all module versions:\n\n$contextBuilder"
        var fullAiResponse = ""
        val toolCallLimit = 5
        var toolCallsCount = 0

        while (toolCallsCount < toolCallLimit) {
            val (rawResult, _) = KoogLlmClient.generateStreaming(
                baseUrlInput = serverUrl,
                modelId = modelId,
                systemPrompt = SYSTEM_PROMPT,
                userPrompt = currentUserPrompt,
                onToken = onToken
            )

            fullAiResponse += rawResult

            // Check if AI requested any file
            val readFileMatcher = Pattern.compile("""\[READ_FILE:\s*([^\]]+)\]""").matcher(rawResult)
            if (readFileMatcher.find()) {
                val requestedPath = readFileMatcher.group(1).trim()
                toolCallsCount++
                onToken("\n▶ [AI Tool: Reading requested file '$requestedPath']\n")

                val additionalContent = readFile(root, requestedPath)
                if (additionalContent != null) {
                    currentUserPrompt =
                        "Content of '$requestedPath':\n$additionalContent\n\nPlease continue your version analysis."
                } else {
                    currentUserPrompt =
                        "File '$requestedPath' could not be found or read. Please proceed with the available information."
                }
            } else {
                break
            }
        }

        val primaryVersion = extractPrimaryVersion(fullAiResponse)
            ?: buildStructure.modules.firstOrNull { !it.version.isNullOrBlank() }?.version
            ?: extractFallbackFromCandidates(buildStructure.candidateConfigFiles)

        val updatedModules = mergeAiModuleVersions(buildStructure.modules, fullAiResponse)

        return MultiModuleVersionResult(
            primaryVersion = primaryVersion,
            modules = updatedModules,
            rawAnalysis = fullAiResponse
        )
    }

    internal fun buildTreeOutline(paths: List<String>): String {
        val interestingPaths = paths.filter { path ->
            val lower = path.lowercase()
            lower.endsWith(".gradle.kts") || lower.endsWith(".gradle") ||
                    lower.endsWith(".yaml") || lower.endsWith(".yml") ||
                    lower.endsWith(".toml") || lower.endsWith(".properties") ||
                    lower.endsWith(".json") || lower.endsWith(".xml") ||
                    !path.contains('/') // root level files
        }.take(80)

        return interestingPaths.joinToString("\n")
    }

    private fun readFile(root: VirtualFile, relPath: String, maxChars: Int = 12000): String? {
        return try {
            val clean = relPath.trim().removePrefix("/").removePrefix("./")
            val vf = root.findFileByRelativePath(clean) ?: return null
            val text = String(vf.contentsToByteArray(), Charsets.UTF_8)
            if (text.length > maxChars) text.take(maxChars) + "\n... [truncated]" else text
        } catch (_: Exception) {
            null
        }
    }

    internal fun extractPrimaryVersion(aiOutput: String): String? {
        val m = Pattern.compile("""(?i)Primary\s*Version\s*:\s*[`"']?([a-zA-Z0-9.\-_+]+)[`"']?""").matcher(aiOutput)
        if (m.find()) {
            val v = m.group(1).trim()
            if (!v.equals("NONE", ignoreCase = true) && !v.equals("UNKNOWN", ignoreCase = true)) {
                return v.removePrefix("v").removePrefix("V")
            }
        }
        return null
    }

    internal fun extractFallbackFromCandidates(candidateFiles: Map<String, String>): String? {
        for ((path, content) in candidateFiles) {
            val lower = path.lowercase()
            if (lower.endsWith("gradle.properties") || lower.endsWith(".toml") || lower.endsWith("pom.xml") || lower.endsWith(
                    "module.yaml"
                )
            ) {
                val v = VersionExtractor.extractVersionFromText(lower.substringAfterLast('/'), content)
                if (!v.isNullOrBlank()) return v
            }
        }
        return null
    }

    internal fun mergeAiModuleVersions(
        initialModules: List<DetectedModule>,
        aiOutput: String
    ): List<DetectedModule> {
        return initialModules.map { mod ->
            // Look for "- <name>: <version>" or "- :<name>: <version>"
            val escapedName = Pattern.quote(mod.name.removePrefix(":"))
            val regex = """(?im)^\s*-\s*:?${escapedName}\s*:\s*[`"']?([a-zA-Z0-9.\-_+]+)[`"']?""".toRegex()
            val match = regex.find(aiOutput)
            if (match != null) {
                val foundVer = match.groupValues[1].trim()
                if (foundVer.isNotBlank() && !foundVer.equals("NONE", ignoreCase = true)) {
                    mod.copy(version = foundVer)
                } else mod
            } else {
                mod
            }
        }
    }
}
