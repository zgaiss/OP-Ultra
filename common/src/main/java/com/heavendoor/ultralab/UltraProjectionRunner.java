package com.heavendoor.ultralab;

import android.content.Context;
import android.net.LocalSocket;
import android.net.LocalSocketAddress;
import android.os.SystemClock;
import android.util.Base64;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Arrays;

/** Fixed instrument projection, owned by the signed app's Shizuku data service.
 * No exported command, path, address, SSH credential, or persistent system change.
 */
final class UltraProjectionRunner implements AutoCloseable {
    private static final String ROOT="/tmp/op-ultra-app-v64perf/", BIN=ROOT+"receiver";
    private static final String ASSET_HASH="eca1e864db36bf2f20cbe0f19bce44d45a74f2f843d35139c9457d22cb91773f";
    private final Context context;
    private final int clientUid;
    private final Object lock=new Object();
    private volatile boolean desired,closed;private volatile int restartTicket;
    private volatile LocalSocket source;
    private volatile Socket sink;
    private volatile long progress;
    private volatile int frameCount;
    private volatile long sourceWaitNanos,receiverNanos;
    org.json.JSONObject diagnostics(){try{return new org.json.JSONObject().put("frames",frameCount).put("sourceWaitNanos",sourceWaitNanos).put("receiverNanos",receiverNanos);}catch(Exception e){return new org.json.JSONObject();}}
    private volatile String state="waiting_for_map", issue="";
    private boolean deployed;
    private volatile boolean receiverOwned;
    private int session=0x4100;
    private final Thread worker;

    UltraProjectionRunner(Context context,int clientUid) {
        this.context=context;this.clientUid=clientUid;
        worker=new Thread(this::loop,"ultra-instrument-output");worker.setDaemon(true);worker.start();
        Thread guard=new Thread(()->{while(!closed){SystemClock.sleep(500);if(sink!=null&&SystemClock.elapsedRealtime()-progress>4500)disconnect();}},"ultra-output-lease");
        guard.setDaemon(true);guard.start();
    }
    void restart(){synchronized(lock){restartTicket++;disconnect();lock.notifyAll();}}
    void active(boolean value){synchronized(lock){desired=value;if(!value)disconnect();lock.notifyAll();}}
    String status(){return state+"; frames="+frameCount+(issue.isEmpty()?"":"; "+issue);}
    private boolean active(){return desired&&!closed;}
    boolean awaitInactive(long ms){long until=SystemClock.elapsedRealtime()+ms;while(SystemClock.elapsedRealtime()<until){if(!desired&&!receiverOwned&&source==null&&sink==null)return true;SystemClock.sleep(50);}return false;}
    boolean originalLayerReady()throws IOException{String text=rpc("if [ -x "+BIN+" ]; then "+BIN+" probe; else printf WIDE_NOT_DEPLOYED; fi");return text.contains("WIDE_NOT_DEPLOYED")||text.contains("xres=240\n")&&text.contains("yres=320\n")&&text.contains("blank=4\n");}
    private void disconnect(){LocalSocket a=source;Socket b=sink;try{if(a!=null){android.system.Os.shutdown(a.getFileDescriptor(),android.system.OsConstants.SHUT_RDWR);a.close();}}catch(Exception ignored){}try{if(b!=null)b.close();}catch(Exception ignored){}}
    @Override public void close(){closed=true;active(false);worker.interrupt();}
    private void loop(){
        while(!closed){
            synchronized(lock){while(!closed&&!desired)try{lock.wait();}catch(InterruptedException ignored){}}
            if(closed)break;
            boolean receiverStarted=false;int ticket=restartTicket;
            try{
                state="preparing_receiver";issue="";frameCount=0;sourceWaitNanos=receiverNanos=0;
                deploy();if(!active())continue;
                String probe=rpc(BIN+" probe");
                for(String expected:new String[]{"xres=240\n","yres=320\n","virtual_x=240\n","virtual_y=960\n","bpp=16\n","stride=480\n","blank=4\n","com=4\n","gw=2147483648\n","pos=0\n"})if(!probe.contains(expected))throw new IOException("instrument_busy");
                // Keep the last restored backup, while giving the O_EXCL writer a fresh pair.
                receiverOwned=true;receiverStarted=true;rpc("rm -f "+ROOT+"continuous.stop; if [ -f "+ROOT+"original.state ]; then mv -f "+ROOT+"original.state "+ROOT+"previous.state; fi; if [ -f "+ROOT+"original.pixels ]; then mv -f "+ROOT+"original.pixels "+ROOT+"previous.pixels; fi; "+BIN+" show > "+ROOT+"run.log 2>&1 < /dev/null &");
                long readyDeadline=SystemClock.elapsedRealtime()+5000;
                while(active()){
                    String log=rpc("cat "+ROOT+"run.log");
                    if(log.contains("RESTORE_FAILED")||log.contains("ERROR ")||log.contains("REFUSED_"))throw new IOException("receiver_refused");
                    if(log.contains("STREAM_READY_"))break;
                    if(SystemClock.elapsedRealtime()>readyDeadline)throw new IOException("receiver_not_ready");
                    SystemClock.sleep(100);
                }
                if(active())stream();
            }catch(Exception e){issue=e.getClass().getSimpleName()+":"+String.valueOf(e.getMessage());}
            finally{
                disconnect();source=null;sink=null;
                if(receiverStarted){
                    state="restoring_oem";
                    try{
                        rpc("touch "+ROOT+"continuous.stop");
                        long deadline=SystemClock.elapsedRealtime()+8000;
                        boolean restored=false;
                        do{String log=rpc("cat "+ROOT+"run.log");if(log.contains("RESTORE_OK")){restored=true;break;}if(log.contains("RESTORE_FAILED"))break;SystemClock.sleep(150);}while(SystemClock.elapsedRealtime()<deadline);
                        if(!restored)issue="oem_restore_not_confirmed";else receiverOwned=false;
                    }catch(Exception e){issue="oem_restore_wait:"+e.getClass().getSimpleName();}
                }
                state=closed?"closed":desired?"retry_wait":"stopped";
            }
            long retry=SystemClock.elapsedRealtime()+(ticket==restartTicket?5000:0);
            while(active()&&ticket==restartTicket&&SystemClock.elapsedRealtime()<retry)SystemClock.sleep(100);
        }
    }
    private void deploy()throws Exception{
        if(deployed)return;
        byte[] bytes;
        try(InputStream in=context.getAssets().open("ultra/ultra_app_receiver")){ByteArrayOutputStream b=new ByteArrayOutputStream();byte[] buf=new byte[4096];int n;while((n=in.read(buf))>0){b.write(buf,0,n);if(b.size()>65536)throw new IOException("asset_bounds");}bytes=b.toByteArray();}
        StringBuilder hash=new StringBuilder();for(byte b:MessageDigest.getInstance("SHA-256").digest(bytes))hash.append(String.format(java.util.Locale.ROOT,"%02x",b&255));
        if(!ASSET_HASH.equals(hash.toString()))throw new SecurityException("receiver_asset_hash");
        rpc("mkdir -p "+ROOT+"; chmod 700 "+ROOT);
        // Reuse only an exact signed asset. A reboot clears /tmp and redeploys it.
        String existing=rpc("if [ -f "+BIN+" ]; then base64 "+BIN+"; fi");
        boolean matches=false;try{matches=Arrays.equals(bytes,Base64.decode(existing,Base64.DEFAULT));}catch(IllegalArgumentException ignored){}
        if(!matches){
            String encoded=Base64.encodeToString(bytes,Base64.NO_WRAP);rpc(": > "+ROOT+"next.b64");
            for(int i=0;i<encoded.length();i+=1800){if(!active())throw new InterruptedIOException("cancelled");rpc("printf '%s' '"+encoded.substring(i,Math.min(i+1800,encoded.length()))+"' >> "+ROOT+"next.b64");}
            rpc("base64 -d "+ROOT+"next.b64 > "+ROOT+"receiver.next; chmod 700 "+ROOT+"receiver.next; mv "+ROOT+"receiver.next "+BIN+"; rm -f "+ROOT+"next.b64");
            if(!Arrays.equals(bytes,Base64.decode(rpc("base64 "+BIN),Base64.DEFAULT)))throw new IOException("receiver_readback_hash");
        }
        deployed=true;
    }
    private void stream()throws Exception{
        progress=SystemClock.elapsedRealtime();
        LocalSocket local=new LocalSocket();source=local;
        local.connect(new LocalSocketAddress("op.ultra.carplay.frames.v1",LocalSocketAddress.Namespace.ABSTRACT));local.setSoTimeout(2500);
        if(local.getPeerCredentials().getUid()!=clientUid)throw new SecurityException("canvas_owner");
        DataOutputStream request=new DataOutputStream(local.getOutputStream());DataInputStream reply=new DataInputStream(local.getInputStream());
        request.writeInt(0x4f505531);request.flush();if(reply.readInt()!=0x4f505531)throw new IOException("canvas_handshake");
        if(!active())return;
        Socket receiver=new Socket();sink=receiver;receiver.setTcpNoDelay(true);receiver.setSendBufferSize(131072);receiver.bind(new InetSocketAddress("172.20.1.45",0));receiver.connect(new InetSocketAddress("172.20.1.40",49173),2000);
        OutputStream out=receiver.getOutputStream();DataInputStream acknowledgement=new DataInputStream(receiver.getInputStream());out.write("OPULT26-UIFR-002".getBytes(StandardCharsets.US_ASCII));out.flush();
        byte[] header=new byte[16],frame=new byte[UltraTileCodec.MAX_PACKET];long start=SystemClock.elapsedRealtime();int sentSequence=0,interval=100;boolean forceKey=true,requestPending=false;UltraFrameHealth frameAgeHealth=new UltraFrameHealth();UltraPerformanceBudget budget=new UltraPerformanceBudget();
        while(active()){
            long requestedAt=System.nanoTime();if(!requestPending){request.writeInt(5);request.writeInt(interval);request.writeInt(forceKey?1:0);request.flush();}requestPending=false;int seq=reply.readInt();long timestamp=reply.readLong();int width=reply.readInt(),height=reply.readInt(),size=reply.readInt();
            if(seq==UltraFrameHealth.RETRY){
                if(width!=0||height!=0||size!=0)throw new IOException("canvas_retry_bounds");
                // Temporarily reveal the live OEM instrument without dismantling this
                // output session. No stale speed is replayed during a data gap.
                int sent=sentSequence++;long tx=SystemClock.elapsedRealtime();writeHeader(out,header,0x56505032,sent,0,(int)(SystemClock.elapsedRealtime()-start));out.flush();interval=ack(acknowledgement,sent,budget,tx);forceKey=true;
                progress=SystemClock.elapsedRealtime();state="waiting_for_canvas";continue;
            }
            if(seq==UltraFrameHealth.STOP)break;
            if(seq<0)throw new IOException("canvas_status");
            if(width!=1920||height!=720||size<16||size>frame.length)throw new IOException("canvas_bounds");
            reply.readFully(frame,0,size);if(!active())break;
            if(Math.abs(SystemClock.elapsedRealtime()-timestamp)>1500){
                if(frameAgeHealth.unavailable(SystemClock.elapsedRealtime())==UltraFrameHealth.STOP)break;
                int sent=sentSequence++;long tx=SystemClock.elapsedRealtime();writeHeader(out,header,0x56505032,sent,0,(int)(SystemClock.elapsedRealtime()-start));out.flush();interval=ack(acknowledgement,sent,budget,tx);forceKey=true;progress=SystemClock.elapsedRealtime();state="waiting_for_canvas";continue;
            }
            frameAgeHealth.available();sourceWaitNanos+=System.nanoTime()-requestedAt;
            // One bounded look-ahead: prepare the next canvas while this frame
            // is transferred/composed. Publication still waits for its exact ACK;
            // failure closes both sockets and the next connection starts with a key.
            // No additional bitmap queue or receiver frames in flight are created.
            request.writeInt(5);request.writeInt(interval);request.writeInt(0);request.flush();requestPending=true;
            long txAt=System.nanoTime();int sent=sentSequence++;long tx=SystemClock.elapsedRealtime();writeHeader(out,header,0x56504633,sent,size,(int)(SystemClock.elapsedRealtime()-start));out.write(frame,0,size);out.flush();interval=ack(acknowledgement,sent,budget,tx);receiverNanos+=System.nanoTime()-txAt;forceKey=false;
            progress=SystemClock.elapsedRealtime();frameCount++;state="projecting";
        }
    }
    private static int ack(DataInputStream in,int sent,UltraPerformanceBudget budget,long transmittedAt)throws IOException{
        int sequence=Integer.reverseBytes(in.readInt()),ms=Integer.reverseBytes(in.readInt());
        if(sequence!=sent||ms<0||ms>4500)throw new IOException("receiver_ack");
        // Include transfer/backpressure, not just remote CPU time: a slow car link
        // should also slow map readback instead of creating unused GPU captures.
        long elapsed=SystemClock.elapsedRealtime()-transmittedAt;
        if(elapsed<0||elapsed>4500)throw new IOException("receiver_ack_age");
        return budget.observeReceiver(Math.max(ms,(int)elapsed));
    }
    private static void writeHeader(OutputStream out,byte[] bytes,int magic,int seq,int size,int elapsed)throws IOException{
        int[] values={magic,seq,size,elapsed};
        for(int k=0;k<4;k++)for(int i=0;i<4;i++)bytes[k*4+i]=(byte)(values[k]>>>(i*8));
        out.write(bytes);
    }

    // Existing instrument management protocol, restricted to fixed code above.
    private String rpc(String command)throws IOException{
        if(command.length()>2400)throw new IOException("command_bounds");
        int id=(++session)&65535;ByteArrayOutputStream payload=new ByteArrayOutputStream();ByteArrayOutputStream header=new ByteArrayOutputStream();header.write(8);varint(header,0x7e710000L+id);field(payload,1,header.toByteArray());field(payload,2,"shell".getBytes(StandardCharsets.UTF_8));field(payload,2,command.getBytes(StandardCharsets.UTF_8));
        try(Socket socket=new Socket()){
            socket.bind(new InetSocketAddress("172.20.1.45",0));socket.connect(new InetSocketAddress("172.20.1.40",30510),1500);socket.setSoTimeout(2500);
            DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeShort(0x101);out.writeShort(0x211);out.writeInt(8+payload.size());out.writeShort(0x7e71);out.writeShort(id);out.write(new byte[]{1,0,0,0});payload.writeTo(out);out.flush();
            DataInputStream in=new DataInputStream(socket.getInputStream());int service=in.readUnsignedShort(),method=in.readUnsignedShort(),size=in.readInt(),client=in.readUnsignedShort(),sessionId=in.readUnsignedShort(),protocol=in.readUnsignedByte(),version=in.readUnsignedByte(),type=in.readUnsignedByte(),rc=in.readUnsignedByte();
            if(service!=0x101||method!=0x211||client!=0x7e71||sessionId!=id||protocol!=1||version!=0||type!=0x80||rc!=0||size<8||size>65528)throw new IOException("management_response");
            byte[] body=new byte[size-8];in.readFully(body);return output(body);
        }
    }
    private static void varint(OutputStream out,long value)throws IOException{while(value>127){out.write(((int)value&127)|128);value>>>=7;}out.write((int)value);}
    private static void field(OutputStream out,int number,byte[] bytes)throws IOException{varint(out,number*8L+2);varint(out,bytes.length);out.write(bytes);}
    private static long vi(byte[] bytes,int[] offset)throws IOException{long value=0;for(int shift=0;shift<64;shift+=7){if(offset[0]>=bytes.length)throw new EOFException();int b=bytes[offset[0]++]&255;value|=(long)(b&127)<<shift;if((b&128)==0)return value;}throw new IOException("varint_bounds");}
    private static String output(byte[] bytes)throws IOException{
        StringBuilder text=new StringBuilder();int[] pos={0};
        while(pos[0]<bytes.length){long tag=vi(bytes,pos);int wire=(int)tag&7;byte[] sub=skip(bytes,pos,wire);if((tag>>>3)==2&&wire==2){int[] inner={0};while(inner[0]<sub.length){long field=vi(sub,inner);int type=(int)field&7;byte[] value=skip(sub,inner,type);if(type==2)text.append(new String(value,StandardCharsets.UTF_8));}}}
        return text.toString();
    }
    private static byte[] skip(byte[] bytes,int[] pos,int wire)throws IOException{
        if(wire==0){vi(bytes,pos);return new byte[0];}
        long length=wire==2?vi(bytes,pos):wire==1?8:wire==5?4:-1;
        if(length<0||length>bytes.length-pos[0])throw new IOException("protobuf_bounds");int start=pos[0];pos[0]+=(int)length;return Arrays.copyOfRange(bytes,start,pos[0]);
    }
}
