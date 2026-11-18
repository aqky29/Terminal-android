package com.termux.agent

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import android.os.Process
import android.util.Log
import com.termux.agent.start.AgentPermissions
import com.termux.agent.start.AgentSettings
import kotlin.concurrent.thread

private const val TAG = "TermuxAgentProvider"

class TermuxAgentProvider : ContentProvider() {

    private val appAliveBinder = Binder()

    override fun onCreate(): Boolean = true

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle? {
        if (method != "sendAgentBinder" || extras == null) return null

        val callingUid = Binder.getCallingUid()
        if (callingUid != 0 && callingUid != 1000 && callingUid != 2000) {
            Log.w(TAG, "Rejected sendAgentBinder from non-privileged uid=$callingUid")
            throw SecurityException("Only uid 0, 1000, or 2000 may attach Termux Agent Server")
        }

        val token = extras.getString("token")
        val appCtx = context?.applicationContext
        val persistentToken = appCtx?.let { AgentSettings.getOrCreateServerToken(it) }
        val tokenValid = (token != null && token == persistentToken) ||
            TermuxAgentClient.consumeExpectedToken(token)
        if (!tokenValid) {
            Log.w(TAG, "Rejected sendAgentBinder due to token mismatch from uid=$callingUid")
            throw SecurityException("Invalid Termux Agent Server launch token")
        }

        val binder = extras.getBinder("agent_binder") ?: return null
        if (!TermuxAgentClient.attachBinder(binder)) {
            Log.w(TAG, "Failed to attach binder from uid=$callingUid")
            return null
        }

        context?.applicationContext?.let { appCtx ->
            thread(isDaemon = true, name = "AgentPostAttachPerms") {
                runCatching { AgentPermissions.grantSelfPermissions(appCtx) }
            }
        }

        return Bundle().apply {
            putBinder("app_callback", appAliveBinder)
            putInt("app_pid", Process.myPid())
        }
    }

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? = null

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0
}
