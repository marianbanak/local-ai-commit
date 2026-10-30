package tk.mallumo.localai.llm

import ai.koog.prompt.Prompt
import ai.koog.prompt.executor.clients.openai.OpenAIClientSettings
import ai.koog.prompt.executor.clients.openai.OpenAILLMClient
import ai.koog.prompt.llm.LLMProvider
import ai.koog.prompt.llm.LLModel
import ai.koog.prompt.message.Message
import ai.koog.prompt.message.RequestMetaInfo
import ai.koog.prompt.streaming.StreamFrame
import com.intellij.openapi.diagnostic.Logger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URI
import java.nio.charset.StandardCharsets

object KoogLlmClient {
    private val LOG = Logger.getInstance(KoogLlmClient::class.java)

    data class ConnectionResult(
        val success: Boolean,
        val message: String,
        val contextWindow: Int = 32768,
        val modelId: String = ""
    )

    private fun normalizeUrl(url: String): String {
        var trimmed = url.trim()
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length - 1)
        }
        return trimmed
    }

    suspend fun verifyConnection(baseUrlInput: String, modelPreference: String = ""): ConnectionResult =
        withContext(Dispatchers.IO) {
            val baseUrl = normalizeUrl(baseUrlInput)
            try {
                // 1. Try reading llama.cpp server /props for n_ctx (context window)
                var detectedContextWindow: Int? = null
                try {
                    val propsUrl = URI("$baseUrl/props").toURL()
                    val conn = propsUrl.openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "GET"
                    if (conn.responseCode in 200..299) {
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        detectedContextWindow = extractContextFromProps(resp)
                    }
                } catch (e: Exception) {
                    LOG.debug("Could not read /props: ${e.message}")
                }

                // 2. Discover models via /v1/models or Koog client
                var detectedModel = modelPreference.ifBlank { "" }
                try {
                    val modelsUrl = URI("$baseUrl/v1/models").toURL()
                    val conn = modelsUrl.openConnection() as HttpURLConnection
                    conn.connectTimeout = 3000
                    conn.readTimeout = 3000
                    conn.requestMethod = "GET"
                    if (conn.responseCode in 200..299) {
                        val resp = conn.inputStream.bufferedReader().use { it.readText() }
                        val models = extractModelIds(resp)
                        if (models.isNotEmpty()) {
                            if (detectedModel.isBlank() || detectedModel !in models) {
                                detectedModel = models.first()
                            }
                        }
                        if (detectedContextWindow == null) {
                            detectedContextWindow = extractContextFromModels(resp)
                        }
                    }
                } catch (e: Exception) {
                    LOG.debug("Could not read /v1/models: ${e.message}")
                }

                // Fallback default context window if not explicitly exposed by server
                val finalContextWindow = detectedContextWindow ?: 32768
                if (detectedModel.isBlank()) {
                    detectedModel = "default"
                }

                // 3. Ping /v1/chat/completions (alive check)
                val isAlive = testChatAlive(baseUrl, detectedModel)
                if (isAlive) {
                    ConnectionResult(
                        success = true,
                        message = "Connected successfully. Model: $detectedModel (Context: $finalContextWindow tokens)",
                        contextWindow = finalContextWindow,
                        modelId = detectedModel
                    )
                } else {
                    ConnectionResult(
                        success = false,
                        message = "Server reached, but /v1/chat/completions did not respond properly."
                    )
                }
            } catch (e: Exception) {
                ConnectionResult(
                    success = false,
                    message = "Connection failed: ${e.message ?: e.toString()}"
                )
            }
        }

    private fun testChatAlive(baseUrl: String, model: String): Boolean {
        return try {
            val chatUrl = URI("$baseUrl/v1/chat/completions").toURL()
            val conn = chatUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 5000
            conn.readTimeout = 5000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val payload = """{"model":"$model","messages":[{"role":"user","content":"ping"}],"max_tokens":1}"""
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload) }
            conn.responseCode in 200..299
        } catch (_: Exception) {
            false
        }
    }

    private fun extractContextFromProps(json: String): Int? {
        val nCtxMatch = """"n_ctx"\s*:\s*(\d+)""".toRegex().find(json)
        if (nCtxMatch != null) {
            return nCtxMatch.groupValues[1].toIntOrNull()
        }
        val defaultCtx = """"context_size"\s*:\s*(\d+)""".toRegex().find(json)
        return defaultCtx?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractContextFromModels(json: String): Int? {
        val match = """"context_length"\s*:\s*(\d+)""".toRegex().find(json)
        return match?.groupValues?.get(1)?.toIntOrNull()
    }

    private fun extractModelIds(json: String): List<String> {
        val ids = mutableListOf<String>()
        val regex = """"id"\s*:\s*"([^"]+)"""".toRegex()
        for (match in regex.findAll(json)) {
            ids.add(match.groupValues[1])
        }
        return ids
    }

    suspend fun countTokens(baseUrlInput: String, text: String): Int = withContext(Dispatchers.IO) {
        if (text.isBlank()) return@withContext 0
        val baseUrl = normalizeUrl(baseUrlInput)
        try {
            val tokenUrl = URI("$baseUrl/tokenize").toURL()
            val conn = tokenUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 4000
            conn.readTimeout = 4000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            val escaped = escapeJson(text)
            val payload = """{"content":"$escaped"}"""
            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload) }
            if (conn.responseCode in 200..299) {
                val resp = conn.inputStream.bufferedReader().use { it.readText() }
                val tokensRegex = """"tokens"\s*:\s*\[([^\]]*)\]""".toRegex()
                val match = tokensRegex.find(resp)
                if (match != null) {
                    val items = match.groupValues[1].trim()
                    if (items.isEmpty()) return@withContext 0
                    return@withContext items.split(',').size
                }
            }
        } catch (_: Exception) {
            // fallback to heuristic
        }
        maxOf(1, (text.length / 3.2).toInt())
    }

    private fun escapeJson(text: String): String {
        val sb = StringBuilder()
        for (c in text) {
            when (c) {
                '\\' -> sb.append("\\\\")
                '"' -> sb.append("\\\"")
                '\b' -> sb.append("\\b")
                '\u000C' -> sb.append("\\f")
                '\n' -> sb.append("\\n")
                '\r' -> sb.append("\\r")
                '\t' -> sb.append("\\t")
                else -> {
                    if (c.code in 0..0x1f) {
                        sb.append(String.format("\\u%04x", c.code))
                    } else {
                        sb.append(c)
                    }
                }
            }
        }
        return sb.toString()
    }

    suspend fun generateStreaming(
        baseUrlInput: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        onToken: suspend (String) -> Unit
    ): Pair<String, Boolean> = withContext(Dispatchers.IO) {
        val baseUrl = normalizeUrl(baseUrlInput)
        val fullContent = StringBuilder()
        var loopDetected = false

        // Attempt 1: Using Koog's OpenAILLMClient
        try {
            val settings = OpenAIClientSettings(baseUrl = baseUrl)
            val client = OpenAILLMClient(apiKey = "local", settings = settings)
            val prompt = Prompt(
                messages = listOf(
                    Message.System(content = systemPrompt, metaInfo = RequestMetaInfo.Empty),
                    Message.User(content = userPrompt, metaInfo = RequestMetaInfo.Empty)
                ),
                id = "commit-generation"
            )
            val model = LLModel(
                provider = LLMProvider("openai", "OpenAI"),
                id = modelId.ifBlank { "default" }
            )

            val flow: Flow<StreamFrame> = client.executeStreaming(prompt, model, emptyList())
            flow.collect { frame ->
                if (frame is StreamFrame.TextDelta) {
                    val delta = frame.text
                    if (delta.isNotEmpty()) {
                        fullContent.append(delta)
                        onToken(delta)
                        if (LoopDetector.detectRepetitionLoop(fullContent.toString())) {
                            loopDetected = true
                            throw RepetitionLoopException()
                        }
                    }
                }
            }
        } catch (e: RepetitionLoopException) {
            // Loop detected and halted early
            loopDetected = true
        } catch (e: Exception) {
            LOG.info("Koog client stream encountered: ${e.message}, falling back to direct SSE connection")
            // Attempt 2: Direct SSE HTTP streaming as robust fallback
            if (fullContent.isEmpty()) {
                val sseResult = runDirectSse(baseUrl, modelId, systemPrompt, userPrompt, onToken)
                return@withContext sseResult
            }
        }

        var result = fullContent.toString().trim()
        if (loopDetected) {
            result = LoopDetector.deduplicateConsecutiveLines(result)
        }
        Pair(result, loopDetected)
    }

    private suspend fun runDirectSse(
        baseUrl: String,
        modelId: String,
        systemPrompt: String,
        userPrompt: String,
        onToken: suspend (String) -> Unit
    ): Pair<String, Boolean> {
        val fullContent = StringBuilder()
        var loopDetected = false

        try {
            val chatUrl = URI("$baseUrl/v1/chat/completions").toURL()
            val conn = chatUrl.openConnection() as HttpURLConnection
            conn.connectTimeout = 10000
            conn.readTimeout = 60000
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.setRequestProperty("Content-Type", "application/json")
            conn.setRequestProperty("Accept", "text/event-stream")

            val payload = """
            {
              "model": "${if (modelId.isBlank()) "default" else modelId}",
              "messages": [
                {"role": "system", "content": "${escapeJson(systemPrompt)}"},
                {"role": "user", "content": "${escapeJson(userPrompt)}"}
              ],
              "temperature": 0.6,
              "top_p": 0.9,
              "stream": true
            }
            """.trimIndent()

            OutputStreamWriter(conn.outputStream, StandardCharsets.UTF_8).use { it.write(payload) }

            if (conn.responseCode in 200..299) {
                BufferedReader(InputStreamReader(conn.inputStream, StandardCharsets.UTF_8)).use { reader ->
                    var line: String? = reader.readLine()
                    while (line != null) {
                        line = line.trim()
                        if (line.startsWith("data: ")) {
                            val dataStr = line.substring(6).trim()
                            if (dataStr == "[DONE]") break
                            val deltaContent = extractDeltaContent(dataStr)
                            if (deltaContent.isNotEmpty()) {
                                fullContent.append(deltaContent)
                                onToken(deltaContent)
                                if (LoopDetector.detectRepetitionLoop(fullContent.toString())) {
                                    loopDetected = true
                                    break
                                }
                            }
                        }
                        line = reader.readLine()
                    }
                }
            }
        } catch (e: Exception) {
            LOG.warn("Direct SSE connection failed: ${e.message}")
        }

        var result = fullContent.toString().trim()
        if (loopDetected) {
            result = LoopDetector.deduplicateConsecutiveLines(result)
        }
        return Pair(result, loopDetected)
    }

    private fun extractDeltaContent(json: String): String {
        val contentRegex = """"content"\s*:\s*"((?:[^"\\]|\\.)*)"""".toRegex()
        val match = contentRegex.find(json) ?: return ""
        val raw = match.groupValues[1]
        return unescapeJson(raw)
    }

    private fun unescapeJson(escaped: String): String {
        return escaped
            .replace("\\n", "\n")
            .replace("\\r", "\r")
            .replace("\\t", "\t")
            .replace("\\\"", "\"")
            .replace("\\\\", "\\")
    }

    private class RepetitionLoopException : RuntimeException()
}
