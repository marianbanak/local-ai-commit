package tk.mallumo.localai.llm

object LoopDetector {
    fun detectRepetitionLoop(content: String, minLen: Int = 8, maxRepeats: Int = 3): Boolean {
        if (content.length < minLen * maxRepeats) return false

        // 1. Substring window check (up to 1000 characters)
        val maxCheckLen = minOf(1000, content.length / maxRepeats + 1)
        for (length in minLen until maxCheckLen) {
            val pattern = content.substring(content.length - length)
            var isRepeat = true
            for (r in 1 until maxRepeats) {
                val start = content.length - length * (r + 1)
                val end = content.length - length * r
                if (start < 0 || content.substring(start, end) != pattern) {
                    isRepeat = false
                    break
                }
            }
            if (isRepeat) return true
        }

        // 2. Line-level repetition check
        val lines = content.lines().map { it.trim() }.filter { it.isNotEmpty() }
        val numLines = lines.size
        if (numLines >= maxRepeats) {
            val maxBlockSize = minOf(20, numLines / maxRepeats)
            for (blockSize in 1..maxBlockSize) {
                val patternLines = lines.subList(numLines - blockSize, numLines)
                var isRepeat = true
                for (r in 1 until maxRepeats) {
                    val start = numLines - blockSize * (r + 1)
                    val end = numLines - blockSize * r
                    if (start < 0 || lines.subList(start, end) != patternLines) {
                        isRepeat = false
                        break
                    }
                }
                if (isRepeat) return true
            }
        }

        return false
    }

    fun deduplicateConsecutiveLines(content: String): String {
        val lines = content.lines()
        val deduped = mutableListOf<String>()
        for (line in lines) {
            if (deduped.isEmpty() || deduped.last() != line) {
                deduped.add(line)
            }
        }
        return deduped.joinToString("\n")
    }
}
