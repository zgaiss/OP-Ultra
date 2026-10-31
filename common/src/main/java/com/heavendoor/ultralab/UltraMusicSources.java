package com.heavendoor.ultralab;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/** Pure, read-only selection: every render uses one complete source snapshot, never field merging. */
public final class UltraMusicSources {
    public static final String CARPLAY = "carplay", NATIVE_PACKAGE = "com.xiaopeng.musicradio";
    public static final long MAX_AGE_MS = 1500;
    public enum Playback { PLAYING, PAUSED, BUFFERING, UNKNOWN }
    public static final class Identity {
        public final String source, packageName, session, item;
        public Identity(String source, String packageName, String session, String item) {
            this.source = bounded(source); this.packageName = bounded(packageName);
            this.session = bounded(session); this.item = bounded(item);
        }
        public boolean valid() { return !source.isEmpty() && !session.isEmpty() && !item.isEmpty(); }
        public String key() { return part(source)+part(packageName)+part(session)+part(item); }
        private static String part(String s) { return s.length()+":"+s; }
        @Override public boolean equals(Object o) { return o instanceof Identity && key().equals(((Identity)o).key()); }
        @Override public int hashCode() { return key().hashCode(); }
    }
    public static final class Snapshot {
        public final Identity identity;
        public final String title, artist;
        public final long artworkKey, positionMs, durationMs, anchorMs, observedMs;
        private final byte[] artwork;
        public final Playback playback;
        public final double speed;
        public final List<UltraLyricsState.Line> lyrics;
        public Snapshot(Identity identity, String title, String artist, long artworkKey, byte[] artwork,
                        long positionMs, long durationMs, long anchorMs, long observedMs,
                        Playback playback, double speed, List<UltraLyricsState.Line> lyrics) {
            this.identity = Objects.requireNonNull(identity); this.title=bounded(title); this.artist=bounded(artist);
            this.artworkKey=artworkKey;
            this.artwork=artwork!=null && artwork.length<=4194304 ? artwork.clone() : null;
            this.positionMs=positionMs; this.durationMs=durationMs; this.anchorMs=anchorMs; this.observedMs=observedMs;
            this.playback=playback==null?Playback.UNKNOWN:playback; this.speed=speed;
            List<UltraLyricsState.Line> bounded=lyrics==null || lyrics.size()>UltraLyricsState.MAX_LINES?Collections.emptyList():new java.util.ArrayList<>(lyrics);
            this.lyrics=UltraLyricsState.valid(bounded)?Collections.unmodifiableList(bounded):Collections.emptyList();
        }
        public byte[] artworkBytes() { return artwork==null?null:artwork.clone(); }
        public boolean fresh(long now) { return identity.valid() && observedMs>=0 && now>=observedMs && now-observedMs<=MAX_AGE_MS; }
        public boolean clockKnown(long now) { return fresh(now) && positionMs>=0 && positionMs<=UltraLyricsState.MAX_TIMESTAMP_MS
                && durationMs>=0 && durationMs<=UltraLyricsState.MAX_TIMESTAMP_MS && anchorMs>0 && anchorMs<=now
                && Double.isFinite(speed) && speed>=0 && speed<=4 && playback!=Playback.UNKNOWN; }
        public boolean advancing(long now) { return clockKnown(now)&&playback==Playback.PLAYING&&speed>0; }
        public long positionAt(long now) {
            if(!clockKnown(now))return 0;
            long delta=advancing(now)?(long)Math.min(UltraLyricsState.MAX_TIMESTAMP_MS,(now-anchorMs)*speed):0;
            long p=Math.min(UltraLyricsState.MAX_TIMESTAMP_MS,positionMs+delta);
            return durationMs>0?Math.min(p,durationMs):p;
        }
    }
    private Snapshot nativeSnapshot;
    private boolean nativeOptIn;
    private Identity selected;
    private long generation, nativeEpoch;
    public synchronized void enableNative(boolean enabled) { if(nativeOptIn!=enabled){nativeOptIn=enabled;nativeSnapshot=null;invalidate();} }
    /** Caller must already have authorized read access. No listener registration or permission request here. */
    public synchronized long nativeEpoch() { return nativeEpoch; }
    public synchronized boolean submitNative(long ownerEpoch, Snapshot value) {
        if(ownerEpoch!=nativeEpoch || !nativeOptIn || value==null || !"native".equals(value.identity.source)
                || !NATIVE_PACKAGE.equals(value.identity.packageName) || !value.identity.valid())return false;
        if(nativeSnapshot!=null && value.observedMs<nativeSnapshot.observedMs)return false;
        nativeSnapshot=value;return true;
    }
    public synchronized void nativeUnavailable() { nativeSnapshot=null;invalidate(); }
    public synchronized Snapshot select(Snapshot carplay,long now) {
        Snapshot next=nativeOptIn&&nativeSnapshot!=null&&nativeSnapshot.fresh(now)?nativeSnapshot:
                carplay!=null&&carplay.fresh(now)?carplay:null;
        Identity id=next==null?null:next.identity;
        if(!Objects.equals(selected,id)){selected=id;generation++;}
        return next;
    }
    public synchronized long generation() { return generation; }
    public synchronized void disconnect() { nativeSnapshot=null;invalidate(); }
    private void invalidate(){selected=null;generation++;nativeEpoch++;}
    private static String bounded(String s){return s==null?"":s.length()>512?"":s;}
}
