package com.shilapi.xcertplay
import java.lang.ref.WeakReference
/** Reuse the accepted exit path, including media/session and task cleanup. */
internal object UltraHostHooks {
    private var host:WeakReference<CarPlayHostActivity>?=null
    fun bind(value:CarPlayHostActivity){host=WeakReference(value)}
    fun release(value:CarPlayHostActivity){if(host?.get()===value)host=null}
    @JvmStatic fun exit(){host?.get()?.let{it.runOnUiThread{it.exitUltraExperiment()}}}
    @JvmStatic fun diagnostics():org.json.JSONObject = host?.get()?.ultraConnectionStatus() ?: org.json.JSONObject()
}
