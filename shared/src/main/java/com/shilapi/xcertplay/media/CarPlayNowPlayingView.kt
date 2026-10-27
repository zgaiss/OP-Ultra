package com.shilapi.xcertplay.media

import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingState
import com.shilapi.xcertplay.iap2.message.Iap2PlaybackStatus

/** Read-only UI text/position, without taking the Media3 service's single observer. */
data class CarPlayNowPlayingSummary(val title:String,val artist:String,val positionMillis:Long,val durationMillis:Long,val playing:Boolean,val artworkKey:Long=0,val artworkBytes:ByteArray?=null)

internal fun Iap2NowPlayingState.ultraSummary(now:Long):CarPlayNowPlayingSummary {
    val delta=if(status==Iap2PlaybackStatus.PLAYING&&positionUpdateRealtimeMillis>0) (now-positionUpdateRealtimeMillis).coerceAtLeast(0) else 0
    val duration=(durationMillis?:0).coerceAtLeast(0)
    val raw=elapsedMillis.coerceAtLeast(0)+delta
    val position=if(duration>0)raw.coerceAtMost(duration) else raw
    return CarPlayNowPlayingSummary((title?:appName?:"").take(160),(artist?:"").take(160),position,duration,status==Iap2PlaybackStatus.PLAYING)
}
object CarPlayNowPlayingView {
    @JvmStatic fun current():CarPlayNowPlayingSummary=CarPlayMediaSessionBridge.ultraRead()
    @JvmStatic fun buttonStatus():String=CarPlayMediaSessionService.p7ButtonStatus
}
