package com.shilapi.xcertplay.media

import org.junit.Assert.*
import org.junit.Test

class NavigationDuckingTest {
    private fun pcm(samples:Int,value:Int)=ByteArray(samples*2).also { for(i in 0 until samples){it[i*2]=value.toByte();it[i*2+1]=(value shr 8).toByte()} }
    private fun sample(b:ByteArray,i:Int)=((b[i*2].toInt() and 255) or (b[i*2+1].toInt() shl 8)).toShort().toInt()
    @Test fun audibleNavigationDucksAndSilenceTimeoutRestoresMedia() {
        val s=NavigationDucking();val owner=Any();s.register(owner);assertEquals(1f,s.gain(0))
        s.audible(owner,0,100);assertEquals(.25f,s.gain(349));assertEquals(1f,s.gain(350))
    }
    @Test fun overlappingPromptsAndLateStoppedRendererCannotGetVolumeStuck() {
        val s=NavigationDucking();val a=Any();val b=Any();s.register(a);s.register(b)
        s.audible(a,0,100);s.audible(b,100,500);s.remove(a);assertEquals(.25f,s.gain(400))
        s.remove(b);s.audible(b,500,1000);assertEquals(1f,s.gain(500))
    }
    @Test fun stoppedOrStalledPromptCannotHoldMediaDownForever() {
        val s=NavigationDucking();val a=Any();s.register(a);s.audible(a,0,Long.MAX_VALUE)
        assertEquals(1f,s.gain(1750));s.clear();s.audible(a,2000,100);assertEquals(1f,s.gain(2000))
    }
    @Test fun zeroAndDitherDoNotTriggerDuckingButPositiveAndNegativePcmDo() {
        assertFalse(NavigationDucking.hasSound(pcm(100,0),0,200));assertFalse(NavigationDucking.hasSound(pcm(100,16),0,200))
        assertTrue(NavigationDucking.hasSound(pcm(100,1000),0,200));assertTrue(NavigationDucking.hasSound(pcm(100,-32768),0,200))
    }
    @Test fun mediaGainRampsPerFrameWithMatchingStereoAndNoExtraBuffer() {
        val r=PcmGainRamp();val bytes=pcm(4800,12000);val n=bytes.size
        r.apply(bytes,0,n,2,48000,.25f);assertEquals(n,bytes.size)
        assertTrue(sample(bytes,0)>11900);assertTrue(sample(bytes,4000) in 2999..3001)
        for(i in 0 until 4800 step 2)assertEquals(sample(bytes,i),sample(bytes,i+1))
        val restored=pcm(20000,12000);r.apply(restored,0,restored.size,2,48000,1f)
        assertTrue(sample(restored,0)<3100);assertEquals(12000,sample(restored,19998))
    }
    @Test fun untouchedMediaRemainsBitExactAndOffsetsStayWithinBounds() {
        val r=PcmGainRamp();val bytes=pcm(100,23456);val original=bytes.copyOf();r.apply(bytes,0,bytes.size,2,48000,1f)
        assertArrayEquals(original,bytes);r.apply(bytes,4,bytes.size-8,2,48000,.25f)
        assertArrayEquals(original.copyOfRange(0,4),bytes.copyOfRange(0,4));assertArrayEquals(original.takeLast(4).toByteArray(),bytes.takeLast(4).toByteArray())
    }
    @Test fun navigationDuckingDoesNotChangeSiriOrTelephonyRoles() {
        fun role(type:String)=AudioChannelMapper.map(type,100,AudioChannelMappingMode.MOBILE_COMPATIBLE).channel
        assertEquals(AudioChannel.ASSISTANT,role("speechrecognition"));assertEquals(AudioChannel.PHONE,role("telephony"))
        assertEquals(AudioChannel.NAVIGATION,role("default"));assertEquals(AudioChannel.MEDIA,role("media"))
    }
}
