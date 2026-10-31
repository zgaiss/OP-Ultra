package com.heavendoor.ultralab;

import java.util.*;
public final class UltraMusicSourcesStandaloneTest {
    private static int checks;
    private static void check(boolean value,String message){checks++;if(!value)throw new AssertionError(message);}
    private static UltraMusicSources.Snapshot sample(String source,String session,String item,long observed,
            long anchor,UltraMusicSources.Playback state,double speed,List<UltraLyricsState.Line> lines){
        return new UltraMusicSources.Snapshot(new UltraMusicSources.Identity(source,
                source.equals("native")?UltraMusicSources.NATIVE_PACKAGE:"",session,item),"same title","artist",1,
                new byte[]{1,2,3,4},1000,10000,anchor,observed,state,speed,lines);
    }
    public static void main(String[] args){
        UltraMusicSources sources=new UltraMusicSources();
        List<UltraLyricsState.Line> lines=Arrays.asList(new UltraLyricsState.Line(0,2000,"one"),new UltraLyricsState.Line(2000,10000,"two"));
        UltraMusicSources.Snapshot cp=sample("carplay","1","1",1000,1000,UltraMusicSources.Playback.PLAYING,1,null);
        UltraMusicSources.Snapshot native1=sample("native","1","1",1000,1000,UltraMusicSources.Playback.PLAYING,2,lines);
        check(!sources.submitNative(sources.nativeEpoch(),native1),"default opt-out");
        check(sources.select(cp,1000)==cp,"CarPlay default");long generation=sources.generation();
        check(sources.select(cp,1100)==cp&&sources.generation()==generation,"same source stable");
        sources.enableNative(true);long epoch=sources.nativeEpoch();
        check(sources.submitNative(epoch,native1),"native submit opted in");
        check(sources.select(cp,1100)==native1,"complete native selection");generation=sources.generation();
        check(native1.positionAt(1500)==2000,"2x clock");
        check(native1.lyrics==sources.select(cp,1500).lyrics,"lyrics from selected source");
        UltraMusicSources.Snapshot item2=sample("native","1","2",1500,1500,UltraMusicSources.Playback.PAUSED,1,lines);
        check(sources.submitNative(epoch,item2),"same-title next item submit");sources.select(cp,1500);
        check(sources.generation()>generation,"same title item reset");generation=sources.generation();
        UltraMusicSources.Snapshot session2=sample("native","2","2",1600,1600,UltraMusicSources.Playback.PAUSED,1,lines);
        sources.submitNative(epoch,session2);sources.select(cp,1600);check(sources.generation()>generation,"session reset");
        check(!sources.submitNative(epoch,native1),"out-of-order observation rejected");
        sources.nativeUnavailable();check(!sources.submitNative(epoch,session2),"late permission-loss response rejected");
        check(sources.select(cp,2000)==cp,"permission loss falls back wholly");
        epoch=sources.nativeEpoch();check(sources.submitNative(epoch,session2),"new owner token accepted");
        check(sources.select(cp,3101)==null,"all sources expire");
        check(sources.select(cp,999)==null,"future observations rejected");
        sources.enableNative(false);check(!sources.submitNative(epoch,session2),"disable invalidates lease");
        sources.enableNative(true);check(!sources.submitNative(epoch,session2),"reenable does not revive old token");
        epoch=sources.nativeEpoch();sources.disconnect();check(!sources.submitNative(epoch,session2),"disconnect invalidates lease");
        for(UltraMusicSources.Playback state:UltraMusicSources.Playback.values()) {
            UltraMusicSources.Snapshot s=sample("native","s","i",1000,1000,state,2,lines);
            check(s.positionAt(1500)==(state==UltraMusicSources.Playback.PLAYING?2000:state==UltraMusicSources.Playback.UNKNOWN?0:1000),"playback "+state);
            check(!s.clockKnown(2501),"expiry "+state);check(!s.clockKnown(999),"future observation "+state);
        }
        for(double speed:new double[]{Double.NaN,Double.POSITIVE_INFINITY,-1,4.1})check(!sample("native","s","i",1000,1000,UltraMusicSources.Playback.PLAYING,speed,lines).clockKnown(1000),"bad speed");
        check(!sample("native","s","i",1000,0,UltraMusicSources.Playback.PLAYING,1,lines).clockKnown(1000),"unknown anchor");
        check(!sample("native","s","i",1000,1001,UltraMusicSources.Playback.PLAYING,1,lines).clockKnown(1000),"future anchor");
        check(sample("native","s","i",1000,1,UltraMusicSources.Playback.PLAYING,4,lines).positionAt(2000)==8996,"bounded extrapolation");
        List<UltraLyricsState.Line> invalid=Arrays.asList(new UltraLyricsState.Line(0,10,"x"),new UltraLyricsState.Line(1,12,"y"));
        check(sample("native","s","i",1000,1000,UltraMusicSources.Playback.PLAYING,1,invalid).lyrics.isEmpty(),"overlap rejected");
        check(sample("native","s","i",1000,1000,UltraMusicSources.Playback.PLAYING,1,Collections.nCopies(513,lines.get(0))).lyrics.isEmpty(),"oversize rejected before copy");
        byte[] copy=native1.artworkBytes();copy[0]=100;check(native1.artworkBytes()[0]==1,"art immutable");
        check(!new UltraMusicSources.Identity("native","p","","i").valid(),"missing session");
        check(!new UltraMusicSources.Identity("native","p","s","").valid(),"missing item");
        check(!new UltraMusicSources.Identity("a","b:c","d","e").equals(new UltraMusicSources.Identity("a:b","c","d","e")),"collision-free identity");
        UltraLyricsState model=new UltraLyricsState();
        model.updateClock("item",1000,10000,true,2,2500,true,1000);long owner=model.generation();
        check(model.accept(owner,lines,1000),"accept current lyrics");
        check(model.frame(1500,true).positionMs==2000,"model speed");
        model.updateClock("item",2000,10000,false,1,3000,true,1500);
        check(model.frame(2000,true).positionMs==2000,"pause freezes");
        model.updateClock("item",500,10000,true,1,3500,true,2000);
        check(model.frame(2000,true).positionMs==500,"seek snaps");
        model.updateClock("item",1000,10000,false,1,3500,false,2100);
        check(model.frame(2100,true).lines.isEmpty(),"unknown clock clears lyrics");
        check(!model.accept(owner,lines,2100),"old generation rejected");
        model.updateClock("item",1000,10000,false,1,2500,true,2200);model.accept(model.generation(),lines,2200);
        check(model.nextDelayMillis(2200,true)==301,"paused expiry wakeup scheduled");
        check(model.frame(2501,true).lines.isEmpty(),"paused timeline expires");
        // Stable observations retain equal timeline and do not restart scrolling on each 300ms poll.
        model.updateClock("stable",1800,10000,true,1,5000,true,3000);model.accept(model.generation(),lines,3000);model.frame(3000,true);
        model.updateClock("stable",2100,10000,true,1,5000,true,3300);model.accept(model.generation(),lines,3300);
        float progress=model.frame(3300,true).scrollProgress;
        model.updateClock("stable",2400,10000,true,1,5000,true,3600);model.accept(model.generation(),lines,3600);
        check(model.frame(3600,true).scrollProgress>progress,"equal timeline keeps scroll progress");
        System.out.println("UltraMusicSources: "+checks+" checks passed");
    }
}
