package com.shilapi.xcertplay

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import com.heavendoor.ultralab.UltraMapBuffers
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.opengl.EGL14
import android.opengl.GLES11Ext
import android.opengl.GLES20
import android.opengl.Matrix
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Log
import android.view.Surface
import com.shilapi.xcertplay.airplay.ClusterLabDisplay
import com.shilapi.xcertplay.media.AndroidMediaSink
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean

/** Independent decoder surface backed by an offscreen EGL buffer, never a window or overlay. */
internal class ClusterMapRenderer(private val context: Context, enabled: Boolean) : AutoCloseable {
    companion object {
        private var instance: ClusterMapRenderer? = null
        @Synchronized fun obtain(context: Context, enabled: Boolean): ClusterMapRenderer {
            val current = instance?.takeUnless { it.closed }
                ?: ClusterMapRenderer(context.applicationContext, enabled).also { instance = it }
            current.setEnabled(enabled)
            return current
        }
        @Synchronized fun stop() { instance?.close(); instance = null }
    }
    private val thread = HandlerThread("op-instrument-render").apply { start() }
    private val handler = Handler(thread.looper)
    @Volatile private var closed = false
    @Volatile private var enabled = enabled
    @Volatile private var hideNativeLane = context.getSharedPreferences("p7-instrument", Context.MODE_PRIVATE)
        .getBoolean("hide_native_lane", true)
    @Volatile private var streamActive = false
    @Volatile private var decodedSize: Pair<Int, Int>? = null
    private var renderer: AndroidMediaSink? = null
    @Volatile private var offsetProvider: () -> Boolean = { false }
    @Volatile private var surface: Surface? = null
    private var texture: SurfaceTexture? = null
    private var display = EGL14.EGL_NO_DISPLAY
    private var eglContext = EGL14.EGL_NO_CONTEXT
    private var buffer = EGL14.EGL_NO_SURFACE
    private var config: android.opengl.EGLConfig? = null
    private var outputWidth = 0
    private var outputHeight = 0
    private var program = 0
    private var textureId = 0
    private var sequence = 0L
    private var generation = 0L
    @Volatile private var bufferGroups = 0L
    @Volatile private var pixelAllocationBytes = 0L
    @Volatile private var readbacks = 0L
    @Volatile private var jpegEncodes = 0L
    @Volatile private var cacheHits = 0L
    private val capturing = AtomicBoolean()
    private val frameCache = InstrumentFrameCache()
    private var pixels: ByteBuffer? = null
    private var upright: ByteArray? = null
    private var uprightBuffer: ByteBuffer? = null
    private var image: Bitmap? = null
    private var lastRenderedKey: InstrumentFrameKey? = null
    private var lastReadbackUpright = false
    private val ultraCopyPaint = Paint(Paint.FILTER_BITMAP_FLAG)
    private var jpeg: ByteArrayOutputStream? = null
    private var positionAttribute = -1
    private var textureAttribute = -1
    private var matrixUniform = -1
    private var samplerUniform = -1
    private var updatedAt = 0L
    private val transform = FloatArray(16)
    private val cropTransform = FloatArray(16)
    private val sampleTransform = FloatArray(16)
    private var lastLoggedCrop: P7InstrumentFraming.Crop? = null
    private val vertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f,-1f,0f,0f, 1f,-1f,1f,0f, -1f,1f,0f,1f, 1f,1f,1f,1f)); position(0) }
    // Flip the output quad on the GPU for Ultra. glReadPixels then already has top-down rows.
    private val uprightVertices = ByteBuffer.allocateDirect(16 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
        .apply { put(floatArrayOf(-1f,1f,0f,0f, 1f,1f,1f,0f, -1f,-1f,0f,1f, 1f,-1f,1f,1f)); position(0) }
    @Volatile private var exporter: InstrumentFrameServer? = null
    val available: Boolean get() = !closed && surface != null

    init {
        handler.post {
            try {
                if (this.enabled) initialize()
                if (this.enabled && P7InstrumentCommand.supported()) startExport()
            } catch (_: Exception) { closed = true; release(); thread.quitSafely() }
        }
    }

    fun setEnabled(value: Boolean) {
        enabled = value
        handler.post {
            if (closed) return@post
            if (value) {
                if (surface == null) {
                    try { initialize() } catch (_: Exception) { closed = true; release(); thread.quitSafely(); return@post }
                }
                if (P7InstrumentCommand.supported()) startExport()
            } else {
                exporter?.close(); exporter = null; frameCache.clear()
                // An already negotiated ALT stream must keep draining its SurfaceTexture.
                // Initial disabled state never allocates EGL; an active surface lives to session teardown.
            }
        }
    }

    private fun startExport() {
        if (exporter == null) exporter = InstrumentFrameServer(::captureEncoded) { hideNativeLane }
    }
    fun setHideNativeLane(value: Boolean) { hideNativeLane = value }

    fun configure(profile: ClusterLabDisplay.Profile, content: ClusterLabDisplay.Content) = Unit
    fun offered(content: Set<ClusterLabDisplay.Content>) = Unit

    fun bind(next: AndroidMediaSink, shouldOffset: () -> Boolean = { false }) {
        handler.post {
            if (closed) return@post
            if (renderer !== next) {
                renderer?.clearStreamObserver(this)
                surface?.let { renderer?.clearSurface(ClusterLabDisplay.TYPE, it) }
                decodedSize = null; streamActive = false; sequence = 0
                lastLoggedCrop = null; generation++; frameCache.clear()
            }
            renderer = next
            offsetProvider = shouldOffset
            next.setStreamObserver(this,
                { type, value ->
                    handler.post {
                        // A callback can already be in flight when the old sink is detached.
                        if (!closed && renderer === next) active(type, value)
                    }
                },
                { type, width, height ->
                    handler.post {
                        if (!closed && renderer === next) decoded(type, width, height)
                    }
                },
            )
            val profile = if(com.heavendoor.ultralab.UltraPreferences.outputMode(context)==com.heavendoor.ultralab.UltraOutputMode.MAP) ClusterLabDisplay.Profile.P7_CENTERED else ClusterLabDisplay.Profile.WIDE
            next.setStreamDimensions(ClusterLabDisplay.TYPE, profile.width, profile.height)
            Log.i("OPInstrumentSize", "build=1349 requested=${profile.width}x${profile.height}")
            surface?.let { next.setSurface(ClusterLabDisplay.TYPE, it) }
            val state = next.screenStateSnapshot()
            streamActive = ClusterLabDisplay.TYPE in state.activeTypes
            decodedSize = state.decodedSizes[ClusterLabDisplay.TYPE]
        }
    }

    fun session(value: Boolean) {
        if (!value) {
            streamActive = false; decodedSize = null
            handler.post { generation++; frameCache.clear() }
        }
    }
    fun active(type: Int, value: Boolean) {
        if (type == ClusterLabDisplay.TYPE) {
            streamActive = value
            if (!value) {
                decodedSize = null
                handler.post { generation++; frameCache.clear() }
            }
        }
    }
    fun decoded(type: Int, width: Int, height: Int) {
        if (type == ClusterLabDisplay.TYPE && width in 1..4096 && height in 1..4096) {
            val crop = P7InstrumentFraming.crop(width, height, offsetProvider())
            Log.i("OPInstrumentSize", "build=1349 decoded=${width}x${height} crop=${crop.left},${crop.top},${crop.width},${crop.height}")
            decodedSize = width to height
        }
    }

    private fun initialize() {
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        check(display != EGL14.EGL_NO_DISPLAY)
        val version = IntArray(2)
        check(EGL14.eglInitialize(display, version, 0, version, 1))
        val choices = arrayOfNulls<android.opengl.EGLConfig>(1)
        val count = IntArray(1)
        check(EGL14.eglChooseConfig(display, intArrayOf(
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL14.EGL_SURFACE_TYPE, EGL14.EGL_PBUFFER_BIT,
            EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_ALPHA_SIZE,8,
            EGL14.EGL_NONE),0,choices,0,1,count,0) && count[0] > 0)
        config = choices[0]
        eglContext = EGL14.eglCreateContext(display, config, EGL14.EGL_NO_CONTEXT,
            intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE),0)
        check(eglContext != EGL14.EGL_NO_CONTEXT)
        resize(664,664)
        val ids = IntArray(1)
        GLES20.glGenTextures(1,ids,0);textureId=ids[0]
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE)
        val vertex = shader(GLES20.GL_VERTEX_SHADER,
            "attribute vec4 p;attribute vec4 t;uniform mat4 m;varying vec2 uv;void main(){gl_Position=p;uv=(m*t).xy;}")
        val fragment = shader(GLES20.GL_FRAGMENT_SHADER,
            "#extension GL_OES_EGL_image_external : require\nprecision mediump float;uniform samplerExternalOES s;varying vec2 uv;void main(){gl_FragColor=texture2D(s,uv);}")
        program=GLES20.glCreateProgram()
        GLES20.glAttachShader(program,vertex);GLES20.glAttachShader(program,fragment);GLES20.glLinkProgram(program)
        val linked=IntArray(1);GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,linked,0)
        GLES20.glDeleteShader(vertex);GLES20.glDeleteShader(fragment);check(linked[0]!=0)
        positionAttribute=GLES20.glGetAttribLocation(program,"p")
        textureAttribute=GLES20.glGetAttribLocation(program,"t")
        matrixUniform=GLES20.glGetUniformLocation(program,"m")
        samplerUniform=GLES20.glGetUniformLocation(program,"s")
        generation++;frameCache.clear()
        texture=SurfaceTexture(textureId).apply {
            setDefaultBufferSize(ClusterLabDisplay.Profile.WIDE.width, ClusterLabDisplay.Profile.WIDE.height)
            setOnFrameAvailableListener({
                if (!closed) runCatching {
                    // Drain every decoded frame, even though JPEG export is limited to the instrument request.
                    updateTexImage();getTransformMatrix(transform)
                    sequence++;updatedAt=SystemClock.elapsedRealtime()
                }
            },handler)
        }
        surface=Surface(texture)
        renderer?.setSurface(ClusterLabDisplay.TYPE, requireNotNull(surface))
    }

    private fun shader(type: Int, source: String): Int {
        val id=GLES20.glCreateShader(type)
        GLES20.glShaderSource(id,source);GLES20.glCompileShader(id)
        val good=IntArray(1);GLES20.glGetShaderiv(id,GLES20.GL_COMPILE_STATUS,good,0)
        check(good[0]!=0);return id
    }

    private fun resize(width: Int, height: Int) {
        if (outputWidth==width && outputHeight==height) return
        if (buffer!=EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,eglContext)
            EGL14.eglDestroySurface(display,buffer)
        }
        buffer=EGL14.eglCreatePbufferSurface(display,config,
            intArrayOf(EGL14.EGL_WIDTH,width,EGL14.EGL_HEIGHT,height,EGL14.EGL_NONE),0)
        check(buffer!=EGL14.EGL_NO_SURFACE && EGL14.eglMakeCurrent(display,buffer,buffer,eglContext))
        outputWidth=width;outputHeight=height
    }

    fun performanceSnapshot() = InstrumentRenderStats(bufferGroups, pixelAllocationBytes, readbacks, jpegEncodes, cacheHits)

    private fun captureEncoded(width: Int, height: Int): InstrumentEncodedFrame? =
        runCapture(width, height) { key ->
            val cached = frameCache.get(key)
            if (cached != null) { cacheHits++; cached } else run {
                renderFrame(key)
                val encoded = jpeg ?: ByteArrayOutputStream(128 * 1024).also { jpeg = it }
                encoded.reset()
                check(requireNotNull(image).compress(Bitmap.CompressFormat.JPEG, 70, encoded))
                check(encoded.size() in 4..1_048_576)
                jpegEncodes++
                frameCache.put(key, InstrumentEncodedFrame(key.sequence, encoded.toByteArray()))
            }
        }

    // Retained for the synthetic pixel fixture. Production exports immutable JPEG, never a borrowed Bitmap.
    @Suppress("unused")
    private fun capture(width: Int, height: Int): Pair<Long, Bitmap>? =
        runCapture(width, height, dispose = { it.second.recycle() }) { key ->
            renderFrame(key)
            key.sequence to requireNotNull(requireNotNull(image).copy(Bitmap.Config.ARGB_8888, false))
        }

    internal fun captureUltra(width:Int,height:Int): Pair<Long,Bitmap>? =
        runCapture(width,height,fullFrame=true,dispose={UltraMapBuffers.release(it.second)}) { key ->
            renderFrame(key,uprightReadback=true)
            val bitmap = UltraMapBuffers.acquire(width,height)
            if (bitmap == null) null else try {
                Canvas(bitmap).drawBitmap(requireNotNull(image),0f,0f,ultraCopyPaint)
                ((key.generation shl 32) xor key.sequence) to bitmap
            } catch (e: Exception) { UltraMapBuffers.release(bitmap); throw e }
        }

    private fun <T> runCapture(
        width: Int,
        height: Int,
        fullFrame: Boolean = false,
        dispose: (T) -> Unit = {},
        render: (InstrumentFrameKey) -> T?,
    ): T? {
        if (width !in 64..2048 || height !in 64..1080 || closed || !enabled || !streamActive ||
            !capturing.compareAndSet(false, true)) return null
        val request = InstrumentCaptureRequest(dispose)
        if (!handler.post {
                try {
                    val size = decodedSize
                    val result = if (closed || !enabled || !streamActive || size == null ||
                        sequence == 0L || surface == null || SystemClock.elapsedRealtime() - updatedAt > 5000) null
                    else {
                        val offset = offsetProvider()
                        val crop = if (fullFrame) P7InstrumentFraming.ultraCrop(size.first, size.second, offset) else P7InstrumentFraming.crop(size.first, size.second, offset)
                        // updateTexImage and its transform are changed together with sequence, on this thread.
                        val key = InstrumentFrameKey(generation, sequence, sequence, size.first, size.second,
                            width, height, crop, offset)
                        render(key)
                    }
                    if (closed || !enabled || !streamActive) {
                        result?.let(dispose)
                        request.complete(null)
                    } else request.complete(result)
                } catch (_: Exception) { request.complete(null) }
                finally { capturing.set(false) }
            }) {
            capturing.set(false)
            return null
        }
        return try { request.await(1500) }
        catch (_: InterruptedException) { request.abandon(); Thread.currentThread().interrupt(); null }
    }

    private fun renderFrame(key: InstrumentFrameKey, uprightReadback: Boolean = false) {
        if (lastRenderedKey == key && lastReadbackUpright == uprightReadback && image != null) { cacheHits++; return }
        val width = key.width; val height = key.height; val crop = key.crop
        resize(width, height)
        GLES20.glViewport(0,0,width,height);GLES20.glClearColor(0f,0f,0f,1f)
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        if (crop != lastLoggedCrop) {
            Log.i("OPInstrumentFraming", "amapNavigation=${key.amapNavigating} source=${key.sourceWidth}x${key.sourceHeight} crop=${crop.left},${crop.top},${crop.width},${crop.height}")
            lastLoggedCrop = crop
        }
        crop.writeUvMatrix(key.sourceWidth,key.sourceHeight,cropTransform)
        Matrix.multiplyMM(sampleTransform,0,transform,0,cropTransform,0)
        val scale=minOf(width.toDouble()/crop.width,height.toDouble()/crop.height)
        val w=(crop.width*scale).toInt().coerceIn(1,width)
        val h=(crop.height*scale).toInt().coerceIn(1,height)
        GLES20.glViewport((width-w)/2,(height-h)/2,w,h)
        GLES20.glUseProgram(program)
        val quad=if(uprightReadback)uprightVertices else vertices
        quad.position(0);GLES20.glVertexAttribPointer(positionAttribute,2,GLES20.GL_FLOAT,false,16,quad)
        quad.position(2);GLES20.glVertexAttribPointer(textureAttribute,2,GLES20.GL_FLOAT,false,16,quad)
        GLES20.glEnableVertexAttribArray(positionAttribute);GLES20.glEnableVertexAttribArray(textureAttribute)
        GLES20.glUniformMatrix4fv(matrixUniform,1,false,sampleTransform,0)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES,textureId)
        GLES20.glUniform1i(samplerUniform,0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP,0,4)
        if (image?.width != width || image?.height != height) {
            image?.recycle()
            pixels=ByteBuffer.allocateDirect(width*height*4)
            upright=null;uprightBuffer=null
            image=Bitmap.createBitmap(width,height,Bitmap.Config.ARGB_8888)
            bufferGroups++
            pixelAllocationBytes += width.toLong()*height*4*2
        }
        val rgba = requireNotNull(pixels)
        rgba.clear()
        GLES20.glReadPixels(0,0,width,height,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,rgba)
        check(GLES20.glGetError()==GLES20.GL_NO_ERROR)
        readbacks++
        if(uprightReadback){rgba.position(0);requireNotNull(image).copyPixelsFromBuffer(rgba)}
        else{
            val rows=upright ?: ByteArray(width*height*4).also {upright=it;uprightBuffer=ByteBuffer.wrap(it);pixelAllocationBytes+=it.size}
            for (row in 0 until height) {rgba.position((height-1-row)*width*4);rgba.get(rows,row*width*4,width*4)}
            val uprightPixels=requireNotNull(uprightBuffer);uprightPixels.position(0)
            requireNotNull(image).copyPixelsFromBuffer(uprightPixels)
        }
        lastRenderedKey = key
        lastReadbackUpright = uprightReadback
    }

    fun dispose() = close()
    override fun close() {
        closed=true;streamActive=false
        exporter?.close();exporter=null
        handler.post { release();thread.quitSafely() }
    }
    private fun release() {
        exporter?.close();exporter=null
        renderer?.clearStreamObserver(this)
        surface?.let { renderer?.clearSurface(ClusterLabDisplay.TYPE,it);it.release() }
        surface=null;renderer=null;offsetProvider={false};texture?.release();texture=null
        lastRenderedKey=null;frameCache.clear();image?.recycle();image=null;pixels=null;upright=null;uprightBuffer=null;jpeg=null
        if (display!=EGL14.EGL_NO_DISPLAY) {
            if (program!=0) GLES20.glDeleteProgram(program)
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT)
            if (buffer!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,buffer)
            if (eglContext!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,eglContext)
            EGL14.eglReleaseThread();EGL14.eglTerminate(display)
        }
        display=EGL14.EGL_NO_DISPLAY
    }
}
