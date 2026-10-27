package com.shilapi.xcertplay

import android.os.Bundle
import com.shilapi.xcertplay.airplay.*
import com.shilapi.xcertplay.media.AndroidMediaSink
import com.shilapi.xcertplay.orchestration.*
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import org.json.JSONObject
import java.io.File

/** Local synthetic lifecycle test; no controller is started and no car/phone is contacted. */
class PerformanceLifecycleFixtureActivity : CarPlayHostActivity() {
    override fun requestStartupPrerequisites() = Unit

    private fun field(name: String) = CarPlayHostActivity::class.java.getDeclaredField(name).apply { isAccessible = true }
    private fun call(name: String) = CarPlayHostActivity::class.java.getDeclaredMethod(name).apply { isAccessible = true }.invoke(this)

    override fun onCreate(state: Bundle?) {
        AirPlayPersistence.saveWirelessEnabled(this, false)
        AirPlayPersistence.saveMfiTarget(this, MfiTarget.I2C)
        AirPlayPersistence.saveMfiI2cPath(this, "/dev/i2c-1")
        AirPlayPersistence.saveManualHotspotSsid(this, "fixture-ap")
        AirPlayPersistence.saveManualHotspotPassphrase(this, "")
        AirPlayPersistence.saveManualHotspotSecurity(this, ManualHotspotSecurity.OPEN)
        getSharedPreferences("p7-instrument", MODE_PRIVATE).edit().putBoolean("enabled", false).apply()
        super.onCreate(state)
        window.decorView.postDelayed({ runChecks() }, 500)
    }

    private fun runChecks() {
        val output = File(filesDir, "performance-lifecycle.json")
        var fake: CarPlayController? = null
        var media: AndroidMediaSink? = null
        var assertions = 0
        fun expect(value: Boolean, label: String) { check(value) { label }; assertions++ }
        try {
            val config = CarPlayRuntimeConfig(
                mfiTarget = MfiTarget.I2C, linuxI2cPath = "/dev/i2c-1",
                identification = Iap2IdentificationConfig("fixture", "fixture", "fixture", "fixture", "1", "1", 3),
            )
            fake = CarPlayController(this, config,
                AirPlayConfig("fixture", "02:00:00:00:00:02", "02:00:00:00:00:01", "1",
                    AirPlayDisplayConfig(800, 480)),
                AirPlayIdentity.generate(), PairingStore(), object : AirPlaySessionListener {},
                object : AirPlayMediaHandler {}, {})
            media = AndroidMediaSink(applicationContext)
            field("controller").set(this, fake)
            field("sink").set(this, media)
            val before = getSharedPreferences("xcertplay_airplay", MODE_PRIVATE).all.toMap()
            call("openSettingsMenu")
            expect(field("menuOpen").getBoolean(this), "settings opened")
            expect(field("controller").get(this) === fake && !fake.isClosed(), "view settings retains session")
            call("cancelSettingsEdits")
            expect(field("controller").get(this) === fake && !fake.isClosed(), "cancel retains session")
            expect(getSharedPreferences("xcertplay_airplay", MODE_PRIVATE).all == before, "no-op cancel leaves preferences unchanged")
            call("openSettingsMenu")
            field("defaultFullscreenBars").setBoolean(this, !field("defaultFullscreenBars").getBoolean(this))
            call("saveSettingsAndReconnect")
            expect(!field("menuOpen").getBoolean(this), "save closes settings")
            expect(field("controller").get(this) === fake && !fake.isClosed(), "startup default save does not reconnect")

            val ultraPrefs = getSharedPreferences("op-ultra-display", MODE_PRIVATE)
            val defaultBefore = com.heavendoor.ultralab.UltraPreferences.defaultCard(this)
            val themeBefore = com.heavendoor.ultralab.UltraPreferences.themeMode(this)
            val layoutBefore = com.heavendoor.ultralab.UltraPreferences.cardsSwapped(this)
            val newCard = if (defaultBefore == 2) 0 else 2
            call("openSettingsMenu")
            field("ultraDefaultCard").setInt(this, newCard)
            field("ultraThemeMode").setInt(this, if (themeBefore == 2) 1 else 2)
            field("ultraCardsSwapped").setBoolean(this, !layoutBefore)
            call("cancelSettingsEdits")
            expect(com.heavendoor.ultralab.UltraPreferences.defaultCard(this)==defaultBefore && com.heavendoor.ultralab.UltraPreferences.themeMode(this)==themeBefore && com.heavendoor.ultralab.UltraPreferences.cardsSwapped(this)==layoutBefore, "Ultra cancel keeps all preferences")
            call("openSettingsMenu")
            expect(field("ultraDefaultCard").getInt(this)==defaultBefore, "Ultra reopened draft restored")
            field("ultraDefaultCard").setInt(this,newCard)
            field("ultraThemeMode").setInt(this,if(themeBefore==2)1 else 2)
            field("ultraCardsSwapped").setBoolean(this,!layoutBefore)
            call("saveSettingsAndReconnect")
            expect(field("controller").get(this) === fake && !fake.isClosed(), "Ultra appearance save retains phone session")
            expect(com.heavendoor.ultralab.UltraPreferences.defaultCard(this)==newCard && com.heavendoor.ultralab.UltraPreferences.cardsSwapped(this)==!layoutBefore, "Ultra appearance save persisted")
            call("openSettingsMenu")
            field("ultraThemeMode").setInt(this,0)
            onNewIntent(android.content.Intent(this,PerformanceLifecycleFixtureActivity::class.java).putExtra("op.ultra.settings",true).putExtra("op.settings.category","vehicle"))
            expect((field("settingsMenu").get(this) as SettingsPanelView).selectedCategory=="vehicle" && field("ultraThemeMode").getInt(this)==0, "category deep link preserves open draft")
            call("cancelSettingsEdits")

            // Preference listeners must see a whole settings snapshot, not intermediate fields.
            val prefs = getSharedPreferences("xcertplay_airplay", MODE_PRIVATE)
            prefs.edit().putBoolean("wireless_enabled", false).putBoolean("hevc_enabled", false).commit()
            var partial = false
            val listener = android.content.SharedPreferences.OnSharedPreferenceChangeListener { changed, _ ->
                if (changed.getBoolean("wireless_enabled", false) != changed.getBoolean("hevc_enabled", false)) partial = true
            }
            prefs.registerOnSharedPreferenceChangeListener(listener)
            AirPlayPersistence.withSettingsBatch(this) {
                AirPlayPersistence.saveWirelessEnabled(this, true)
                AirPlayPersistence.saveHevcEnabled(this, true)
            }
            prefs.unregisterOnSharedPreferenceChangeListener(listener)
            expect(!partial && prefs.getBoolean("wireless_enabled", false) && prefs.getBoolean("hevc_enabled", false), "batch publishes complete snapshot")
            AirPlayPersistence.saveWirelessEnabled(this, false)

            val oldOwner = Any(); val newOwner = Any()
            media.setUiListeners(newOwner, null, null)
            expect(!media.clearUiListeners(oldOwner), "old activity cannot clear new UI owner")
            expect(media.clearUiListeners(newOwner), "current UI owner can detach")

            call("openSettingsMenu")
            field("instrumentZoomSteps").setInt(this, field("instrumentZoomSteps").getInt(this) + 1)
            call("saveSettingsAndReconnect")
            val expected = checkNotNull(fake)
            Thread {
                try {
                    val deadline = System.nanoTime() + 5_000_000_000L
                    while (!expected.isClosed() && System.nanoTime() < deadline) Thread.sleep(10)
                    check(expected.isClosed()) { "changed negotiated setting must close old controller" }
                    expected.awaitClosed(3000)
                    output.writeText(JSONObject().put("passed", true).put("assertions", assertions + 1)
                        .put("networkStarted", false).put("scenarios", "open/cancel; presentation-only save; whole preference snapshot; owner detach; negotiated zoom save").toString())
                } catch (error: Throwable) { output.writeText(JSONObject().put("passed", false).put("error", error.stackTraceToString()).toString()) }
                finally { expected.close(); media?.close() }
            }.start()
        } catch (error: Throwable) {
            fake?.close(); media?.close()
            output.writeText(JSONObject().put("passed", false).put("error", error.stackTraceToString()).toString())
        }
    }
}
