package me.bechberger.jstall

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.fileEditor.FileEditor
import com.intellij.openapi.fileEditor.FileEditorState
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.UserDataHolderBase
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.util.concurrency.AppExecutorUtil
import me.bechberger.jstall.actions.analyzeRecording
import java.beans.PropertyChangeListener
import javax.swing.JComponent
import javax.swing.JPanel
import java.awt.BorderLayout

/**
 * A read-only editor that runs `jstall status <recording.zip>` and displays the
 * result in an embedded console view. Automatically starts analysis when opened.
 */
class JStallRecordingEditor(
    project: Project,
    private val file: VirtualFile
) : UserDataHolderBase(), FileEditor {

    private val console = ConsoleViewImpl(project, true)
    private val panel = JPanel(BorderLayout()).apply {
        add(console.component, BorderLayout.CENTER)
    }

    init {
        console.print("Analyzing ${file.name}…\n\n", ConsoleViewContentType.SYSTEM_OUTPUT)
        // Run analysis in the background
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val localPath = VfsUtilCore.virtualToIoFile(file).absolutePath
                val analysis = analyzeRecording(localPath)

                ApplicationManager.getApplication().invokeLater({
                    console.clear()
                    console.print(analysis.output, ConsoleViewContentType.NORMAL_OUTPUT)
                    if (analysis.exitCode != 0) {
                        console.print(
                            "\n\nProcess exited with code ${analysis.exitCode}",
                            ConsoleViewContentType.ERROR_OUTPUT
                        )
                    }
                }, ModalityState.defaultModalityState())
            } catch (ex: Exception) {
                thisLogger().warn("Failed to analyze recording: ${file.path}", ex)
                ApplicationManager.getApplication().invokeLater({
                    console.clear()
                    console.print(
                        "Failed to analyze recording: ${ex.message}",
                        ConsoleViewContentType.ERROR_OUTPUT
                    )
                }, ModalityState.defaultModalityState())
            }
        }
    }

    override fun getComponent(): JComponent = panel
    override fun getPreferredFocusedComponent(): JComponent = console.preferredFocusableComponent
    override fun getName(): String = "JStall Analysis"
    override fun isModified(): Boolean = false
    override fun isValid(): Boolean = file.isValid

    override fun setState(state: FileEditorState) {}
    override fun addPropertyChangeListener(listener: PropertyChangeListener) {}
    override fun removePropertyChangeListener(listener: PropertyChangeListener) {}

    override fun getFile(): VirtualFile = file

    override fun dispose() {
        console.dispose()
    }
}