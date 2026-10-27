package com.heavendoor.ultralab;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;

/** Private read-only trip transport; separate client ID/thread from projection. */
final class UltraInstrumentRpc implements AutoCloseable {
 private volatile Socket active;private volatile boolean closed;private int sequence=0x5800;
 String call(String command)throws IOException{
  if(closed||command.length()>2400)throw new IOException("trip_rpc_closed_or_bounds");
  int id=(++sequence)&65535;ByteArrayOutputStream payload=new ByteArrayOutputStream(),header=new ByteArrayOutputStream();header.write(8);vi(header,0x7e720000L+id);field(payload,1,header.toByteArray());field(payload,2,"shell".getBytes(StandardCharsets.US_ASCII));field(payload,2,command.getBytes(StandardCharsets.UTF_8));
  try(Socket socket=new Socket()){
   active=socket;if(closed)throw new IOException("trip_rpc_closed");socket.bind(new InetSocketAddress("172.20.1.45",0));socket.connect(new InetSocketAddress("172.20.1.40",30510),1000);socket.setSoTimeout(2500);
   DataOutputStream out=new DataOutputStream(socket.getOutputStream());out.writeShort(0x101);out.writeShort(0x211);out.writeInt(8+payload.size());out.writeShort(0x7e72);out.writeShort(id);out.write(new byte[]{1,0,0,0});payload.writeTo(out);out.flush();
   DataInputStream in=new DataInputStream(socket.getInputStream());int service=in.readUnsignedShort(),method=in.readUnsignedShort(),size=in.readInt(),client=in.readUnsignedShort(),session=in.readUnsignedShort(),protocol=in.readUnsignedByte(),version=in.readUnsignedByte(),type=in.readUnsignedByte(),rc=in.readUnsignedByte();
   if(service!=0x101||method!=0x211||client!=0x7e72||session!=id||protocol!=1||version!=0||type!=0x80||rc!=0||size<8||size>65528)throw new IOException("trip_rpc_response");byte[] body=new byte[size-8];in.readFully(body);return output(body);
  }finally{active=null;}
 }
 @Override public void close(){closed=true;Socket s=active;if(s!=null)try{s.close();}catch(IOException ignored){}}
 private static void vi(OutputStream out,long value)throws IOException{while(value>127){out.write(((int)value&127)|128);value>>>=7;}out.write((int)value);}
 private static void field(OutputStream out,int number,byte[] bytes)throws IOException{vi(out,number*8L+2);vi(out,bytes.length);out.write(bytes);}
 private static long vi(byte[] bytes,int[] offset)throws IOException{long value=0;for(int shift=0;shift<64;shift+=7){if(offset[0]>=bytes.length)throw new EOFException();int b=bytes[offset[0]++]&255;value|=(long)(b&127)<<shift;if((b&128)==0)return value;}throw new IOException("trip_varint_bounds");}
 private static String output(byte[] bytes)throws IOException{
  StringBuilder text=new StringBuilder();int[] pos={0};while(pos[0]<bytes.length){long tag=vi(bytes,pos);int wire=(int)tag&7;byte[] sub=skip(bytes,pos,wire);if((tag>>>3)==2&&wire==2){int[] p={0};while(p[0]<sub.length){long field=vi(sub,p);int type=(int)field&7;byte[] value=skip(sub,p,type);if(type==2)text.append(new String(value,StandardCharsets.UTF_8));}}}return text.toString();
 }
 private static byte[] skip(byte[] bytes,int[] p,int wire)throws IOException{if(wire==0){vi(bytes,p);return new byte[0];}long n=wire==2?vi(bytes,p):wire==1?8:wire==5?4:-1;if(n<0||n>bytes.length-p[0])throw new IOException("trip_protobuf_bounds");int start=p[0];p[0]+=(int)n;return Arrays.copyOfRange(bytes,start,p[0]);}
}
