package me.bechberger.jstall.actions

import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.vfs.LocalFileSystem
import java.nio.file.Path

/**
 * Action available on jstall recording .zip files that extracts the
 * recording contents into a folder next to the zip via `record extract`.
 */
class JStallExtractAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = getJStallRecordingFile(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = getJStallRecordingFile(e) ?: return
        val zipPath = file.path
        val fileName = file.nameWithoutExtension
        val outputDir = Path.of(file.parent.path, fileName).toString()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Extracting JStall Recording $fileName", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Extracting ${file.name}…"

                if (indicator.isCanceled) return
                try {
                    val result = runJStallCaptured("record", "extract", zipPath, outputDir)
                    if (indicator.isCanceled) return
                    val output = formatJStallOutput(result)

                    if (result.exitCode() == 0) {
                        // Refresh VFS so the extracted folder shows up immediately
                        LocalFileSystem.getInstance().refreshAndFindFileByPath(outputDir)

                        ApplicationManager.getApplication().invokeLater({
                            notifyInfo(project, "Extracted to $outputDir")
                        }, ModalityState.nonModal())
                    } else {
                        ApplicationManager.getApplication().invokeLater({
                            notifyError(project, "jstall record extract failed (exit ${result.exitCode()})")
                            if (output.isNotBlank()) {
                                showPlainConsole(project, "JStall Extract Error", output)
                            }
                        }, ModalityState.nonModal())
                    }
                } catch (ex: Exception) {
                    thisLogger().warn("Failed to extract jstall recording: $zipPath", ex)
                    ApplicationManager.getApplication().invokeLater({
                        notifyError(project, "Failed to extract recording: ${ex.message}")
                    }, ModalityState.nonModal())
                }
            }
        })
    }
}