package com.shilapi.xcertplay.transport

import android.hardware.usb.UsbDeviceConnection
import android.hardware.usb.UsbEndpoint
import android.os.Build
import android.util.Log

/** Android 9 bulk I/O without holding a JNI critical array across a blocking USB wait. */
internal object NativeUsbBulk {
    private val enabled = Build.VERSION.SDK_INT == 28 && runCatching {
        System.loadLibrary("op_usb_bulk")
        true
    }.getOrDefault(false)

    init { Log.i("OPUsbBackend", "bulk backend=${if (enabled) "native-reuse" else "platform"}") }

    fun transfer(connection: UsbDeviceConnection, endpoint: UsbEndpoint, buffer: ByteArray,
                 length: Int, timeoutMillis: Int): Int =
        if (enabled) nativeTransfer(connection.fileDescriptor, endpoint.address, buffer, length, timeoutMillis)
        else connection.bulkTransfer(endpoint, buffer, length, timeoutMillis)

    private external fun nativeTransfer(fd: Int, endpoint: Int, buffer: ByteArray,
                                       length: Int, timeoutMillis: Int): Int
}
