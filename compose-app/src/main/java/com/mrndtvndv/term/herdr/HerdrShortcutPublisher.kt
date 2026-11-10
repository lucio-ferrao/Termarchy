package com.mrndtvndv.term.herdr

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.util.Log
import com.mrndtvndv.term.MainActivity
import com.mrndtvndv.term.R
import com.mrndtvndv.term.ui.notification.NotificationDestination
import com.mrndtvndv.term.ui.notification.NotificationDestinationCodec

/**
 * Keeps one launcher shortcut per pane of a server's Herdr session, opening the app on that pane (connecting first,
 * as a notification tap does): the most recent ones, as many as the launcher takes, named by their Herdr tab. The
 * state of their agents goes to [AgentStates] (read by Keyarchy, live) and not into the shortcuts: Android lets an app
 * in the background update its shortcuts only 10 times a day, so a shortcut is updated only when its name changes.
 * Panes gone from the session lose their shortcut; disconnecting keeps them, so they still open the session later.
 */
class HerdrShortcutPublisher(
    private val context: Context,
    private val serverId: String,
    private val socketPath: String,
    /** True with only one remote server saved (see [paneTitle]). */
    private val singleServer: () -> Boolean,
) {
    /**
     * For a Herdr that does not say when an agent's state changed: the status of each terminal and when this app saw
     * it change (0 when it was already so when first seen).
     */
    private val observed = mutableMapOf<String, Pair<HerdrAgentStatus, Long>>()

    fun publish(snapshot: HerdrSnapshot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            val now = System.currentTimeMillis()
            val existing = manager.dynamicShortcuts
            val prefix = herdrShortcutId(serverId, "")
            val ours = existing.filter { it.id.startsWith(prefix) }.associateBy { it.id }
            val single = singleServer()
            val tabs = snapshot.tabs.associate { it.id to it.label }
            val shortcuts = snapshot.panes.map { pane ->
                val seen = observed[pane.terminalId]
                if (seen?.first != pane.agentStatus) observed[pane.terminalId] = pane.agentStatus to (if (seen == null) 0L else now)
                herdrPaneShortcut(serverId, pane, tabs[pane.tabId], single, observed.getValue(pane.terminalId).second)
            }
            observed.keys.retainAll(snapshot.panes.map { it.terminalId }.toSet())
            val chosen = chooseHerdrShortcuts(shortcuts, now, manager.maxShortcutCountPerActivity - (existing.size - ours.size))
            val labels = herdrShortcutLabels(chosen)
            val gone = ours.keys - chosen.map { it.id }.toSet()
            if (gone.isNotEmpty()) manager.removeDynamicShortcuts(gone.toList())
            val infos = chosen.mapIndexedNotNull { rank, shortcut ->
                val label = labels.getValue(shortcut.id)
                val shown = ours[shortcut.id]
                // the state and the order change all the time: only a new name is worth one of the few updates
                val same = shown != null && shown.shortLabel?.toString() == label &&
                    shown.longLabel?.toString() == longLabel(shortcut)
                if (same) null else shortcut to info(shortcut, label, rank)
            }
            val added = infos.filter { (shortcut, _) -> shortcut.id !in ours }.map { it.second }
            val updated = infos.filter { (shortcut, _) -> shortcut.id in ours }.map { it.second }
            if (added.isNotEmpty()) manager.addDynamicShortcuts(added)
            if (updated.isNotEmpty()) manager.updateShortcuts(updated)
            if (AgentStates.set(serverId, chosen.map { it.agentState() })) AgentStateProvider.notifyChanged(context)
        }.onFailure { Log.w(TAG, "Could not publish the Herdr shortcuts of $serverId", it) }
    }

    /** On disconnecting: the states of this server's agents are no longer known (the shortcuts stay). */
    fun forget() {
        observed.clear()
        if (AgentStates.set(serverId, emptyList())) AgentStateProvider.notifyChanged(context)
    }

    /** The pane's folder, or its title when the folder is unknown: what changes little. */
    private fun longLabel(shortcut: HerdrPaneShortcut): String = shortcut.folder.ifEmpty { shortcut.title }

    private fun info(shortcut: HerdrPaneShortcut, label: String, rank: Int): ShortcutInfo {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_HERDR_TERMINAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        NotificationDestinationCodec.encode(NotificationDestination.HerdrPane(serverId, socketPath, shortcut.terminalId))
            .forEach { (key, value) -> intent.putExtra(key, value) }
        return ShortcutInfo.Builder(context, shortcut.id)
            .setShortLabel(label)
            .setLongLabel(longLabel(shortcut))
            .setIcon(Icon.createWithResource(context, if (shortcut.isClaude) R.drawable.ic_shortcut_claude else R.drawable.ic_shortcut_terminal))
            .setIntent(intent)
            .setRank(rank)
            .build()
    }

    companion object {
        private const val TAG = "HerdrShortcuts"
        const val ACTION_OPEN_HERDR_TERMINAL = "com.mrndtvndv.term.OPEN_HERDR_TERMINAL"
    }
}
