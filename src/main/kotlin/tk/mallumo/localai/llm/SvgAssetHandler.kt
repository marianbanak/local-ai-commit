package tk.mallumo.localai.llm

object SvgAssetHandler {
    fun isSvgFile(filepath: String): Boolean {
        val normalized = filepath.replace('\\', '/')
        val parts = normalized.split('/')
        if (parts.size >= 2 && parts[parts.size - 2].equals("svg", ignoreCase = true)) {
            return normalized.endsWith(".kt", ignoreCase = true) || normalized.endsWith(".svg", ignoreCase = true)
        }
        return normalized.endsWith(".svg", ignoreCase = true)
    }

    fun appendSvgBullets(commitMsg: String, svgBullets: List<String>): String {
        if (svgBullets.isEmpty()) return commitMsg
        var msg = commitMsg.trim()
        if (msg.isEmpty()) {
            return "feat(svg): update SVG assets\n\n" + svgBullets.joinToString("\n")
        }

        val lines = msg.lines().toMutableList()
        if (lines.size == 1) {
            return msg + "\n\n" + svgBullets.joinToString("\n")
        }

        if (lines.size > 1 && lines[1].isNotBlank()) {
            lines.add(1, "")
        }

        var footerIdx = lines.size
        for (i in 1 until lines.size) {
            val line = lines[i]
            if (line.startsWith("BREAKING CHANGE") || line.startsWith("BREAKING-CHANGE")) {
                footerIdx = i
                break
            }
        }

        val insertedLines = lines.subList(0, footerIdx).toMutableList()
        if (insertedLines.size == 2 && insertedLines[1].isBlank()) {
            insertedLines.addAll(svgBullets)
        } else {
            insertedLines.addAll(svgBullets)
        }
        insertedLines.addAll(lines.subList(footerIdx, lines.size))
        return insertedLines.joinToString("\n")
    }
}
