package tk.mallumo.localai.llm

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import tk.mallumo.localai.settings.LocalAiCommitSettingsService
import java.util.regex.Pattern

object VersionExtractor {
    private val LOG = Logger.getInstance(VersionExtractor::class.java)

    /**
     * Attempts fast regex-based extraction from known version file formats.
     */
    fun extractVersionFast(file: VirtualFile): String? {
        val text = try {
            VfsUtil.loadText(file)
        } catch (e: Exception) {
            LOG.warn("Failed to read ${file.name}: ${e.message}")
            return null
        }
        return extractVersionFromText(file.name, text)
    }

    fun extractVersionFromText(fileName: String, text: String): String? {
        val lowerName = fileName.lowercase()

        // 1. Gradle Properties: version = 1.0.0 or version=1.0.0
        if (lowerName == "gradle.properties") {
            val matcher = Pattern.compile("""(?m)^\s*version\s*=\s*['"]?([a-zA-Z0-9.\-_+]+)['"]?\s*$""").matcher(text)
            if (matcher.find()) return matcher.group(1).trim()
        }

        // 2. Gradle / Gradle KTS: version = "1.0.0" or version = '1.0.0' or val version = "1.0.0"
        if (lowerName.endsWith(".gradle") || lowerName.endsWith(".gradle.kts")) {
            val p1 = Pattern.compile("""(?m)^\s*(?:val\s+|var\s+)?version\s*=\s*["']([^"'\r\n]+)["']""").matcher(text)
            if (p1.find()) return p1.group(1).trim()

            val p2 = Pattern.compile("""(?m)^\s*version\s+["']([^"'\r\n]+)["']""").matcher(text)
            if (p2.find()) return p2.group(1).trim()
        }

        // 3. TOML (e.g. libs.versions.toml)
        if (lowerName.endsWith(".toml")) {
            val appVer =
                Pattern.compile("""(?m)^\s*(?:app|project|appVersion|version|release)\s*=\s*["']([^"'\r\n]+)["']""")
                    .matcher(text)
            if (appVer.find()) return appVer.group(1).trim()

            val generic = Pattern.compile("""(?m)^\s*version\s*=\s*["']([^"'\r\n]+)["']""").matcher(text)
            if (generic.find()) return generic.group(1).trim()
        }

        // 4. Package.json
        if (lowerName == "package.json") {
            val matcher = Pattern.compile(""""version"\s*:\s*"([^"]+)"""").matcher(text)
            if (matcher.find()) return matcher.group(1).trim()
        }

        // 5. Maven pom.xml
        if (lowerName == "pom.xml") {
            val matcher = Pattern.compile("""<version>([^<]+)</version>""").matcher(text)
            if (matcher.find()) return matcher.group(1).trim()
        }

        // 6. Plain text version file (e.g. version.txt or VERSION)
        if (lowerName.startsWith("version")) {
            val firstLine = text.lineSequence().firstOrNull { it.isNotBlank() }?.trim()
            if (firstLine != null && firstLine.length <= 30 && firstLine.matches(Regex("""[vV]?[0-9][a-zA-Z0-9.\-_+]*"""))) {
                return firstLine.removePrefix("v").removePrefix("V")
            }
        }

        return null
    }

    /**
     * AI-based version extraction when regex heuristics fail or are ambiguous.
     */
    suspend fun extractVersionWithAi(
        file: VirtualFile,
        settings: LocalAiCommitSettingsService
    ): String? = withContext(Dispatchers.IO) {
        val text = try {
            VfsUtil.loadText(file)
        } catch (_: Exception) {
            return@withContext null
        }

        val snippet = if (text.length > 3000) text.take(3000) else text
        val systemPrompt =
            "You are a precision parser. Inspect the file content and output ONLY the primary release version string or NONE. Do not provide explanations or markdown tags."
        val userPrompt = "File name: ${file.name}\nFile content:\n$snippet"

        val serverUrl = settings.state.serverUrl
        val modelId = settings.state.modelName.ifBlank { "default" }

        val fullText = StringBuilder()
        try {
            KoogLlmClient.generateStreaming(
                baseUrlInput = serverUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                userPrompt = userPrompt,
                onToken = { delta ->
                    fullText.append(delta)
                }
            )

            val result = fullText.toString().trim()
            if (result.isNotBlank() && !result.equals("NONE", ignoreCase = true) && result.length <= 40) {
                result.removePrefix("v").removePrefix("V").trim('"', '\'', '`')
            } else {
                null
            }
        } catch (e: Exception) {
            LOG.warn("AI version extraction error: ${e.message}")
            null
        }
    }
}
