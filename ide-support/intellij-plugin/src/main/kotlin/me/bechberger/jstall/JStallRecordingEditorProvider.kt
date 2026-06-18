package me.bechberger.jstall

import com.intellij.openapi.fileEditor.*
import com.intellij.openapi.project.DumbAware
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import me.bechberger.jstall.actions.isJStallRecording

/**
 * FileEditorProvider that intercepts double-click on jstall recording zip files
 * and opens them by running `jstall status` analysis.
 */
class JStallRecordingEditorProvider : FileEditorProvider, DumbAware {

    override fun accept(project: Project, file: VirtualFile): Boolean {
        if (file.isDirectory) return false
        if (!file.extension.equals("zip", ignoreCase = true)) return false
        return isJStallRecording(file.path)
    }

    override fun createEditor(project: Project, file: VirtualFile): FileEditor {
        return JStallRecordingEditor(project, file)
    }

    override fun getEditorTypeId(): String = "jstall-recording"

    override fun getPolicy(): FileEditorPolicy = FileEditorPolicy.HIDE_DEFAULT_EDITOR
}