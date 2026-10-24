package com.lightsync.android

import android.Manifest
import android.annotation.SuppressLint
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.webkit.JavascriptInterface
import android.webkit.WebSettings
import android.webkit.WebView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import com.lightsync.android.audio.AndroidAudioVisualizerEngine
import com.lightsync.android.bluetooth.LightsyncBluetoothManager
import com.lightsync.android.protocol.HidppProtocol
import com.lightsync.android.usb.UsbOtgHidppManager
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var btManager: LightsyncBluetoothManager
    private lateinit var usbOtgManager: UsbOtgHidppManager
    private lateinit var audioEngine: AndroidAudioVisualizerEngine

    private var bridgeHostIp: String = "192.168.100.38:5177"
    private var lastStatus: String = "Pronto (Bluetooth BLE / RFCOMM / USB OTG / Bridge)"

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        requestRequiredPermissions()

        btManager = LightsyncBluetoothManager(
            context = this,
            onStatusChange = { status ->
                lastStatus = status
                notifyWebStatus(status)
            },
            onPacketLogged = { dir, bytes ->
                notifyWebPacket(dir, HidppProtocol.toHex(bytes))
            }
        )

        usbOtgManager = UsbOtgHidppManager(
            context = this,
            onStatusChange = { status ->
                lastStatus = status
                notifyWebStatus(status)
            },
            onPacketLogged = { dir, bytes ->
                notifyWebPacket(dir, HidppProtocol.toHex(bytes))
            }
        )

        audioEngine = AndroidAudioVisualizerEngine { zones, _ ->
            for (z in zones.indices) {
                sendZoneColorEverywhere(z, zones[z][0], zones[z][1], zones[z][2])
            }
        }

        webView = WebView(this)
        webView.settings.javaScriptEnabled = true
        webView.settings.domStorageEnabled = true
        webView.settings.cacheMode = WebSettings.LOAD_NO_CACHE
        webView.addJavascriptInterface(AndroidBridgeInterface(), "AndroidLightsync")
        setContentView(webView)

        webView.loadUrl("file:///android_asset/mobile_ui.html")
    }

    private fun sendZoneColorEverywhere(zoneIndex: Int, r: Int, g: Int, b: Int) {
        val pkt = HidppProtocol.buildSetZoneFixedColor(
            btManager.feature8070Index, zoneIndex, r, g, b
        )
        val sentBt = btManager.sendHidppPacket(pkt)
        val sentUsb = usbOtgManager.sendHidppPacket(pkt)

        if (!sentBt && !sentUsb && bridgeHostIp.isNotBlank()) {
            val hex = "#%02X%02X%02X".format(r and 0xFF, g and 0xFF, b and 0xFF)
            postToPcBridge("/api/zone", """{"zoneIndex":$zoneIndex,"color":"$hex"}""")
        }
    }

    private fun postToPcBridge(path: String, jsonBody: String) {
        thread {
            try {
                val url = URL("http://$bridgeHostIp$path")
                val conn = url.openConnection() as HttpURLConnection
                conn.requestMethod = "POST"
                conn.connectTimeout = 800
                conn.readTimeout = 800
                conn.setRequestProperty("Content-Type", "application/json")
                conn.doOutput = true
                conn.outputStream.use { it.write(jsonBody.toByteArray()) }
                conn.responseCode
                conn.disconnect()
            } catch (_: Exception) {}
        }
    }

    private fun notifyWebStatus(status: String) {
        runOnUiThread {
            val safe = JSONObject.quote(status)
            webView.evaluateJavascript("window.onNativeStatus && window.onNativeStatus($safe);", null)
        }
    }

    private fun notifyWebPacket(dir: String, hex: String) {
        runOnUiThread {
            webView.evaluateJavascript("window.onNativePacket && window.onNativePacket('$dir', '$hex');", null)
        }
    }

    inner class AndroidBridgeInterface {
        @JavascriptInterface
        fun getPairedBluetoothDevices(): String {
            val arr = JSONArray()
            for (dev in btManager.getPairedDevices()) {
                val obj = JSONObject()
                obj.put("name", dev.name ?: "Dispositivo Bluetooth")
                obj.put("address", dev.address)
                arr.put(obj)
            }
            return arr.toString()
        }

        @JavascriptInterface
        fun connectBluetoothDevice(address: String, mode: String) {
            val dev = btManager.getPairedDevices().firstOrNull { it.address == address } ?: return
            if (mode == "BLE") {
                btManager.connectBleGatt(dev)
            } else {
                btManager.connectRfcommBridge(dev)
            }
        }

        @JavascriptInterface
        fun connectUsbOtg(): Boolean = usbOtgManager.connectFirstLogitechDevice()

        @JavascriptInterface
        fun setBridgeHost(hostPort: String) {
            bridgeHostIp = hostPort.trim()
            notifyWebStatus("Bridge configurado para $bridgeHostIp")
        }

        @JavascriptInterface
        fun setZoneColor(zoneIndex: Int, r: Int, g: Int, b: Int) {
            audioEngine.stop()
            sendZoneColorEverywhere(zoneIndex, r, g, b)
        }

        @JavascriptInterface
        fun setAllZonesEffect(effect: String, r: Int, g: Int, b: Int, speedMs: Int) {
            audioEngine.stop()
            for (z in 0..3) {
                val pkt = when (effect.uppercase()) {
                    "CYCLE" -> HidppProtocol.buildSetZoneCycleEffect(btManager.feature8070Index, z, speedMs)
                    "BREATHING" -> HidppProtocol.buildSetZoneBreathingEffect(btManager.feature8070Index, z, r, g, b, speedMs)
                    "OFF" -> HidppProtocol.buildPacket(0xFF.toByte(), btManager.feature8070Index, 0x03, z.toByte(), 0x00)
                    else -> HidppProtocol.buildSetZoneFixedColor(btManager.feature8070Index, z, r, g, b)
                }
                btManager.sendHidppPacket(pkt)
                usbOtgManager.sendHidppPacket(pkt)
            }
            val hex = "#%02X%02X%02X".format(r and 0xFF, g and 0xFF, b and 0xFF)
            postToPcBridge("/api/mode", """{"mode":"$effect","color":"$hex","speedMs":$speedMs}""")
        }

        @JavascriptInterface
        fun setBrightness(percent: Int) {
            val pkt = HidppProtocol.buildSetBrightness(btManager.feature8040Index, percent)
            btManager.sendHidppPacket(pkt)
            usbOtgManager.sendHidppPacket(pkt)
            postToPcBridge("/api/brightness", """{"brightness":$percent}""")
        }

        @JavascriptInterface
        fun startPhoneAudioVisualizer(enable: Boolean) {
            if (enable) audioEngine.start() else audioEngine.stop()
        }
    }

    private fun requestRequiredPermissions() {
        val perms = mutableListOf(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            perms.add(Manifest.permission.BLUETOOTH_SCAN)
            perms.add(Manifest.permission.BLUETOOTH_CONNECT)
        } else {
            perms.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }
        val missing = perms.filter {
            ActivityCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), 1001)
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        audioEngine.stop()
        btManager.disconnect()
        usbOtgManager.close()
    }
}
