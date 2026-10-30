package tk.mallumo.localai.ui

import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBTabbedPane
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import tk.mallumo.localai.git.GitChangesManager
import java.awt.BorderLayout
import javax.swing.JPanel

class AICommitMainPanel(
    private val project: Project
) : JPanel(BorderLayout()), Disposable {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val gitChangesManager = GitChangesManager(project)

    private val treePanel: ChangesTreePanel
    private val aiControlPanel: AiControlPanel
    private val releaseControlPanel: ReleaseControlPanel
    private val userMessageControlPanel: UserMessageControlPanel
    private val tabbedPane = JBTabbedPane()
    private val splitter = JBSplitter(false, 0.40f)

    init {
        aiControlPanel = AiControlPanel(
            project = project,
            gitChangesManager = gitChangesManager,
            cs = scope,
            onCommitCompleted = {
                treePanel.forceFullRefresh()
            }
        )

        releaseControlPanel = ReleaseControlPanel(
            project = project,
            gitChangesManager = gitChangesManager,
            cs = scope,
            onReleaseCommitted = {
                treePanel.forceFullRefresh()
            },
            onSwitchToCommitTab = {
                tabbedPane.selectedIndex = 0
            }
        )

        userMessageControlPanel = UserMessageControlPanel(
            project = project,
            gitChangesManager = gitChangesManager,
            cs = scope
        )

        treePanel = ChangesTreePanel(
            project = project,
            gitChangesManager = gitChangesManager,
            cs = scope,
            onSelectionChanged = { selectedFiles ->
                aiControlPanel.updateSelectionState(selectedFiles)
                val repo = treePanel.getSelectedRepository()
                if (repo != null) {
                    releaseControlPanel.selectRepository(repo)
                    userMessageControlPanel.selectRepository(repo)
                }
            },
            onRefreshRequested = {
                aiControlPanel.clearCaches()
                releaseControlPanel.reloadRepositories()
                userMessageControlPanel.reloadRepositories()
            }
        )

        gitChangesManager.subscribeChanges {
            treePanel.refreshChanges()
        }

        // Add tabs to right panel: 1. Commit (default), 2. Release, 3. User Message
        tabbedPane.addTab("Commit", AllIcons.Actions.Commit, aiControlPanel)
        tabbedPane.addTab("Release", AllIcons.Actions.ShowAsTree, releaseControlPanel)
        tabbedPane.addTab("User Message", AllIcons.General.User, userMessageControlPanel)
        tabbedPane.selectedIndex = 0
        tabbedPane.addChangeListener {
            if (tabbedPane.selectedIndex == 1) {
                releaseControlPanel.updateGuideline()
            }
        }

        splitter.firstComponent = treePanel
        splitter.secondComponent = tabbedPane
        splitter.dividerWidth = 4

        add(splitter, BorderLayout.CENTER)
    }

    override fun dispose() {
        scope.cancel()
    }
}
