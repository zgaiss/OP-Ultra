package com.shilapi.xcertplay

import android.app.Activity
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import com.heavendoor.ultralab.FactoryRootClient
import org.json.JSONObject
import java.io.File
import java.util.concurrent.Executors

/** User-initiated fixed authorizations. Work and verification never run on the UI thread. */
internal object FactoryRootAuthorization {
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "op-factory-authorization").apply { isDaemon = true } }
    private val main = Handler(Looper.getMainLooper())
    fun request(activity: Activity, wired: Boolean, update: (String, Boolean) -> Unit, done: () -> Unit = {}) {
        update("正在连接原车授权服务…", true)
        worker.execute {
            val result = runCatching {
                FactoryRootClient.connect(activity.applicationContext).use { client ->
                    val report = if (wired) client.authorizeWired() else client.authorizeHotspot()
                    File(activity.filesDir, if (wired) "factory-wired-authorization.json" else "factory-hotspot-authorization.json")
                        .writeText(report.put("timeMillis", System.currentTimeMillis()).toString())
                    if (wired) {
                        check(report.optBoolean("vpn")) { "系统没有确认 VPN 授权" }
                        "有线 CarPlay 已授权：VPN、麦克风。" +
                            if (report.optInt("usbPhones") > 0) "当前 iPhone 的 USB 访问已授予 OP。" else "接入 iPhone 后继续确认 USB 访问与手机信任。"
                    } else {
                        check(report.optBoolean("writeSettings")) { "系统没有确认热点授权" }
                        "原车热点授权成功，可返回开启自动热点并保存。"
                    }
                }
            }
            main.post {
                if (activity.isDestroyed || activity.isFinishing) return@post
                val message = result.getOrElse { "原车授权未完成：${it.message ?: it.javaClass.simpleName}。可使用原有 Shizuku / ADB 方式。" }
                update(message, false)
                done()
            }
        }
    }
}
