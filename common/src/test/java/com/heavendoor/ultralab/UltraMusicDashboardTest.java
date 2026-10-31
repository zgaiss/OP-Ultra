package com.heavendoor.ultralab;

import static org.junit.Assert.*;
import android.app.Application;
import java.lang.reflect.Field;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE,application=Application.class)
public final class UltraMusicDashboardTest {
    private UltraMusicSources.Snapshot paused(List<UltraLyricsState.Line> lines) {
        return new UltraMusicSources.Snapshot(new UltraMusicSources.Identity("native",UltraMusicSources.NATIVE_PACKAGE,"session","item"),
                "same title","same artist",1,null,100,5000,1000,1000,UltraMusicSources.Playback.PAUSED,1,lines);
    }
    @Test public void pausedLyricArrivalAndClearInvalidateDespiteIdenticalMetadata() throws Exception {
        UltraDashboardView view=new UltraDashboardView(RuntimeEnvironment.getApplication());
        Field sequence=UltraDashboardView.class.getDeclaredField("sequence");sequence.setAccessible(true);
        try {
            UltraMusicSources.Snapshot empty=paused(Collections.emptyList());
            UltraMusicSources.Snapshot lyrics=paused(Arrays.asList(new UltraLyricsState.Line(0,5000,"actual supplied text")));
            view.media(empty,1,1000);long before=sequence.getLong(view);
            view.media(lyrics,1,1000);assertTrue(sequence.getLong(view)>before);
            before=sequence.getLong(view);view.media(lyrics,1,1000);assertEquals(before,sequence.getLong(view));
            view.media(empty,1,1000);assertTrue(sequence.getLong(view)>before);
        } finally { view.release(); }
    }
    @Test public void nullSnapshotAlwaysInvalidatesOutstandingLyricsEvenWithEmptyMetadata() {
        UltraDashboardView view=new UltraDashboardView(RuntimeEnvironment.getApplication());
        try {
            UltraMusicSources.Snapshot emptyMetadata=new UltraMusicSources.Snapshot(
                    new UltraMusicSources.Identity("native",UltraMusicSources.NATIVE_PACKAGE,"session","item"),
                    "","",1,null,0,0,1000,1000,UltraMusicSources.Playback.PAUSED,1,
                    Arrays.asList(new UltraLyricsState.Line(0,5000,"text")));
            view.media(emptyMetadata,1,1000);long old=view.lyricsGeneration();
            view.media(null,2,1000);assertTrue(view.lyricsGeneration()>old);
            assertFalse(view.lyrics(old,Arrays.asList(new UltraLyricsState.Line(0,5000,"late"))));
        } finally { view.release(); }
    }
}
