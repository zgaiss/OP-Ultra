#!/usr/bin/env python3
"""Compile the actual read-only client against tiny host fakes; no Android/vehicle access."""
import os
from pathlib import Path
import subprocess
import tempfile
root = Path(__file__).resolve().parents[1]
sources = {
"android/content/Context.java": "package android.content; public class Context {}",
"android/content/ComponentName.java": "package android.content; public class ComponentName { public ComponentName(Context c,Class<?> k){} }",
"android/content/ServiceConnection.java": "package android.content; import android.os.IBinder; public interface ServiceConnection { void onServiceConnected(ComponentName n, IBinder b); void onServiceDisconnected(ComponentName n); }",
"android/content/pm/PackageManager.java": "package android.content.pm; public class PackageManager { public static final int PERMISSION_GRANTED=0; }",
"android/os/IBinder.java": "package android.os; public interface IBinder {}",
"android/os/RemoteException.java": "package android.os; public class RemoteException extends Exception {}",
"com/heavendoor/ultralab/UltraDataService.java": "package com.heavendoor.ultralab; public class UltraDataService {}",
"com/heavendoor/ultralab/UltraEngine.java": """package com.heavendoor.ultralab; public class UltraEngine { public static UltraEngine instance; public static UltraEngine peek(){return instance;} public boolean started(){return true;} }""",
"com/heavendoor/ultralab/IUltraDataService.java": """package com.heavendoor.ultralab;
public interface IUltraDataService { String snapshot() throws android.os.RemoteException;
class Stub { public static int snapshots; public static boolean fail, denied; public static IUltraDataService asInterface(android.os.IBinder b) { return ()->{if(denied)throw new SecurityException();if(fail)throw new android.os.RemoteException(); snapshots++;return "snapshot";}; } } }""",
"rikka/shizuku/Shizuku.java": """package rikka.shizuku;
import android.content.*;
public class Shizuku {
 public static boolean available=true, fail, denied; public static int permission=0, binds, unbinds;
 public static ServiceConnection connection;
 public static class UserServiceArgs {
  public UserServiceArgs(ComponentName n){} public UserServiceArgs daemon(boolean x){return this;}
  public UserServiceArgs processNameSuffix(String x){if(!x.equals("ultra-read-only"))throw new AssertionError();return this;}
  public UserServiceArgs debuggable(boolean x){return this;} public UserServiceArgs version(int x){return this;}
 }
 public static boolean pingBinder(){return available;} public static int checkSelfPermission(){if(denied)throw new SecurityException();return permission;}
 public static void bindUserService(UserServiceArgs a, ServiceConnection c){if(fail)throw new IllegalStateException(); binds++;connection=c;}
 public static void unbindUserService(UserServiceArgs a, ServiceConnection c,boolean remove){if(!remove)throw new AssertionError();unbinds++;}
}""",
"com/heavendoor/ultralab/UltraReadOnlySessionHostTest.java": """package com.heavendoor.ultralab;
import rikka.shizuku.Shizuku;
public class UltraReadOnlySessionHostTest {
 static int checks; static void check(boolean v){checks++;if(!v)throw new AssertionError("check "+checks);}
 public static void main(String[] args)throws Exception {
  android.content.Context context=new android.content.Context();
  UltraReadOnlySession s=new UltraReadOnlySession(context);
  check(Shizuku.binds==0);check(s.snapshot().contains("read_only_disconnected"));
  Shizuku.permission=-1;
  try{s.start();throw new AssertionError();}catch(SecurityException expected){}
  check(Shizuku.binds==0);Shizuku.permission=0;
  UltraEngine.instance=new UltraEngine();
  try{s.start();throw new AssertionError();}catch(IllegalStateException expected){}
  check(Shizuku.binds==0);UltraEngine.instance=null;
  Shizuku.fail=true;try{s.start();throw new AssertionError();}catch(IllegalStateException expected){}
  Shizuku.fail=false;s.start();s.start();check(Shizuku.binds==1);
  Shizuku.connection.onServiceConnected(null,null);
  check(s.snapshot().equals("snapshot"));check(IUltraDataService.Stub.snapshots==1);
  Shizuku.connection.onServiceDisconnected(null);check(s.snapshot().contains("read_only_disconnected"));
  s.close();s.close();check(Shizuku.unbinds==1);
  Shizuku.connection.onServiceConnected(null,null);check(s.snapshot().contains("read_only_disconnected"));
  try{s.start();throw new AssertionError();}catch(IllegalStateException expected){}
  UltraReadOnlySession unused=new UltraReadOnlySession(context);unused.close();check(Shizuku.unbinds==1);
  UltraReadOnlySession revoked=new UltraReadOnlySession(context);revoked.start();
  Shizuku.connection.onServiceConnected(null,null);Shizuku.permission=-1;
  check(revoked.snapshot().contains("read_only_disconnected"));check(Shizuku.unbinds==2);
  check(IUltraDataService.Stub.snapshots==1);Shizuku.permission=0;
  UltraReadOnlySession dead=new UltraReadOnlySession(context);dead.start();
  Shizuku.connection.onServiceConnected(null,null);IUltraDataService.Stub.fail=true;
  check(dead.snapshot().contains("read_only_disconnected"));check(Shizuku.unbinds==3);
  check(IUltraDataService.Stub.snapshots==1);
  IUltraDataService.Stub.fail=false;
  UltraReadOnlySession permissionRace=new UltraReadOnlySession(context);permissionRace.start();
  Shizuku.connection.onServiceConnected(null,null);Shizuku.denied=true;
  check(permissionRace.snapshot().contains("read_only_disconnected"));check(Shizuku.unbinds==4);
  check(IUltraDataService.Stub.snapshots==1);Shizuku.denied=false;
  UltraReadOnlySession readerDenied=new UltraReadOnlySession(context);readerDenied.start();
  Shizuku.connection.onServiceConnected(null,null);IUltraDataService.Stub.denied=true;
  check(readerDenied.snapshot().contains("read_only_disconnected"));check(Shizuku.unbinds==5);
  check(IUltraDataService.Stub.snapshots==1);
  System.out.println("Read-only client host lifecycle: "+checks+" checks passed (Android/Shizuku fakes)");
 }
}"""
}
java = str(Path(os.environ["JAVA_HOME"]) / "bin/java") if "JAVA_HOME" in os.environ else "java"
with tempfile.TemporaryDirectory() as tmp:
    dest = Path(tmp)
    for name, body in sources.items():
        path = dest / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(body)
    actual = root / "common/src/main/java/com/heavendoor/ultralab/UltraReadOnlySession.java"
    subprocess.run([java, "-m", "jdk.compiler/com.sun.tools.javac.Main", "-source", "11", "-target", "11",
                    "-Xlint:-options", "-d", tmp, str(actual), *map(str, dest.rglob("*.java"))], check=True)
    subprocess.run([java, "-cp", tmp, "com.heavendoor.ultralab.UltraReadOnlySessionHostTest"], check=True)
