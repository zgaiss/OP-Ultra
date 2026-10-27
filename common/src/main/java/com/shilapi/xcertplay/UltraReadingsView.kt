package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.View
import android.widget.LinearLayout
import android.widget.TextView
import com.heavendoor.ultralab.UltraEngine
import kotlin.math.roundToInt

/** Presentation of already-received data. Hidden categories perform no polling or reads. */
internal class UltraReadingsView(context: Context, private val statusOnly: Boolean = false) : LinearLayout(context) {
    private data class Reading(val key: String, val label: String)
    private val groups = listOf(
        "车辆与行程" to listOf(Reading("speed", "车速"), Reading("gear", "挡位"), Reading("soc", "电量"),
            Reading("range", "WLTP 续航"), Reading("trip", "本次行程"), Reading("sinceCharge", "充电后里程"),
            Reading("duration", "行程时间"), Reading("average", "平均能耗"), Reading("total", "总里程"),
            Reading("power", "电池功率"), Reading("outside", "空调外温")),
        "轮胎与车门" to listOf(Reading("tyre0", "左前胎压 / 胎温"), Reading("tyre1", "右前胎压 / 胎温"),
            Reading("tyre2", "左后胎压 / 胎温"), Reading("tyre3", "右后胎压 / 胎温"),
            Reading("door0", "左前门"), Reading("door1", "右前门"), Reading("door2", "左后门"), Reading("door3", "右后门"), Reading("bonnet", "前机盖")),
        "CarPlay 导航" to listOf(Reading("destination", "目的地"), Reading("distance", "剩余路程"),
            Reading("remaining", "剩余时间"), Reading("arrival", "预计到达"), Reading("nextTurn", "下一转向距离")),
        "音乐" to listOf(Reading("song", "歌曲"), Reading("artist", "歌手"), Reading("progress", "播放进度"), Reading("cover", "专辑封面")),
    )
    private val main = Handler(Looper.getMainLooper())
    private val values = linkedMapOf<String, TextView>()
    private var scale = 1f
    private var columns = 0
    private var active = false
    internal var refreshCount = 0L
        private set
    private val tick = object : Runnable {
        override fun run() {
            if (!active || !isShown || !isAttachedToWindow) return
            refresh()
            main.postDelayed(this, 1000)
        }
    }

    init { orientation = VERTICAL; rebuild(if (statusOnly) 1 else 2) }
    fun presentationScale(value: Float) {
        if (scale == value) return
        scale = value
        rebuild(columns.coerceAtLeast(1))
    }
    private fun u(n: Int) = (n * scale).roundToInt()
    private fun label(value: String, size: Float, color: Int) = TextView(context).apply {
        text = value; setTextSize(TypedValue.COMPLEX_UNIT_PX, size * scale); setTextColor(color)
    }
    private fun rebuild(count: Int) {
        columns = count; removeAllViews(); values.clear()
        val sections = if (statusOnly) listOf("" to listOf(Reading("reader", "车况读取"), Reading("output", "仪表输出"))) else groups
        for ((name, fields) in sections) {
            if (name.isNotEmpty()) addView(label(name, 14f, SECONDARY), LayoutParams(-1, -2).apply { topMargin = if (childCount == 0) 0 else u(22); bottomMargin = u(12) })
            for (part in fields.chunked(count)) {
                val row = LinearLayout(context).apply { orientation = HORIZONTAL }
                addView(row, LayoutParams(-1, -2).apply { bottomMargin = u(10) })
                for ((index, field) in part.withIndex()) {
                    val tile = LinearLayout(context).apply {
                        orientation = VERTICAL
                        setPadding(u(14), u(13), u(14), u(13))
                        background = GradientDrawable().apply { cornerRadius = u(9).toFloat(); setColor(Color.rgb(21, 23, 26)) }
                    }
                    row.addView(tile, LayoutParams(0, -2, 1f).apply { if (index > 0) marginStart = u(10) })
                    tile.addView(label(field.label, 12f, SECONDARY))
                    val value = label("—", if (statusOnly) 17f else 20f, PRIMARY).apply {
                        typeface = Typeface.create(if (statusOnly) "sans-serif" else "sans-serif-medium", Typeface.NORMAL)
                        maxLines = if (statusOnly) 2 else 1
                        ellipsize = TextUtils.TruncateAt.END
                        setPadding(0, u(8), 0, 0)
                    }
                    values[field.key] = value; tile.addView(value, LayoutParams(-1, -2))
                }
                repeat(count - part.size) { row.addView(View(context), LayoutParams(0, 1, 1f).apply { marginStart = u(10) }) }
            }
        }
        refresh()
    }
    private fun refresh() {
        refreshCount++
        val engine = UltraEngine.peek()
        val next = if (statusOnly) mapOf("reader" to (engine?.status ?: "尚未启动车况读取"), "output" to outputStatus(engine))
            else engine?.dashboard?.readings().orEmpty()
        for ((key, view) in values) {
            val value = next[key]?.takeIf { it.isNotBlank() } ?: "—"
            if (view.text.toString() != value) view.text = value
            view.contentDescription = value
        }
    }
    private fun outputStatus(engine: UltraEngine?): String {
        if (engine == null || !engine.started()) return "已停止"
        if (!engine.projectionEnabled()) return "仪表显示已关闭"
        return when (engine.projectionStatus().substringBefore(';')) {
            "projecting" -> "正在投放到仪表"
            "waiting_for_canvas" -> "等待新画面 · 原车显示中"
            "preparing_receiver" -> "正在准备仪表显示"
            "restoring_oem" -> "正在恢复原车画面"
            "retry_wait" -> "等待仪表通道恢复"
            "closed", "stopped" -> "已停止"
            else -> "等待 CarPlay 独立地图"
        }
    }
    override fun onMeasure(w: Int, h: Int) {
        val available = MeasureSpec.getSize(w) / scale
        val count = if (statusOnly || available < 560) 1 else if (available >= 800) 3 else 2
        if (columns != count) rebuild(count)
        super.onMeasure(w, h)
    }
    override fun onVisibilityAggregated(visible: Boolean) {
        super.onVisibilityAggregated(visible); active = visible
        main.removeCallbacks(tick)
        if (visible && isAttachedToWindow) main.post(tick)
    }
    override fun onAttachedToWindow() { super.onAttachedToWindow(); active = isShown; main.removeCallbacks(tick); if (active) main.post(tick) }
    override fun onDetachedFromWindow() { active = false; main.removeCallbacks(tick); super.onDetachedFromWindow() }
    companion object { private val PRIMARY = Color.rgb(238, 238, 240); private val SECONDARY = Color.rgb(170, 173, 181) }
}
