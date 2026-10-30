package tk.mallumo.localai.git

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.vcs.FilePath
import com.intellij.openapi.vcs.changes.Change
import com.intellij.openapi.vcs.changes.ChangeListListener
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import git4idea.commands.Git
import git4idea.commands.GitCommand
import git4idea.commands.GitLineHandler
import git4idea.repo.GitRepository
import git4idea.repo.GitRepositoryChangeListener
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

enum class FileItemStatus {
    MODIFIED, ADDED, DELETED, UNVERSIONED
}

data class GitFileItem(
    val virtualFile: VirtualFile?,
    val filePath: FilePath,
    val repository: GitRepository,
    val relativePath: String,
    val status: FileItemStatus,
    val change: Change? = null
)

data class GitRepoGroup(
    val repository: GitRepository,
    val root: VirtualFile,
    val name: String,
    val trackedFiles: List<GitFileItem>,
    val unversionedFiles: List<GitFileItem>
) {
    val files: List<GitFileItem> get() = trackedFiles + unversionedFiles
}

class GitChangesManager(private val project: Project) {
    private val LOG = Logger.getInstance(GitChangesManager::class.java)

    fun subscribeChanges(listener: () -> Unit) {
        val busConnection = project.messageBus.connect(project)
        busConnection.subscribe(ChangeListListener.TOPIC, object : ChangeListListener {
            override fun changeListChanged(list: com.intellij.openapi.vcs.changes.ChangeList) {
                listener()
            }

            override fun changeListUpdateDone() {
                listener()
            }

            override fun changedFileStatusChanged() {
                listener()
            }

            override fun unchangedFileStatusChanged() {
                listener()
            }

            override fun changesAdded(
                changes: MutableCollection<out Change>,
                toList: com.intellij.openapi.vcs.changes.ChangeList
            ) {
                listener()
            }

            override fun changesRemoved(
                changes: MutableCollection<out Change>,
                fromList: com.intellij.openapi.vcs.changes.ChangeList
            ) {
                listener()
            }

            override fun allChangeListsMappingsChanged() {
                listener()
            }
        })

        busConnection.subscribe(GitRepository.GIT_REPO_CHANGE, GitRepositoryChangeListener {
            listener()
        })
    }

    fun isSubmodule(filePath: FilePath): Boolean {
        val repoManager = GitRepositoryManager.getInstance(project)
        return repoManager.repositories.any { it.root.path == filePath.path }
    }

    fun getRepoGroups(): List<GitRepoGroup> {
        val repoManager = GitRepositoryManager.getInstance(project)
        val repos = repoManager.repositories
        val changeListManager = ChangeListManager.getInstance(project)

        val allChanges = changeListManager.allChanges
        val unversionedFiles = changeListManager.unversionedFilesPaths

        val repoTrackedMap = mutableMapOf<GitRepository, MutableList<GitFileItem>>()
        val repoUnversionedMap = mutableMapOf<GitRepository, MutableList<GitFileItem>>()
        for (repo in repos) {
            repoTrackedMap[repo] = mutableListOf()
            repoUnversionedMap[repo] = mutableListOf()
        }

        // Helper to find the correct repository for a FilePath
        fun findRepoForPath(filePath: FilePath): GitRepository? {
            // If filePath matches a submodule root, it is a submodule entry inside the PARENT repo,
            // not a change inside the submodule itself!
            val exactSubmoduleRepo = repos.find { it.root.path == filePath.path }
            if (exactSubmoduleRepo != null) {
                return repos.filter { it != exactSubmoduleRepo && filePath.path.startsWith("${it.root.path}/") }
                    .maxByOrNull { it.root.path.length }
            }

            // Normal file: check GitRepositoryManager
            val fromManager = repoManager.getRepositoryForFileQuick(filePath)
                ?: repoManager.getRepositoryForFile(filePath)
            if (fromManager != null && fromManager.root.path != filePath.path) {
                return fromManager
            }

            // Fallback: find innermost repo that contains this file path
            return repos.filter { repo ->
                filePath.path != repo.root.path && filePath.path.startsWith("${repo.root.path}/")
            }.maxByOrNull { it.root.path.length }
        }

        // 1. Process tracked changes (Modified, Deleted, Added)
        for (change in allChanges) {
            val filePath = change.afterRevision?.file ?: change.beforeRevision?.file ?: continue
            val repo = findRepoForPath(filePath) ?: continue
            val repoRoot = repo.root

            val relativePath = (filePath.virtualFile?.let { VfsUtilCore.getRelativePath(it, repoRoot) })
                ?: filePath.path.removePrefix(repoRoot.path).removePrefix("/")

            if (relativePath.isBlank()) continue

            val status = when {
                change.type == Change.Type.DELETED || change.afterRevision == null -> FileItemStatus.DELETED
                change.type == Change.Type.NEW || change.beforeRevision == null -> FileItemStatus.ADDED
                else -> FileItemStatus.MODIFIED
            }

            repoTrackedMap[repo]?.add(
                GitFileItem(
                    virtualFile = filePath.virtualFile,
                    filePath = filePath,
                    repository = repo,
                    relativePath = relativePath,
                    status = status,
                    change = change
                )
            )
        }

        // 2. Process unversioned (untracked) files
        for (unversioned in unversionedFiles) {
            if (changeListManager.isIgnoredFile(unversioned)) continue
            val repo = findRepoForPath(unversioned) ?: continue
            val repoRoot = repo.root

            val relativePath = (unversioned.virtualFile?.let { VfsUtilCore.getRelativePath(it, repoRoot) })
                ?: unversioned.path.removePrefix(repoRoot.path).removePrefix("/")

            if (relativePath.isBlank()) continue

            repoUnversionedMap[repo]?.add(
                GitFileItem(
                    virtualFile = unversioned.virtualFile,
                    filePath = unversioned,
                    repository = repo,
                    relativePath = relativePath,
                    status = FileItemStatus.UNVERSIONED,
                    change = null
                )
            )
        }

        val groups = mutableListOf<GitRepoGroup>()
        for (repo in repos) {
            val trackedInRepo = repoTrackedMap[repo] ?: emptyList()
            val unversionedInRepo = repoUnversionedMap[repo] ?: emptyList()

            trackedInRepo.sortedBy { it.relativePath }
            unversionedInRepo.sortedBy { it.relativePath }

            val repoRoot = repo.root
            val isMain = repoRoot.path == project.basePath || repoRoot.path == project.guessProjectDir()?.path
            val repoName = if (isMain) {
                "${repoRoot.name} [main]"
            } else {
                "${repoRoot.name} [submodule: ${repoRoot.path.removePrefix(project.basePath ?: "")}]"
            }

            groups.add(
                GitRepoGroup(
                    repository = repo,
                    root = repoRoot,
                    name = repoName,
                    trackedFiles = trackedInRepo,
                    unversionedFiles = unversionedInRepo
                )
            )
        }

        return groups
    }

    suspend fun getDiffForFile(item: GitFileItem): String = withContext(Dispatchers.IO) {
        if (!FileContentTypeHelper.isAiSupportedTextFile(item)) {
            val typeDesc = FileContentTypeHelper.getContentTypeDescription(item.relativePath)
            val action = when (item.status) {
                FileItemStatus.DELETED -> "deleted"
                FileItemStatus.ADDED, FileItemStatus.UNVERSIONED -> "added"
                FileItemStatus.MODIFIED -> "modified"
            }
            return@withContext "[Binary/media file ($typeDesc): ${item.relativePath} ($action)]"
        }

        if ((item.status == FileItemStatus.UNVERSIONED || item.status == FileItemStatus.ADDED) && item.change == null) {
            // Unversioned new file: diff is file content
            val vf = item.virtualFile
            if (vf != null && vf.isValid && !vf.isDirectory) {
                try {
                    val content = String(vf.contentsToByteArray(), vf.charset)
                    return@withContext "--- /dev/null\n+++ b/${item.relativePath}\n@@ -0,0 +1,${content.lines().size} @@\n" +
                            content.lines().joinToString("\n") { "+$it" }
                } catch (_: Exception) {
                }
            }
        }

        try {
            val handler = GitLineHandler(project, item.repository.root, GitCommand.DIFF)
            handler.addParameters("--", item.relativePath)
            val result = Git.getInstance().runCommand(handler)
            var out = result.outputAsJoinedString
            if (out.isBlank()) {
                // Try cached diff if unstaged diff is empty
                val cachedHandler = GitLineHandler(project, item.repository.root, GitCommand.DIFF)
                cachedHandler.addParameters("--cached", "--", item.relativePath)
                out = Git.getInstance().runCommand(cachedHandler).outputAsJoinedString
            }
            return@withContext out
        } catch (e: Exception) {
            LOG.warn("Error running git diff for ${item.relativePath}: ${e.message}")
            return@withContext ""
        }
    }

    suspend fun stageFiles(items: List<GitFileItem>): Boolean = withContext(Dispatchers.IO) {
        if (items.isEmpty()) return@withContext true
        val byRepo = items.groupBy { it.repository }
        var allSuccess = true

        for ((repo, repoItems) in byRepo) {
            val deletedItems = repoItems.filter { it.status == FileItemStatus.DELETED }
            val otherItems = repoItems.filterNot { it.status == FileItemStatus.DELETED }

            if (deletedItems.isNotEmpty()) {
                val handler = GitLineHandler(project, repo.root, GitCommand.RM).apply {
                    addParameters("--ignore-unmatch", "--")
                    for (item in deletedItems) {
                        val rel = item.relativePath.replace('\\', '/').trimStart('/')
                        addParameters(rel)
                    }
                }
                val result = Git.getInstance().runCommand(handler)
                if (!result.success()) {
                    LOG.warn("git rm failed in ${repo.root.name}: ${result.errorOutputAsJoinedString}")
                    allSuccess = false
                }
            }

            if (otherItems.isNotEmpty()) {
                val handler = GitLineHandler(project, repo.root, GitCommand.ADD).apply {
                    addParameters("-A", "--")
                    for (item in otherItems) {
                        val rel = item.relativePath.replace('\\', '/').trimStart('/')
                        addParameters(rel)
                    }
                }
                val result = Git.getInstance().runCommand(handler)
                if (!result.success()) {
                    LOG.warn("git add failed in ${repo.root.name}: ${result.errorOutputAsJoinedString}")
                    allSuccess = false
                }
            }
        }

        for (repo in byRepo.keys) {
            try {
                repo.update()
            } catch (e: Exception) {
                LOG.warn("Error updating repo ${repo.root.name}: ${e.message}")
            }
        }

        val vfs = (items.mapNotNull { it.virtualFile } + byRepo.keys.map { it.root }).distinct()
        if (vfs.isNotEmpty()) {
            VfsUtil.markDirtyAndRefresh(true, true, true, *vfs.toTypedArray())
        }

        val dirtyManager = VcsDirtyScopeManager.getInstance(project)
        val (dirs, files) = items.map { it.filePath }.partition { it.isDirectory }
        if (files.isNotEmpty() || dirs.isNotEmpty()) {
            dirtyManager.filePathsDirty(files, dirs)
        }
        for (dir in dirs) {
            dirtyManager.dirDirtyRecursively(dir)
        }
        dirtyManager.markEverythingDirty()

        allSuccess
    }

    suspend fun stageFile(item: GitFileItem): Boolean = stageFiles(listOf(item))

    suspend fun commitFiles(repository: GitRepository, items: List<GitFileItem>, message: String): Boolean =
        withContext(Dispatchers.IO) {
            if (items.isEmpty()) return@withContext true
            try {
                // Stage each selected item first
                stageFiles(items)

                // Commit ONLY the explicitly selected items
                val handler = GitLineHandler(project, repository.root, GitCommand.COMMIT)
                handler.addParameters("-m", message, "--")
                for (item in items) {
                    val rel = item.relativePath.replace('\\', '/').trimStart('/')
                    handler.addParameters(rel)
                }
                val result = Git.getInstance().runCommand(handler)
                val success = result.success()

                try {
                    repository.update()
                } catch (e: Exception) {
                    LOG.warn("Error updating repo ${repository.root.name}: ${e.message}")
                }

                val vfs = items.mapNotNull { it.virtualFile }
                if (vfs.isNotEmpty()) {
                    VfsUtil.markDirtyAndRefresh(true, true, true, *vfs.toTypedArray())
                }
                VcsDirtyScopeManager.getInstance(project).markEverythingDirty()

                success
            } catch (e: Exception) {
                LOG.warn("Failed to commit in ${repository.root.name}: ${e.message}")
                false
            }
        }

    fun findReleaseCommitTimestamp(
        repository: GitRepository,
        marker: String = "***AI release commit***"
    ): Pair<String, String>? {
        val handler = GitLineHandler(project, repository.root, GitCommand.LOG).apply {
            addParameters("-1", "--fixed-strings", "--grep=$marker", "--format=%H %ci")
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return null
        val output = result.outputAsJoinedString.trim()
        if (output.isBlank()) return null
        val parts = output.split(" ", limit = 3)
        return if (parts.size >= 2) {
            Pair(parts[0], "${parts[1]} ${parts.getOrNull(2)?.substringBefore(" ") ?: "00:00:00"}")
        } else {
            null
        }
    }

    fun getCommitLogsSince(repository: GitRepository, sinceDateTimeStr: String): String {
        val handler = GitLineHandler(project, repository.root, GitCommand.LOG).apply {
            addParameters(
                "--since=$sinceDateTimeStr",
                "--pretty=format:Commit: %h%nAuthor: %an%nDate: %ad%nSubject: %s%nBody: %b%n---"
            )
        }
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.outputAsJoinedString.trim() else ""
    }

    fun getCommitLogsForRevision(repository: GitRepository, revision: String): String {
        val cleanRev = revision.trim()
        val revArg =
            if (cleanRev.contains("..")) cleanRev else if (cleanRev.startsWith("-")) cleanRev else "$cleanRev..HEAD"
        val handler = GitLineHandler(project, repository.root, GitCommand.LOG).apply {
            addParameters(
                revArg,
                "--pretty=format:Commit: %h%nAuthor: %an%nDate: %ad%nSubject: %s%nBody: %b%n---"
            )
        }
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.outputAsJoinedString.trim() else ""
    }

    fun getCommitDate(repository: GitRepository, revision: String): String? {
        val handler = GitLineHandler(project, repository.root, GitCommand.LOG).apply {
            addParameters("-1", "--format=%ci", revision.trim())
        }
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.outputAsJoinedString.trim().ifBlank { null } else null
    }

    fun isRepositoryClean(repository: GitRepository): Boolean {
        val changeListManager = ChangeListManager.getInstance(project)
        val allChanges = changeListManager.allChanges
        val unversionedFiles = changeListManager.unversionedFilesPaths
        val repoManager = GitRepositoryManager.getInstance(project)

        val hasChanges = allChanges.any { change ->
            val fp = change.afterRevision?.file ?: change.beforeRevision?.file
            if (fp != null) {
                val r = repoManager.getRepositoryForFileQuick(fp) ?: repoManager.getRepositoryForFile(fp)
                r == repository || fp.path.startsWith("${repository.root.path}/")
            } else false
        }
        if (hasChanges) return false

        val hasUnversioned = unversionedFiles.any { fp ->
            if (changeListManager.isIgnoredFile(fp)) false
            else {
                val r = repoManager.getRepositoryForFileQuick(fp) ?: repoManager.getRepositoryForFile(fp)
                r == repository || fp.path.startsWith("${repository.root.path}/")
            }
        }
        return !hasUnversioned
    }

    fun branchOrTagExists(repository: GitRepository, refName: String): Boolean {
        val clean = refName.trim()
        if (clean.isBlank()) return false
        val localBranches = getLocalBranches(repository)
        if (localBranches.any { it.equals(clean, ignoreCase = true) }) return true

        // Check remote branches or tags via rev-parse
        val handler = GitLineHandler(project, repository.root, GitCommand.REV_PARSE).apply {
            addParameters("--verify", "refs/heads/$clean")
        }
        if (Git.getInstance().runCommand(handler).success()) return true

        val tagHandler = GitLineHandler(project, repository.root, GitCommand.REV_PARSE).apply {
            addParameters("--verify", "refs/tags/$clean")
        }
        if (Git.getInstance().runCommand(tagHandler).success()) return true

        val tagVHandler = GitLineHandler(project, repository.root, GitCommand.REV_PARSE).apply {
            addParameters("--verify", "refs/tags/v$clean")
        }
        return Git.getInstance().runCommand(tagVHandler).success()
    }

    fun getLocalBranches(repository: GitRepository): List<String> {
        val branches = repository.branches.localBranches.map { it.name }
        if (branches.isNotEmpty()) return branches
        val handler = GitLineHandler(project, repository.root, GitCommand.BRANCH).apply {
            addParameters("--list")
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return emptyList()
        return result.output.map { line ->
            line.trim().removePrefix("*").trim()
        }.filter { it.isNotBlank() }
    }

    fun getRootCommit(repository: GitRepository): String? {
        val handler = GitLineHandler(project, repository.root, GitCommand.REV_LIST).apply {
            addParameters("--max-parents=0", "HEAD")
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return null
        return result.output.firstOrNull { it.isNotBlank() }?.trim()
    }

    fun hasPreviousRelease(repository: GitRepository, releaseBranch: String, sourceBranch: String): Boolean {
        val existingBranches = getLocalBranches(repository)
        if (existingBranches.contains(releaseBranch.trim())) {
            val base = getMergeBase(repository, releaseBranch, sourceBranch)
            if (!base.isNullOrBlank()) return true
        }
        return findReleaseCommitTimestamp(repository) != null
    }

    fun getMergeBase(repository: GitRepository, branch1: String, branch2: String): String? {
        val handler = GitLineHandler(project, repository.root, GitCommand.MERGE_BASE).apply {
            addParameters(branch1.trim(), branch2.trim())
        }
        val result = Git.getInstance().runCommand(handler)
        if (!result.success()) return null
        return result.outputAsJoinedString.trim().ifBlank { null }
    }

    fun getCommitLogsBetween(
        repository: GitRepository,
        fromRev: String,
        toRev: String,
        inclusiveFrom: Boolean = false
    ): String {
        val cleanFrom = fromRev.trim()
        val cleanTo = toRev.trim()
        val range = if (inclusiveFrom) {
            // Check if fromRev has a parent commit
            val parentCheck = GitLineHandler(project, repository.root, GitCommand.REV_PARSE).apply {
                addParameters("--verify", "$cleanFrom~1")
            }
            val parentRes = Git.getInstance().runCommand(parentCheck)
            if (parentRes.success() && parentRes.outputAsJoinedString.isNotBlank()) {
                "${cleanFrom}~1..$cleanTo"
            } else {
                // It's the root commit, all commits reachable from cleanTo
                cleanTo
            }
        } else {
            "$cleanFrom..$cleanTo"
        }
        val handler = GitLineHandler(project, repository.root, GitCommand.LOG).apply {
            addParameters(
                range,
                "--pretty=format:Commit: %h%nAuthor: %an%nDate: %ad%nSubject: %s%nBody: %b%n---"
            )
        }
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.outputAsJoinedString.trim() else ""
    }

    fun createAndCheckoutBranch(repository: GitRepository, branchName: String, startPoint: String? = null): Boolean {
        val cleanBranch = branchName.trim()
        val existingBranches = getLocalBranches(repository)
        val handler = if (existingBranches.contains(cleanBranch)) {
            GitLineHandler(project, repository.root, GitCommand.CHECKOUT).apply {
                addParameters(cleanBranch)
            }
        } else {
            GitLineHandler(project, repository.root, GitCommand.CHECKOUT).apply {
                addParameters("-b", cleanBranch)
                if (!startPoint.isNullOrBlank()) {
                    addParameters(startPoint.trim())
                }
            }
        }
        val result = Git.getInstance().runCommand(handler)
        repository.update()
        return result.success()
    }

    fun checkoutBranch(repository: GitRepository, branchName: String): Boolean {
        val cleanBranch = branchName.trim()
        val handler = GitLineHandler(project, repository.root, GitCommand.CHECKOUT).apply {
            addParameters(cleanBranch)
        }
        val result = Git.getInstance().runCommand(handler)
        repository.update()
        return result.success()
    }

    fun getLatestReleaseBranch(repository: GitRepository): String? {
        val branches = getLocalBranches(repository)
        val releaseBranches = branches.filter {
            it.startsWith("release/", ignoreCase = true) || it.startsWith("release-", ignoreCase = true)
        }
        if (releaseBranches.isEmpty()) return null
        return releaseBranches.sortedWith(Comparator { b1, b2 ->
            b2.compareTo(b1, ignoreCase = true)
        }).firstOrNull()
    }

    fun getBranchHeadCommitMessage(repository: GitRepository, branchName: String): String {
        val handler = GitLineHandler(project, repository.root, GitCommand.LOG).apply {
            addParameters("-1", "--pretty=format:%B", branchName.trim())
        }
        val result = Git.getInstance().runCommand(handler)
        return if (result.success()) result.outputAsJoinedString.trim() else ""
    }

    fun getBranchNotesForUser(repository: GitRepository, branchName: String): String {
        val headMsg = getBranchHeadCommitMessage(repository, branchName)
        if (headMsg.contains("# RELEASE", ignoreCase = true) || headMsg.contains(
                "***AI release commit***",
                ignoreCase = true
            )
        ) {
            return headMsg
        }
        val handler = GitLineHandler(project, repository.root, GitCommand.LOG).apply {
            addParameters(
                "-20",
                "--pretty=format:Commit: %h%nDate: %ad%nSubject: %s%nBody: %b%n---",
                branchName.trim()
            )
        }
        val result = Git.getInstance().runCommand(handler)
        val logs = if (result.success()) result.outputAsJoinedString.trim() else ""
        return if (headMsg.isNotBlank()) "$headMsg\n\n$logs" else logs
    }

    fun detectCandidateVersionFiles(repository: GitRepository): List<VirtualFile> {
        val root = repository.root
        val candidates = mutableListOf<VirtualFile>()

        fun scan(file: VirtualFile, depth: Int = 0) {
            if (depth > 3) return
            if (file.isDirectory) {
                val name = file.name
                if (name == ".git" || name == "build" || name == ".gradle" || name == "node_modules" || name == ".idea") return
                for (child in file.children) {
                    scan(child, depth + 1)
                }
            } else {
                val name = file.name.lowercase()
                if (name.endsWith(".versions.toml") ||
                    name == "libs.versions.toml" ||
                    name == "build.gradle.kts" ||
                    name == "build.gradle" ||
                    name == "gradle.properties" ||
                    name == "package.json" ||
                    name == "pom.xml" ||
                    name == "version.txt" ||
                    name == "version"
                ) {
                    candidates.add(file)
                }
            }
        }

        scan(root)
        return candidates
    }
}
