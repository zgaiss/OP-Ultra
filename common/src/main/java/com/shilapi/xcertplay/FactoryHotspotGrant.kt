package com.shilapi.xcertplay

import android.content.Context
import android.content.pm.PackageManager
import android.net.LocalSocket
import android.net.LocalSocketAddress
import android.os.Process
import android.os.SystemClock
import android.provider.Settings
import java.security.MessageDigest

/** Fixed authorization for this signed app only. Call on a worker, never the UI thread.
 * No exported receiver, caller-provided command, su dependency or ADB connection.
 */
internal object FactoryHotspotGrant {
    private const val SIGNER = "5202f52cb75f7073bb06fe90cd13cc5151a6240bda9ed7e7a6f96352f9935da5"

    fun grant(context: Context): Boolean {
        if (!NativeHotspotPreferences.supported() || context.packageName != HotspotGrantCommand.PACKAGE || Process.myUid() / 100000 != 0) return false
        if (Settings.System.canWrite(context)) return true
        val info = context.packageManager.getPackageInfo(context.packageName, PackageManager.GET_SIGNING_CERTIFICATES)
        val signers = info.signingInfo?.apkContentsSigners ?: return false
        if (signers.size != 1) return false
        val digest = MessageDigest.getInstance("SHA-256").digest(signers[0].toByteArray())
        if (digest.joinToString("") { "%02x".format(it) } != SIGNER) return false
        // Entire command is fixed; authorization is verified through Android in
        // this app's UID, rather than trusting a shell exit code or root-written file.
        val command = "if [ \"\$(id -u)\" = 0 ]; then cmd appops set --user 0 ${HotspotGrantCommand.PACKAGE} WRITE_SETTINGS allow; fi"
            LocalSocket().use { socket ->
                socket.soTimeout = 1500
                socket.connect(LocalSocketAddress("/dev/socket/xpaftersalesdw", LocalSocketAddress.Namespace.FILESYSTEM))
                check(socket.peerCredentials.uid == 0)
                socket.outputStream.write(("XP_AFTERSALES_REQ~3~1~0#" + command + "\r\n").toByteArray(Charsets.UTF_8))
                socket.outputStream.flush()
            }
            val deadline = SystemClock.elapsedRealtime() + 6000
            while (!Thread.currentThread().isInterrupted && SystemClock.elapsedRealtime() < deadline) {
                if (Settings.System.canWrite(context)) return true
                Thread.sleep(100)
            }
            return false
    }
}
