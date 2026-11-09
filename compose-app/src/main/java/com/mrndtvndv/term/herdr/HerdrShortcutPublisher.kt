package com.mrndtvndv.term.herdr

import android.content.Context
import android.content.Intent
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.drawable.Icon
import android.os.Build
import android.os.PersistableBundle
import android.util.Log
import com.mrndtvndv.term.MainActivity
import com.mrndtvndv.term.R
import com.mrndtvndv.term.ui.notification.NotificationDestination
import com.mrndtvndv.term.ui.notification.NotificationDestinationCodec

/**
 * Keeps one launcher shortcut per pane of a server's Herdr session, opening the app on that pane (connecting first,
 * as a notification tap does): the most recent ones, as many as the launcher takes, named by their Herdr tab. Each one
 * tells a launcher how recent it is in its extras: [EXTRA_TIME], when its agent last changed state, and
 * [EXTRA_ACTIVE] while it works or waits for input (as recent as now). A shortcut is updated only when what it shows
 * or tells changes. Panes gone from the session lose their shortcut; disconnecting keeps them, so they still open the
 * session later.
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
                val same = shown != null && shown.shortLabel?.toString() == label &&
                    shown.longLabel?.toString() == longLabel(shortcut) && shown.rank == rank &&
                    shown.extras?.getLong(EXTRA_TIME, -1L) == shortcut.since &&
                    shown.extras?.getBoolean(EXTRA_ACTIVE, false) == shortcut.active
                if (same) null else shortcut to info(shortcut, label, rank)
            }
            val added = infos.filter { (shortcut, _) -> shortcut.id !in ours }.map { it.second }
            val updated = infos.filter { (shortcut, _) -> shortcut.id in ours }.map { it.second }
            if (added.isNotEmpty()) manager.addDynamicShortcuts(added)
            if (updated.isNotEmpty()) manager.updateShortcuts(updated)
        }.onFailure { Log.w(TAG, "Could not publish the Herdr shortcuts of $serverId", it) }
    }

    /** The agent's status in a few words, or the pane's folder when it has no agent. */
    private fun longLabel(shortcut: HerdrPaneShortcut): String = when (shortcut.status) {
        HerdrAgentStatus.Working -> context.getString(R.string.herdr_shortcut_working)
        HerdrAgentStatus.Blocked -> context.getString(R.string.herdr_shortcut_blocked)
        HerdrAgentStatus.Done -> context.getString(R.string.herdr_shortcut_done)
        HerdrAgentStatus.Idle -> context.getString(R.string.herdr_shortcut_idle)
        else -> shortcut.folder.ifEmpty { shortcut.title }
    }

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
            .setExtras(
                PersistableBundle().apply {
                    putLong(EXTRA_TIME, shortcut.since)
                    putBoolean(EXTRA_ACTIVE, shortcut.active)
                },
            )
            .build()
    }

    companion object {
        private const val TAG = "HerdrShortcuts"
        const val ACTION_OPEN_HERDR_TERMINAL = "com.mrndtvndv.term.OPEN_HERDR_TERMINAL"

        /** When the pane's agent last changed state, wall-clock millis (0 when unknown), for Keyarchy's order. */
        const val EXTRA_TIME = "org.ferrao.homekey.time"

        /** True while the pane's agent works or waits for input: as recent as now. */
        const val EXTRA_ACTIVE = "org.ferrao.homekey.active"
    }
}
