package com.mrndtvndv.term.herdr

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Binder
import com.mrndtvndv.term.service.isAllowedCaller

/**
 * Serves [AgentStates] to the Keyarchy launcher, live (it observes [uri]), one row per pane with a shortcut: the
 * shortcut's id, the status, whether it is active and since when. Only Keyarchy may read it, by the UID Android stamps
 * on the call; any other caller gets a [SecurityException].
 */
class AgentStateProvider : ContentProvider() {
    override fun onCreate() = true

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?,
                       sortOrder: String?): Cursor {
        val packages = context?.packageManager?.getPackagesForUid(Binder.getCallingUid())
        if (!isAllowedCaller(packages)) throw SecurityException("Only Keyarchy may read the agents of Termarchy")
        return MatrixCursor(COLUMNS).apply {
            AgentStates.all().forEach { addRow(arrayOf<Any>(it.shortcutId, it.status, if (it.active) 1 else 0, it.since)) }
        }
    }

    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException()

    companion object {
        private val COLUMNS = arrayOf("id", "status", "active", "since")

        /** What Keyarchy reads and observes: content://<package>.agents/agents. */
        fun uri(context: Context): Uri = Uri.parse("content://${context.packageName}.agents/agents")

        /** Tells the observers (Keyarchy) that the states changed. */
        fun notifyChanged(context: Context) = context.contentResolver.notifyChange(uri(context), null)
    }
}
