package com.shilapi.xcertplay

import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.heavendoor.ultralab.UltraEngine
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** DEBUG ONLY: the real OP settings screen with explicit demonstration data. */
class UltraSettingsFixtureActivity : CarPlayHostActivity() {
    private val handler = Handler(Looper.getMainLooper())
    private val seedTick = object : Runnable { override fun run() { seed(); handler.postDelayed(this, 500) } }
    private fun field(n: String) = CarPlayHostActivity::class.java.getDeclaredField(n).apply { isAccessible = true }
    private fun request(i: Intent) { i.putExtra("op.ultra.settings", true); i.putExtra("op.settings.category", i.getStringExtra("page") ?: "instrument") }
    override fun requestStartupPrerequisites() = Unit
    override fun onCreate(state: Bundle?) {
        AirPlayPersistence.saveWirelessEnabled(this, false); AirPlayPersistence.saveMfiTarget(this, MfiTarget.I2C); AirPlayPersistence.saveMfiI2cPath(this, "/dev/i2c-1")
        AirPlayPersistence.saveManualHotspotSsid(this, "fixture-ap"); AirPlayPersistence.saveManualHotspotPassphrase(this, ""); AirPlayPersistence.saveManualHotspotSecurity(this, ManualHotspotSecurity.OPEN)
        getSharedPreferences("p7-native-hotspot", 0).edit().clear().apply()
        request(intent); super.onCreate(state)
        handler.postDelayed({ UltraEngine.peek()?.stop(); handler.removeCallbacks(seedTick); handler.post(seedTick); seed(); File(filesDir, "settings-ready.txt").writeText("ready"); if (intent.getBooleanExtra("checks", false)) verify() }, 400)
    }
    override fun onNewIntent(i: Intent) { request(i); super.onNewIntent(i); handler.postDelayed({ UltraEngine.peek()?.stop(); handler.removeCallbacks(seedTick); handler.post(seedTick); seed(); File(filesDir, "settings-ready.txt").writeText("ready") }, 300) }
    private fun seed() {
        val e = UltraEngine.peek() ?: return
        e.status = "本地演示数据 · 未连接车辆"
        val panel = field("settingsMenu").get(this) as SettingsPanelView
        val description = SettingsPanelView::class.java.getDeclaredField("description").apply { isAccessible=true }.get(panel) as TextView
        description.text = "本地演示数据 · 非车辆当前读数"
        val fields = JSONObject()
        fun row(key: String, value: Any) { fields.put(key, JSONObject().put("raw", value).put("state", "returned").put("ageMs", 0)) }
        row("speed_raw", 0); row("gear", 4); row("soc", 68); row("range_wltp", 397); row("range_mode", 1)
        row("trip_since_start_raw", 16.6); row("trip_since_charge_raw", 126.4); row("mileage_total_raw", 61332); row("average_consumption_raw", 20.8)
        row("charge_discharge_power_raw", -12); row("outside_temperature_raw", 29)
        listOf("tpms_fl_raw", "tpms_fr_raw", "tpms_rl_raw", "tpms_rr_raw").forEachIndexed { n, name -> row(name, intArrayOf(230,230,240,250)[n]) }
        row("tpms_temperature_raw", JSONArray("[29,30,31,30]")); row("doors_raw", JSONArray("[0,0,0,0]")); row("bonnet_raw", 0)
        e.dashboard.accept(JSONObject().put("schema",1).put("source","vehicle").put("uid",2000).put("connected",true).put("fields",fields))
        e.dashboard.navigation(com.shilapi.xcertplay.iap2.message.RouteGuidanceSummary(true,"深圳大学",1080L,12600L,1793018400L,320L))
        e.dashboard.media("Save Your Tears (Remix)","The Weeknd",83400,241000,true)
    }
    private fun descendants(v: View): List<View> = listOf(v) + if (v is ViewGroup) (0 until v.childCount).flatMap { descendants(v.getChildAt(it)) } else emptyList()
    private fun verify() {
        val result = JSONObject()
        fun save() { File(filesDir,"settings-fixture.json").writeText(result.toString()) }
        try {
            val panel = field("settingsMenu").get(this) as SettingsPanelView
            val controls = descendants(panel)
            check(panel.selectedCategory == "instrument"); result.put("direct_ultra_category",true)
            val labels = controls.filterIsInstance<TextView>().map { it.text.toString() }
            check(listOf("Ultra 仪表","车辆数据","默认展示卡片","左右卡片位置","日夜外观","开始仪表投放","使用 Shizuku 读取车况","有线 CarPlay 授权").all { it in labels }); result.put("controls_in_op_settings",true)
            check(labels.none { "限时工具" in it || "限停车预览" in it || "打开仪表信息卡" == it }); result.put("obsolete_nested_copy_removed",true)
            val data = controls.filterIsInstance<UltraReadingsView>().single { view -> descendants(view).filterIsInstance<TextView>().any { it.text == "车速" } }
            panel.select("vehicle"); handler.postDelayed({
                try {
                    val count = data.refreshCount; check(count>0); result.put("visible_data_refreshed",true)
                    panel.select("instrument"); val hiddenCount=data.refreshCount
                    handler.postDelayed({
                        try { check(data.refreshCount==hiddenCount); result.put("hidden_data_does_not_poll",true); check(field("menuOpen").getBoolean(this)); result.put("category_switch_keeps_settings",true); result.put("allPassed",true);save() }
                        catch (e:Throwable) { result.put("allPassed",false).put("error",e.toString());save() }
                    },1250)
                } catch(e:Throwable) { result.put("allPassed",false).put("error",e.toString());save() }
            },1250)
        } catch (e:Throwable) { result.put("allPassed",false).put("error",e.toString());save() }
    }
    override fun onDestroy() { handler.removeCallbacksAndMessages(null); super.onDestroy() }
}
