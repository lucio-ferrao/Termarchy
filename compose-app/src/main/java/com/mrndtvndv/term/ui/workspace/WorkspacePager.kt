package com.mrndtvndv.term.ui.workspace

import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerDefaults
import androidx.compose.foundation.pager.PagerSnapDistance
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.unit.Velocity
import com.mrndtvndv.term.CrashBreadcrumbs
import com.mrndtvndv.term.workspace.Tab
import kotlin.math.abs
import kotlinx.coroutines.launch

/**
 * Pages [tabs] horizontally. [currentTabId] is the source of truth; the pager is a view of it
 * and reports user-driven page changes through [onSelectTab]. [empty] is shown instead of pages when there are no tabs.
 * [leading], when given, is one more page left of the first tab, which selects no tab: it gets whether the pager rests
 * on it, and a function that goes back to the current tab. Selecting another tab from it is enough: the pager follows.
 */
@Composable
fun WorkspacePager(
    tabs: List<Tab>,
    currentTabId: String?,
    onSelectTab: (Tab) -> Unit,
    empty: @Composable () -> Unit,
    modifier: Modifier = Modifier,
    leading: (@Composable (shown: Boolean, showCurrent: () -> Unit) -> Unit)? = null,
    content: @Composable (tab: Tab, isActive: Boolean) -> Unit,
) {
    // the pages before the first tab
    val offset = if (leading != null) 1 else 0
    val pagerState = rememberPagerState(
        initialPage = tabs.indexOfFirst { it.id == currentTabId }.coerceAtLeast(0) + offset,
        pageCount = { tabs.size + offset }
    )
    val scope = rememberCoroutineScope()
    val flingBehavior = PagerDefaults.flingBehavior(
        state = pagerState,
        pagerSnapDistance = PagerSnapDistance.atMost(1),
    )

    val pageNestedScrollConnection = rememberHorizontalDominantConnection(pagerState)

    PagerSyncEffects(pagerState, tabs, offset, currentTabId, onSelectTab)

    Scaffold(
        modifier = modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
    ) { paddingValues ->
        val pageModifier = Modifier
            .fillMaxSize()
            .padding(paddingValues)
        if (tabs.isEmpty()) {
            Box(modifier = pageModifier) { empty() }
            return@Scaffold
        }
        HorizontalPager(
            state = pagerState,
            flingBehavior = flingBehavior,
            pageNestedScrollConnection = pageNestedScrollConnection,
            // Stable identity per tab, not per index, so moved pages survive
            // list-instance changes without teardown. Keys must be Bundle-storable:
            // Lazy layouts route page keys through SaveableStateProvider, which crashes
            // on arbitrary objects.
            key = { index -> if (index < offset) "leading" else tabs.getOrNull(index - offset)?.id ?: "page:$index" },
            modifier = pageModifier.clipToBounds(),
        ) { page ->
            if (page < offset) {
                leading?.invoke(pagerState.settledPage == page && !pagerState.isScrollInProgress) {
                    // sliding in from the right, as a swipe would
                    val index = tabs.indexOfFirst { it.id == currentTabId }
                    if (index >= 0) scope.launch { pagerState.animateScrollToPage(index + offset) }
                }
                return@HorizontalPager
            }
            tabs.getOrNull(page - offset)?.let { tab ->
                val isActive = tab.id == currentTabId &&
                    pagerState.settledPage == page &&
                    !pagerState.isScrollInProgress
                content(tab, isActive)
            }
        }
    }
}

@Composable
private fun rememberHorizontalDominantConnection(pagerState: PagerState): NestedScrollConnection {
    // pageNestedScrollConnection is @Composable in foundation 1.11+, so it must be
    // called from the composable body, not inside remember {} or the object expression.
    val defaultPagerNestedScrollConnection = PagerDefaults.pageNestedScrollConnection(
        pagerState,
        Orientation.Horizontal
    )
    // Include the default connection as a remember key: it is a @Composable return
    // value that can change across recompositions. Capturing only pagerState would
    // pin a stale delegate that forwards flings to detached scroll state mid-layout.
    return remember(pagerState, defaultPagerNestedScrollConnection) {
        HorizontalDominantScrollConnection(defaultPagerNestedScrollConnection)
    }
}

@Composable
private fun PagerSyncEffects(
    pagerState: PagerState,
    tabs: List<Tab>,
    offset: Int,
    currentTabId: String?,
    onSelectTab: (Tab) -> Unit,
) {
    val currentTabPage = tabs.indexOfFirst { it.id == currentTabId }.let { if (it < 0) it else it + offset }
    val latestTabs by rememberUpdatedState(tabs)
    val latestCurrentTabId by rememberUpdatedState(currentTabId)

    // The only place that moves the pager to the active tab (tab taps, notifications, closes).
    // Keyed on the tab and its index, not the tab list: the list changes on every model update (an SFTP
    // tab saving its dir), which must not restart these effects. A tab chosen on a leading page can land on the index
    // the current one had, so the tab is a key too. Skipping when already
    // at/heading to the page avoids re-animating, which replaces pager nodes mid-layout.
    // Swipes move the pager themselves; this only follows the app's own changes, and goes straight there: a page
    // becomes active, and opens its shell, only once the pager settles, and a list usually covers the pager anyway.
    var followedTabId by remember { mutableStateOf(currentTabId) }
    LaunchedEffect(currentTabId, currentTabPage) {
        val sameTab = followedTabId == currentTabId
        followedTabId = currentTabId
        if (currentTabPage < 0 || pagerState.targetPage == currentTabPage) return@LaunchedEffect
        // on a leading page, the same tab only moving (another one listed before it) does not take the pager away
        if (sameTab && pagerState.targetPage < offset) return@LaunchedEffect
        // a tab chosen on a leading page slides in from the right, as a swipe would
        if (pagerState.targetPage < offset) {
            pagerState.animateScrollToPage(currentTabPage)
        } else {
            pagerState.scrollToPage(currentTabPage)
        }
    }

    // Tabs can be closed while the pager sits on the removed page. Snap to a valid page
    // immediately; leaving currentPage out of bounds breaks the pager's layout.
    LaunchedEffect(tabs.size) {
        if (tabs.isNotEmpty() && pagerState.currentPage >= tabs.size + offset) {
            pagerState.scrollToPage(currentTabPage.coerceIn(offset, tabs.lastIndex + offset))
        }
    }

    // Propagates a user swipe to the active tab.
    // Also records a breadcrumb for crash reports (see CrashBreadcrumbs).
    // Keyed on settledPage alone (the idle page): re-running on a tab-list change while a
    // tap's scroll is still in flight would select the old page and undo the tap.
    LaunchedEffect(pagerState.settledPage) {
        // a leading page selects no tab
        val tab = latestTabs.getOrNull(pagerState.settledPage - offset)
        CrashBreadcrumbs.setWorkspace(
            tab = tab?.typeName ?: "none",
            page = pagerState.settledPage,
            currentPage = pagerState.currentPage,
            tabCount = latestTabs.size,
        )
        if (tab != null && tab.id != latestCurrentTabId) {
            onSelectTab(tab)
        }
    }
}

private val Tab.typeName: String
    get() = when (this) {
        is Tab.Terminal -> "Terminal"
        is Tab.Sftp -> "SFTP"
        is Tab.Git -> "Git"
        is Tab.Viewer -> "Viewer"
    }

/** Lets the pager react only to clearly horizontal gestures so vertical scrolling inside pages is untouched. */
private class HorizontalDominantScrollConnection(
    private val delegate: NestedScrollConnection,
) : NestedScrollConnection {
    private fun isHorizontal(x: Float, y: Float) = abs(x) > abs(y) * HORIZONTAL_DOMINANCE

    override fun onPreScroll(available: Offset, source: NestedScrollSource): Offset =
        if (isHorizontal(available.x, available.y)) delegate.onPreScroll(available, source) else Offset.Zero

    override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset =
        if (isHorizontal(available.x, available.y)) {
            delegate.onPostScroll(consumed, available, source)
        } else {
            Offset.Zero
        }

    override suspend fun onPreFling(available: Velocity): Velocity =
        if (isHorizontal(available.x, available.y)) delegate.onPreFling(available) else Velocity.Zero

    override suspend fun onPostFling(consumed: Velocity, available: Velocity): Velocity =
        if (isHorizontal(available.x, available.y)) delegate.onPostFling(consumed, available) else Velocity.Zero

    private companion object {
        const val HORIZONTAL_DOMINANCE = 1.5f
    }
}
