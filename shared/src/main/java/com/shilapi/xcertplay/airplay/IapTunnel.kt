package com.shilapi.xcertplay.airplay

import java.io.Closeable
import java.io.InputStream
import java.net.Inet4Address
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/**
 * Receive-only iAP2-over-CarPlay DataStream tunnel (stream type 130).
 *
 * The TCP stream is NetSocketChaCha20Poly1305 framed, then carries APTransportPackage records.
 * iAP2 bodies (messageType "comm") are emitted verbatim for the wired iAP2 relay.
 */
class IapTunnel(
    private val readKey: ByteArray,
    bindAddress: InetAddress = InetAddress.getByName("0.0.0.0"),
) : Closeable {
    interface Listener {
        fun onOpen(remoteAddress: String?) {}
        fun onIap(bytes: ByteArray) {}
        fun onDebug(message: String) {}
        fun onClosed(cause: Throwable?) {}
    }

    private val closed = AtomicBoolean(false)
    private val readCounter = AtomicLong(0)
    private val bindAddress =
        if (bindAddress is Inet4Address) InetAddress.getByName("0.0.0.0") else bindAddress
    private val servers = mutableListOf<ServerSocket>()
    private var socket: Socket? = null
    private val threads = mutableListOf<Thread>()
    private val peerConnected = CountDownLatch(1)
    @Volatile private var listener: Listener = object : Listener {}

    fun listen(listener: Listener): Int {
        this.listener = listener
        val bound = bindAny()
        servers += bound
        listener.onDebug("AirPlay iAP tunnel listener bound=${bound.localSocketAddress}")
        val secondaryAddress = when {
            bindAddress is java.net.Inet6Address && bindAddress.isAnyLocalAddress -> null
            bindAddress is java.net.Inet6Address -> InetAddress.getByName("0.0.0.0")
            else -> InetAddress.getByName("::")
        }
        secondaryAddress?.let { address ->
            val secondary = ServerSocket()
            runCatching {
                secondary.apply {
                    reuseAddress = true
                    bind(InetSocketAddress(address, bound.localPort))
                }
            }.onSuccess { secondary ->
                servers += secondary
                listener.onDebug(
                    "AirPlay iAP tunnel secondary listener bound=" +
                        "${secondary.localSocketAddress}",
                )
            }.onFailure { error ->
                safeClose(secondary)
                listener.onDebug(
                    "AirPlay iAP tunnel secondary listener failed address=" +
                        "$address port=${bound.localPort}: ${error.message}",
                )
            }
        }
        servers.forEach { server ->
            threads += Thread({ accept(server) }, "airplay-iap-tunnel").apply {
                isDaemon = true
                start()
            }
        }
        return bound.localPort
    }

    private fun bindAny(): ServerSocket =
        ServerSocket().apply {
            reuseAddress = true
            bind(InetSocketAddress(bindAddress, 0))
        }

    override fun close() {
        if (!closed.compareAndSet(false, true)) return
        safeClose(socket)
        servers.toList().forEach(::safeClose)
        servers.clear()
        threads.toList().forEach(Thread::interrupt)
        threads.clear()
        peerConnected.countDown()
    }

    /** Waits until the iPhone has connected to the advertised dataPort. */
    fun awaitPeerConnection(timeoutMillis: Long): Boolean {
        require(timeoutMillis >= 0) { "timeoutMillis must not be negative" }
        return try {
            peerConnected.await(timeoutMillis, TimeUnit.MILLISECONDS)
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            false
        }
    }

    private fun accept(bound: ServerSocket) {
        listener.onDebug(
            "AirPlay iAP tunnel accepting local=${bound.localSocketAddress}",
        )
        while (!closed.get()) {
            val accepted = try {
                bound.accept()
            } catch (error: Exception) {
                if (!closed.get()) listener.onClosed(error)
                return
            }
            if (closed.get()) {
                safeClose(accepted)
                return
            }
            accepted.setSoLinger(true, 0)
            socket = accepted
            readCounter.set(0)
            peerConnected.countDown()
            listener.onOpen(accepted.remoteSocketAddress?.toString())
            run(accepted)
        }
    }

    private fun run(sock: Socket) {
        val ciphertext = BoundedByteQueue(65_535 + FRAME_HEADER_LEN + TAG_SIZE + READ_CHUNK_BYTES)
        val plaintext = BoundedByteQueue(MAX_PACKAGE + 65_535)
        val decryptor = AirPlayCrypto.PacketDecryptor(readKey)
        val aad = ByteArray(FRAME_HEADER_LEN)
        var failure: Throwable? = null
        var announcedData = false
        try {
            val input = sock.getInputStream()
            val buffer = ByteArray(READ_CHUNK_BYTES)
            while (!closed.get()) {
                val read = input.read(buffer)
                if (read < 0) {
                    listener.onDebug("AirPlay iAP tunnel peer EOF")
                    break
                }
                if (!announcedData) {
                    announcedData = true
                    listener.onDebug("AirPlay iAP tunnel received data")
                }
                ciphertext.append(buffer, 0, read)
                while (ciphertext.size >= FRAME_HEADER_LEN) {
                    val length = readU16Le(ciphertext.bytes, ciphertext.start)
                    val frameLength = FRAME_HEADER_LEN + length + TAG_SIZE
                    if (ciphertext.size < frameLength) break
                    ciphertext.bytes.copyInto(aad, 0, ciphertext.start, ciphertext.start + FRAME_HEADER_LEN)
                    val plain = decryptor.open(ciphertext.bytes, ciphertext.start + FRAME_HEADER_LEN,
                        length + TAG_SIZE, AirPlayCrypto.nonce64(readCounter.get()), aad)
                    readCounter.incrementAndGet()
                    ciphertext.discard(frameLength)
                    plaintext.append(plain)
                    parsePackages(plaintext)
                }
            }
        } catch (error: Exception) {
            failure = error
        } finally {
            if (socket === sock) socket = null
            safeClose(sock)
            if (!closed.get() && failure != null) listener.onClosed(failure)
        }
    }

    private fun parsePackages(buffer: BoundedByteQueue) {
        while (buffer.size >= PACKAGE_HEADER_LEN) {
            val size = readU32Be(buffer.bytes, buffer.start)
            require(size in PACKAGE_HEADER_LEN..MAX_PACKAGE) { "Invalid iAP package length" }
            if (buffer.size < size) return
            val messageType = readU32Be(buffer.bytes, buffer.start + MESSAGE_TYPE_OFFSET)
            if (messageType == MSG_TYPE_COMM) {
                listener.onDebug("AirPlay iAP tunnel package type=comm body=${size - PACKAGE_HEADER_LEN}")
                listener.onIap(buffer.copy(PACKAGE_HEADER_LEN, size - PACKAGE_HEADER_LEN))
            }
            buffer.discard(size)
        }
    }

    private fun readU16Le(source: ByteArray, offset: Int): Int =
        (source[offset].toInt() and 0xff) or ((source[offset + 1].toInt() and 0xff) shl 8)

    private fun readU32Be(source: ByteArray, offset: Int): Int =
        ((source[offset].toInt() and 0xff) shl 24) or
            ((source[offset + 1].toInt() and 0xff) shl 16) or
            ((source[offset + 2].toInt() and 0xff) shl 8) or
            (source[offset + 3].toInt() and 0xff)

    private companion object {
        const val FRAME_HEADER_LEN = 2
        const val TAG_SIZE = 16
        const val PACKAGE_HEADER_LEN = 32
        const val MESSAGE_TYPE_OFFSET = 16
        const val MSG_TYPE_COMM = 0x636f6d6d
        const val MAX_PACKAGE = 4 * 1024 * 1024
        const val READ_CHUNK_BYTES = 16 * 1024
    }
}
