package com.shilapi.xcertplay.airplay

import android.util.Log
import com.shilapi.xcertplay.transport.BlockingDuplexByteStream
import java.io.Closeable
import java.io.File
import java.net.Inet4Address
import java.net.Inet6Address
import java.net.InetAddress
import java.math.BigInteger
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.ConcurrentHashMap

/** Rendering seam for the decrypted CarPlay media streams. */
interface MediaSink {
    fun onVideoCodec(type: Int, codec: VideoCodec) {}
    fun onVideoRecoveryHandler(type: Int, requestKeyFrame: (() -> Boolean)?) {}
    fun onVideoConfig(type: Int, codecData: ByteArray) {}
    fun onVideoFrame(type: Int, naluBytes: ByteArray) {}
    fun captureVideoFrameConsumer(type: Int): (ByteArray) -> Unit = { onVideoFrame(type, it) }
    fun onScreenStreamActive(type: Int, active: Boolean) {}
    fun onAudioStarted(type: Int, format: AudioFormat, firstSample: Int) {}
    fun onAudioRtp(type: Int, format: AudioFormat, rtp: ByteArray, sample: Int) {}
    fun onAudioStopped(type: Int) {}
    fun onMicrophoneStarted(type: Int, config: MicrophoneConfig) {}
    fun onMicrophoneStopped(type: Int) {}
    fun onIapMessage(bytes: ByteArray) {}
}

/**
 * Concrete [AirPlayMediaHandler] that binds the screen, audio and iAP2 DataStream ports,
 * decrypts their payloads, and hands decoded media to a [MediaSink]. Main audio streams can
 * additionally return microphone audio when the phone supplies an input port.
 */
class CarPlayMediaEngine(
    private val sink: MediaSink,
    private val microphoneEnabled: Boolean = false,
    private val audioCaptureDirectory: File? = null,
    private val microphonePcmMaxBodyBytes: Int = Int.MAX_VALUE,
) : AirPlayMediaHandler {
    internal data class StreamKey(
        val session: AirPlaySession,
        val type: Int,
    )

    private data class AudioMeta(
        val type: Int,
        val format: AudioFormat,
        val connectionId: Any?,
        val playoutLatencyMs: Int,
        @Volatile var firstSample: Int? = null,
        @Volatile var originNs: Long? = null,
    )

    private data class PendingIapTunnel(
        val bridge: AirPlayIapTunnelStream,
        val handler: (BlockingDuplexByteStream) -> Boolean,
    )

    private val streams = ConcurrentHashMap<StreamKey, Closeable>()
    private val audioMeta = ConcurrentHashMap<StreamKey, AudioMeta>()
    private val pendingMicrophone = ConcurrentHashMap<StreamKey, MicrophoneConfig>()
    private val startedMicrophone = ConcurrentHashMap.newKeySet<StreamKey>()
    private val audioCaptures = ConcurrentHashMap<StreamKey, AudioPacketCapture>()
    private val pendingIapTunnels = ConcurrentHashMap<AirPlaySession, PendingIapTunnel>()
    private class OutputOwner(val key: StreamKey) {
        var videoConsumer: ((ByteArray) -> Unit)? = null
    }
    private class OutputSlot { @Volatile var owner: OutputOwner? = null }
    private val outputSlots = ConcurrentHashMap<Int, OutputSlot>()
    private val latestPrimary = AtomicReference<AirPlaySession?>()
    fun consumePrimaryEnd(session: AirPlaySession): Boolean = latestPrimary.compareAndSet(session, null)

    private fun slot(type: Int): OutputSlot = outputSlots.computeIfAbsent(type) { OutputSlot() }
    fun mainScreenSession(): AirPlaySession? {
        val output = outputSlots[STREAM_TYPE_MAIN_SCREEN] ?: return null
        return synchronized(output) { output.owner?.key?.session }
    }

    /** Called under the per-output lock; old callbacks cannot stop a replacement decoder. */
    private fun releaseOutput(slot: OutputSlot, owner: OutputOwner) {
        if (slot.owner !== owner) return
        slot.owner = null
        val key = owner.key
        streams.remove(key)?.close()
        audioMeta.remove(key)
        audioCaptures.remove(key)?.close()
        stopMicrophone(key)
        if (isScreenStreamType(key.type)) {
            sink.onVideoRecoveryHandler(key.type, null)
            sink.onScreenStreamActive(key.type, false)
        } else if (key.type in STREAM_TYPE_MAIN_AUDIO..STREAM_TYPE_MAIN_HIGH_AUDIO) {
            sink.onAudioStopped(key.type)
        }
    }

    @Volatile private var iapTunnelHandler: ((BlockingDuplexByteStream) -> Boolean)? = null

    override fun setIapTunnelHandler(handler: ((BlockingDuplexByteStream) -> Boolean)?) {
        iapTunnelHandler = handler
    }

    override fun onScreen(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Int? {
        val key = outputKey(session, stream) ?: return null
        val owner = OutputOwner(StreamKey(session, type))
        val output = slot(type)
        val screen = ScreenStream(key, diagnosticType = type)
        val port = try {
            screen.listen(object : ScreenStream.Listener {
                override fun onCodec(codec: VideoCodec) = synchronized(output) {
                    if (output.owner === owner) sink.onVideoCodec(type, codec)
                }
                override fun onConfig(codecData: ByteArray) = synchronized(output) {
                    if (output.owner === owner) sink.onVideoConfig(type, codecData)
                }
                override fun onFrame(naluBytes: ByteArray) {
                    val consumer = synchronized(output) { if (output.owner === owner) owner.videoConsumer else null }
                    // Producer leases keep backpressure outside the ownership/teardown lock.
                    consumer?.invoke(naluBytes)
                }
                override fun onClosed(cause: Throwable?) {
                    val current = synchronized(output) {
                        if (output.owner !== owner) false else {
                            releaseOutput(output, owner)
                            true
                        }
                    }
                    // Only the current primary's EOF ends the primary session.
                    if (current && type == STREAM_TYPE_MAIN_SCREEN) session.close()
                }
            })
        } catch (error: Throwable) { screen.close(); throw error }
        synchronized(output) {
            output.owner?.let { releaseOutput(output, it) }
            output.owner = owner
            if (type == STREAM_TYPE_MAIN_SCREEN) latestPrimary.set(session)
            streams[owner.key] = screen
            sink.onVideoRecoveryHandler(type) {
                if (output.owner !== owner) false else session.sendCommand(
                    if (type == STREAM_TYPE_ALT_SCREEN) ClusterLabDisplay.keyFrameCommand()
                    else mainScreenKeyFrameCommand(),
                )
            }
            sink.onScreenStreamActive(type, true)
            owner.videoConsumer = sink.captureVideoFrameConsumer(type)
        }
        return port
    }

    override fun onAudio(session: AirPlaySession, type: Int, stream: Map<String, Any?>): Map<String, Any?>? {
        val key = outputKey(session, stream) ?: return null
        val owner = OutputOwner(StreamKey(session, type))
        val output = slot(type)
        val audioType = stream["audioType"]?.toString()?.lowercase() ?: "default"
        val format = AudioStreamCodec.fromFormatBits((stream["audioFormat"] as? Number)?.toLong() ?: 0L, type, audioType)
        val connectionId = stream["streamConnectionID"]
        val meta = AudioMeta(type, format, connectionId, (stream["audioLatencyMs"] as? Number)?.toInt() ?: 0)
        val microphone = microphoneConfig(session, type, stream, format)
        val capture = audioCaptureDirectory?.let { AudioPacketCapture(it, type) }
        val audio = AudioStream(key, type, capturePackets = capture != null)
        val ports = try {
            audio.listen(object : AudioStream.Listener {
                override fun onStarted(firstSample: Int) = synchronized(output) {
                    if (output.owner === owner) {
                        meta.firstSample = firstSample
                        meta.originNs = System.nanoTime()
                        sink.onAudioStarted(type, format, firstSample)
                    }
                }
                override fun onRtp(rtp: ByteArray, sample: Int) = synchronized(output) {
                    if (output.owner === owner) sink.onAudioRtp(type, format, rtp, sample)
                }
                override fun onPacket(wire: ByteArray, rtp: ByteArray?, sample: Int?, error: Throwable?) = synchronized(output) {
                    if (output.owner === owner) capture?.record(wire, rtp, sample, error)
                    Unit
                }
            })
        } catch (error: Throwable) { audio.close(); capture?.close(); throw error }
        synchronized(output) {
            output.owner?.let { releaseOutput(output, it) }
            output.owner = owner
            streams[owner.key] = audio
            audioMeta[owner.key] = meta
            if (capture != null) audioCaptures[owner.key] = capture
            if (microphone != null) pendingMicrophone[owner.key] = microphone
        }
        return linkedMapOf("type" to type, "dataPort" to ports.first, "controlPort" to ports.second,
            "streamConnectionID" to unsignedPlistInteger(connectionId ?: 0L))
    }

    override fun onDataStream(session: AirPlaySession, stream: Map<String, Any?>): Map<String, Any?>? {
        val uuid = (stream["clientTypeUUID"] as? String)?.uppercase() ?: return null
        if (uuid != IAP_DATASTREAM_UUID) return null
        val shared = session.sharedSecret ?: return null
        val seed = unsignedPlistDecimal(stream["seed"]) ?: return null
        session.logDebug(
            "AirPlay iAP SETUP uuid=$uuid seed=$seed " +
                "streamConnectionID=${unsignedPlistDecimal(stream["streamConnectionID"]) ?: "none"}",
        )
        val key = AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$seed".toByteArray(Charsets.US_ASCII),
            DATASTREAM_OUTPUT_KEY.toByteArray(Charsets.US_ASCII),
            32,
        )
        val tunnel = IapTunnel(
            readKey = key,
            bindAddress = if (session.isWireless) {
                InetAddress.getByName("::")
            } else {
                session.localAddress
                    ?: when (session.remoteAddress) {
                        is Inet6Address -> InetAddress.getByName("::")
                        is Inet4Address -> InetAddress.getByName("0.0.0.0")
                        else -> InetAddress.getByName("0.0.0.0")
                    }
            },
        )
        val bridge = AirPlayIapTunnelStream(session, tunnel)
        val handler = iapTunnelHandler
        val port = try {
            if (handler != null) {
                val boundPort = bridge.listen()
                session.logDebug(
                    "AirPlay iAP tunnel listening address=" +
                        "${if (session.isWireless) "::" else session.localAddress?.hostAddress ?: "wildcard"} " +
                        "port=$boundPort",
                )
                replacePendingIapTunnel(session, PendingIapTunnel(bridge, handler))
                boundPort
            } else {
                tunnel.listen(
                    object : IapTunnel.Listener {
                        override fun onIap(bytes: ByteArray) = sink.onIapMessage(bytes)

                        override fun onClosed(cause: Throwable?) {
                            Log.w(
                                TAG,
                                "iAP tunnel ended reason=${cause?.message ?: "peer EOF"}",
                            )
                            session.close()
                        }
                    },
                )
            }
        } catch (error: Throwable) {
            bridge.close()
            throw error
        }
        streams[StreamKey(session, STREAM_TYPE_DATA)] = if (handler != null) bridge else tunnel
        return linkedMapOf<String, Any?>("type" to STREAM_TYPE_DATA, "streamID" to 1L, "dataPort" to port)
            .apply {
                stream["streamConnectionID"]?.let { connectionId ->
                    this["streamConnectionID"] = unsignedPlistInteger(connectionId)
                }
            }
    }

    override fun onSetupResponseSent(session: AirPlaySession) {
        pendingMicrophone.forEach { (key, config) ->
            if (key.session === session) {
                val output = slot(key.type)
                synchronized(output) {
                    if (pendingMicrophone[key] === config &&
                        (output.owner?.key?.let { it == key } != false) && startedMicrophone.add(key)) {
                        sink.onMicrophoneStarted(key.type, config)
                    }
                }
            }
        }
        val pending = pendingIapTunnels.remove(session) ?: return
        val attached = try {
            pending.handler(pending.bridge)
        } catch (error: Throwable) {
            Log.w(TAG, "iAP tunnel relay attachment failed", error)
            false
        }
        if (!attached) {
            Log.w(TAG, "iAP tunnel relay attachment was rejected after SETUP")
            pending.bridge.close()
            session.close()
        }
    }

    override fun onFeedback(session: AirPlaySession): Map<String, Any?>? {
        val active = audioMeta.entries.filter { it.key.session === session }.map { it.value }
        if (active.isEmpty()) return null
        val streams = active.map { meta ->
            val entry = linkedMapOf<String, Any?>(
                "type" to meta.type,
                "sampleRate" to meta.format.sampleRate,
            )
            val firstSample = meta.firstSample
            val originNs = meta.originNs
            if (firstSample != null && originNs != null) {
                val nowNs = System.nanoTime()
                val elapsedSec = Math.max(
                    0.0,
                    (nowNs - originNs) / 1e9 - meta.playoutLatencyMs / 1000.0,
                )
                val firstUnsigned = firstSample.toLong() and 0xffff_ffffL
                val sampleTime = (firstUnsigned + Math.round(elapsedSec * meta.format.sampleRate)) and
                    0xffff_ffffL
                entry["streamConnectionID"] = unsignedPlistInteger(meta.connectionId ?: 0L)
                entry["timestamp"] = session.syncedNtp()
                entry["timestampRawNs"] = nowNs
                entry["sampleTime"] = sampleTime
            }
            entry
        }
        return linkedMapOf("streams" to streams)
    }

    override fun onTeardown(session: AirPlaySession, type: Int) {
        val key = StreamKey(session, type)
        if (type == STREAM_TYPE_DATA) {
            clearPendingIapTunnel(session)
            streams.remove(key)?.close()
            return
        }
        val output = slot(type)
        synchronized(output) {
            val owner = output.owner
            if (owner?.key == key) releaseOutput(output, owner)
            else {
                // No sink-wide effects for a session that does not own this output.
                streams.remove(key)?.close()
                audioMeta.remove(key)
                audioCaptures.remove(key)?.close()
                if (owner == null) stopMicrophone(key)
            }
        }
    }

    override fun onSessionClosed(session: AirPlaySession) {
        clearPendingIapTunnel(session)
        outputSlots.forEach { (type, output) ->
            synchronized(output) {
                output.owner?.takeIf { it.key.session === session }?.let { releaseOutput(output, it) }
            }
        }
        streams.keys.filter { it.session === session }.forEach { streams.remove(it)?.close() }
        audioMeta.keys.filter { it.session === session }.forEach { audioMeta.remove(it) }
        audioCaptures.keys.filter { it.session === session }.forEach { audioCaptures.remove(it)?.close() }
        pendingMicrophone.keys.filter { it.session === session }.forEach { key ->
            val output = slot(key.type)
            synchronized(output) { if (output.owner == null) stopMicrophone(key) }
        }
    }

    private fun replacePendingIapTunnel(session: AirPlaySession, next: PendingIapTunnel) {
        val previous = pendingIapTunnels.put(session, next)
        previous?.bridge?.close()
    }

    private fun clearPendingIapTunnel(session: AirPlaySession? = null) {
        if (session == null) {
            val pending = pendingIapTunnels.values.toList()
            pendingIapTunnels.clear()
            pending.forEach { it.bridge.close() }
            return
        }
        pendingIapTunnels.remove(session)?.bridge?.close()
    }

    private fun outputKey(session: AirPlaySession, stream: Map<String, Any?>): ByteArray? {
        return dataStreamKey(session, stream, DATASTREAM_OUTPUT_KEY)
    }

    private fun stopMicrophone(key: StreamKey) {
        pendingMicrophone.remove(key)
        if (startedMicrophone.remove(key)) sink.onMicrophoneStopped(key.type)
    }

    private fun microphoneConfig(
        session: AirPlaySession,
        type: Int,
        stream: Map<String, Any?>,
        format: AudioFormat,
    ): MicrophoneConfig? {
        val port = requestedMicrophonePort(microphoneEnabled, type, stream) ?: return null
        if (format.codec == AudioCodecKind.AAC_LC) {
            Log.w(TAG, "microphone input requested with unsupported AAC-LC format")
            return null
        }
        val host = session.remoteAddress ?: return null
        val key = dataStreamKey(session, stream, DATASTREAM_INPUT_KEY) ?: return null
        val formatBits = (stream["audioFormat"] as? Number)?.toLong() ?: 0L
        val micRate = if (format.codec == AudioCodecKind.OPUS) {
            AudioStreamCodec.opusCaptureRate(formatBits)
        } else {
            format.sampleRate
        }
        val framesPerPacket = (stream["framesPerPacket"] as? Number)?.toInt() ?: 0
        val frameMillis = if (format.codec == AudioCodecKind.OPUS) {
            20
        } else if (framesPerPacket > 0) {
            Math.round(framesPerPacket * 1000.0 / micRate).toInt().coerceIn(5, 60)
        } else {
            20
        }
        val opusBitrate = if (micRate <= 24_000) 48_000 else 96_000
        return MicrophoneConfig(
            audioType = format.audioType,
            sampleRate = micRate,
            channels = if (format.codec == AudioCodecKind.OPUS) 1 else format.channels,
            payloadType = type,
            frameMillis = frameMillis,
            host = host,
            port = port,
            key = key,
            codec = format.codec,
            bitrate = if (format.codec == AudioCodecKind.OPUS) opusBitrate else null,
            pcmMaxBodyBytes = microphonePcmMaxBodyBytes,
        )
    }

    private fun dataStreamKey(
        session: AirPlaySession,
        stream: Map<String, Any?>,
        label: String,
    ): ByteArray? {
        val shared = session.sharedSecret ?: return null
        val connectionId = unsignedPlistDecimal(stream["streamConnectionID"]) ?: return null
        return AirPlayCrypto.hkdfSha512(
            shared,
            "DataStream-Salt$connectionId".toByteArray(Charsets.US_ASCII),
            label.toByteArray(Charsets.US_ASCII),
            32,
        )
    }

    private fun isScreenStreamType(type: Int): Boolean =
        type == STREAM_TYPE_MAIN_SCREEN || type == STREAM_TYPE_ALT_SCREEN

    private companion object {
        const val TAG = "xcertplay-usb"
        const val STREAM_TYPE_MAIN_SCREEN = 110
        const val STREAM_TYPE_ALT_SCREEN = 111
        const val STREAM_TYPE_MAIN_AUDIO = 100
        const val STREAM_TYPE_MAIN_HIGH_AUDIO = 102
        const val STREAM_TYPE_DATA = 130
        const val DATASTREAM_OUTPUT_KEY = "DataStream-Output-Encryption-Key"
        const val DATASTREAM_INPUT_KEY = "DataStream-Input-Encryption-Key"
        const val IAP_DATASTREAM_UUID = "E9459FD0-BCAD-4C45-820F-1E72447EF2F2"
    }
}

/** The input port, rather than the downlink category, signals that the phone requests a mic. */
internal fun requestedMicrophonePort(
    microphoneEnabled: Boolean,
    type: Int,
    stream: Map<String, Any?>,
): Int? {
    if (!microphoneEnabled || type != 100) return null
    return (stream["dataPort"] as? Number)?.toLong()?.takeIf { it in 1..65535 }?.toInt()
}

internal fun unsignedPlistDecimal(value: Any?): String? = when (value) {
    is Long -> java.lang.Long.toUnsignedString(value)
    is Int -> Integer.toUnsignedString(value)
    is Short -> (value.toInt() and 0xffff).toString()
    is Byte -> (value.toInt() and 0xff).toString()
    is BigInteger -> if (value.signum() >= 0) value.toString() else null
    else -> (value as? Number)?.toLong()?.let(java.lang.Long::toUnsignedString)
}

internal fun unsignedPlistInteger(value: Any?): Any = when (value) {
    is Long -> if (value < 0) BigInteger(java.lang.Long.toUnsignedString(value)) else value
    is Int -> if (value < 0) BigInteger(Integer.toUnsignedString(value)) else value
    else -> value ?: 0L
}

/** AirPlaySender carEndpoint_forceKeyFrame defaults to the primary stream with empty params. */
internal fun mainScreenKeyFrameCommand(): Map<String, Any?> =
    linkedMapOf("type" to "forceKeyFrame", "params" to emptyMap<String, Any?>())
