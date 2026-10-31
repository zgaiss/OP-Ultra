package com.shilapi.xcertplay.media
import com.shilapi.xcertplay.iap2.message.Iap2NowPlayingState
import com.shilapi.xcertplay.iap2.message.Iap2PlaybackStatus
import org.junit.Assert.*
import org.junit.Test
class CarPlayNowPlayingViewTest {
 @Test fun playingExtrapolatesAndStopsAtDuration(){val s=Iap2NowPlayingState(title="song",artist="artist",durationMillis=5000,status=Iap2PlaybackStatus.PLAYING,elapsedMillis=3500,positionUpdateRealtimeMillis=1000).ultraSummary(4000);assertEquals(5000L,s.positionMillis);assertTrue(s.playing)}
 @Test fun pausedPositionDoesNotAdvance(){val s=Iap2NowPlayingState(status=Iap2PlaybackStatus.PAUSED,elapsedMillis=1200,positionUpdateRealtimeMillis=1000).ultraSummary(9000);assertEquals(1200L,s.positionMillis);assertFalse(s.playing)}
 @Test fun missingTimestampDoesNotJumpToUptime(){val s=Iap2NowPlayingState(status=Iap2PlaybackStatus.PLAYING,elapsedMillis=1000,positionUpdateRealtimeMillis=0).ultraSummary(9000000);assertEquals(1000L,s.positionMillis)}
 @Test fun negativeAndFuturePositionAreBounded(){assertEquals(0L,Iap2NowPlayingState(elapsedMillis=-1).ultraSummary(1000).positionMillis);assertEquals(12L,Iap2NowPlayingState(status=Iap2PlaybackStatus.PLAYING,elapsedMillis=12,positionUpdateRealtimeMillis=2000).ultraSummary(1000).positionMillis)}
 @Test fun metadataIsBoundedAndFallsBackToApp(){assertEquals("app",Iap2NowPlayingState(appName="app").ultraSummary(0).title);assertEquals(160,Iap2NowPlayingState(title="a".repeat(1000)).ultraSummary(0).title.length)}
 @Test fun unknownAndFutureAnchorsCannotDriveLyrics(){
  assertFalse(Iap2NowPlayingState(status=Iap2PlaybackStatus.PLAYING,positionUpdateRealtimeMillis=0).ultraSummary(1000).clockKnown)
  assertFalse(Iap2NowPlayingState(status=Iap2PlaybackStatus.PLAYING,positionUpdateRealtimeMillis=1001).ultraSummary(1000).clockKnown)
  assertTrue(Iap2NowPlayingState(status=Iap2PlaybackStatus.PLAYING,positionUpdateRealtimeMillis=999).ultraSummary(1000).clockKnown)
 }
 @Test fun seekingStoppedAndUnknownNeverBecomePausedLyricClock(){
  for(status in listOf(Iap2PlaybackStatus.UNKNOWN,Iap2PlaybackStatus.SEEKING_FORWARD,Iap2PlaybackStatus.SEEKING_BACKWARD,Iap2PlaybackStatus.STOPPED))
   assertFalse(Iap2NowPlayingState(status=status,positionUpdateRealtimeMillis=999).ultraSummary(1000).clockKnown)
 }
}
