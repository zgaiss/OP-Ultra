package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.RouteGuidanceState
import com.shilapi.xcertplay.iap2.message.RouteGuidanceSummary

/** Read-only process-local view; Activity changes do not reset an active phone route. */
object CarPlayRouteGuidanceView {
    private var owner: Any? = null
    private var state: RouteGuidanceState? = null
    @Synchronized fun attach(controller: Any, guidance: RouteGuidanceState) { owner = controller; state = guidance }
    @Synchronized fun detach(controller: Any) { if (owner === controller) { owner = null; state = null } }
    @JvmStatic @Synchronized fun current(): RouteGuidanceSummary = state?.snapshot() ?: RouteGuidanceSummary()
    @JvmStatic @Synchronized fun destinationStatus(): String = state?.destinationStatus() ?: "inactive"
}
