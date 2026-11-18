package com.termux.agent

import android.content.Context
import android.util.Log
import com.termux.agent.start.AgentPrivilegeOrchestrator
import com.termux.agent.start.AgentSettings
import com.termux.shared.termux.TermuxConstants
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

private const val TAG = "TermuxAgentManager"

object TermuxAgentManager {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    @JvmStatic
    var agentSocketServerEnabled: Boolean = false
        private set

    @JvmStatic
    fun init(context: Context) {
        val appCtx = context.applicationContext
        val socketPath = TermuxConstants.TERMUX_APP.TERMUX_AGENT_SOCKET_FILE_PATH
        agentSocketServerEnabled = TermuxAgentSocketServer.start(appCtx, socketPath)
        ensureCliTool()

        if (AgentSettings.getAutoStart(appCtx)) {
            scope.launch {
                runCatching {
                    val outcome = AgentPrivilegeOrchestrator.ensureStarted(appCtx)
                    Log.i(TAG, "Auto-start result: ok=${outcome.ok} method=${outcome.method} msg=${outcome.message}")
                }.onFailure {
                    Log.w(TAG, "Auto-start check failed", it)
                }
            }
        }
    }

    @JvmStatic
    fun ensureCliTool() {
        TermuxAgentCliInstaller.installIfPrefixReady(
            TermuxConstants.TERMUX_BIN_PREFIX_DIR_PATH,
            TermuxConstants.TERMUX_APP.TERMUX_AGENT_SOCKET_FILE_PATH
        )
    }

    @JvmStatic
    fun onBootCompleted(context: Context) {
        val appCtx = context.applicationContext
        if (!AgentSettings.getAutoStart(appCtx)) return
        scope.launch {
            runCatching {
                AgentPrivilegeOrchestrator.ensureStarted(appCtx)
            }.onFailure {
                Log.w(TAG, "Boot auto-start failed", it)
            }
        }
    }
}
