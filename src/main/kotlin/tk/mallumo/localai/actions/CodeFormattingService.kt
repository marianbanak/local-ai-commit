package tk.mallumo.localai.actions

import com.intellij.codeInsight.actions.AbstractLayoutCodeProcessor
import com.intellij.codeInsight.actions.OptimizeImportsProcessor
import com.intellij.codeInsight.actions.RearrangeCodeProcessor
import com.intellij.codeInsight.actions.ReformatCodeProcessor
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiManager

object CodeFormattingService {
    fun formatFiles(
        project: Project,
        files: List<VirtualFile>,
        reformat: Boolean,
        optimizeImports: Boolean,
        rearrange: Boolean
    ) {
        if (files.isEmpty() || (!reformat && !optimizeImports && !rearrange)) return

        ApplicationManager.getApplication().invokeAndWait {
            FileDocumentManager.getInstance().saveAllDocuments()

            val psiManager = PsiManager.getInstance(project)
            val psiFiles = files.mapNotNull { vf ->
                if (vf.isValid && !vf.isDirectory) {
                    psiManager.findFile(vf)
                } else null
            }
            if (psiFiles.isEmpty()) return@invokeAndWait

            val array = psiFiles.toTypedArray()
            try {
                var processor: AbstractLayoutCodeProcessor? = null
                if (optimizeImports) {
                    processor = OptimizeImportsProcessor(project, array, null)
                }
                if (rearrange) {
                    processor = if (processor != null) {
                        RearrangeCodeProcessor(processor)
                    } else {
                        RearrangeCodeProcessor(project, array, "Rearrange Code", null)
                    }
                }
                if (reformat) {
                    processor = if (processor != null) {
                        ReformatCodeProcessor(processor, true)
                    } else {
                        ReformatCodeProcessor(project, array, null, true)
                    }
                }

                processor?.run()
                FileDocumentManager.getInstance().saveAllDocuments()
            } catch (e: Throwable) {
                // Ignore formatting or language-specific provider exceptions
            }
        }
    }
}
