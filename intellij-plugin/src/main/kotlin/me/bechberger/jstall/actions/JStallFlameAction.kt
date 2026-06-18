package me.bechberger.jstall.actions

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.project.Project
import me.bechberger.femtocli.RunResult
import me.bechberger.jstall.settings.JStallSettings
import java.nio.file.Files
import java.nio.file.Path

/**
 * Action that runs `jstall flame <pid> --output <tmp>.html` and renders the
 * resulting flamegraph HTML inside an embedded JCEF browser in a tool window.
 *
 * Disabled on Windows (async-profiler is not supported there).
 */
class JStallFlameAction : AbstractJvmAction() {

    override val pickerTitle = "Select JVM Process for Flamegraph"
    override val progressTitlePrefix = "JStall Flame"
    override val progressDescription = "Capturing flame graph"

    override fun update(e: AnActionEvent) {
        // Disable entirely on Windows
        if (System.getProperty("os.name", "").lowercase().contains("win")) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        super.update(e)
    }

    override fun buildArgs(project: Project, pid: Long): ActionArgs {
        val tmpFile = Files.createTempFile("jstall-flame-$pid-", ".html")
        val state = JStallSettings.getInstance().state.copy()
        return ActionArgs(buildList {
            add("flame")
            add(pid.toString())
            add("--output"); add(tmpFile.toFile().absolutePath)
            add("--duration"); add("${state.flameDurationSeconds}s")
        }, tmpFile)
    }

    override fun onResult(project: Project, pid: Long, result: RunResult, outputFile: Path?) {
        val htmlFile = outputFile?.toFile()

        if (result.exitCode() != 0 || htmlFile == null || !htmlFile.exists()) {
            htmlFile?.delete()
            val errorText = buildString {
                append("jstall flame failed (exit ${result.exitCode()})\n\n")
                append(formatJStallOutput(result))
            }
            ApplicationManager.getApplication().invokeLater({
                showPlainConsole(project, buildResultTitle(pid), errorText)
                notifyError(project, "$progressTitlePrefix failed: exit ${result.exitCode()}")
            }, ModalityState.nonModal())
            return
        }

        val htmlContent = htmlFile.readText()
        htmlFile.delete()

        ApplicationManager.getApplication().invokeLater({
            showFlameWindow(project, buildResultTitle(pid), htmlContent)
        }, ModalityState.nonModal())
    }
}