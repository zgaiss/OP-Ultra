package com.shilapi.xcertplay.iap2.message

import com.shilapi.xcertplay.iap2.wire.Iap2Frame
import com.shilapi.xcertplay.iap2.wire.Iap2ParameterList
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction

/** Ephemeral phone-provided route summary. Not persisted or included in diagnostic logs. */
data class RouteGuidanceSummary @JvmOverloads constructor(
    val active: Boolean = false,
    val destinationName: String? = null,
    val remainingSeconds: Long? = null,
    val remainingMeters: Long? = null,
    val arrivalEpochSeconds: Long? = null,
    val nextTurnMeters: Long? = null,
    val turnText: String? = null,
    val turnRoad: String? = null,
    val turnIcon: RouteTurnIcon? = null,
)

/** Incremental 0x5201 fields, owned by exactly one iAP2 control session. */
class RouteGuidanceState(
    private val nowMillis: () -> Long = { System.nanoTime() / 1_000_000L },
    private val staleMillis: Long = 120_000,
) {
    private data class Timed(val value: Long, val at: Long)
    private var owner: Any? = null
    private var source: String? = null
    private var state: Int? = null
    private var supported: Boolean? = null
    private var destination: String? = null
    private var destinationDelivery = "not_received"
    private var seconds: Timed? = null
    private var meters: Timed? = null
    private var arrival: Timed? = null
    private var turn: Timed? = null
    private var updatedAt: Long? = null
    private data class Maneuver(val type: Int, val side: Int, val road: String, val at: Long)
    private val maneuvers = linkedMapOf<Int, Maneuver>()
    private var activeManeuver: Int? = null
    private var emptyManeuversAt: Long? = null

    @Synchronized fun attach(session: Any) { if (owner !== session) { reset(); owner = session } }
    @Synchronized fun detach(session: Any) { if (owner === session) clear() }
    @Synchronized fun clear() { reset(); owner = null }
    private fun clearDetails() { destination = null; destinationDelivery = "not_received"; seconds = null; meters = null; arrival = null; turn = null; maneuvers.clear(); activeManeuver = null; emptyManeuversAt = null }
    private fun reset() { source = null; state = null; supported = null; updatedAt = null; clearDetails() }

    @Synchronized fun accept(session: Any, frame: Iap2Frame): Boolean {
        if (owner !== session) return false
        if (frame.messageId == 0x5202) return acceptManeuver(frame)
        if (frame.messageId != 0x5201) return false
        val fields = try { Iap2ParameterList.parse(frame.payload).asList().filter { it.id in IDS } }
            catch (_: Exception) { return false }
        if (fields.isEmpty() || fields.groupBy { it.id }.any { it.value.size != 1 }) return false
        // Decode the complete update before mutating: a malformed field never partially changes a route.
        val decoded = mutableMapOf<Int, Any>()
        try {
            for (p in fields) decoded[p.id] = when (p.id) {
                1 -> { require(p.payload.size == 1); p.payload[0].toInt() and 255 }
                0x14 -> { require(p.payload.size == 1 && p.payload[0].toInt() in 0..1); p.payload[0].toInt() == 1 }
                4, 0x13 -> decodeString(p.payload, if (p.id == 4) 512 else 128)
                5, 6 -> { require(p.payload.size == 8); ByteBuffer.wrap(p.payload).long }
                0x0d -> { require(p.payload.size <= 64 && p.payload.size % 2 == 0); p.payload }
                else -> { require(p.payload.size == 4); ByteBuffer.wrap(p.payload).int.toLong() and 0xffff_ffffL }
            }
        } catch (_: Exception) { return false }
        val nextSource = decoded[0x13] as? String
        if (nextSource != null && nextSource != source) {
            // Initial source identification may follow the route fields in this same
            // session. Preserve fresh fields in that case. A known source change,
            // stale first identification, or another phone still starts cleanly.
            val fresh = updatedAt?.let { nowMillis() - it in 0..staleMillis } == true
            if (source != null || !fresh) reset()
            source = nextSource
        }
        val hasDestination = decoded.containsKey(4)
        val nextDestination = (decoded[4] as? String)?.takeUnless { it.isBlank() || it.lowercase(java.util.Locale.ROOT) in setOf("未知位置", "未知地点", "未知目的地", "unknown location", "unknown destination") }
        if (hasDestination && nextDestination != destination) clearDetails()
        if (decoded.containsKey(1)) state = decoded[1] as Int
        if (decoded.containsKey(0x14)) supported = decoded[0x14] as Boolean
        val now = nowMillis()
        updatedAt = now
        if (source == "" || state == 0 || state == 2 || supported == false) { clearDetails(); return true }
        if (hasDestination) {
            destination = nextDestination
            destinationDelivery = if (nextDestination != null) "received"
                else if ((decoded[4] as String).isBlank()) "phone_empty" else "phone_placeholder"
        }
        if (decoded.containsKey(5)) arrival = (decoded[5] as Long).takeIf { it in 946684800L..4133980799L }?.let { Timed(it, now) }
        if (decoded.containsKey(6)) seconds = (decoded[6] as Long).takeIf { it in 0..1_209_600L }?.let { Timed(it, now) }
        if (decoded.containsKey(7)) meters = (decoded[7] as Long).takeIf { it in 0..50_000_000L }?.let { Timed(it, now) }
        if (decoded.containsKey(10)) turn = (decoded[10] as Long).takeIf { it in 0..50_000_000L }?.let { Timed(it, now) }
        (decoded[0x0d] as? ByteArray)?.let { list ->
            if (list.isEmpty()) { if (emptyManeuversAt == null) emptyManeuversAt = now }
            else { activeManeuver = ByteBuffer.wrap(list).short.toInt() and 65535; emptyManeuversAt = null }
        }
        return true
    }

    /** 0x5202 maneuver index/type/after-road/driving-side, as received from the phone.
     * Details may precede the active-index list; a different index never inherits
     * the previous direction. No street or instruction is written to a log. */
    private fun acceptManeuver(frame: Iap2Frame): Boolean {
        val fields = try { Iap2ParameterList.parse(frame.payload).asList().filter { it.id in setOf(1,3,4,8) } }
            catch (_: Exception) { return false }
        if (fields.groupBy { it.id }.any { it.value.size != 1 }) return false
        var index: Int? = null; var type: Int? = null; var road = ""; var side = 0
        try { for (p in fields) when (p.id) {
            1 -> { require(p.payload.size == 2); index = ByteBuffer.wrap(p.payload).short.toInt() and 65535 }
            3 -> { require(p.payload.size == 1); type = p.payload[0].toInt() and 255 }
            4 -> road = decodeString(p.payload,512)
            8 -> { require(p.payload.size == 1 && p.payload[0].toInt() in 0..1); side = p.payload[0].toInt() }
        } } catch (_: Exception) { return false }
        val id = index ?: return false; val kind = type ?: return false
        if (maneuvers.size >= 64 && !maneuvers.containsKey(id)) maneuvers.remove(maneuvers.keys.first())
        maneuvers[id] = Maneuver(kind,side,road,nowMillis())
        return true
    }

    private fun turnLabel(type: Int, side: Int): String? = when (type) {
        1,20 -> "左转"; 2,21 -> "右转"; 47 -> "向左急转"; 48 -> "向右急转"
        13,22,49,52 -> "向左前方行驶"; 14,23,50,53 -> "向右前方行驶"
        4,18,19,26 -> if (side == 1) "向右掉头" else "掉头"
        6 -> "进入环岛"; 7 -> "驶出环岛"
        in 28..46 -> "环岛第${type - 27}出口"
        10,12,24,25,27 -> "到达目的地"
        3,5,8,9,11,51 -> "继续直行"
        else -> null
    }

    private fun turnIcon(type: Int, side: Int): RouteTurnIcon? = when(type) {
        1,20 -> RouteTurnIcon.LEFT; 2,21 -> RouteTurnIcon.RIGHT
        47 -> RouteTurnIcon.SHARP_LEFT; 48 -> RouteTurnIcon.SHARP_RIGHT
        13,22,49,52 -> RouteTurnIcon.SLIGHT_LEFT
        14,23,50,53 -> RouteTurnIcon.SLIGHT_RIGHT
        4,18,19,26 -> if(side==1) RouteTurnIcon.U_TURN_RIGHT else RouteTurnIcon.U_TURN_LEFT
        6,7,in 28..46 -> RouteTurnIcon.ROUNDABOUT
        10,12,24,25,27 -> RouteTurnIcon.ARRIVAL
        3,5,8,9,11,51 -> RouteTurnIcon.STRAIGHT
        else -> null
    }

    /** Field availability only; never exports a destination, street or coordinate. */
    @Synchronized fun destinationStatus(): String =
        if (snapshot().active) destinationDelivery else "inactive"

    @Synchronized fun snapshot(): RouteGuidanceSummary {
        val now = nowMillis()
        if (owner == null || updatedAt?.let { now - it in 0..staleMillis } != true || state !in 1..6 || state == 2 || supported == false || source == "") return RouteGuidanceSummary()
        fun Timed?.fresh(): Long? = this?.takeIf { now - it.at in 0..staleMillis }?.value
        val maneuver = activeManeuver?.let { maneuvers[it] }?.takeIf {
            // iPhone can send maneuver details once, long before the turn.
            // Fresh route updates and the active index own their lifetime.
            now >= it.at && (emptyManeuversAt?.let { at -> now - at < 4000 } != false) && state == 1
        }
        return RouteGuidanceSummary(true, destination, seconds.fresh(), meters.fresh(), arrival.fresh(), turn.fresh(),
            maneuver?.let { turnLabel(it.type,it.side) }, maneuver?.road?.takeIf { it.isNotEmpty() },
            maneuver?.let { turnIcon(it.type,it.side) })
    }

    companion object {
        private val IDS = setOf(1, 4, 5, 6, 7, 10, 0x0d, 0x13, 0x14)
        private fun decodeString(data: ByteArray, limit: Int): String {
            require(data.size <= limit)
            val end = data.indexOf(0.toByte()).let { if (it < 0) data.size else it }
            require(data.drop(end).all { it == 0.toByte() })
            val text = Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data, 0, end)).toString().trim()
            require(text.none { it.isISOControl() || it in '\u202a'..'\u202e' || it in '\u2066'..'\u2069' })
            return text
        }
    }
}
