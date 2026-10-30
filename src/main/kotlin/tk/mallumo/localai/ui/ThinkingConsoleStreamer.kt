package tk.mallumo.localai.ui

import javax.swing.JTextArea

/**
 * Handles live token streaming into a JTextArea console, providing dynamic 4-line rolling window
 * display for <think>...</think> blocks that automatically collapses into a compact status line
 * upon completion of the thinking phase.
 */
class ThinkingConsoleStreamer(private val textArea: JTextArea) {

    private val baseText = StringBuilder()
    private var inThinking = false
    private val thinkingBuffer = StringBuilder()

    @Synchronized
    fun reset() {
        baseText.setLength(0)
        inThinking = false
        thinkingBuffer.setLength(0)
        textArea.text = ""
    }

    @Synchronized
    fun appendProgress(message: String) {
        if (inThinking) {
            endThinkingBlock()
        }
        baseText.append("▶ ").append(message).append("\n")
        render()
    }

    @Synchronized
    fun appendChunkComplete(title: String) {
        if (inThinking) {
            endThinkingBlock()
        }
        baseText.append("\n\n[").append(title).append(" Ready]\n")
            .append("-".repeat(40)).append("\n\n")
        render()
    }

    @Synchronized
    fun appendToken(token: String) {
        var remaining = token

        while (remaining.isNotEmpty()) {
            if (!inThinking) {
                val thinkStartIdx = remaining.indexOf("<think>")
                if (thinkStartIdx != -1) {
                    val before = remaining.substring(0, thinkStartIdx)
                    baseText.append(before)
                    inThinking = true
                    thinkingBuffer.setLength(0)
                    remaining = remaining.substring(thinkStartIdx + 7)
                    render()
                } else {
                    baseText.append(remaining)
                    remaining = ""
                    render()
                }
            } else {
                val thinkEndIdx = remaining.indexOf("</think>")
                if (thinkEndIdx != -1) {
                    val thinkPart = remaining.substring(0, thinkEndIdx)
                    thinkingBuffer.append(thinkPart)
                    endThinkingBlock()
                    remaining = remaining.substring(thinkEndIdx + 8).trimStart('\r', '\n')
                    render()
                } else {
                    thinkingBuffer.append(remaining)
                    remaining = ""
                    render()
                }
            }
        }
    }

    private fun endThinkingBlock() {
        val lines = thinkingBuffer.toString().split(Regex("""\r?\n""")).filter { it.isNotBlank() }
        val lineCount = lines.size
        baseText.append("▶ [Thinking completed (")
            .append(lineCount)
            .append(" lines) - collapsed]\n\n")
        inThinking = false
        thinkingBuffer.setLength(0)
    }

    private fun render() {
        if (!inThinking) {
            textArea.text = baseText.toString()
        } else {
            val lines = thinkingBuffer.toString().split(Regex("""\r?\n""")).filter { it.isNotBlank() }
            val last4 = if (lines.size > 4) lines.takeLast(4) else lines
            val rollingBox = buildString {
                append("💭 Thinking...\n")
                for (line in last4) {
                    append("  │ ").append(line).append("\n")
                }
            }
            textArea.text = baseText.toString() + rollingBox
        }
        textArea.caretPosition = textArea.document.length
    }
}
