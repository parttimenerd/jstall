package me.bechberger.jstall.actions

import com.intellij.execution.process.BaseProcessHandler
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.LangDataKeys
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.progress.ProgressIndicator
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.progress.Task
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.popup.JBPopupFactory
import com.intellij.ui.ColoredListCellRenderer
import com.intellij.ui.SimpleTextAttributes
import com.intellij.util.concurrency.AppExecutorUtil
import com.intellij.util.text.DateFormatUtil
import me.bechberger.femtocli.RunResult
import me.bechberger.jstall.settings.JStallSettings
import me.bechberger.jstall.util.JVMDiscovery
import java.nio.file.Path
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CancellationException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutionException
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException

/**
 * Base class for JStall actions that target a running JVM process.
 *
 * Handles:
 * - PID extraction from run window, or JVM picker fallback
 * - Time-based progress bar (estimated from interval × count settings)
 * - Running FemtoCli on a background thread with progress updates
 */
abstract class AbstractJvmAction : DumbAwareAction() {

    /** Single-threaded executor for running CLI commands. Uses the shared app pool to avoid leaking threads. */
    protected val executor = AppExecutorUtil.getAppExecutorService()

    /**
     * Holds the CLI arguments and optional output file path for one invocation.
     */
    protected data class ActionArgs(
        val args: List<String>,
        val outputFile: Path? = null
    )

    /**
     * Computes a timestamped output zip path inside the project base dir (or home dir).
     *
     * Note: the timestamp reflects when [buildArgs] executes on the background thread,
     * not the moment the user triggers the action.
     */
    protected fun buildOutputPath(project: Project, pid: Long): Path {
        val baseDir = project.basePath ?: System.getProperty("user.home")
        val timestamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        return Path.of(baseDir, "$pid-$timestamp.zip")
    }

    /**
     * Common CLI tail args shared by all actions: --interval and optionally --full.
     *
     * Settings are captured at call time (inside [buildArgs]), so they are frozen before
     * the command starts and won't reflect changes made while the progress bar is visible.
     */
    protected fun commonArgs(): List<String> {
        val state = JStallSettings.getInstance().state.copy()
        return buildList {
            add("--interval"); add("${state.recordIntervalSeconds}s")
            if (state.fullDiagnostics) add("--full")
        }
    }

    /**
     * CLI args specific to `jstall status`: --intelligent-filter, --no-native, --keep, --top.
     */
    protected fun statusArgs(): List<String> {
        val state = JStallSettings.getInstance().state.copy()
        return buildList {
            if (state.intelligentFilter) add("--intelligent-filter")
            if (state.noNative) add("--no-native")
            if (state.keep) add("--keep")
            if (state.top != 3) { add("--top"); add(state.top.toString()) }
        }
    }

    /** Title shown in the JVM picker popup. */
    protected abstract val pickerTitle: String

    /** Title prefix shown in the progress bar, e.g. "JStall Status". */
    protected abstract val progressTitlePrefix: String

    /** Human-readable description shown in the progress bar, e.g. "Capturing flame graph". */
    protected abstract val progressDescription: String

    /**
     * Build the CLI args for this action. Called on a background thread.
     * Return an [ActionArgs] containing the argument list and an optional output file path.
     */
    protected abstract fun buildArgs(project: Project, pid: Long): ActionArgs

    /**
     * Called after the command completes successfully on a background thread.
     * Default implementation shows output in a plain console and reports non-zero exit codes.
     * Override to add extra behaviour (e.g. notifications).
     *
     * @param outputFile the output file path from [ActionArgs], if any.
     */
    protected open fun onResult(project: Project, pid: Long, result: RunResult, outputFile: Path?) {
        val output = formatJStallOutput(result)
        val title = buildResultTitle(pid)

        ApplicationManager.getApplication().invokeLater({
            showPlainConsole(project, title, output)
        }, ModalityState.nonModal())

        if (result.exitCode() != 0) {
            ApplicationManager.getApplication().invokeLater({
                notifyError(project, "$progressTitlePrefix exited with code ${result.exitCode()}")
            }, ModalityState.nonModal())
        }
    }

    protected fun buildResultTitle(pid: Long): String {
        val timestamp = DateFormatUtil.formatTimeWithSeconds(System.currentTimeMillis())
        return "$progressTitlePrefix $pid — $timestamp"
    }

    // ── update / actionPerformed ──

    override fun getActionUpdateThread(): ActionUpdateThread = ActionUpdateThread.EDT

    override fun update(e: AnActionEvent) {
        val project = e.project
        if (project == null) {
            e.presentation.isEnabledAndVisible = false
            return
        }
        val descriptor = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)
        if (descriptor != null) {
            val handler = descriptor.processHandler
            e.presentation.isVisible = true
            e.presentation.isEnabled = handler != null && !handler.isProcessTerminated
        } else {
            e.presentation.isEnabledAndVisible = true
        }
    }

    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val descriptor = e.getData(LangDataKeys.RUN_CONTENT_DESCRIPTOR)
        val handler = descriptor?.processHandler

        if (handler is BaseProcessHandler<*> && !handler.isProcessTerminated) {
            runWithProgress(project, handler.process.pid())
        } else {
            showJvmPicker(project)
        }
    }

    // ── JVM picker ──

    private fun showJvmPicker(project: Project) {
        AppExecutorUtil.getAppExecutorService().execute {
            try {
                val jvms = JVMDiscovery.listJVMs()
                if (jvms.isEmpty()) {
                    ApplicationManager.getApplication().invokeLater({
                        notifyError(project, "No running JVMs found")
                    }, ModalityState.nonModal())
                    return@execute
                }
                ApplicationManager.getApplication().invokeLater({
                    val renderer = object : ColoredListCellRenderer<JVMDiscovery.JVMProcess>() {
                        override fun customizeCellRenderer(
                            list: javax.swing.JList<out JVMDiscovery.JVMProcess>,
                            value: JVMDiscovery.JVMProcess,
                            index: Int,
                            selected: Boolean,
                            hasFocus: Boolean
                        ) {
                            val fqcn = value.mainClass()
                            val displayClass = fqcn.let {
                                if (it.length > MAX_DISPLAY_LENGTH) it.substring(0, MAX_DISPLAY_LENGTH - 1) + "…" else it
                            }
                            append(value.pid().toString(), SimpleTextAttributes.GRAYED_BOLD_ATTRIBUTES)
                            appendTextPadding(60)
                            append(displayClass, SimpleTextAttributes.REGULAR_ATTRIBUTES)
                        }
                    }
                    JBPopupFactory.getInstance()
                        .createPopupChooserBuilder(jvms)
                        .setTitle(pickerTitle)
                        .setRenderer(renderer)
                        .setNamerForFiltering { jvm ->
                            // Include both PID and full class name so users can filter by either
                            "${jvm.pid()} ${jvm.mainClass()}"
                        }
                        .setItemChosenCallback { selectedValue: JVMDiscovery.JVMProcess ->
                            runWithProgress(project, selectedValue.pid())
                        }
                        .createPopup()
                        .showCenteredInCurrentWindow(project)
                }, ModalityState.nonModal())
            } catch (ex: Exception) {
                thisLogger().warn("Failed to list JVMs", ex)
                ApplicationManager.getApplication().invokeLater({
                    notifyError(project, "Failed to list JVMs: ${ex.message}")
                }, ModalityState.nonModal())
            }
        }
    }

    // ── Progress + execution ──

    private fun runWithProgress(project: Project, pid: Long) {
        val estimatedTotalMs = estimatedDurationMs()

        ProgressManager.getInstance().run(object : Task.Backgroundable(
            project, "JStall: $progressDescription (PID $pid)", true
        ) {
            override fun run(indicator: ProgressIndicator) {
                indicator.isIndeterminate = false
                indicator.fraction = 0.0
                indicator.text = "JStall: $progressDescription (PID $pid)"
                indicator.text2 = "0%"

                val startTime = System.currentTimeMillis()
                val actionArgs = buildArgs(project, pid)

                val futureResult: CompletableFuture<RunResult> = CompletableFuture.supplyAsync({
                    runJStallCaptured(*actionArgs.args.toTypedArray())
                }, executor)

                while (!futureResult.isDone) {
                    if (indicator.isCanceled) {
                        futureResult.cancel(true)
                        return
                    }
                    val elapsed = System.currentTimeMillis() - startTime
                    val fraction = if (estimatedTotalMs > 0) {
                        (elapsed.toDouble() / estimatedTotalMs).coerceAtMost(0.99)
                    } else 0.0
                    indicator.fraction = fraction
                    indicator.text2 = "${(fraction * 100).toInt()}%"
                    try {
                        futureResult.get(250, TimeUnit.MILLISECONDS)
                    } catch (_: TimeoutException) {
                        // expected — keep polling for progress updates
                    } catch (_: InterruptedException) {
                        futureResult.cancel(true)
                        return
                    } catch (_: CancellationException) {
                        return
                    } catch (_: ExecutionException) {
                        break // will be handled below
                    }
                }

                indicator.fraction = 1.0
                indicator.text2 = "100%"
                indicator.text = "Processing…"

                val result = try {
                    futureResult.get()
                } catch (ex: Exception) {
                    thisLogger().warn("$progressTitlePrefix failed for PID $pid", ex)
                    ApplicationManager.getApplication().invokeLater({
                        notifyError(project, "$progressTitlePrefix failed: ${ex.cause?.message ?: ex.message}")
                    }, ModalityState.nonModal())
                    return
                }

                onResult(project, pid, result, actionArgs.outputFile)
            }
        })
    }
}