package tk.mallumo.localai.llm

object UserMessageFormatter {

    fun toHtml(markdown: String): String {
        if (markdown.isBlank()) return ""
        val lines = markdown.lines()
        val sb = StringBuilder()
        var inList = false

        for (rawLine in lines) {
            val line = rawLine.trim()
            if (line.startsWith("- ") || line.startsWith("* ")) {
                if (!inList) {
                    sb.append("<ul>\n")
                    inList = true
                }
                val itemContent = line.substring(2).trim()
                sb.append("  <li>").append(formatInlineHtml(itemContent)).append("</li>\n")
            } else {
                if (inList) {
                    sb.append("</ul>\n")
                    inList = false
                }
                when {
                    line.startsWith("### ") -> {
                        sb.append("<h3>").append(escapeHtml(line.substring(4).trim())).append("</h3>\n")
                    }

                    line.startsWith("## ") -> {
                        sb.append("<h2>").append(escapeHtml(line.substring(3).trim())).append("</h2>\n")
                    }

                    line.startsWith("# ") -> {
                        sb.append("<h1>").append(escapeHtml(line.substring(2).trim())).append("</h1>\n")
                    }

                    line.isBlank() -> {
                        sb.append("\n")
                    }

                    else -> {
                        sb.append("<p>").append(formatInlineHtml(line)).append("</p>\n")
                    }
                }
            }
        }
        if (inList) {
            sb.append("</ul>\n")
        }
        return sb.toString().trim()
    }

    private fun escapeHtml(text: String): String {
        return text.replace("&", "&amp;")
            .replace("<", "&lt;")
            .replace(">", "&gt;")
            .replace("\"", "&quot;")
    }

    private fun formatInlineHtml(text: String): String {
        var res = escapeHtml(text)
        // [text](url) -> <a href="url">text</a>
        res = res.replace(Regex("""\[(.*?)\]\((.*?)\)"""), """<a href="$2">$1</a>""")
        // **bold** -> <strong>bold</strong>
        res = res.replace(Regex("""\*\*(.*?)\*\*"""), "<strong>$1</strong>")
        // *italic* -> <em>italic</em>
        res = res.replace(Regex("""\*(.*?)\*"""), "<em>$1</em>")
        // `code` -> <code>code</code>
        res = res.replace(Regex("""`(.*?)`"""), "<code>$1</code>")
        return res
    }

    fun toPlainText(markdown: String): String {
        if (markdown.isBlank()) return ""
        val lines = markdown.lines()
        val sb = StringBuilder()

        for (rawLine in lines) {
            val line = rawLine.trim()
            when {
                line.startsWith("# ") -> {
                    val title = line.substring(2).trim().uppercase()
                    sb.append(title).append("\n").append("=".repeat(title.length.coerceAtLeast(10))).append("\n\n")
                }

                line.startsWith("## ") -> {
                    val title = line.substring(3).trim().uppercase()
                    sb.append(title).append("\n").append("-".repeat(title.length.coerceAtLeast(10))).append("\n\n")
                }

                line.startsWith("### ") -> {
                    val title = line.substring(4).trim()
                    sb.append(title).append(":\n")
                }

                line.startsWith("- ") || line.startsWith("* ") -> {
                    val item = line.substring(2).trim()
                    sb.append("  • ").append(cleanInlineMarkdown(item)).append("\n")
                }

                line.isBlank() -> {
                    sb.append("\n")
                }

                else -> {
                    sb.append(cleanInlineMarkdown(line)).append("\n\n")
                }
            }
        }
        return sb.toString().replace(Regex("""\n{3,}"""), "\n\n").trim()
    }

    private fun cleanInlineMarkdown(text: String): String {
        var res = text
        res = res.replace(Regex("""\*\*(.*?)\*\*"""), "$1")
        res = res.replace(Regex("""\*(.*?)\*"""), "$1")
        res = res.replace(Regex("""`(.*?)`"""), "$1")
        res = res.replace(Regex("""\[(.*?)\]\((.*?)\)"""), "$1 ($2)")
        return res
    }
}
