package tk.mallumo.localai.llm

import tk.mallumo.localai.git.FileContentTypeHelper

class DirNode(val path: String) {
    val files = mutableListOf<String>()
    val children = mutableMapOf<String, DirNode>()
}

object CommitStrategy {

    interface GenerationListener {
        suspend fun onProgress(message: String)
        suspend fun onToken(token: String)
        suspend fun onChunkComplete(title: String, summary: String)
    }

    data class GenerationResult(
        val commitMessage: String,
        val skippedLoopingFiles: List<String>
    )

    data class SelectionStats(
        val totalFiles: Int,
        val normalFiles: Int,
        val svgFiles: Int,
        val totalDiffTokens: Int,
        val estimatedCalls: Int,
        val numChunks: Int,
        val maxDiffTokens: Int,
        val contextWindowTokens: Int,
        val binaryFiles: Int = 0
    )

    fun calculateSelectionStats(
        filePaths: List<String>,
        fileTokens: Map<String, Int>,
        maxDiffTokens: Int,
        contextWindowTokens: Int
    ): SelectionStats {
        val binaryFiles = filePaths.filter { FileContentTypeHelper.isBinaryOrMediaFile(it) }
        val eligibleFiles = filePaths.filterNot { FileContentTypeHelper.isBinaryOrMediaFile(it) }

        val svgFiles = eligibleFiles.filter { SvgAssetHandler.isSvgFile(it) }
        val normalFiles = eligibleFiles.filterNot { SvgAssetHandler.isSvgFile(it) }

        val totalTokens = normalFiles.sumOf { fileTokens[it] ?: 0 }

        val (estimatedCalls, numChunks) = when {
            normalFiles.isEmpty() && svgFiles.isNotEmpty() -> Pair(0, 0)
            normalFiles.isEmpty() && binaryFiles.isNotEmpty() -> Pair(1, 1)
            normalFiles.isEmpty() -> Pair(0, 0)
            else -> {
                val chunks = buildChunks(normalFiles, fileTokens, maxDiffTokens)
                if (chunks.size <= 1) {
                    Pair(1, 1)
                } else {
                    Pair(chunks.size + 1, chunks.size)
                }
            }
        }

        return SelectionStats(
            totalFiles = filePaths.size,
            normalFiles = normalFiles.size,
            svgFiles = svgFiles.size,
            totalDiffTokens = totalTokens,
            estimatedCalls = estimatedCalls,
            numChunks = numChunks,
            maxDiffTokens = maxDiffTokens,
            contextWindowTokens = contextWindowTokens,
            binaryFiles = binaryFiles.size
        )
    }

    private fun truncateDiff(diffText: String, maxTokens: Int): String {
        val maxChars = (maxTokens * 3.2).toInt()
        return if (diffText.length > maxChars) {
            diffText.substring(0, maxChars) + "\n\n... [DIFF TRUNCATED DUE TO MODEL LIMITS] ..."
        } else {
            diffText
        }
    }

    private fun chunkNodeFiles(
        files: List<String>,
        fileTokens: Map<String, Int>,
        maxTokens: Int
    ): List<List<String>> {
        val chunks = mutableListOf<List<String>>()
        var currentChunk = mutableListOf<String>()
        var currentTokens = 0

        for (f in files) {
            val tokens = fileTokens[f] ?: 0
            if (tokens >= maxTokens) {
                if (currentChunk.isNotEmpty()) {
                    chunks.add(currentChunk)
                    currentChunk = mutableListOf()
                    currentTokens = 0
                }
                chunks.add(listOf(f))
                continue
            }

            if (currentChunk.isNotEmpty() && currentTokens + tokens > maxTokens) {
                chunks.add(currentChunk)
                currentChunk = mutableListOf(f)
                currentTokens = tokens
            } else {
                currentChunk.add(f)
                currentTokens += tokens
            }
        }

        if (currentChunk.isNotEmpty()) {
            chunks.add(currentChunk)
        }
        return chunks
    }

    private fun processNode(
        node: DirNode,
        fileTokens: Map<String, Int>,
        maxTokens: Int
    ): List<List<String>> {
        val allChunks = mutableListOf<List<String>>()

        // Bottom-up: process children first
        for (child in node.children.values) {
            allChunks.addAll(processNode(child, fileTokens, maxTokens))
        }

        if (node.files.isNotEmpty()) {
            val nodeChunks = chunkNodeFiles(node.files, fileTokens, maxTokens)
            allChunks.addAll(nodeChunks)
        }

        return allChunks
    }

    fun buildChunks(
        fileList: List<String>,
        fileTokens: Map<String, Int>,
        maxTokens: Int
    ): List<List<String>> {
        val root = DirNode("")
        for (f in fileList) {
            if (f.isBlank()) continue
            val parts = f.replace('\\', '/').split('/')
            var curr = root
            for (i in 0 until parts.size - 1) {
                val part = parts[i]
                curr = curr.children.computeIfAbsent(part) {
                    DirNode(if (curr.path.isEmpty()) part else "${curr.path}/$part")
                }
            }
            curr.files.add(f)
        }

        return processNode(root, fileTokens, maxTokens)
    }

    suspend fun generateCommit(
        baseUrl: String,
        modelId: String,
        files: List<String>,
        fileStatuses: Map<String, String>, // filepath -> 'added' | 'removed' | 'modified'
        fileDiffs: Map<String, String>,     // filepath -> diff content
        maxDiffTokens: Int,
        systemPrompt: String,
        chunkSystemPrompt: String,
        finalSystemPrompt: String,
        listener: GenerationListener
    ): GenerationResult {
        if (files.isEmpty()) return GenerationResult("", emptyList())

        val binaryFiles = files.filter { FileContentTypeHelper.isBinaryOrMediaFile(it) }
        val eligibleFiles = files.filterNot { FileContentTypeHelper.isBinaryOrMediaFile(it) }

        val svgFiles = eligibleFiles.filter { SvgAssetHandler.isSvgFile(it) }
        val normalFiles = eligibleFiles.filterNot { SvgAssetHandler.isSvgFile(it) }

        val binaryBullets = mutableListOf<String>()
        for (f in binaryFiles) {
            val baseName = f.substringAfterLast('/').substringAfterLast('\\')
            val status = fileStatuses[f] ?: "modified"
            val category = FileContentTypeHelper.getContentTypeDescription(f)
            binaryBullets.add("- $category $baseName ($status)")
        }

        val svgBullets = mutableListOf<String>()
        for (f in svgFiles) {
            val baseName = f.substringAfterLast('/').substringAfterLast('\\')
            val status = fileStatuses[f] ?: "modified"
            val action = if (status == "removed") "removed" else "added"
            svgBullets.add("- SVG $baseName $action")
        }

        // Case 1: ONLY binary/media files changed (images, videos, pdfs, etc.)
        if (normalFiles.isEmpty() && svgFiles.isEmpty() && binaryFiles.isNotEmpty()) {
            listener.onProgress("Only binary/media assets detected (${binaryFiles.size} file(s)). Generating message from asset metadata...")
            val assetPrompt =
                "The following binary/media assets (images, videos, documents) were changed. Their binary contents are omitted.\n" +
                        binaryBullets.joinToString("\n") +
                        "\n\nOutput a concise Conventional Commit message for these asset changes:"
            val (msg, _) = KoogLlmClient.generateStreaming(
                baseUrlInput = baseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                userPrompt = assetPrompt,
                onToken = { listener.onToken(it) }
            )
            val fallbackMsg =
                if (msg.isNotBlank()) msg else "chore(assets): update binary media assets\n\n" + binaryBullets.joinToString(
                    "\n"
                )
            return GenerationResult(fallbackMsg, emptyList())
        }

        // Case 2: Only SVG asset changes
        if (normalFiles.isEmpty() && binaryFiles.isEmpty() && svgFiles.isNotEmpty()) {
            val msg = SvgAssetHandler.appendSvgBullets("feat(svg): update SVG assets", svgBullets)
            listener.onProgress("Only SVG asset changes detected. Commit message generated directly.")
            return GenerationResult(msg, emptyList())
        }

        // Case 3: Only SVG and binary assets without code files
        if (normalFiles.isEmpty()) {
            val allAssetBullets = binaryBullets + svgBullets
            val msg = SvgAssetHandler.appendSvgBullets("chore(assets): update vector and media assets", allAssetBullets)
            listener.onProgress("Asset-only changes detected. Commit message generated.")
            return GenerationResult(msg, emptyList())
        }

        // 1. Calculate tokens per file (only for normal text/code files)
        listener.onProgress("Calculating token counts for ${normalFiles.size} text/code file(s)...")
        val fileTokens = mutableMapOf<String, Int>()
        for (f in normalFiles) {
            val diff = fileDiffs[f] ?: ""
            val tokens = KoogLlmClient.countTokens(baseUrl, diff)
            fileTokens[f] = tokens
        }

        // 2. Build dynamic directory chunks based on token budget
        val chunks = buildChunks(normalFiles, fileTokens, maxDiffTokens)
        listener.onProgress("Organized into ${chunks.size} processing chunk(s) based on context budget ($maxDiffTokens tokens).")

        val skippedFiles = mutableListOf<String>()
        var finalCommitMsg = ""

        val binaryNote = if (binaryBullets.isNotEmpty()) {
            "\n\nNote: The following binary/media assets are also included in this commit (binary contents omitted):\n" +
                    binaryBullets.joinToString("\n") + "\n"
        } else ""

        if (chunks.size <= 1) {
            // Single chunk directly uses SYSTEM_PROMPT
            val combinedDiff = StringBuilder()
            val chunkFiles = chunks.firstOrNull() ?: normalFiles
            for ((idx, f) in chunkFiles.withIndex()) {
                val d = fileDiffs[f] ?: ""
                if (d.isNotBlank()) {
                    combinedDiff.append("--- FILE ${idx + 1}: $f ---\n$d\n\n")
                }
            }

            val truncated = truncateDiff(combinedDiff.toString(), maxDiffTokens)
            listener.onProgress("Generating commit message...")
            val (rawMsg, loopDetected) = KoogLlmClient.generateStreaming(
                baseUrlInput = baseUrl,
                modelId = modelId,
                systemPrompt = systemPrompt,
                userPrompt = "Analyze the following git diff and output the requested commit message:$binaryNote\n\n$truncated",
                onToken = { listener.onToken(it) }
            )
            val msg = KoogLlmClient.sanitizeOutput(rawMsg)

            if (loopDetected) {
                skippedFiles.addAll(chunkFiles)
                finalCommitMsg = "feat: update files (looping detected)\n\n$msg"
            } else {
                finalCommitMsg = msg
            }
        } else {
            // Multi-chunk bottom-up summarization
            val summaries = mutableListOf<String>()
            for ((chunkIdx, chunk) in chunks.withIndex()) {
                listener.onProgress("Summarizing chunk ${chunkIdx + 1} of ${chunks.size} (${chunk.size} files)...")
                val chunkDiff = StringBuilder()
                for ((idx, f) in chunk.withIndex()) {
                    val d = fileDiffs[f] ?: ""
                    if (d.isNotBlank()) {
                        chunkDiff.append("--- FILE ${idx + 1}: $f ---\n$d\n\n")
                    }
                }

                val truncated = truncateDiff(chunkDiff.toString(), maxDiffTokens)
                val (rawSummary, loopDetected) = KoogLlmClient.generateStreaming(
                    baseUrlInput = baseUrl,
                    modelId = modelId,
                    systemPrompt = chunkSystemPrompt,
                    userPrompt = "Analyze the git diff for this subset of files and generate a detailed bulleted list of changes:\n\n$truncated",
                    onToken = { listener.onToken(it) }
                )
                val summary = KoogLlmClient.sanitizeOutput(rawSummary)

                if (loopDetected) {
                    skippedFiles.addAll(chunk)
                } else if (summary.isNotBlank()) {
                    summaries.add(summary)
                    listener.onChunkComplete("Chunk ${chunkIdx + 1}", summary)
                }
            }

            if (summaries.isEmpty()) {
                finalCommitMsg = "feat: update multiple files"
            } else if (summaries.size == 1) {
                finalCommitMsg = summaries.first()
            } else {
                listener.onProgress("Consolidating final commit message from ${summaries.size} summaries...")
                val combinedSummaries = summaries.joinToString("\n\n")
                val (rawMsg, loopDetected) = KoogLlmClient.generateStreaming(
                    baseUrlInput = baseUrl,
                    modelId = modelId,
                    systemPrompt = finalSystemPrompt,
                    userPrompt = "Consolidate these change summaries into a unified Conventional Commit message:$binaryNote\n\n$combinedSummaries",
                    onToken = { listener.onToken(it) }
                )
                val msg = KoogLlmClient.sanitizeOutput(rawMsg)

                if (loopDetected) {
                    finalCommitMsg =
                        "feat: update multiple files (summaries truncated due to looping)\n\n$combinedSummaries"
                } else {
                    finalCommitMsg = msg
                }
            }
        }

        if (svgBullets.isNotEmpty()) {
            finalCommitMsg = SvgAssetHandler.appendSvgBullets(finalCommitMsg, svgBullets)
        }

        return GenerationResult(finalCommitMsg, skippedFiles)
    }
}
