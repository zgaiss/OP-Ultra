package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.wire.Iap2Frame

/** The offset belongs to one control session, never to an Activity or the music app. */
class SecondaryMapNavigationState(
    nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val enableSettleMillis: Long = 300,
) {
    private val source = ExperimentalNavigationSource(nowMillis)
    private var owner: Any? = null

    @Synchronized fun attach(session: Any) {
        if (owner !== session) { source.clear(); owner = session }
    }

    @Synchronized fun detach(session: Any) {
        if (owner === session) { source.clear(); owner = null }
    }

    @Synchronized fun clear() { source.clear(); owner = null }

    @Synchronized fun accept(session: Any, frame: Iap2Frame): ExperimentalNavigationSource.Snapshot? {
        if (owner !== session || !source.accept(frame)) return null
        return source.snapshot()
    }

    @Synchronized fun shouldOffset(): Boolean {
        if (owner == null) return false
        val state = source.snapshot()
        // Exactly the observed AMap + RouteSet(1) condition. Loading/rerouting,
        // unknown sources, route end and another phone never inherit the offset.
        // Missing incremental fields or quiet intervals do not end a known route.
        return state.app == ExperimentalNavigationSource.App.AMAP && state.routeState == 1 &&
            state.amapNavigationStableMillis >= enableSettleMillis
    }
}
