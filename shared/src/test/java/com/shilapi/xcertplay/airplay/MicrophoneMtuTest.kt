package com.shilapi.xcertplay.airplay

import java.net.InetAddress
import org.junit.Assert.*
import org.junit.Test

class MicrophoneMtuTest {
    private fun config(rate: Int, channels: Int, codec: AudioCodecKind = AudioCodecKind.LPCM) =
        MicrophoneConfig("telephony",rate,channels,100,20,InetAddress.getByName("::1"),10000,ByteArray(32),codec,
            pcmMaxBodyBytes=1196)

    @Test fun wired48kMonoFitsOneIpv6Packet() {
        val c=config(48000,1)
        assertEquals(480,c.samplesPerPacket)
        val packet=MicrophonePacketizer.sealPacket(c.key,c.payloadType,MicrophoneCounters(),ByteArray(c.frameBytes),c.rtpSamplesPerPacket)
        assertEquals(996,packet.size)
        assertTrue(packet.size+40+8<=1280)
    }

    @Test fun supportedPcmRatesAndChannelsFitWithoutFragmentation() {
        for (rate in listOf(8000,16000,24000,32000,44100,48000,96000)) for (channels in 1..2) {
            val c=config(rate,channels)
            assertTrue(c.samplesPerPacket>0)
            assertTrue(c.frameBytes+36+40+8<=1280)
            assertEquals(c.samplesPerPacket,c.rtpSamplesPerPacket)
        }
    }

    @Test fun smallerPcmAndOpusPacketizationAreUnchanged() {
        assertEquals(320,config(16000,1).samplesPerPacket)
        assertEquals(960,config(48000,1,AudioCodecKind.OPUS).samplesPerPacket)
        assertEquals(960,config(48000,1,AudioCodecKind.OPUS).rtpSamplesPerPacket)
    }

    @Test fun defaultWirelessPcmRetainsNegotiatedDuration() {
        val c=MicrophoneConfig("telephony",48000,1,100,20,InetAddress.getByName("127.0.0.1"),10000,ByteArray(32))
        assertEquals(960,c.samplesPerPacket)
        assertEquals(1920,c.frameBytes)
    }

    @Test fun timestampCountsActualPcmSamplesAcrossNewPackets() {
        val c=config(48000,1);val counters=MicrophoneCounters()
        repeat(100) {
            MicrophonePacketizer.sealPacket(c.key,c.payloadType,counters,ByteArray(c.frameBytes),c.rtpSamplesPerPacket)
        }
        assertEquals(48000,counters.timestamp)
        assertEquals(100,counters.sequence)
        assertEquals(100L,counters.nonce)
    }
}
