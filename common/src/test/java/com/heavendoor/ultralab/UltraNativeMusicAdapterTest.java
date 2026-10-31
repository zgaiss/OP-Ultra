package com.heavendoor.ultralab;
import static org.junit.Assert.*;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.annotation.Config;
import org.json.JSONObject;
@RunWith(RobolectricTestRunner.class)
@Config(sdk=28,manifest=Config.NONE)
public class UltraNativeMusicAdapterTest {
    private UltraMusicSources.Snapshot parse(String pkg,String json,long duration) {
        return UltraNativeMusicAdapter.snapshot(pkg,"session","item","title","artist",1,null,0,duration,1000,1000,
                UltraMusicSources.Playback.PLAYING,1,json);
    }
    @Test public void standardLyricsAreLineOnlyAndBoundedByDuration() throws Exception {
        UltraMusicSources.Snapshot s=parse(UltraMusicSources.NATIVE_PACKAGE,new JSONObject().put("lyricContent","[00:00.00]one\n[00:02.00]two").toString(),4000);
        assertEquals(2,s.lyrics.size());assertEquals(4000,s.lyrics.get(1).endMs);assertTrue(s.lyrics.get(0).words.isEmpty());
    }
    @Test public void noPackageGuessingOrTypeCoercion() {
        assertNull(parse("other.music","{}",4000));
        for(String input:new String[]{null,"", "invalid", "{}", "{\"lyricContent\":7}","{\"lyricContent\":null}","{\"lyricContent\":[\"text\"]}"})
            assertTrue(parse(UltraMusicSources.NATIVE_PACKAGE,input,4000).lyrics.isEmpty());
    }
    @Test public void unknownFinalEndIsOmitted() throws Exception {
        String json=new JSONObject().put("lyricContent","[00:00.00]one\n[00:02.00]two").toString();
        assertEquals(1,parse(UltraMusicSources.NATIVE_PACKAGE,json,0).lyrics.size());
    }
    @Test public void maliciousBoundsFailClosed() {
        String nested="{\"ignored\":"+"[".repeat(2000)+"0"+"]".repeat(2000)+",\"lyricContent\":\"[00:00]x\"}";
        assertTrue(parse(UltraMusicSources.NATIVE_PACKAGE,nested,4000).lyrics.isEmpty());
        assertTrue(parse(UltraMusicSources.NATIVE_PACKAGE," ".repeat(131073),4000).lyrics.isEmpty());
        assertTrue(parse(UltraMusicSources.NATIVE_PACKAGE,"{'ignored':'\"',\"x\":"+nested+"}",4000).lyrics.isEmpty());
    }
}
