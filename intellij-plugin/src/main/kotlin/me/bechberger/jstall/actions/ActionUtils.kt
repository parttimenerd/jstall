package me.bechberger.jstall.actions

import com.intellij.execution.impl.ConsoleViewImpl
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.execution.ui.RunContentDescriptor
import com.intellij.execution.ui.RunContentManager
import com.intellij.ide.BrowserUtil
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.fileChooser.FileChooserFactory
import com.intellij.openapi.fileChooser.FileSaverDescriptor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.openapi.util.Key
import com.intellij.openapi.vfs.LocalFileSystem
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.wm.ToolWindow
import com.intellij.openapi.wm.ToolWindowAnchor
import com.intellij.openapi.wm.ToolWindowManager
import com.intellij.ui.jcef.JBCefBrowser
import me.bechberger.femtocli.FemtoCli
import me.bechberger.femtocli.RunResult
import me.bechberger.jstall.Main
import me.bechberger.jstall.provider.ReplayProvider
import me.bechberger.jstall.settings.JStallSettings
import org.cef.browser.CefBrowser
import org.cef.browser.CefFrame
import org.cef.handler.CefLifeSpanHandlerAdapter
import org.cef.handler.CefRequestHandlerAdapter
import org.cef.network.CefRequest
import java.awt.BorderLayout
import java.io.File
import java.nio.file.Path
import java.util.Collections
import java.util.LinkedHashMap
import javax.swing.JComponent

internal const val MAX_DISPLAY_LENGTH = 100

private val ANSI_PATTERN = Regex("\u001B\\[[;\\d]*m")

internal fun stripAnsiCodes(text: String): String = ANSI_PATTERN.replace(text, "")

/**
 * Run a jstall command via FemtoCli and return the captured result.
 */
internal fun runJStallCaptured(vararg args: String): RunResult {
    return FemtoCli.builder()
        .commandConfig(Main::setFemtoCliCommandConfig)
        .runCaptured(Main(), *args)
}

/**
 * Format the output of a jstall command: stdout first, stderr appended if non-blank.
 */
internal fun formatJStallOutput(result: RunResult): String {
    return buildString {
        append(stripAnsiCodes(result.out()))
        if (result.err().isNotBlank()) {
            if (isNotEmpty() && !endsWith("\n")) append("\n")
            append("\n--- stderr ---\n")
            append(stripAnsiCodes(result.err()))
        }
    }
}

/**
 * The estimated total duration of a jstall command in milliseconds,
 * based on interval from settings, plus a two-second buffer to account for startup and analysis time.
 */
internal fun estimatedDurationMs(): Long {
    val state = JStallSettings.getInstance().state.copy()
    return state.recordIntervalSeconds.toLong() * 1000 + 2000
}

/**
 * Show [text] in a new Run tool window tab with the given [title],
 * using plain normal output (no stack trace red highlighting).
 * Must be called on the EDT.
 */
internal fun showPlainConsole(project: Project, title: String, text: String) {
    val console = ConsoleViewImpl(project, true)
    console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)

    val descriptor = RunContentDescriptor(console, null, object : JComponent() {
        init {
            layout = BorderLayout()
            add(console.component, BorderLayout.CENTER)
        }
    }, title)

    RunContentManager.getInstance(project).showRunContent(
        com.intellij.execution.executors.DefaultRunExecutor.getRunExecutorInstance(),
        descriptor
    )
}

private val LOG = Logger.getInstance("JStall")

private data class RecordingCacheEntry(val isRecording: Boolean, val timestamp: Long)
private const val RECORDING_CACHE_MAX_SIZE = 200
private const val RECORDING_CACHE_TTL_MS = 5000L
private val recordingCache: MutableMap<String, RecordingCacheEntry> =
    Collections.synchronizedMap(object : LinkedHashMap<String, RecordingCacheEntry>(16, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, RecordingCacheEntry>?): Boolean =
            size > RECORDING_CACHE_MAX_SIZE
    })

/**
 * Checks if the zip file at [path] is a valid jstall recording
 * by verifying it contains recorded JVMs via [ReplayProvider].
 * Results are cached for [RECORDING_CACHE_TTL_MS] ms to avoid repeated I/O.
 */
internal fun isJStallRecording(path: String): Boolean {
    val now = System.currentTimeMillis()
    recordingCache[path]?.let { entry ->
        if (now - entry.timestamp < RECORDING_CACHE_TTL_MS) return entry.isRecording
    }
    val result = try {
        val file = File(path)
        if (!file.exists() || !file.isFile) false
        else ReplayProvider(file.toPath()).listRecordedJvms(null).isNotEmpty()
    } catch (ex: Exception) {
        false
    }
    recordingCache[path] = RecordingCacheEntry(result, now)
    return result
}

/**
 * Result of analyzing a jstall recording.
 */
internal data class RecordingAnalysisResult(
    val output: String,
    val exitCode: Int
)

/**
 * Runs `jstall status <path>` on a recording file and returns the formatted output.
 * Respects the [JStallSettings.State.fullDiagnostics] setting.
 * Must be called on a background thread.
 */
internal fun analyzeRecording(path: String): RecordingAnalysisResult {
    val state = JStallSettings.getInstance().state.copy()
    val args = mutableListOf("status", path)
    if (state.fullDiagnostics) args.add("--full")
    if (state.intelligentFilter) args.add("--intelligent-filter")
    if (state.noNative) args.add("--no-native")
    if (state.keep) args.add("--keep")
    if (state.top != 3) { args.add("--top"); args.add(state.top.toString()) }
    val result = runJStallCaptured(*args.toTypedArray())
    return RecordingAnalysisResult(formatJStallOutput(result), result.exitCode())
}

/**
 * Returns the selected virtual file from the event if it is a jstall recording zip,
 * or null otherwise.
 */
internal fun getJStallRecordingFile(e: AnActionEvent): VirtualFile? {
    val file = e.getData(CommonDataKeys.VIRTUAL_FILE) ?: return null
    if (file.isDirectory || !file.extension.equals("zip", ignoreCase = true)) return null
    return if (isJStallRecording(file.path)) file else null
}

internal fun notifyInfo(project: Project, message: String) {
    try {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JStall Notifications")
            .createNotification(message, NotificationType.INFORMATION)
            .notify(project)
    } catch (ex: Exception) {
        LOG.info("JStall: $message", ex)
    }
}

internal fun notifyError(project: Project, message: String) {
    try {
        NotificationGroupManager.getInstance()
            .getNotificationGroup("JStall Notifications")
            .createNotification(message, NotificationType.ERROR)
            .notify(project)
    } catch (ex: Exception) {
        LOG.error("JStall: $message", ex)
    }
}

// ── Flamegraph viewer (shared by JStallFlameAction and JStallRecordingFlameAction) ──

/** Key used to stash the raw HTML on a tool-window Content tab. */
internal val FLAME_HTML_KEY: Key<String> = Key.create("JStallFlame.html")
internal const val FLAME_TOOL_WINDOW_ID = "JStall Flamegraph"

/**
 * Shows [htmlContent] in the JStall Flamegraph tool window with the given [title].
 * Must be called on the EDT.
 */
internal fun showFlameWindow(project: Project, title: String, htmlContent: String) {
    val toolWindowManager = ToolWindowManager.getInstance(project)

    val toolWindow = toolWindowManager.getToolWindow(FLAME_TOOL_WINDOW_ID)
        ?: toolWindowManager.registerToolWindow(FLAME_TOOL_WINDOW_ID) {
            anchor = ToolWindowAnchor.BOTTOM
            canCloseContent = true
        }.also { installFlameGearAction(it, project) }

    val browser = JBCefBrowser()
    val client = browser.jbCefClient

    // Intercept target="_blank" popups ─ open in system browser
    client.addLifeSpanHandler(object : CefLifeSpanHandlerAdapter() {
        override fun onBeforePopup(
            cefBrowser: CefBrowser?, frame: CefFrame?, targetUrl: String?,
            targetFrameName: String?
        ): Boolean {
            if (targetUrl != null && (targetUrl.startsWith("http://") || targetUrl.startsWith("https://"))) {
                BrowserUtil.browse(targetUrl)
            }
            return true
        }
    }, browser.cefBrowser)

    // Intercept same-window navigation to external URLs
    client.addRequestHandler(object : CefRequestHandlerAdapter() {
        override fun onBeforeBrowse(
            cefBrowser: CefBrowser?, frame: CefFrame?, request: CefRequest?,
            userGesture: Boolean, isRedirect: Boolean
        ): Boolean {
            val url = request?.url ?: return false
            if (url.startsWith("http://") || url.startsWith("https://")) {
                BrowserUtil.browse(url)
                return true
            }
            return false
        }
    }, browser.cefBrowser)

    browser.loadHTML(htmlContent)

    val browserComponent: JComponent = browser.component
    val contentManager = toolWindow.contentManager
    val content = contentManager.factory.createContent(browserComponent, title, false).also {
        it.isCloseable = true
        it.putUserData(FLAME_HTML_KEY, htmlContent)
    }
    Disposer.register(content, browser)
    contentManager.addContent(content)
    contentManager.setSelectedContent(content)
    toolWindow.show()
}

/**
 * Adds a "Save Flamegraph…" action to the tool window's gear / title-bar menu.
 */
internal fun installFlameGearAction(toolWindow: ToolWindow, project: Project) {
    val saveAction = object : AnAction("Save Flamegraph…") {
        override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.BGT

        override fun actionPerformed(e: AnActionEvent) {
            val content = toolWindow.contentManager.selectedContent ?: return
            val html = content.getUserData(FLAME_HTML_KEY) ?: return
            val suggestedName = content.displayName
                .replace(Regex("[^a-zA-Z0-9_\\-]"), "_")
                .trimEnd('_')

            val descriptor = FileSaverDescriptor(
                "Save Flamegraph",
                "Choose where to save the flamegraph HTML file",
                "html"
            )
            val saveDialog = FileChooserFactory.getInstance()
                .createSaveFileDialog(descriptor, project)
            val baseDir = project.basePath
                ?.let { LocalFileSystem.getInstance().findFileByPath(it) }
            val wrapper = saveDialog.save(baseDir, "$suggestedName.html") ?: return
            try {
                wrapper.file.writeText(html)
                notifyInfo(project, "Flamegraph saved to ${wrapper.file.absolutePath}")
            } catch (ex: Exception) {
                notifyError(project, "Failed to save flamegraph: ${ex.message}")
            }
        }

        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled =
                toolWindow.contentManager.selectedContent?.getUserData(FLAME_HTML_KEY) != null
        }
    }
    toolWindow.setAdditionalGearActions(DefaultActionGroup(saveAction))
}

/**
 * Extracts the flamegraph HTML from a jstall recording zip, or null if no flamegraph is present.
 * Must be called on a background thread.
 */
internal fun getRecordingFlamegraph(path: String): String? {
    return try {
        val replay = ReplayProvider(Path.of(path))
        val jvms = replay.listRecordedJvms(null)
        for (jvm in jvms) {
            val data = replay.getFlamegraph(jvm.pid())
            if (data != null) return data.htmlContent()
        }
        null
    } catch (_: Exception) {
        null
    }
}

/**
 * Checks whether a jstall recording zip contains a flamegraph.
 * Must be called on a background thread.
 */
internal fun recordingHasFlamegraph(path: String): Boolean {
    return try {
        val replay = ReplayProvider(Path.of(path))
        val jvms = replay.listRecordedJvms(null)
        jvms.any { replay.getFlamegraph(it.pid()) != null }
    } catch (_: Exception) {
        false
    }
}