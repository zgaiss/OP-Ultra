package com.heavendoor.ultralab;

import android.content.ComponentName;
import android.content.Context;
import android.content.ServiceConnection;
import android.content.pm.PackageManager;
import android.os.IBinder;
import android.os.RemoteException;
import rikka.shizuku.Shizuku;

/**
 * Explicit head-unit-only diagnostic lifecycle. Does not request permission, start the normal
 * engine, create a frame server, connect the factory helper, or send output-control commands.
 * Caller owns this session and must close it. Snapshot calls belong on a background thread.
 * Permission/binder loss is terminal: create a new explicit session to retry. This session
 * does not prevent a separately started normal session from operating the instrument.
 * No vehicle access is performed until start() is explicitly called with existing permission.
 */
public final class UltraReadOnlySession implements AutoCloseable {
    private final Shizuku.UserServiceArgs args;
    private IUltraDataService reader;
    private boolean bound, closed;
    private final ServiceConnection connection = new ServiceConnection() {
        @Override public void onServiceConnected(ComponentName name, IBinder binder) {
            synchronized (UltraReadOnlySession.this) {
                if (!closed && bound) reader = IUltraDataService.Stub.asInterface(binder);
            }
        }
        @Override public void onServiceDisconnected(ComponentName name) {
            UltraReadOnlySession.this.close();
        }
    };

    public UltraReadOnlySession(Context context) {
        args = new Shizuku.UserServiceArgs(new ComponentName(context, UltraDataService.class))
                .daemon(false).processNameSuffix("ultra-read-only").debuggable(false).version(32);
    }

    public synchronized void start() {
        if (closed) throw new IllegalStateException("session_closed");
        if (bound) return;
        UltraEngine normal = UltraEngine.peek();
        if (normal != null && normal.started())
            throw new IllegalStateException("stop_normal_session_first");
        if (!Shizuku.pingBinder()
                || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
            throw new SecurityException("existing_shizuku_permission_required");
        bound = true;
        try { Shizuku.bindUserService(args, connection); }
        catch (RuntimeException | Error failure) { bound = false; throw failure; }
    }

    public synchronized String snapshot() throws RemoteException {
        try {
            if (bound && (!Shizuku.pingBinder()
                    || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)) close();
            if (closed || reader == null) return disconnected();
            return reader.snapshot();
        } catch (RemoteException | SecurityException failure) {
            close();
            return disconnected();
        }
    }

    private static String disconnected() { return "{\"schema\":1,\"source\":\"vehicle\","
                + "\"collectionMode\":\"READ_ONLY\",\"connected\":false,"
                + "\"error\":\"read_only_disconnected\",\"fields\":{}}";
    }

    @Override public synchronized void close() {
        if (closed) return;
        closed = true;
        reader = null;
        if (bound) {
            bound = false;
            // Removes only this dedicated head-unit process, not the normal service.
            try { Shizuku.unbindUserService(args, connection, true); }
            catch (RuntimeException ignored) { /* Binder/permission already lost; no retry or upgrade. */ }
        }
    }
}
