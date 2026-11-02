package tk.mallumo.localai.settings

import java.io.File

object LocalAiUrlStorage {
    private val configDir = File(System.getProperty("user.home"), ".config/local-ai-commit")
    private val configFile = File(configDir, "last_url.txt")

    fun loadLastUsedUrl(fallback: String = "http://localhost:11434"): String {
        return try {
            if (configFile.exists() && configFile.isFile) {
                val text = configFile.readText().trim()
                if (text.isNotBlank()) text else fallback
            } else {
                fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    fun saveLastUsedUrl(url: String) {
        try {
            val clean = url.trim()
            if (clean.isNotBlank()) {
                if (!configDir.exists()) {
                    configDir.mkdirs()
                }
                configFile.writeText(clean)
            }
        } catch (_: Exception) {
        }
    }
}
