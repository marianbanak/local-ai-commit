package tk.mallumo.localai.git

import com.intellij.openapi.fileTypes.FileTypeManager
import com.intellij.openapi.vfs.VirtualFile
import java.util.*

object FileContentTypeHelper {

    // Binary and media file extensions that must NEVER be pushed into AI
    private val BINARY_EXTENSIONS = setOf(
        // Images (raster, design, camera raw)
        "png",
        "jpg",
        "jpeg",
        "gif",
        "bmp",
        "webp",
        "ico",
        "tiff",
        "tif",
        "psd",
        "ai",
        "eps",
        "raw",
        "cr2",
        "nef",
        "heic",
        "heif",
        // Videos
        "mp4",
        "mkv",
        "avi",
        "mov",
        "wmv",
        "flv",
        "webm",
        "m4v",
        "3gp",
        "mpg",
        "mpeg",
        "m2ts",
        "mts",
        "vob",
        // Audio
        "mp3",
        "wav",
        "ogg",
        "flac",
        "aac",
        "m4a",
        "wma",
        "mid",
        "midi",
        "opus",
        // PDF & Binary Documents
        "pdf",
        "doc",
        "docx",
        "xls",
        "xlsx",
        "ppt",
        "pptx",
        "odt",
        "ods",
        "odp",
        "pages",
        "numbers",
        "key",
        // Archives, Installers & Packages
        "zip",
        "tar",
        "gz",
        "tgz",
        "7z",
        "rar",
        "bz2",
        "xz",
        "jar",
        "war",
        "ear",
        "aar",
        "apk",
        "dmg",
        "iso",
        "deb",
        "rpm",
        // Binaries, Executables, Libraries & Bytecode
        "exe",
        "dll",
        "so",
        "dylib",
        "bin",
        "class",
        "pyc",
        "pyo",
        "wasm",
        "o",
        "obj",
        "lib",
        "a",
        "pdb",
        // Fonts
        "ttf",
        "otf",
        "woff",
        "woff2",
        "eot",
        // Databases, Keystores & Blobs
        "db",
        "sqlite",
        "sqlite3",
        "jks",
        "keystore",
        "p12",
        "realm"
    )

    // Allowed source code, text, markup, and configuration extensions that can go into AI
    private val ALLOWED_TEXT_EXTENSIONS = setOf(
        // JVM & Modern languages
        "kt", "kts", "java", "scala", "groovy", "clj", "cljs",
        // Web & Scripting
        "js", "jsx", "mjs", "cjs", "ts", "tsx", "vue", "svelte", "html", "htm", "xhtml",
        // Styles
        "css", "scss", "sass", "less", "styl",
        // Native & Systems
        "c", "cpp", "cc", "cxx", "h", "hpp", "hxx", "rs", "go", "swift", "cs", "m", "mm",
        // Dynamic / Scripting
        "py", "pyw", "rb", "php", "pl", "pm", "lua", "r", "dart", "sh", "bash", "zsh", "fish", "bat", "cmd", "ps1",
        // Documentation & Markup
        "md", "markdown", "mdown", "svg", "txt", "text", "rst", "adoc", "asciidoc", "tex",
        // Data & Config
        "json", "json5", "jsonc", "yaml", "yml", "toml", "xml", "properties", "ini", "conf",
        "config", "env", "gradle", "pro", "proto", "graphql", "gql", "sql", "csv", "tsv"
    )

    // Allowed text files without extensions or standard dotfiles
    private val ALLOWED_TEXT_FILENAMES = setOf(
        "dockerfile", "makefile", "license", "licence", "notice", "readme", "changelog",
        "gemfile", "rakefile", "procfile", "vagrantfile", "cmakelists.txt",
        ".gitignore", ".gitattributes", ".editorconfig", ".dockerignore", ".npmignore",
        ".prettierrc", ".eslintrc", ".babelrc", ".env", ".env.example", ".env.local"
    )

    /**
     * Determines whether a file is a source code, markup, or text file safe to push into AI.
     * Returns false for images, videos, PDFs, archives, and other binary files.
     */
    fun isAiSupportedTextFile(filePath: String, virtualFile: VirtualFile? = null): Boolean {
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
        val ext = fileName.substringAfterLast('.', "")

        // 1. Check known binary extensions first (immediate rejection)
        if (ext.isNotEmpty() && BINARY_EXTENSIONS.contains(ext)) {
            return false
        }

        // 2. Check exact text filenames (e.g. Dockerfile, Makefile, .gitignore)
        if (ALLOWED_TEXT_FILENAMES.contains(fileName) || ALLOWED_TEXT_FILENAMES.contains(fileName.removePrefix("."))) {
            return true
        }

        // 3. Check known allowed text/code extensions (e.g. kt, java, js, md, svg, txt)
        if (ext.isNotEmpty() && ALLOWED_TEXT_EXTENSIONS.contains(ext)) {
            return true
        }

        // 4. Check VirtualFile fileType if available
        if (virtualFile != null && virtualFile.isValid) {
            if (virtualFile.fileType.isBinary) {
                return false
            }
        }

        // 5. Check IntelliJ FileTypeManager by file name
        try {
            val fileType = FileTypeManager.getInstance().getFileTypeByFileName(fileName)
            if (fileType.isBinary) {
                return false
            }
        } catch (_: Throwable) {
            // Platform not initialized (e.g. in unit tests)
        }

        // 6. Inspect file bytes for null bytes if virtualFile is accessible
        if (virtualFile != null && virtualFile.isValid && !virtualFile.isDirectory) {
            try {
                val bytes = virtualFile.contentsToByteArray()
                val sampleSize = minOf(bytes.size, 1024)
                for (i in 0 until sampleSize) {
                    if (bytes[i] == 0.toByte()) {
                        return false // Null byte indicates binary content
                    }
                }
            } catch (_: Throwable) {
            }
        }

        return true
    }

    fun isAiSupportedTextFile(item: GitFileItem): Boolean {
        return isAiSupportedTextFile(item.relativePath, item.virtualFile)
    }

    fun isBinaryOrMediaFile(filePath: String, virtualFile: VirtualFile? = null): Boolean {
        return !isAiSupportedTextFile(filePath, virtualFile)
    }

    fun isBinaryOrMediaFile(item: GitFileItem): Boolean {
        return !isAiSupportedTextFile(item)
    }

    /**
     * Human-readable classification for UI labels, logs, and prompt summaries.
     */
    fun getContentTypeDescription(filePath: String): String {
        val fileName = filePath.substringAfterLast('/').substringAfterLast('\\').lowercase(Locale.ROOT)
        val ext = fileName.substringAfterLast('.', "")
        return when {
            setOf(
                "png",
                "jpg",
                "jpeg",
                "gif",
                "bmp",
                "webp",
                "ico",
                "tiff",
                "tif",
                "psd",
                "heic"
            ).contains(ext) -> "image"

            setOf("mp4", "mkv", "avi", "mov", "wmv", "flv", "webm", "m4v").contains(ext) -> "video"
            setOf("mp3", "wav", "ogg", "flac", "aac", "m4a", "opus").contains(ext) -> "audio"
            ext == "pdf" -> "PDF document"
            setOf("doc", "docx", "xls", "xlsx", "ppt", "pptx", "odt").contains(ext) -> "office document"
            setOf("zip", "tar", "gz", "tgz", "7z", "rar", "jar", "aar").contains(ext) -> "archive"
            setOf("class", "exe", "dll", "so", "dylib", "bin", "wasm").contains(ext) -> "binary"
            ext == "svg" -> "SVG vector"
            ext in setOf("kt", "kts", "java", "js", "ts", "py", "rs", "go", "cpp", "c") -> "source code"
            ext in setOf("md", "markdown", "txt") -> "text document"
            else -> "file"
        }
    }
}
