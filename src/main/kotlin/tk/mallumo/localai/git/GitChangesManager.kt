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
            if (trackedInRepo.isEmpty() && unversionedInRepo.isEmpty()) continue

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
}
