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
import com.intellij.util.text.DateFormatUtil

/**
 * Action available on .zip files in the project view that interprets
 * the file as a jstall recording and runs `status <file.zip>` on it.
 *
 * Only enabled for zip files that contain a `metadata.json` entry
 * (the marker for jstall recordings).
 */
class JStallReplayAction : DumbAwareAction() {

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

    override fun update(e: AnActionEvent) {
        e.presentation.isEnabledAndVisible = getJStallRecordingFile(e) != null
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val file = getJStallRecordingFile(e) ?: return
        val path = file.path
        val fileName = file.name

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "Analyzing JStall Recording $fileName", false
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = true
                indicator.text = "Analyzing $fileName…"

                try {
                    val analysis = analyzeRecording(path)
                    val timestamp = DateFormatUtil.formatTimeWithSeconds(System.currentTimeMillis())
                    val title = "JStall Replay $fileName — $timestamp"

                    ApplicationManager.getApplication().invokeLater({
                        showPlainConsole(project, title, analysis.output)
                    }, ModalityState.nonModal())

                    if (analysis.exitCode != 0) {
                        ApplicationManager.getApplication().invokeLater({
                            notifyError(project, "jstall replay exited with code ${analysis.exitCode}")
                        }, ModalityState.nonModal())
                    }
                } catch (ex: Exception) {
                    thisLogger().warn("Failed to replay jstall recording: $path", ex)
                    ApplicationManager.getApplication().invokeLater({
                        notifyError(project, "Failed to replay recording: ${ex.message}")
                    }, ModalityState.nonModal())
                }
            }
        })
    }
}