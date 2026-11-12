package com.mrndtvndv.term.skiff

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.mrndtvndv.term.MainActivity
import com.mrndtvndv.term.R
import com.mrndtvndv.term.herdr.AgentState
import com.mrndtvndv.term.herdr.AgentStateProvider
import com.mrndtvndv.term.herdr.AgentStates
import com.mrndtvndv.term.herdr.HerdrShortcutPublisher
import com.mrndtvndv.term.ui.notification.NotificationDestination
import com.mrndtvndv.term.ui.notification.NotificationDestinationCodec

/**
 * Keeps one launcher shortcut per skiff session of a server outside a folded space, opening the app on that session's
 * tab (connecting first), as [HerdrShortcutPublisher] does for Herdr's panes: the most recent ones, as many as the
 * launcher takes. The state of their agents goes to [AgentStates], as Herdr's states (asking as blocked, ready as
 * done), and the shortcuts are updated only when their name changes. Sessions ended or folded lose their shortcut;
 * disconnecting keeps them.
 */
class SkiffShortcutPublisher(private val context: Context, private val serverId: String) {
    fun publish(sessions: List<SkiffSession>, collapsed: Set<String>) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            val now = System.currentTimeMillis()
            val existing = manager.dynamicShortcuts
            val prefix = skiffShortcutId(serverId, "")
            val ours = existing.filter { it.id.startsWith(prefix) }.associateBy { it.id }
            val budget = manager.maxShortcutCountPerActivity - (existing.size - ours.size)
            val chosen = sessions
                .filter { it.space == null || it.space !in collapsed }
                .sortedByDescending { if (it.active) now else it.since ?: 0L }
                .take(budget.coerceAtLeast(0))
            val gone = ours.keys - chosen.map { skiffShortcutId(serverId, it.name) }.toSet()
            if (gone.isNotEmpty()) manager.removeDynamicShortcuts(gone.toList())
            val infos = chosen.mapIndexedNotNull { rank, session ->
                val id = skiffShortcutId(serverId, session.name)
                val shown = ours[id]
                // the state and the order change all the time: only a new name is worth one of the few updates
                val same = shown != null && shown.shortLabel?.toString() == session.shownName &&
                    shown.longLabel?.toString() == longLabel(session)
                if (same) null else id to info(id, session, rank)
            }
            val added = infos.filter { (id, _) -> id !in ours }.map { it.second }
            val updated = infos.filter { (id, _) -> id in ours }.map { it.second }
            if (added.isNotEmpty()) manager.addDynamicShortcuts(added)
            if (updated.isNotEmpty()) manager.updateShortcuts(updated)
            val states = chosen.map { session ->
                AgentState(skiffShortcutId(serverId, session.name), session.herdrStatus, session.active, session.since ?: 0L)
            }
            if (AgentStates.set(statesKey(), states)) AgentStateProvider.notifyChanged(context)
        }.onFailure { Log.w(TAG, "Could not publish the skiff shortcuts of $serverId", it) }
    }

    /** On disconnecting: the states of this server's sessions are no longer known (the shortcuts stay). */
    fun forget() {
        if (AgentStates.set(statesKey(), emptyList())) AgentStateProvider.notifyChanged(context)
    }

    /** Apart from the server's Herdr states in [AgentStates]. */
    private fun statesKey() = "skiff:$serverId"

    /** The session's space, or its zmx name without one: what changes little. */
    private fun longLabel(session: SkiffSession): String = session.space ?: session.name

    private fun info(id: String, session: SkiffSession, rank: Int): ShortcutInfo {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(HerdrShortcutPublisher.ACTION_OPEN_HERDR_TERMINAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        NotificationDestinationCodec.encode(NotificationDestination.SkiffSession(serverId, session.name))
            .forEach { (key, value) -> intent.putExtra(key, value) }
        return ShortcutInfo.Builder(context, id)
            .setShortLabel(session.shownName)
            .setLongLabel(longLabel(session))
            .setIcon(Icon.createWithResource(context, R.drawable.ic_shortcut_terminal))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    private companion object {
        const val TAG = "SkiffShortcuts"
    }
}

/** Stable across reconnects: the zmx name never changes, and the server ID keeps servers apart. */
fun skiffShortcutId(serverId: String, name: String): String = "skiff:$serverId:$name"

/** Working or asking: as recent as now, for as long as it lasts. */
private val SkiffSession.active: Boolean
    get() = state == SkiffSessionState.Working || state == SkiffSessionState.Asking

/** Herdr's name of the state, as Keyarchy reads the agents: asking is blocked, ready is done. */
private val SkiffSession.herdrStatus: String
    get() = when (state) {
        SkiffSessionState.Idle -> "idle"
        SkiffSessionState.Working -> "working"
        SkiffSessionState.Asking -> "blocked"
        SkiffSessionState.Ready -> "done"
    }
