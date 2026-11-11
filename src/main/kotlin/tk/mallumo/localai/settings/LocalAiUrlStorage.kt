package tk.mallumo.localai.settings

import java.io.File

object LocalAiUrlStorage {
    private val configDir = File(System.getProperty("user.home"), ".config/local-ai-commit")
    private val configFile = File(configDir, "last_url.txt")
    private val sizeFile = File(configDir, "commit_size.txt")
    private val reformatXmlFile = File(configDir, "reformat_xml.txt")

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

    fun loadCommitSize(fallback: String = "Medium"): String {
        return try {
            if (sizeFile.exists() && sizeFile.isFile) {
                val text = sizeFile.readText().trim()
                if (text.isNotBlank()) text else fallback
            } else {
                fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    fun saveCommitSize(size: String) {
        try {
            val clean = size.trim()
            if (clean.isNotBlank()) {
                if (!configDir.exists()) {
                    configDir.mkdirs()
                }
                sizeFile.writeText(clean)
            }
        } catch (_: Exception) {
        }
    }

    fun loadReformatXml(fallback: Boolean = false): Boolean {
        return try {
            if (reformatXmlFile.exists() && reformatXmlFile.isFile) {
                reformatXmlFile.readText().trim().toBooleanStrictOrNull() ?: fallback
            } else {
                fallback
            }
        } catch (_: Exception) {
            fallback
        }
    }

    fun saveReformatXml(enabled: Boolean) {
        try {
            if (!configDir.exists()) {
                configDir.mkdirs()
            }
            reformatXmlFile.writeText(enabled.toString())
        } catch (_: Exception) {
        }
    }
}
