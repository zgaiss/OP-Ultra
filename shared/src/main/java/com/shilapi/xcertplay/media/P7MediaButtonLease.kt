package com.shilapi.xcertplay.media

import android.os.Build
import android.os.Bundle
import dalvik.system.PathClassLoader
import java.lang.reflect.Method

/** OEM's normal, per-process media-button request. No root or global setting changes. */
internal class P7MediaButtonLease {
    private var manager: Any? = null
    private var request: Method? = null
    private var held = false
    private var attempted = false
    private var status = "idle"

    fun update(wanted: Boolean) {
        if (!wanted) { release(); return }
        if (held || attempted || Build.VERSION.SDK_INT != 28 || Build.MODEL != "E28") return
        attempted = true
        try {
            val loader = PathClassLoader("/system/framework/com.xiaopeng.xuimanager.jar", javaClass.classLoader)
            val api = loader.loadClass("com.xiaopeng.xuimanager.XUIManager")
            val xui = api.getMethod("getInstance").invoke(null)
            val media = api.getMethod("getService", String::class.java).invoke(xui, "mediacenter") ?: return
            api.getMethod("registerObserver").invoke(xui)
            val call = media.javaClass.getMethod("requestMediaButton", Boolean::class.javaPrimitiveType, Bundle::class.java)
            manager = media; request = call
            call.invoke(media, true, Bundle())
            held = true; status = "oem_request_active"
        } catch (_: ReflectiveOperationException) { status = "oem_request_unavailable" }
        catch (_: RuntimeException) { status = "oem_request_unavailable" }
    }
    fun release() {
        if (held) try { request?.invoke(manager, false, Bundle()) }
        catch (_: ReflectiveOperationException) {} catch (_: RuntimeException) {}
        held = false; attempted = false; status = "released"
    }
    fun status():String = status
}
