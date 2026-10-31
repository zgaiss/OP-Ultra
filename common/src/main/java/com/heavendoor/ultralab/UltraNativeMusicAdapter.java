package com.heavendoor.ultralab;

import org.json.JSONObject;
import java.util.Collections;
import java.util.List;

/**
 * Documented candidate, NOT a runtime-verified observer. The caller owns already-authorized
 * MediaSession access and must report permission loss/session destruction to nativeMusicUnavailable.
 * No notification bodies, playback controls, permission requests or listener registration.
 */
public final class UltraNativeMusicAdapter {
    private UltraNativeMusicAdapter() {}
    // Cap recursive JSON nesting before the platform decoder can allocate a deep object tree.
    private static boolean boundedJson(String text) {
        int depth=0; boolean quoted=false, escaped=false;
        for(int i=0;i<text.length();i++) {
            char c=text.charAt(i);
            if(quoted) { if(escaped)escaped=false;else if(c=='\\')escaped=true;else if(c=='"')quoted=false; }
            else if(c=='"')quoted=true;
            else if(c=='\''||c=='/')return false;
            else if(c=='{'||c=='[') { if(++depth>16)return false; }
            else if(c=='}'||c==']') { if(--depth<0)return false; }
        }
        return depth==0&&!quoted;
    }
    public static UltraMusicSources.Snapshot snapshot(String packageName, String sessionId, String itemId,
            String title, String artist, long artworkKey, byte[] artwork, long positionMs, long durationMs,
            long anchorMs, long observedMs, UltraMusicSources.Playback playback, double speed, String lyricInfo) {
        if(!UltraMusicSources.NATIVE_PACKAGE.equals(packageName))return null;
        List<UltraLyricsState.Line> lines=Collections.emptyList();
        if(lyricInfo!=null && lyricInfo.length()<=131072 && boundedJson(lyricInfo)) {
            try {
                Object content=new JSONObject(lyricInfo).opt("lyricContent");
                if(content instanceof String)lines=UltraLrcParser.parse((String)content,durationMs);
            } catch(Exception ignored) { /* Malformed payload is unavailable lyrics, never synthetic text. */ }
        }
        return new UltraMusicSources.Snapshot(new UltraMusicSources.Identity("native",packageName,sessionId,itemId),
                title,artist,artworkKey,artwork,positionMs,durationMs,anchorMs,observedMs,playback,speed,lines);
    }
}
