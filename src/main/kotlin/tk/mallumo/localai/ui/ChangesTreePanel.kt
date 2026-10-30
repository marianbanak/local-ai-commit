package tk.mallumo.localai.ui

import com.intellij.diff.DiffContentFactory
import com.intellij.diff.DiffManager
import com.intellij.diff.requests.SimpleDiffRequest
import com.intellij.icons.AllIcons
import com.intellij.openapi.actionSystem.*
import com.intellij.openapi.actionSystem.ex.ActionUtil
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileEditor.OpenFileDescriptor
import com.intellij.openapi.ide.CopyPasteManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.project.guessProjectDir
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.FileStatus
import com.intellij.openapi.vcs.VcsDataKeys
import com.intellij.openapi.vcs.changes.ChangeListManager
import com.intellij.openapi.vcs.changes.InvokeAfterUpdateMode
import com.intellij.openapi.vcs.changes.VcsDirtyScopeManager
import com.intellij.openapi.vcs.changes.actions.diff.ShowDiffAction
import com.intellij.openapi.vcs.changes.ui.RollbackChangesDialog
import com.intellij.openapi.vfs.VfsUtil
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.ui.*
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBScrollPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.tree.TreeUtil
import git4idea.repo.GitRepositoryManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import tk.mallumo.localai.git.FileItemStatus
import tk.mallumo.localai.git.GitChangesManager
import tk.mallumo.localai.git.GitFileItem
import tk.mallumo.localai.git.GitRepoGroup
import java.awt.BorderLayout
import java.awt.Font
import java.awt.datatransfer.StringSelection
import java.awt.event.KeyAdapter
import java.awt.event.KeyEvent
import java.awt.event.MouseAdapter
import java.awt.event.MouseEvent
import javax.swing.*
import javax.swing.event.TreeModelEvent
import javax.swing.event.TreeModelListener
import javax.swing.tree.DefaultTreeModel

data class DirectoryItem(val path: String, val displayName: String)
data class ChangeCategoryItem(val name: String, val count: Int)

class ChangesTreePanel(
    private val project: Project,
    private val gitChangesManager: GitChangesManager,
    private val cs: CoroutineScope,
    private val onSelectionChanged: (List<GitFileItem>) -> Unit,
    private val onRefreshRequested: () -> Unit = {}
) : JPanel(BorderLayout()), DataProvider {

    private val LOG = Logger.getInstance(ChangesTreePanel::class.java)
    private val rootNode = CheckedTreeNode("All Repositories")
    private val tree: CheckboxTree
    private val headerCountLabel = JBLabel("").apply {
        foreground = JBColor.GRAY
        font = font.deriveFont(Font.PLAIN, 11f)
    }
    private var selectionUpdateScheduled = false

    init {
        val renderer = object : CheckboxTree.CheckboxTreeCellRenderer(true, false) {
            override fun customizeRenderer(
                tree: JTree,
                value: Any?,
                selected: Boolean,
                expanded: Boolean,
                leaf: Boolean,
                row: Int,
                hasFocus: Boolean
            ) {
                if (value !is CheckedTreeNode) return
                val userObj = value.userObject

                when (userObj) {
                    is GitRepoGroup -> {
                        textRenderer.icon = AllIcons.Vcs.Branch
                        textRenderer.append(userObj.root.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        val branch = userObj.repository.currentBranchName
                        if (branch != null) {
                            textRenderer.append(" [$branch]", SimpleTextAttributes.GRAY_ATTRIBUTES)
                        } else if (userObj.name.contains("submodule")) {
                            textRenderer.append(" [submodule]", SimpleTextAttributes.GRAY_ATTRIBUTES)
                        }
                        textRenderer.append(" (${userObj.files.size})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }

                    is ChangeCategoryItem -> {
                        textRenderer.append(userObj.name, SimpleTextAttributes.REGULAR_BOLD_ATTRIBUTES)
                        textRenderer.append(" (${userObj.count})", SimpleTextAttributes.GRAYED_ATTRIBUTES)
                    }

                    is DirectoryItem -> {
                        textRenderer.icon = AllIcons.Nodes.Folder
                        textRenderer.append(userObj.displayName, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }

                    is GitFileItem -> {
                        val isSubmodule = gitChangesManager.isSubmodule(userObj.filePath)
                        val icon = when {
                            isSubmodule -> AllIcons.Nodes.Module
                            userObj.filePath.isDirectory -> AllIcons.Nodes.Folder
                            else -> userObj.virtualFile?.fileType?.icon
                                ?: userObj.filePath.fileType.icon
                                ?: AllIcons.FileTypes.Text
                        }
                        textRenderer.icon = icon

                        val fileName = userObj.filePath.name
                        val (color, attr) = when (userObj.status) {
                            FileItemStatus.MODIFIED -> Pair(
                                FileStatus.MODIFIED.color,
                                SimpleTextAttributes.REGULAR_ATTRIBUTES
                            )

                            FileItemStatus.ADDED -> Pair(
                                FileStatus.ADDED.color,
                                SimpleTextAttributes.REGULAR_ATTRIBUTES
                            )

                            FileItemStatus.DELETED -> Pair(
                                FileStatus.DELETED.color,
                                SimpleTextAttributes(SimpleTextAttributes.STYLE_STRIKEOUT, FileStatus.DELETED.color)
                            )

                            FileItemStatus.UNVERSIONED -> Pair(
                                FileStatus.UNKNOWN.color,
                                SimpleTextAttributes.REGULAR_ATTRIBUTES
                            )
                        }

                        val styledAttr = if (color != null) {
                            SimpleTextAttributes(attr.style, color)
                        } else attr

                        textRenderer.append(fileName, styledAttr)
                    }

                    else -> {
                        textRenderer.append(userObj?.toString() ?: "", SimpleTextAttributes.REGULAR_ATTRIBUTES)
                    }
                }
            }
        }

        tree = object : CheckboxTree(renderer, rootNode, CheckPolicy(true, true, false, true)) {
            override fun onNodeStateChanged(node: CheckedTreeNode) {
                super.onNodeStateChanged(node)
                scheduleSelectionChange()
            }

            override fun onDoubleClick(node: CheckedTreeNode) {
                val item = node.userObject as? GitFileItem
                if (item != null) {
                    showDiff(item)
                } else {
                    super.onDoubleClick(node)
                }
            }
        }

        tree.rowHeight = JBUI.scale(22)
        tree.border = JBUI.Borders.empty(2, 4)
        tree.isRootVisible = false
        tree.showsRootHandles = true

        tree.addCheckboxTreeListener(object : CheckboxTreeListener {
            override fun nodeStateChanged(node: CheckedTreeNode) {
                scheduleSelectionChange()
            }
        })

        (tree.model as? DefaultTreeModel)?.addTreeModelListener(object : TreeModelListener {
            override fun treeNodesChanged(e: TreeModelEvent?) {
                scheduleSelectionChange()
            }

            override fun treeNodesInserted(e: TreeModelEvent?) {
                scheduleSelectionChange()
            }

            override fun treeNodesRemoved(e: TreeModelEvent?) {
                scheduleSelectionChange()
            }

            override fun treeStructureChanged(e: TreeModelEvent?) {
                scheduleSelectionChange()
            }
        })

        tree.addKeyListener(object : KeyAdapter() {
            override fun keyReleased(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_SPACE) {
                    scheduleSelectionChange()
                }
            }
        })

        tree.addMouseListener(object : MouseAdapter() {
            override fun mousePressed(e: MouseEvent) {
                if (e.isPopupTrigger) handlePopup(e)
            }

            override fun mouseReleased(e: MouseEvent) {
                if (e.isPopupTrigger) {
                    handlePopup(e)
                } else {
                    scheduleSelectionChange()
                }
            }

            override fun mouseClicked(e: MouseEvent) {
                if (e.clickCount == 2) {
                    val path = tree.getPathForLocation(e.x, e.y) ?: return
                    val node = path.lastPathComponent as? CheckedTreeNode ?: return
                    val item = node.userObject as? GitFileItem ?: return
                    showDiff(item)
                }
            }
        })

        // Action Toolbar: Refresh, Rollback Selected, Expand All, Collapse All
        val actionGroup = DefaultActionGroup().apply {
            add(object : AnAction("Refresh Changes", "Scan git changes", AllIcons.Actions.Refresh) {
                override fun actionPerformed(e: AnActionEvent) {
                    forceFullRefresh()
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            add(object : AnAction("Rollback Selected", "Rollback selected changes", AllIcons.Actions.Rollback) {
                override fun actionPerformed(e: AnActionEvent) {
                    val selected = getSelectedFiles()
                    rollbackFiles(selected)
                }

                override fun update(e: AnActionEvent) {
                    e.presentation.isEnabled = getSelectedFiles().any { it.change != null }
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            addSeparator()
            add(object : AnAction("Expand All", "Expand all nodes", AllIcons.Actions.Expandall) {
                override fun actionPerformed(e: AnActionEvent) {
                    TreeUtil.expandAll(tree)
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
            add(object : AnAction("Collapse All", "Collapse all nodes", AllIcons.Actions.Collapseall) {
                override fun actionPerformed(e: AnActionEvent) {
                    TreeUtil.collapseAll(tree, 1)
                }

                override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT
            })
        }

        val toolbar = ActionManager.getInstance().createActionToolbar("AICommitLeftToolbar", actionGroup, true).apply {
            targetComponent = tree
        }
        toolbar.component.isOpaque = false
        toolbar.component.border = JBUI.Borders.empty()

        val headerPanel = JPanel(BorderLayout()).apply {
            border = JBUI.Borders.customLine(JBColor.border(), 0, 0, 1, 0)
            background = JBColor.PanelBackground
        }
        val leftTitleBox = Box.createHorizontalBox().apply {
            border = JBUI.Borders.empty(5, 10, 5, 4)
            add(JBLabel("Changes").apply {
                font = font.deriveFont(Font.BOLD, 12f)
            })
            add(Box.createHorizontalStrut(6))
            add(headerCountLabel)
        }
        headerPanel.add(leftTitleBox, BorderLayout.WEST)
        headerPanel.add(toolbar.component, BorderLayout.EAST)

        val scrollPane = JBScrollPane(tree).apply {
            border = JBUI.Borders.empty()
        }

        add(headerPanel, BorderLayout.NORTH)
        add(scrollPane, BorderLayout.CENTER)
        refreshChanges()
    }

    private fun scheduleSelectionChange() {
        if (selectionUpdateScheduled) return
        selectionUpdateScheduled = true
        SwingUtilities.invokeLater {
            selectionUpdateScheduled = false
            val selected = getSelectedFiles()
            updateHeader(selected.size)
            onSelectionChanged(selected)
        }
    }

    private fun updateHeader(selectedCount: Int) {
        val totalCount = getTotalChangesCount()
        headerCountLabel.text = if (totalCount == 0) {
            "(0)"
        } else if (selectedCount == totalCount) {
            "($totalCount)"
        } else {
            "($selectedCount/$totalCount)"
        }
    }

    private fun getTotalChangesCount(): Int {
        var count = 0
        fun countFiles(node: CheckedTreeNode) {
            if (node.userObject is GitFileItem) {
                count++
            }
            val children = node.children()
            while (children.hasMoreElements()) {
                val child = children.nextElement() as? CheckedTreeNode
                if (child != null) countFiles(child)
            }
        }
        countFiles(rootNode)
        return count
    }

    private var hasLoadedInitialSelection = false

    private class DirBuilder(var name: String, var fullPath: String) {
        val files = mutableListOf<GitFileItem>()
        val subdirs = mutableMapOf<String, DirBuilder>()

        fun addFile(parts: List<String>, fileItem: GitFileItem) {
            if (parts.size <= 1) {
                files.add(fileItem)
            } else {
                val dirName = parts.first()
                val childPath = if (fullPath.isEmpty()) dirName else "$fullPath/$dirName"
                val child = subdirs.computeIfAbsent(dirName) { DirBuilder(dirName, childPath) }
                child.addFile(parts.subList(1, parts.size), fileItem)
            }
        }

        fun compact() {
            for (child in subdirs.values) {
                child.compact()
            }
            while (name.isNotEmpty() && files.isEmpty() && subdirs.size == 1) {
                val onlyChild = subdirs.values.first()
                name = "$name/${onlyChild.name}"
                fullPath = onlyChild.fullPath
                files.addAll(onlyChild.files)
                subdirs.clear()
                subdirs.putAll(onlyChild.subdirs)
            }
        }

        fun populateTreeNode(
            parentNode: CheckedTreeNode,
            checkedPaths: Set<String>,
            isFirstLoad: Boolean,
            defaultChecked: Boolean = true
        ) {
            for (child in subdirs.values.sortedBy { it.name }) {
                val dirNode = CheckedTreeNode(DirectoryItem(child.fullPath, child.name))
                child.populateTreeNode(dirNode, checkedPaths, isFirstLoad, defaultChecked)
                dirNode.isChecked = hasCheckedDescendant(dirNode)
                parentNode.add(dirNode)
            }
            for (file in files.sortedBy { it.filePath.name }) {
                val fileNode = CheckedTreeNode(file)
                fileNode.isChecked = if (isFirstLoad) defaultChecked else checkedPaths.contains(file.filePath.path)
                parentNode.add(fileNode)
            }
        }

        private fun hasCheckedDescendant(node: CheckedTreeNode): Boolean {
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
                if (child.isChecked || hasCheckedDescendant(child)) return true
            }
            return false
        }
    }

    fun refreshChanges() {
        onRefreshRequested()
        ApplicationManager.getApplication().invokeLater {
            val checkedPaths = getSelectedFiles().map { it.filePath.path }.toSet()
            val isFirstLoad = !hasLoadedInitialSelection
            hasLoadedInitialSelection = true

            val groups = gitChangesManager.getRepoGroups()
            rootNode.removeAllChildren()

            for (group in groups) {
                val groupNode = CheckedTreeNode(group)

                // 1. Tracked Changes section ("Changes")
                if (group.trackedFiles.isNotEmpty()) {
                    val trackedDirRoot = DirBuilder("", "")
                    for (fileItem in group.trackedFiles) {
                        val parts = fileItem.relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                        trackedDirRoot.addFile(parts, fileItem)
                    }
                    trackedDirRoot.compact()

                    if (group.unversionedFiles.isNotEmpty()) {
                        val changesCategoryNode =
                            CheckedTreeNode(ChangeCategoryItem("Changes", group.trackedFiles.size))
                        trackedDirRoot.populateTreeNode(
                            changesCategoryNode,
                            checkedPaths,
                            isFirstLoad,
                            defaultChecked = true
                        )
                        changesCategoryNode.isChecked = hasCheckedDescendant(changesCategoryNode)
                        groupNode.add(changesCategoryNode)
                    } else {
                        trackedDirRoot.populateTreeNode(groupNode, checkedPaths, isFirstLoad, defaultChecked = true)
                    }
                }

                // 2. Unversioned Files section ("Unversioned Files")
                if (group.unversionedFiles.isNotEmpty()) {
                    val unversionedDirRoot = DirBuilder("", "")
                    for (fileItem in group.unversionedFiles) {
                        val parts = fileItem.relativePath.replace('\\', '/').split('/').filter { it.isNotEmpty() }
                        unversionedDirRoot.addFile(parts, fileItem)
                    }
                    unversionedDirRoot.compact()

                    val unversionedCategoryNode =
                        CheckedTreeNode(ChangeCategoryItem("Unversioned Files", group.unversionedFiles.size))
                    // Unversioned files are unchecked by default on initial load
                    unversionedDirRoot.populateTreeNode(
                        unversionedCategoryNode,
                        checkedPaths,
                        isFirstLoad,
                        defaultChecked = false
                    )
                    unversionedCategoryNode.isChecked = hasCheckedDescendant(unversionedCategoryNode)
                    groupNode.add(unversionedCategoryNode)
                }

                groupNode.isChecked = hasCheckedDescendant(groupNode)
                rootNode.add(groupNode)
            }

            (tree.model as? DefaultTreeModel)?.nodeStructureChanged(rootNode)
            TreeUtil.expandAll(tree)
            scheduleSelectionChange()
        }
    }

    private fun hasCheckedDescendant(node: CheckedTreeNode): Boolean {
        val count = node.childCount
        for (i in 0 until count) {
            val child = node.getChildAt(i) as? CheckedTreeNode ?: continue
            if (child.isChecked || hasCheckedDescendant(child)) return true
        }
        return false
    }

    fun getSelectedFiles(): List<GitFileItem> {
        val list = mutableListOf<GitFileItem>()
        fun collect(node: CheckedTreeNode) {
            val userObj = node.userObject
            if (userObj is GitFileItem && node.isChecked) {
                list.add(userObj)
            }
            val count = node.childCount
            for (i in 0 until count) {
                val child = node.getChildAt(i) as? CheckedTreeNode
                if (child != null) collect(child)
            }
        }
        collect(rootNode)
        return list
    }

    fun getSelectedRepository(): git4idea.repo.GitRepository? {
        val paths = tree.selectionPaths
        if (!paths.isNullOrEmpty()) {
            val selectedRepos = paths.mapNotNull { path ->
                val node = path.lastPathComponent as? CheckedTreeNode ?: return@mapNotNull null
                when (val obj = node.userObject) {
                    is GitRepoGroup -> obj.repository
                    is GitFileItem -> obj.repository
                    else -> null
                }
            }.distinct()
            if (selectedRepos.size == 1) return selectedRepos.first()
        }

        // Fallback to checked items
        val checkedRepos = getSelectedFiles().map { it.repository }.distinct()
        if (checkedRepos.size == 1) return checkedRepos.first()

        return null
    }

    private fun getSelectedNodeItems(): List<GitFileItem> {
        val paths = tree.selectionPaths ?: return emptyList()
        val items = mutableListOf<GitFileItem>()
        for (p in paths) {
            val node = p.lastPathComponent as? CheckedTreeNode ?: continue
            collectFilesUnderNode(node, items)
        }
        return items.distinct()
    }

    private fun collectFilesUnderNode(node: CheckedTreeNode, result: MutableList<GitFileItem>) {
        val obj = node.userObject
        if (obj is GitFileItem) {
            result.add(obj)
        }
        val children = node.children()
        while (children.hasMoreElements()) {
            val child = children.nextElement() as? CheckedTreeNode
            if (child != null) {
                collectFilesUnderNode(child, result)
            }
        }
    }

    private fun handlePopup(e: MouseEvent) {
        val row = tree.getClosestRowForLocation(e.x, e.y)
        if (row >= 0 && !tree.isRowSelected(row)) {
            tree.setSelectionRow(row)
        }

        val items = getSelectedNodeItems()
        if (items.isEmpty()) return

        val menu = JPopupMenu()

        // 1. Show Diff
        val diffItem = menu.add("Show Diff")
        diffItem.addActionListener {
            showDiff(items.first())
        }

        // 2. Show Diff in New Tab
        val diffTabItem = menu.add("Show Diff in New Tab")
        diffTabItem.addActionListener {
            showDiff(items.first())
        }

        menu.addSeparator()

        // 3. Rollback Changes
        val rollbackItem = menu.add("Rollback Changes...")
        rollbackItem.addActionListener {
            rollbackFiles(items)
        }

        // 4. Add into VCS (git add)
        val addItem = menu.add("Add into VCS")
        addItem.addActionListener {
            cs.launch(Dispatchers.IO) {
                gitChangesManager.stageFiles(items)
                forceFullRefresh()
            }
        }

        // 5. Add to .gitignore
        val gitIgnoreItem = menu.add("Add to .gitignore")
        gitIgnoreItem.addActionListener {
            addToGitIgnore(items)
        }

        // 6. Delete
        val deleteItem = menu.add("Delete")
        deleteItem.addActionListener {
            deleteFiles(items)
        }

        menu.addSeparator()

        // 7. Jump to Source
        val jumpItem = menu.add("Jump to Source")
        jumpItem.addActionListener {
            openFileInEditor(items.first())
        }

        // 8. Copy Path
        val copyPathItem = menu.add("Copy Path")
        copyPathItem.addActionListener {
            val path = items.first().filePath.path
            CopyPasteManager.getInstance().setContents(StringSelection(path))
        }

        // 9. Local History
        val localHistoryItem = menu.add("Local History -> Show History")
        localHistoryItem.addActionListener {
            val action = ActionManager.getInstance().getAction("LocalHistory.ShowHistory")
            if (action != null) {
                ActionUtil.invokeAction(action, tree, ActionPlaces.POPUP, null, null)
            }
        }

        menu.show(tree, e.x, e.y)
    }

    private fun openFileInEditor(item: GitFileItem) {
        val vf = item.virtualFile ?: return
        if (vf.isValid && !vf.isDirectory) {
            OpenFileDescriptor(project, vf).navigate(true)
        }
    }

    private fun showDiff(item: GitFileItem) {
        val change = item.change
        if (change != null) {
            ShowDiffAction.showDiffForChange(project, listOf(change))
        } else {
            val vf = item.virtualFile
            if (vf != null && vf.isValid) {
                try {
                    val content1 = DiffContentFactory.getInstance().createEmpty()
                    val content2 = DiffContentFactory.getInstance().create(project, vf)
                    val request = SimpleDiffRequest(
                        "Diff: ${item.relativePath}",
                        content1,
                        content2,
                        "(Empty)",
                        item.relativePath
                    )
                    DiffManager.getInstance().showDiff(project, request)
                } catch (_: Exception) {
                    OpenFileDescriptor(project, vf).navigate(true)
                }
            }
        }
    }

    private fun rollbackFiles(items: List<GitFileItem>) {
        val changes = items.mapNotNull { it.change }
        if (changes.isNotEmpty()) {
            RollbackChangesDialog.rollbackChanges(project, changes)
            forceFullRefresh()
        }
    }

    private fun deleteFiles(items: List<GitFileItem>) {
        val confirm = Messages.showYesNoDialog(
            project,
            "Are you sure you want to delete ${items.size} file(s)?",
            "Delete Confirmation",
            "Delete",
            "Cancel",
            Messages.getQuestionIcon()
        )
        if (confirm == Messages.YES) {
            WriteCommandAction.writeCommandAction(project).withName("Delete Files").run<Throwable> {
                for (item in items) {
                    try {
                        item.virtualFile?.delete(this)
                    } catch (e: Exception) {
                        Messages.showErrorDialog(
                            project,
                            "Could not delete ${item.relativePath}: ${e.message}",
                            "Delete Error"
                        )
                    }
                }
            }
            forceFullRefresh()
        }
    }

    private fun addToGitIgnore(items: List<GitFileItem>) {
        if (items.isEmpty()) return

        val affectedRepos = items.map { it.repository }.distinct()
        val affectedGitignoreVfs = mutableListOf<VirtualFile>()

        WriteCommandAction.writeCommandAction(project).withName("Add to .gitignore").run<Throwable> {
            val byRepo = items.groupBy { it.repository }
            for ((repo, repoItems) in byRepo) {
                try {
                    val root = repo.root
                    val gitignoreFile = root.findChild(".gitignore") ?: root.createChildData(this, ".gitignore")
                    affectedGitignoreVfs.add(gitignoreFile)
                    val existingText = try {
                        VfsUtil.loadText(gitignoreFile)
                    } catch (_: Exception) {
                        ""
                    }
                    val existingLines = existingText.lines().map { it.trim() }.toSet()
                    val patternsToAdd = mutableListOf<String>()

                    for (item in repoItems) {
                        var rel = item.relativePath.replace('\\', '/')
                        if (!rel.startsWith("/")) {
                            rel = "/$rel"
                        }
                        if (item.filePath.isDirectory && !rel.endsWith("/")) {
                            rel = "$rel/"
                        }
                        val altPattern = rel.removePrefix("/")
                        if (!existingLines.contains(rel) && !existingLines.contains(altPattern)) {
                            patternsToAdd.add(rel)
                        }
                    }

                    if (patternsToAdd.isNotEmpty()) {
                        val sb = StringBuilder(existingText)
                        if (existingText.isNotEmpty() && !existingText.endsWith("\n")) {
                            sb.append("\n")
                        }
                        for (p in patternsToAdd) {
                            sb.append(p).append("\n")
                        }
                        VfsUtil.saveText(gitignoreFile, sb.toString())
                    }
                } catch (e: Exception) {
                    Messages.showErrorDialog(project, "Could not update .gitignore: ${e.message}", "Error")
                }
            }
        }

        forceFullRefresh()
    }

    fun forceFullRefresh() {
        cs.launch(Dispatchers.IO) {
            val repos = GitRepositoryManager.getInstance(project).repositories
            val roots = (repos.map { it.root } + listOfNotNull(project.guessProjectDir())).distinct()
            if (roots.isNotEmpty()) {
                VfsUtil.markDirtyAndRefresh(true, true, true, *roots.toTypedArray())
            }
            for (repo in repos) {
                try {
                    repo.update()
                } catch (e: Exception) {
                    LOG.warn("Error updating repo ${repo.root.name}: ${e.message}")
                }
            }
            val dirtyManager = VcsDirtyScopeManager.getInstance(project)
            dirtyManager.markEverythingDirty()

            ApplicationManager.getApplication().invokeLater {
                ChangeListManager.getInstance(project).invokeAfterUpdate(
                    Runnable { refreshChanges() },
                    InvokeAfterUpdateMode.SILENT,
                    null,
                    null
                )
            }

            // Fallback: Ensure refresh after CLM debounce timer (300ms) elapses
            kotlinx.coroutines.delay(400)
            refreshChanges()
        }
    }

    override fun getData(dataId: String): Any? {
        if (VcsDataKeys.CHANGES.`is`(dataId)) {
            return getSelectedNodeItems().mapNotNull { it.change }.toTypedArray()
        }
        if (CommonDataKeys.VIRTUAL_FILE_ARRAY.`is`(dataId)) {
            return getSelectedNodeItems().mapNotNull { it.virtualFile }.toTypedArray()
        }
        if (CommonDataKeys.NAVIGATABLE_ARRAY.`is`(dataId)) {
            return getSelectedNodeItems().mapNotNull { it.virtualFile?.let { vf -> OpenFileDescriptor(project, vf) } }
                .toTypedArray()
        }
        return null
    }
}
