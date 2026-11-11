package tk.mallumo.localai.buildsystem

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.vfs.VirtualFile
import git4idea.repo.GitRepository
import tk.mallumo.localai.git.GitChangesManager
import java.io.File
import java.util.regex.Pattern

enum class BuildSystemType(val displayName: String) {
    AMPER("Amper"),
    GRADLE("Gradle"),
    MAVEN("Maven"),
    NPM("NPM/Node"),
    UNKNOWN("Unknown")
}

data class DetectedModule(
    val name: String,
    val relativePath: String,
    val buildSystem: BuildSystemType,
    val descriptorFile: String,
    val version: String? = null,
    val versionReference: String? = null,
    val isChangedInReleaseRange: Boolean = false
)

data class ProjectBuildStructure(
    val primaryBuildSystem: BuildSystemType,
    val modules: List<DetectedModule>,
    val candidateConfigFiles: Map<String, String>, // relativePath -> fileContent snippet
    val fileTreePaths: List<String>
)

object BuildSystemDetector {
    private val LOG = Logger.getInstance(BuildSystemDetector::class.java)

    fun detect(
        repository: GitRepository,
        gitChangesManager: GitChangesManager,
        changedFilesInRange: Set<String> = emptySet()
    ): ProjectBuildStructure {
        val root = repository.root
        val trackedFiles = gitChangesManager.getTrackedFiles(repository)

        val amperModules = mutableListOf<DetectedModule>()
        val gradleModules = mutableListOf<DetectedModule>()
        val mavenModules = mutableListOf<DetectedModule>()
        val npmModules = mutableListOf<DetectedModule>()
        val candidateConfigFiles = mutableMapOf<String, String>()

        // 1. Scan for Amper (module.yaml, template.yaml)
        for (relPath in trackedFiles) {
            val fileName = relPath.substringAfterLast('/')
            val dirPath = if (relPath.contains('/')) relPath.substringBeforeLast('/') else ""

            if (fileName.equals("module.yaml", ignoreCase = true)) {
                val content = readFileSnippet(root, relPath)
                if (content != null) candidateConfigFiles[relPath] = content
                val (ver, ref) = parseAmperVersion(root, dirPath, content ?: "")
                val moduleName = if (dirPath.isBlank()) root.name else dirPath.substringAfterLast('/')
                val isChanged = isModuleChanged(dirPath, changedFilesInRange)
                amperModules.add(
                    DetectedModule(
                        name = moduleName,
                        relativePath = dirPath,
                        buildSystem = BuildSystemType.AMPER,
                        descriptorFile = relPath,
                        version = ver,
                        versionReference = ref,
                        isChangedInReleaseRange = isChanged
                    )
                )
            } else if (fileName.endsWith(
                    ".yaml",
                    ignoreCase = true
                ) && (fileName.contains("template") || fileName.contains("common"))
            ) {
                val content = readFileSnippet(root, relPath)
                if (content != null) candidateConfigFiles[relPath] = content
            }
        }

        // 2. Scan for Gradle (settings.gradle*, build.gradle*, libs.versions.toml, gradle.properties)
        for (relPath in trackedFiles) {
            val fileName = relPath.substringAfterLast('/')
            val dirPath = if (relPath.contains('/')) relPath.substringBeforeLast('/') else ""

            if (fileName.startsWith("build.gradle", ignoreCase = true)) {
                val content = readFileSnippet(root, relPath)
                if (content != null) candidateConfigFiles[relPath] = content
                val ver = parseGradleVersion(content ?: "")
                val moduleName = if (dirPath.isBlank()) ":root" else ":${dirPath.replace('/', ':')}"
                val isChanged = isModuleChanged(dirPath, changedFilesInRange)
                gradleModules.add(
                    DetectedModule(
                        name = moduleName,
                        relativePath = dirPath,
                        buildSystem = BuildSystemType.GRADLE,
                        descriptorFile = relPath,
                        version = ver,
                        isChangedInReleaseRange = isChanged
                    )
                )
            } else if (fileName.equals("gradle.properties", ignoreCase = true) ||
                fileName.endsWith(".versions.toml", ignoreCase = true) ||
                fileName.startsWith("settings.gradle", ignoreCase = true)
            ) {
                val content = readFileSnippet(root, relPath)
                if (content != null) candidateConfigFiles[relPath] = content
            }
        }

        // 3. Scan for Maven (pom.xml)
        for (relPath in trackedFiles) {
            val fileName = relPath.substringAfterLast('/')
            val dirPath = if (relPath.contains('/')) relPath.substringBeforeLast('/') else ""

            if (fileName.equals("pom.xml", ignoreCase = true)) {
                val content = readFileSnippet(root, relPath)
                if (content != null) candidateConfigFiles[relPath] = content
                val ver = parseMavenVersion(content ?: "")
                val moduleName = if (dirPath.isBlank()) root.name else dirPath.substringAfterLast('/')
                val isChanged = isModuleChanged(dirPath, changedFilesInRange)
                mavenModules.add(
                    DetectedModule(
                        name = moduleName,
                        relativePath = dirPath,
                        buildSystem = BuildSystemType.MAVEN,
                        descriptorFile = relPath,
                        version = ver,
                        isChangedInReleaseRange = isChanged
                    )
                )
            }
        }

        // 4. Scan for NPM (package.json)
        for (relPath in trackedFiles) {
            val fileName = relPath.substringAfterLast('/')
            val dirPath = if (relPath.contains('/')) relPath.substringBeforeLast('/') else ""

            if (fileName.equals("package.json", ignoreCase = true) && !relPath.contains("node_modules/")) {
                val content = readFileSnippet(root, relPath)
                if (content != null) candidateConfigFiles[relPath] = content
                val ver = parseNpmVersion(content ?: "")
                val moduleName = if (dirPath.isBlank()) root.name else dirPath.substringAfterLast('/')
                val isChanged = isModuleChanged(dirPath, changedFilesInRange)
                npmModules.add(
                    DetectedModule(
                        name = moduleName,
                        relativePath = dirPath,
                        buildSystem = BuildSystemType.NPM,
                        descriptorFile = relPath,
                        version = ver,
                        isChangedInReleaseRange = isChanged
                    )
                )
            }
        }

        // Determine primary build system and consolidated module list
        val primarySystem = when {
            amperModules.isNotEmpty() -> BuildSystemType.AMPER
            gradleModules.isNotEmpty() -> BuildSystemType.GRADLE
            mavenModules.isNotEmpty() -> BuildSystemType.MAVEN
            npmModules.isNotEmpty() -> BuildSystemType.NPM
            else -> BuildSystemType.UNKNOWN
        }

        val allModules = when (primarySystem) {
            BuildSystemType.AMPER -> amperModules + gradleModules
            BuildSystemType.GRADLE -> gradleModules
            BuildSystemType.MAVEN -> mavenModules
            BuildSystemType.NPM -> npmModules
            BuildSystemType.UNKNOWN -> emptyList()
        }

        // Post-process modules with catalog / properties fallbacks if direct module version was absent
        val enrichedModules = enrichModulesWithCatalogVersions(allModules, candidateConfigFiles)

        return ProjectBuildStructure(
            primaryBuildSystem = primarySystem,
            modules = enrichedModules,
            candidateConfigFiles = candidateConfigFiles,
            fileTreePaths = trackedFiles
        )
    }

    internal fun isModuleChanged(moduleDirPath: String, changedFiles: Set<String>): Boolean {
        if (changedFiles.isEmpty()) return false
        val cleanDir = moduleDirPath.trim('/', '\\')
        if (cleanDir.isEmpty()) {
            // Root module: changed if root-level files changed
            return changedFiles.any { !it.contains('/') }
        }
        return changedFiles.any { it.startsWith("$cleanDir/") || it == cleanDir }
    }

    private fun readFileSnippet(root: VirtualFile, relPath: String, maxChars: Int = 8000): String? {
        return try {
            val file = root.findFileByRelativePath(relPath) ?: return null
            val text = String(file.contentsToByteArray(), Charsets.UTF_8)
            if (text.length > maxChars) text.take(maxChars) + "\n... [truncated]" else text
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Parses Amper version directly from module.yaml or from referenced templates (via apply:).
     */
    fun parseAmperVersion(root: VirtualFile, moduleDir: String, content: String): Pair<String?, String?> {
        // Direct product version:
        // version: 1.0.0
        val directMatcher = Pattern.compile("""(?m)^\s*version\s*:\s*['"]?([a-zA-Z0-9.\-_+]+)['"]?""").matcher(content)
        if (directMatcher.find()) {
            return Pair(directMatcher.group(1).trim(), null)
        }

        // Check apply template:
        // apply:
        //   - ../template.yaml
        val applyMatcher = Pattern.compile("""(?m)^\s*-\s*([^\s\r\n#]+\.ya?ml)""").matcher(content)
        while (applyMatcher.find()) {
            val templateRef = applyMatcher.group(1).trim().trim('"', '\'')
            val templateFile = resolveRelativeFile(root, moduleDir, templateRef)
            if (templateFile != null && templateFile.exists()) {
                val tContent = templateFile.readText()
                val tMatcher =
                    Pattern.compile("""(?m)^\s*version\s*:\s*['"]?([a-zA-Z0-9.\-_+]+)['"]?""").matcher(tContent)
                if (tMatcher.find()) {
                    return Pair(tMatcher.group(1).trim(), templateRef)
                }
            }
        }

        return Pair(null, null)
    }

    private fun resolveRelativeFile(root: VirtualFile, baseDir: String, relativePath: String): File? {
        val base = if (baseDir.isBlank()) File(root.path) else File(root.path, baseDir)
        val target = File(base, relativePath).canonicalFile
        return if (target.exists()) target else null
    }

    internal fun parseGradleVersion(content: String): String? {
        val p1 = Pattern.compile("""(?m)^\s*(?:val\s+|var\s+)?version\s*=\s*["']([^"'\r\n]+)["']""").matcher(content)
        if (p1.find()) return p1.group(1).trim()

        val p2 = Pattern.compile("""(?m)^\s*version\s+["']([^"'\r\n]+)["']""").matcher(content)
        if (p2.find()) return p2.group(1).trim()

        return null
    }

    internal fun parseMavenVersion(content: String): String? {
        val matcher = Pattern.compile("""<version>([^<]+)</version>""").matcher(content)
        if (matcher.find()) return matcher.group(1).trim()
        return null
    }

    internal fun parseNpmVersion(content: String): String? {
        val matcher = Pattern.compile(""""version"\s*:\s*"([^"]+)"""").matcher(content)
        if (matcher.find()) return matcher.group(1).trim()
        return null
    }

    /**
     * If modules have no individual version, resolve from gradle.properties or version catalogs.
     */
    internal fun enrichModulesWithCatalogVersions(
        modules: List<DetectedModule>,
        candidateConfigFiles: Map<String, String>
    ): List<DetectedModule> {
        var catalogVersion: String? = null
        var catalogFile: String? = null

        // 1. Check gradle.properties
        for ((path, content) in candidateConfigFiles) {
            if (path.endsWith("gradle.properties", ignoreCase = true)) {
                val m = Pattern.compile("""(?m)^\s*(?:version|appVersion)\s*=\s*['"]?([a-zA-Z0-9.\-_+]+)['"]?""")
                    .matcher(content)
                if (m.find()) {
                    catalogVersion = m.group(1).trim()
                    catalogFile = path
                    break
                }
            }
        }

        // 2. Check libs.versions.toml
        if (catalogVersion == null) {
            for ((path, content) in candidateConfigFiles) {
                if (path.endsWith(".versions.toml", ignoreCase = true)) {
                    val m =
                        Pattern.compile("""(?m)^\s*(?:app|project|appVersion|version|release)\s*=\s*["']([^"'\r\n]+)["']""")
                            .matcher(content)
                    if (m.find()) {
                        catalogVersion = m.group(1).trim()
                        catalogFile = path
                        break
                    }
                }
            }
        }

        if (catalogVersion == null) return modules

        return modules.map { mod ->
            if (mod.version.isNullOrBlank()) {
                mod.copy(version = catalogVersion, versionReference = catalogFile)
            } else {
                mod
            }
        }
    }
}
