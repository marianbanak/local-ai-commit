package tk.mallumo.localai.ui

import com.intellij.openapi.Disposable
import com.intellij.openapi.project.Project
import com.intellij.ui.JBSplitter
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

        treePanel = ChangesTreePanel(
            project = project,
            gitChangesManager = gitChangesManager,
            cs = scope,
            onSelectionChanged = { selectedFiles ->
                aiControlPanel.updateSelectionState(selectedFiles)
            },
            onRefreshRequested = {
                aiControlPanel.clearCaches()
            }
        )

        gitChangesManager.subscribeChanges {
            treePanel.refreshChanges()
        }

        splitter.firstComponent = treePanel
        splitter.secondComponent = aiControlPanel
        splitter.dividerWidth = 4

        add(splitter, BorderLayout.CENTER)
    }

    override fun dispose() {
        scope.cancel()
    }
}
