package com.lightsync.android.usb

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.hardware.usb.*
import com.lightsync.android.protocol.HidppProtocol

/**
 * Driver Android USB Host (OTG) para comunicação HID++ 2.0 direta com periféricos
 * Logitech (VID 0x046D, incluindo G560 PID 0x0A78) conectados via cabo USB-C OTG no celular.
 */
class UsbOtgHidppManager(
    private val context: Context,
    private val onStatusChange: (String) -> Unit,
    private val onPacketLogged: (String, ByteArray) -> Unit
) {
    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var connection: UsbDeviceConnection? = null
    private var hidInterface: UsbInterface? = null
    private var outEndpoint: UsbEndpoint? = null

    val isConnected: Boolean get() = connection != null

    fun connectFirstLogitechDevice(): Boolean {
        val logiDevice = usbManager.deviceList.values.firstOrNull {
            it.vendorId == HidppProtocol.LOGITECH_VID
        } ?: return false

        if (!usbManager.hasPermission(logiDevice)) {
            val pi = PendingIntent.getBroadcast(
                context, 0, Intent("com.lightsync.android.USB_PERMISSION"),
                PendingIntent.FLAG_IMMUTABLE
            )
            usbManager.requestPermission(logiDevice, pi)
            onStatusChange("Solicitando permissão USB OTG para ${logiDevice.productName ?: "Logitech"}...")
            return false
        }

        // Procura a interface HID vendor-defined (no G560 é a Interface 2)
        for (i in 0 until logiDevice.interfaceCount) {
            val intf = logiDevice.getInterface(i)
            if (intf.interfaceClass == UsbConstants.USB_CLASS_HID) {
                val conn = usbManager.openDevice(logiDevice) ?: continue
                if (conn.claimInterface(intf, true)) {
                    connection = conn
                    hidInterface = intf
                    for (e in 0 until intf.endpointCount) {
                        val ep = intf.getEndpoint(e)
                        if (ep.direction == UsbConstants.USB_DIR_OUT) {
                            outEndpoint = ep
                            break
                        }
                    }
                    onStatusChange("Conectado via USB OTG Direto: ${logiDevice.productName ?: "PID 0x%04X".format(logiDevice.productId)}")
                    return true
                }
            }
        }
        return false
    }

    fun sendHidppPacket(packet20: ByteArray): Boolean {
        val conn = connection ?: return false
        val intf = hidInterface ?: return false

        val ep = outEndpoint
        val sent = if (ep != null) {
            conn.bulkTransfer(ep, packet20, packet20.size, 200)
        } else {
            // HID SET_REPORT (RequestType = 0x21, Request = 0x09, Value = 0x0211 [Output Report 0x11])
            conn.controlTransfer(0x21, 0x09, 0x0211, intf.id, packet20, packet20.size, 200)
        }

        if (sent >= 0) {
            onPacketLogged("TX -> USB OTG", packet20)
            return true
        }
        return false
    }

    fun close() {
        try {
            hidInterface?.let { connection?.releaseInterface(it) }
            connection?.close()
        } catch (_: Exception) {}
        connection = null
        hidInterface = null
        outEndpoint = null
    }
}
