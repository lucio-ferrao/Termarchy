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
 * as a notification tap does). A shortcut is updated only when its title or agent status changes, so its last-change
 * time tells a launcher which sessions moved last. Panes gone from the session lose their shortcut; disconnecting
 * keeps them, so they still open the session later.
 */
class HerdrShortcutPublisher(
    private val context: Context,
    private val serverId: String,
    private val socketPath: String,
    /** True with only one remote server saved (see [paneTitle]). */
    private val singleServer: () -> Boolean,
) {
    fun publish(snapshot: HerdrSnapshot) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N_MR1) return
        val manager = context.getSystemService(ShortcutManager::class.java) ?: return
        runCatching {
            val now = System.currentTimeMillis()
            val existing = manager.dynamicShortcuts
            val prefix = herdrShortcutId(serverId, "")
            val ours = existing.filter { it.id.startsWith(prefix) }.associateBy { it.id }
            val single = singleServer()
            val shortcuts = snapshot.panes.map { herdrPaneShortcut(serverId, it, single) }
            val changed = shortcuts.filter { shortcut ->
                val shown = ours[shortcut.id] ?: return@filter true
                shown.shortLabel?.toString() != shortcut.shortLabel || shown.longLabel?.toString() != longLabel(shortcut)
            }.map { it.id }.toSet()
            val changedAt = shortcuts.associate {
                it.terminalId to if (it.id in changed) now else ours.getValue(it.id).lastChangedTimestamp
            }
            val chosen = chooseHerdrShortcuts(shortcuts, changedAt, manager.maxShortcutCountPerActivity - (existing.size - ours.size))
            val chosenIds = chosen.map { it.id }.toSet()
            val gone = ours.keys - chosenIds
            if (gone.isNotEmpty()) manager.removeDynamicShortcuts(gone.toList())
            val infos = chosen.mapIndexedNotNull { rank, shortcut ->
                if (shortcut.id in ours && shortcut.id !in changed) null else shortcut to info(shortcut, rank)
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
        else -> shortcut.folder.ifEmpty { shortcut.shortLabel }
    }

    private fun info(shortcut: HerdrPaneShortcut, rank: Int): ShortcutInfo {
        val intent = Intent(context, MainActivity::class.java)
            .setAction(ACTION_OPEN_HERDR_TERMINAL)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        NotificationDestinationCodec.encode(NotificationDestination.HerdrPane(serverId, socketPath, shortcut.terminalId))
            .forEach { (key, value) -> intent.putExtra(key, value) }
        return ShortcutInfo.Builder(context, shortcut.id)
            .setShortLabel(shortcut.shortLabel)
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
