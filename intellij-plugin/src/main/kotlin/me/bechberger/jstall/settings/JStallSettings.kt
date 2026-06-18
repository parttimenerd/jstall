package me.bechberger.jstall.settings

import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.util.xmlb.XmlSerializerUtil

@Service(Service.Level.APP)
@State(name = "JStallSettings", storages = [Storage("jstall.xml")])
class JStallSettings : PersistentStateComponent<JStallSettings.State> {

    data class State(
        /** Record interval in seconds */
        var recordIntervalSeconds: Int = 5,
        /** Include expensive diagnostics (--full) */
        var fullDiagnostics: Boolean = false,
        /** Flame graph profiling duration in seconds */
        var flameDurationSeconds: Int = 10,
        /** Use intelligent stack trace filtering (--intelligent-filter) */
        var intelligentFilter: Boolean = false,
        /** Persist dumps to disk (--keep) */
        var keep: Boolean = false,
        /** Ignore threads without stack traces, typically native/system threads (--no-native) */
        var noNative: Boolean = false,
        /** Number of top threads to display (--top, default: 3) */
        var top: Int = 3,
    )

    private var myState = State()

    override fun getState(): State = myState

    override fun loadState(state: State) {
        XmlSerializerUtil.copyBean(state, myState)
    }

    companion object {
        fun getInstance(): JStallSettings =
            ApplicationManager.getApplication().getService(JStallSettings::class.java)
    }
}