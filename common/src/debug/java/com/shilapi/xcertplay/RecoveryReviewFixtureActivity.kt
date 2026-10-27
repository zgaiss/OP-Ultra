package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import android.os.SystemClock
import android.widget.TextView
import com.shilapi.xcertplay.airplay.AudioCodecKind
import com.shilapi.xcertplay.airplay.AudioFormat
import com.shilapi.xcertplay.media.AndroidMediaSink
import org.json.JSONObject
import java.io.File

/** Emulator-only real AudioTrack exercise. Never included in release. No CarPlay/network. */
class RecoveryReviewFixtureActivity : Activity() {
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        setContentView(TextView(this).apply { text = "普通版恢复逻辑本地检查" })
        check(android.os.Build.MODEL != "E28") { "fixture refuses the real vehicle" }
        Thread {
            val result=JSONObject();var sink:AndroidMediaSink?=null
            try {
                check(!FactoryHotspotGrant.grant(applicationContext))
                result.put("factoryGrantUnsupportedDeviceRefused",true)
                val media=AudioFormat(AudioCodecKind.LPCM,48000,2,102,"media")
                val nav=AudioFormat(AudioCodecKind.LPCM,24000,1,100,"default")
                val siri=AudioFormat(AudioCodecKind.LPCM,24000,1,100,"speechrecognition")
                val renderer=AndroidMediaSink(applicationContext,navigationDuckingEnabled=true);sink=renderer
                val df=AndroidMediaSink::class.java.getDeclaredField("navigationDucking").apply { isAccessible=true }
                val ducker=df.get(renderer)!!;val gain=ducker.javaClass.getDeclaredMethod("gain",Long::class.javaPrimitiveType)
                fun currentGain()=gain.invoke(ducker,SystemClock.elapsedRealtime()) as Float
                fun packet(frames:Int,channels:Int)=ByteArray(12+frames*channels*2).also { b ->
                    // Low-amplitude big-endian S16; enough to exercise PCM processing.
                    for(i in 0 until frames*channels){b[12+i*2]=0;b[13+i*2]=32}
                }
                renderer.onAudioStarted(102,media,0)
                var ducked=false
                for(i in 0 until 45){
                    renderer.onAudioRtp(102,media,packet(480,2),i*480)
                    if(i==8)renderer.onAudioStarted(100,nav,0)
                    if(i in 8..32)renderer.onAudioRtp(100,nav,packet(240,1),(i-8)*240)
                    Thread.sleep(10);ducked=ducked||currentGain()==.25f
                }
                check(ducked);result.put("actualPcmNavigationDuckedMedia",true)
                renderer.onAudioStopped(100);check(currentGain()==1f)
                result.put("navigationStopRestoresImmediately",true)
                renderer.onAudioStarted(103,siri,0)
                for(i in 0 until 14){
                    renderer.onAudioRtp(103,siri,packet(240,1),i*240)
                    renderer.onAudioRtp(102,media,packet(480,2),(45+i)*480)
                    Thread.sleep(10)
                }
                check(currentGain()==1f);result.put("siriDoesNotTriggerNavigationDucking",true)
                val af=AndroidMediaSink::class.java.getDeclaredField("audioRenderers").apply{isAccessible=true}
                val map=af.get(renderer) as Map<*,*>
                val mediaRenderer=map[102]!!
                val started=mediaRenderer.javaClass.getDeclaredField("playbackStarted").apply{isAccessible=true}
                check(started.getBoolean(mediaRenderer));result.put("realAudioTrackPlaybackStarted",true)
                renderer.close();renderer.onAudioRtp(102,media,packet(480,2),99999)
                check((af.get(renderer) as Map<*,*>).isEmpty());check(currentGain()==1f)
                result.put("exitRejectsLateAudioAndClearsDucking",true)
                result.put("allPassed",true)
            }catch(e:Throwable){result.put("allPassed",false).put("error",e.toString())}
            finally{sink?.close()}
            File(filesDir,"recovery-review.json").writeText(result.toString(2))
            runOnUiThread { NativeHotspotAuthorization.show(this){} }
        }.start()
    }
}
