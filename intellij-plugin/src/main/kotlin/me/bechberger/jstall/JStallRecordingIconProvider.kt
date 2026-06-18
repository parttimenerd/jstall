package me.bechberger.jstall

import com.intellij.ide.IconProvider
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import me.bechberger.jstall.actions.isJStallRecording
import javax.swing.Icon

/**
 * Provides a custom icon for zip files that are jstall recordings
 * (i.e. contain a metadata.json entry).
 */
class JStallRecordingIconProvider : IconProvider() {

    override fun getIcon(element: PsiElement, flags: Int): Icon? {
        if (element !is PsiFile) return null
        val vFile = element.virtualFile ?: return null
        if (vFile.isDirectory) return null
        if (!vFile.extension.equals("zip", ignoreCase = true)) return null
        if (!isJStallRecording(vFile.path)) return null
        return JStallIcons.RecordingFile
    }
}