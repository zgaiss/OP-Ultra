package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.catalog.Iap2Endpoints
import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.Locale

/** Route-guidance metadata requests. The rendering rule lives in SecondaryMapNavigationState. */
object ExperimentalRouteGuidanceRequests {
    const val COMPONENT_ID = 42

    fun addComponent(identification: Iap2Frame): Iap2Frame {
        require(identification.messageId == Iap2Endpoints.IDENTIFICATION_INFORMATION.id)
        require(!identification.body().has(30)) { "RouteGuidance component is already present" }
        val component = Iap2Messages.buildRaw(identification.messageId) {
            group(30) {
                u16(0, COMPONENT_ID)
                string(1, "RouteGuidance")
                u16(2, 64)
                u16(4, 64)
                u16(6, 8)
            }
        }
        return Iap2Frame(identification.messageId, identification.payload + component.payload)
    }

    fun startUpdates(): Iap2Frame = Iap2Messages.build(Iap2Endpoints.START_ROUTE_GUIDANCE_UPDATES) {
        u16(0, COMPONENT_ID)
        void(1)
        void(2)
    }

    fun traceSummary(message: String, enabled: Boolean): String {
        if (!enabled || !message.startsWith("IAP2 RX [")) return message
        val header = message.substringBefore('\n')
        return if (listOf(" 0x5201", " 0x5202", " 0x5204").any { header.contains(it) })
            "$header [navigation body omitted in source probe]" else message
    }
}

/**
 * Narrow parser for navigation source and lifecycle only. Never retains roads, destination or GPS.
 * Suggested source-name aliases below are synthetic research fixtures, not P7 capture evidence.
 */
class ExperimentalNavigationSource(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val staleMillis: Long = 8_000,
) {
    enum class App { AMAP, APPLE_MAPS, BAIDU_MAPS, UNKNOWN }
    enum class FramingHint { AMAP_NAVIGATION, CENTERED, UNCONFIRMED }
    data class Snapshot(val sourceName: String?, val app: App, val routeState: Int?, val supportsGuidance: Boolean?, val fresh: Boolean,
        val amapNavigationStableMillis: Long = 0)

    private var sourceName: String? = null
    private var routeState: Int? = null
    private var supportsGuidance: Boolean? = null
    private var updatedAt: Long? = null
    private var amapNavigationSince: Long? = null

    @Synchronized fun accept(frame: Iap2Frame): Boolean {
        if (frame.messageId != 0x5201) return false
        val parameters = try { Iap2ParameterList.parse(frame.payload).asList() } catch (_: Exception) { return false }
        if (parameters.isEmpty()) return false
        val relevant = parameters.filter { it.id in setOf(1, 0x13, 0x14) }
        if (relevant.groupBy { it.id }.any { it.value.size != 1 }) return false
        var nextSource: String? = null
        var hasSource = false
        var nextState: Int? = null
        var nextSupport: Boolean? = null
        try {
            for (parameter in relevant) {
                val data = parameter.payload
                when (parameter.id) {
                    1 -> { require(data.size == 1); nextState = data[0].toInt() and 0xff }
                    0x13 -> { hasSource = true; nextSource = decodeSource(data) }
                    0x14 -> { require(data.size == 1 && data[0].toInt() in 0..1); nextSupport = data[0].toInt() == 1 }
                }
            }
        } catch (_: Exception) { return false }
        val previouslyAmapNavigation = classify(sourceName) == App.AMAP && routeState == 1
        // Never apply the previous application's route state to a newly named source.
        val previousFresh = updatedAt?.let { nowMillis() - it in 0..staleMillis } == true
        if (hasSource && nextSource != sourceName && (sourceName != null || !previousFresh)) {
            routeState = null
            supportsGuidance = null
        }
        if (hasSource) sourceName = nextSource
        if (nextState != null) routeState = nextState
        if (nextSupport != null) supportsGuidance = nextSupport
        val nowAmapNavigation = classify(sourceName) == App.AMAP && routeState == 1
        if (!nowAmapNavigation) amapNavigationSince = null
        else if (!previouslyAmapNavigation || amapNavigationSince == null) amapNavigationSince = nowMillis()
        updatedAt = nowMillis()
        return true
    }

    @Synchronized fun clear() {
        sourceName = null; routeState = null; supportsGuidance = null; updatedAt = null
        amapNavigationSince = null
    }

    @Synchronized fun snapshot(): Snapshot {
        val age = updatedAt?.let { nowMillis() - it }
        return Snapshot(sourceName, classify(sourceName), routeState, supportsGuidance, age != null && age in 0..staleMillis,
            amapNavigationSince?.let { (nowMillis() - it).coerceAtLeast(0) } ?: 0)
    }

    @Synchronized fun framingHint(): FramingHint {
        val state = snapshot()
        if (!state.fresh) return FramingHint.UNCONFIRMED
        if (state.sourceName == "" || state.routeState == 0 || state.routeState == 2) return FramingHint.CENTERED
        if (state.supportsGuidance == false || state.routeState !in setOf(1,3,4,5,6)) return FramingHint.UNCONFIRMED
        return when (state.app) {
            App.AMAP -> FramingHint.AMAP_NAVIGATION
            App.APPLE_MAPS, App.BAIDU_MAPS -> FramingHint.CENTERED
            App.UNKNOWN -> FramingHint.UNCONFIRMED
        }
    }

    private fun decodeSource(data: ByteArray): String {
        require(data.size <= 128)
        val zero = data.indexOf(0.toByte()).let { if (it < 0) data.size else it }
        require(data.drop(zero).all { it == 0.toByte() })
        val name = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data,0,zero)).toString().trim()
        require(name.none { it.isISOControl() })
        return name
    }

    private fun classify(name: String?): App = when (name?.lowercase(Locale.ROOT)) {
        "高德地图", "高德导航", "amap", "autonavi", "gaode" -> App.AMAP
        "apple maps", "apple 地图", "苹果地图" -> App.APPLE_MAPS
        "百度地图", "baidu maps" -> App.BAIDU_MAPS
        else -> App.UNKNOWN
    }
}
