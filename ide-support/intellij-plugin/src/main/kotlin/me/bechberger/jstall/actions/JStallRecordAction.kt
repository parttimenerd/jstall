package me.bechberger.jstall.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.LocalFileSystem
import me.bechberger.femtocli.RunResult
import java.nio.file.Path

/**
 * Action that runs `jstall record create <pid> -o <output>` and displays the result.
 *
 * When triggered from a run window, it extracts the PID from the running ProcessHandler.
 * When triggered outside a run window (e.g., from the Tools menu), it shows a JVM picker popup.
 *
 * The output file is written to the project base directory as `<pid>-<timestamp>.zip`.
 */
class JStallRecordAction : AbstractJvmAction() {

    override val pickerTitle = "Select JVM Process to Record"
    override val progressTitlePrefix = "JStall Record"
    override val progressDescription = "Recording process diagnostics"

    override fun buildArgs(project: Project, pid: Long): ActionArgs {
        val outputFile = buildOutputPath(project, pid)
        return ActionArgs(buildList {
            add("record"); add("create"); add(pid.toString())
            add("-o"); add(outputFile.toString())
            addAll(commonArgs())
        }, outputFile)
    }

    override fun onResult(project: Project, pid: Long, result: RunResult, outputFile: Path?) {
        if (result.exitCode() != 0) {
            // Show full output in a console tab only on failure
            super.onResult(project, pid, result, outputFile)
            return
        }

        if (outputFile != null) {
            // Refresh VFS so the new file appears in the project tree
            LocalFileSystem.getInstance().refreshAndFindFileByPath(outputFile.toString())

            ApplicationManager.getApplication().invokeLater({
                notifyInfo(project, "Recording saved to $outputFile")
            }, ModalityState.nonModal())
        }
    }
}