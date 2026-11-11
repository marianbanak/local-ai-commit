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

    fun isXmlFile(fileName: String): Boolean {
        return fileName.endsWith(".xml", ignoreCase = true)
    }

    fun isXmlFile(file: VirtualFile): Boolean {
        return (file.extension?.equals("xml", ignoreCase = true) == true) || isXmlFile(file.name)
    }

    fun formatFiles(
        project: Project,
        files: List<VirtualFile>,
        reformat: Boolean,
        optimizeImports: Boolean,
        rearrange: Boolean,
        reformatXml: Boolean = false
    ) {
        val targetFiles = if (!reformatXml) {
            files.filterNot { isXmlFile(it) }
        } else {
            files
        }
        if (targetFiles.isEmpty() || (!reformat && !optimizeImports && !rearrange)) return

        ApplicationManager.getApplication().invokeAndWait {
            FileDocumentManager.getInstance().saveAllDocuments()

            val psiManager = PsiManager.getInstance(project)
            val psiFiles = targetFiles.mapNotNull { vf ->
                if (vf.isValid && !vf.isDirectory) {
                    val psi = psiManager.findFile(vf)
                    if (psi != null) {
                        if (!reformatXml && (psi.fileType.name.equals(
                                "XML",
                                ignoreCase = true
                            ) || isXmlFile(psi.name))
                        ) {
                            null
                        } else {
                            psi
                        }
                    } else null
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
