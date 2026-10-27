package com.shilapi.xcertplay

import android.app.Activity
import android.os.Bundle
import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.SurfaceTexture
import android.graphics.Paint
import android.view.Surface
import java.io.File

/** Synthetic pixel check of the real offscreen renderer; never in the release APK. */
class InstrumentHeadlessFixtureActivity : Activity() {
    private var renderer: ClusterMapRenderer? = null
    override fun onCreate(state: Bundle?) {
        super.onCreate(state)
        val r=ClusterMapRenderer(this,true);renderer=r
        Thread {
            val output=File(filesDir,"headless-check.txt")
            try {
                val field=ClusterMapRenderer::class.java.getDeclaredField("surface").apply{isAccessible=true}
                repeat(40){if(field.get(r)==null)Thread.sleep(100)}
                val surface=field.get(r) as Surface
                // Match the synthetic producer's real buffer to the declared decoded dimensions.
                val fixtureTexture=ClusterMapRenderer::class.java.getDeclaredField("texture").apply{isAccessible=true}.get(r) as SurfaceTexture
                fixtureTexture.setDefaultBufferSize(664,664)
                r.session(true);r.active(111,true);r.decoded(111,664,664)
                val capture=ClusterMapRenderer::class.java.getDeclaredMethod("capture",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}
                val encoded=ClusterMapRenderer::class.java.getDeclaredMethod("captureEncoded",Int::class.javaPrimitiveType,Int::class.javaPrimitiveType).apply{isAccessible=true}
                val paint=Paint()
                var sequence=0L
                repeat(2){ pass ->
                    val c=surface.lockCanvas(null)
                    c.drawColor(Color.BLACK)
                    paint.color=if(pass==0)Color.RED else Color.GREEN
                    c.drawRect(0f,0f,332f,332f,paint)
                    paint.color=Color.BLUE;c.drawRect(332f,332f,664f,664f,paint)
                    surface.unlockCanvasAndPost(c)
                    Thread.sleep(400)
                    @Suppress("UNCHECKED_CAST")
                    val frame=capture.invoke(r,660,660) as Pair<Long,Bitmap>?
                    check(frame!=null && frame.first>sequence);sequence=frame.first
                    val top=frame.second.getPixel(100,100);val bottom=frame.second.getPixel(550,550)
                    check(if(pass==0)Color.red(top)>220&&Color.blue(top)<30 else Color.green(top)>220&&Color.red(top)<30)
                    check(Color.blue(bottom)>220&&Color.red(bottom)<30)
                    check(Color.red(frame.second.getPixel(550,100))<30)
                    frame.second.recycle()
                    val before=r.performanceSnapshot()
                    check(before.bufferGroups==1L)
                    val first=encoded.invoke(r,660,660) as InstrumentEncodedFrame
                    val encodedStats=r.performanceSnapshot()
                    val same=encoded.invoke(r,660,660) as InstrumentEncodedFrame
                    val hitStats=r.performanceSnapshot()
                    check(first===same && first.bytes===same.bytes)
                    check(encodedStats.jpegEncodes==before.jpegEncodes+1 && encodedStats.readbacks in before.readbacks..before.readbacks+1)
                    check(hitStats.jpegEncodes==encodedStats.jpegEncodes && hitStats.readbacks==encodedStats.readbacks)
                    check(hitStats.cacheHits==encodedStats.cacheHits+1 && hitStats.bufferGroups==1L)
                    check(hitStats.pixelAllocationBytes==660L*660*4*3)
                    val jpeg=BitmapFactory.decodeByteArray(first.bytes,0,first.bytes.size)
                    check(jpeg.width==660 && jpeg.height==660)
                    val jpegTop=jpeg.getPixel(100,100)
                    check(if(pass==0)Color.red(jpegTop)>220 else Color.green(jpegTop)>220)
                    jpeg.recycle()
                }
                val beforeResize=r.performanceSnapshot()
                encoded.invoke(r,664,664) as InstrumentEncodedFrame
                val resized=r.performanceSnapshot()
                check(resized.bufferGroups==beforeResize.bufferGroups+1)
                // The accepted AMap rule can change with no new source frame.
                var navigating=false
                ClusterMapRenderer::class.java.getDeclaredField("offsetProvider").apply{isAccessible=true}.set(r,{navigating})
                val texture=ClusterMapRenderer::class.java.getDeclaredField("texture").apply{isAccessible=true}.get(r) as SurfaceTexture
                texture.setDefaultBufferSize(1328,664)
                r.decoded(111,1328,664)
                val c=surface.lockCanvas(null)
                c.drawColor(Color.BLACK)
                paint.color=Color.RED;c.drawRect(332f,0f,544f,664f,paint)
                paint.color=Color.GREEN;c.drawRect(544f,0f,996f,664f,paint)
                paint.color=Color.BLUE;c.drawRect(996f,0f,1208f,664f,paint)
                surface.unlockCanvasAndPost(c)
                Thread.sleep(400)
                val normal=encoded.invoke(r,660,660) as InstrumentEncodedFrame
                navigating=true
                val shifted=encoded.invoke(r,660,660) as InstrumentEncodedFrame
                check(normal.sequence==shifted.sequence && normal!==shifted)
                val a=BitmapFactory.decodeByteArray(normal.bytes,0,normal.bytes.size)
                val b=BitmapFactory.decodeByteArray(shifted.bytes,0,shifted.bytes.size)
                check(Color.red(a.getPixel(50,330))>220 && Color.green(b.getPixel(50,330))>220)
                check(Color.green(a.getPixel(610,330))>220 && Color.blue(b.getPixel(610,330))>220)
                a.recycle();b.recycle()
                val finalStats=r.performanceSnapshot()
                check(finalStats.bufferGroups==3L)
                // Compare the new Ultra GPU flip against the accepted CPU-row-flip path.
                texture.setDefaultBufferSize(1920,720);navigating=false;r.decoded(111,1920,720)
                val quad=surface.lockCanvas(null)
                paint.color=Color.RED;quad.drawRect(0f,0f,960f,360f,paint)
                paint.color=Color.GREEN;quad.drawRect(960f,0f,1920f,360f,paint)
                paint.color=Color.BLUE;quad.drawRect(0f,360f,960f,720f,paint)
                paint.color=Color.YELLOW;quad.drawRect(960f,360f,1920f,720f,paint)
                surface.unlockCanvasAndPost(quad);Thread.sleep(400)
                @Suppress("UNCHECKED_CAST")
                val reference=capture.invoke(r,1920,720) as Pair<Long,Bitmap>
                com.heavendoor.ultralab.UltraMapBuffers.begin()
                val gpu=r.captureUltra(1920,720) ?: error("Ultra capture missing")
                for(y in 20 until 720 step 41)for(x in 20 until 1920 step 53){
                    val a=reference.second.getPixel(x,y);val b=gpu.second.getPixel(x,y)
                    check(kotlin.math.abs(Color.red(a)-Color.red(b))<=8&&kotlin.math.abs(Color.green(a)-Color.green(b))<=4&&kotlin.math.abs(Color.blue(a)-Color.blue(b))<=8){"Ultra flip mismatch at $x,$y"}
                }
                val cachedBefore=r.performanceSnapshot()
                com.heavendoor.ultralab.UltraMapBuffers.release(gpu.second)
                val repeated=r.captureUltra(1920,720) ?: error("Ultra cached capture missing")
                check(r.performanceSnapshot().readbacks==cachedBefore.readbacks)
                com.heavendoor.ultralab.UltraMapBuffers.release(repeated.second)
                com.heavendoor.ultralab.UltraMapBuffers.clear();reference.second.recycle()
                // A callback captured by an old sink may arrive after a new sink is bound.
                val firstSink=com.shilapi.xcertplay.media.AndroidMediaSink(applicationContext)
                val nextSink=com.shilapi.xcertplay.media.AndroidMediaSink(applicationContext)
                val glHandler=ClusterMapRenderer::class.java.getDeclaredField("handler").apply{isAccessible=true}.get(r) as android.os.Handler
                fun barrier() { val task=java.util.concurrent.FutureTask<Unit>{ Unit };check(glHandler.post(task));task.get(3,java.util.concurrent.TimeUnit.SECONDS) }
                r.bind(firstSink);barrier()
                val observer=com.shilapi.xcertplay.media.AndroidMediaSink::class.java.getDeclaredField("streamObserver").apply{isAccessible=true}.get(firstSink)
                @Suppress("UNCHECKED_CAST")
                val late=observer.javaClass.getDeclaredField("active").apply{isAccessible=true}.get(observer) as (Int,Boolean)->Unit
                r.bind(nextSink);barrier()
                nextSink.onScreenStreamActive(111,true);barrier()
                late(111,false);barrier()
                check(ClusterMapRenderer::class.java.getDeclaredField("streamActive").apply{isAccessible=true}.getBoolean(r))
                firstSink.close();nextSink.close()
                r.session(false);check(capture.invoke(r,660,660)==null)
                r.session(true);r.active(111,true);r.decoded(111,664,664)
                r.setEnabled(false);check(capture.invoke(r,660,660)==null)
                output.writeText("PASS: headless EGL; reusable pixels; upright RGB; Ultra GPU flip matches CPU reference; Ultra duplicate capture avoids GPU readback; JPEG reuse; size invalidation; no-frame AMap shift; late observer isolation; disconnect; disabled\n"+
                    "pooledPixelGroups=${finalStats.bufferGroups} allocatedPixelBytes=${finalStats.pixelAllocationBytes} readbacks=${finalStats.readbacks} jpegEncodes=${finalStats.jpegEncodes} cacheHits=${finalStats.cacheHits}\n"+
                    "steady660GroupAllocations=1 steady660PixelBytes=${660L*660*4*3} sameFrameExtraReadbacks=0 sameFrameExtraEncodes=0")
            }catch(e:Throwable){output.writeText("FAIL: "+e.stackTraceToString())}
            finally{r.close()}
        }.start()
    }
    override fun onDestroy(){renderer?.close();super.onDestroy()}
}
