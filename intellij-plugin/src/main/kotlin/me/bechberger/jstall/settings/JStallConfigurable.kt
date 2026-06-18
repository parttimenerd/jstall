package me.bechberger.jstall.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.ui.DialogPanel
import com.intellij.ui.dsl.builder.bindIntValue
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.panel

class JStallConfigurable : BoundConfigurable("JStall") {

    private val settings = JStallSettings.getInstance()

    override fun createPanel(): DialogPanel = panel {
        group("General") {
            row {
                checkBox("Include expensive diagnostics (--full)")
                    .bindSelected(settings.state::fullDiagnostics)
            }
        }
        group("Status Settings") {
            row {
                checkBox("Intelligent stack trace filtering (--intelligent-filter)")
                    .comment("Collapse internal frames and focus on application code")
                    .bindSelected(settings.state::intelligentFilter)
            }
            row {
                checkBox("Ignore native/system threads (--no-native)")
                    .comment("Skip threads without stack traces")
                    .bindSelected(settings.state::noNative)
            }
            row {
                checkBox("Persist dumps to disk (--keep)")
                    .bindSelected(settings.state::keep)
            }
            row("Top threads:") {
                spinner(1..100, 1)
                    .comment("Number of top threads to display")
                    .bindIntValue(settings.state::top)
            }
        }
        group("Record Settings") {
            row("Sample interval (seconds):") {
                spinner(1..300, 1)
                    .bindIntValue(settings.state::recordIntervalSeconds)
            }
        }
        group("Flame Settings") {
            row("Profiling duration (seconds):") {
                spinner(1..300, 1)
                    .bindIntValue(settings.state::flameDurationSeconds)
            }
        }
    }
}