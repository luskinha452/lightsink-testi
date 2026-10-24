package com.lightsync.android.bluetooth

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import com.lightsync.android.protocol.HidppProtocol
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.util.UUID

/**
 * Gerenciador Bluetooth Dual-Mode para o LIGHTSYNC Android:
 *
 * 1. BLE GATT HID++ 2.0 Direto:
 *    - Conecta diretamente a periféricos Logitech Bluetooth Low Energy via:
 *      - Serviço Logitech BLE Direct HID++: 00010000-0000-1000-8000-011f2000046d
 *        Característica de Comando/Notificação: 00010001-0000-1000-8000-011f2000046d
 *      - Serviço BLE HOGP (HID over GATT): 00001812-0000-1000-8000-00805f9b34fb
 *        Característica HID Report: 00002a4d-0000-1000-8000-00805f9b34fb
 *    - Transmite relatórios HID++ 2.0 (0x11 de 20 bytes) diretamente para as features 0x8070 e 0x8040.
 *
 * 2. Bluetooth Classic RFCOMM (SPP UUID 00001101-0000-1000-8000-00805F9B34FB):
 *    - Para dispositivos cujo controlador RGB 0x8070 é fisicamente cabeado na porta USB (como a caixa
 *      de som Logitech G560, cujo rádio Bluetooth interno é exclusivo para áudio A2DP), permite
 *      enviar os pacotes HID++ de 20 bytes via Bluetooth RFCOMM para o bridge no PC.
 */
@SuppressLint("MissingPermission")
class LightsyncBluetoothManager(
    private val context: Context,
    private val onStatusChange: (String) -> Unit,
    private val onPacketLogged: (String, ByteArray) -> Unit
) {
    companion object {
        val LOGI_BLE_HIDPP_SERVICE: UUID = UUID.fromString("00010000-0000-1000-8000-011f2000046d")
        val LOGI_BLE_HIDPP_CHAR: UUID = UUID.fromString("00010001-0000-1000-8000-011f2000046d")
        val BLE_HOGP_HID_SERVICE: UUID = UUID.fromString("00001812-0000-1000-8000-00805f9b34fb")
        val BLE_HOGP_REPORT_CHAR: UUID = UUID.fromString("00002a4d-0000-1000-8000-00805f9b34fb")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        val RFCOMM_SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    }

    private val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
    private val adapter: BluetoothAdapter? get() = bluetoothManager?.adapter

    private var activeGatt: BluetoothGatt? = null
    private var hidppCharacteristic: BluetoothGattCharacteristic? = null
    private var rfcommSocket: BluetoothSocket? = null
    private var rfcommOutput: OutputStream? = null

    var feature8070Index: Byte = 0x04
    var feature8040Index: Byte = 0x08
    private var pendingFeatureQueryId: Int = 0

    val isConnected: Boolean
        get() = (activeGatt != null && hidppCharacteristic != null) || (rfcommSocket?.isConnected == true)

    fun getPairedDevices(): List<BluetoothDevice> {
        return adapter?.bondedDevices?.toList() ?: emptyList()
    }

    fun startBleScan(onDeviceFound: (BluetoothDevice, Int) -> Unit) {
        val scanner = adapter?.bluetoothLeScanner
        if (scanner == null) {
            onStatusChange("Bluetooth LE indisponível ou desativado")
            return
        }
        onStatusChange("Escaneando periféricos BLE Logitech HID++...")
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()

        scanner.startScan(null, settings, object : ScanCallback() {
            override fun onScanResult(callbackType: Int, result: ScanResult) {
                val dev = result.device ?: return
                val name = dev.name ?: ""
                val uuids = result.scanRecord?.serviceUuids?.map { it.uuid } ?: emptyList()
                if (name.contains("G560", true) ||
                    name.contains("Logi", true) ||
                    name.contains("G ", true) ||
                    uuids.contains(LOGI_BLE_HIDPP_SERVICE) ||
                    uuids.contains(BLE_HOGP_HID_SERVICE)
                ) {
                    onDeviceFound(dev, result.rssi)
                }
            }
        })
    }

    fun connectBleGatt(device: BluetoothDevice) {
        disconnect()
        onStatusChange("Conectando BLE GATT a ${device.name ?: device.address}...")
        activeGatt = device.connectGatt(context, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
    }

    fun connectRfcommBridge(device: BluetoothDevice) {
        disconnect()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                onStatusChange("Conectando Bluetooth RFCOMM a ${device.name ?: device.address}...")
                val socket = device.createRfcommSocketToServiceRecord(RFCOMM_SPP_UUID)
                adapter?.cancelDiscovery()
                socket.connect()
                rfcommSocket = socket
                rfcommOutput = socket.outputStream
                onStatusChange("Conectado via Bluetooth RFCOMM (SPP Bridge): ${device.name}")
            } catch (e: Exception) {
                onStatusChange("Falha RFCOMM (${e.message}). Tentando BLE GATT...")
                connectBleGatt(device)
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatusChange("BLE conectado. Descobrindo serviços HID++...")
                gatt.requestMtu(64)
                gatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                hidppCharacteristic = null
                onStatusChange("BLE desconectado")
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (status != BluetoothGatt.GATT_SUCCESS) return

            // 1. Procura serviço proprietário Logitech BLE Direct HID++ (00010000-0000-1000-8000-011f2000046d)
            val logiService = gatt.getService(LOGI_BLE_HIDPP_SERVICE)
            if (logiService != null) {
                val ch = logiService.getCharacteristic(LOGI_BLE_HIDPP_CHAR)
                if (ch != null) {
                    setupHidppCharacteristic(gatt, ch, "Logitech BLE HID++ Direto")
                    return
                }
            }

            // 2. Fallback: Serviço HOGP HID padrão (0x1812) com característica de Report (0x2A4D) de 20 bytes
            val hogpService = gatt.getService(BLE_HOGP_HID_SERVICE)
            if (hogpService != null) {
                val writeCh = hogpService.characteristics.firstOrNull { ch ->
                    ch.uuid == BLE_HOGP_REPORT_CHAR &&
                        ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE) != 0 ||
                         (ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                }
                if (writeCh != null) {
                    setupHidppCharacteristic(gatt, writeCh, "BLE HOGP HID Report (0x1812)")
                    return
                }
            }

            onStatusChange("Aviso: Este dispositivo expõe apenas Áudio A2DP no Bluetooth (use o modo Bridge p/ G560)")
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            val data = characteristic.value ?: return
            onPacketLogged("RX <- BLE", data)
            // Se recebemos resposta do IRoot.getFeature(0x8070), atualiza feature8070Index dinamicamente
            if (data.size >= 5 && data[2] == 0x00.toByte() && data[4] != 0x00.toByte()) {
                if (pendingFeatureQueryId == HidppProtocol.FEAT_COLOR_LED_EFFECTS) {
                    feature8070Index = data[4]
                } else if (pendingFeatureQueryId == HidppProtocol.FEAT_BRIGHTNESS) {
                    feature8040Index = data[4]
                }
            }
        }
    }

    private fun setupHidppCharacteristic(
        gatt: BluetoothGatt,
        ch: BluetoothGattCharacteristic,
        label: String
    ) {
        hidppCharacteristic = ch
        gatt.setCharacteristicNotification(ch, true)
        val cccd = ch.getDescriptor(CCCD_UUID)
        if (cccd != null) {
            cccd.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            gatt.writeDescriptor(cccd)
        }
        onStatusChange("Conectado via $label (${gatt.device.name})")

        // Consulta índice dinâmico da Feature 0x8070 via IRoot (0x0000)
        pendingFeatureQueryId = HidppProtocol.FEAT_COLOR_LED_EFFECTS
        sendHidppPacket(HidppProtocol.buildRootGetFeature(HidppProtocol.FEAT_COLOR_LED_EFFECTS))
    }

    fun sendHidppPacket(packet20: ByteArray): Boolean {
        // 1. Envia via BLE GATT se conectado
        val gatt = activeGatt
        val ch = hidppCharacteristic
        if (gatt != null && ch != null) {
            ch.writeType = if ((ch.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0)
                BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE
            else
                BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
            ch.value = packet20
            val ok = gatt.writeCharacteristic(ch)
            onPacketLogged("TX -> BLE", packet20)
            return ok
        }

        // 2. Envia via Bluetooth Classic RFCOMM Bridge se conectado
        val out = rfcommOutput
        if (out != null && rfcommSocket?.isConnected == true) {
            return try {
                synchronized(out) {
                    out.write(packet20)
                    out.flush()
                }
                onPacketLogged("TX -> BT SPP", packet20)
                true
            } catch (e: Exception) {
                false
            }
        }

        return false
    }

    fun disconnect() {
        try { activeGatt?.disconnect(); activeGatt?.close() } catch (_: Exception) {}
        try { rfcommOutput?.close(); rfcommSocket?.close() } catch (_: Exception) {}
        activeGatt = null
        hidppCharacteristic = null
        rfcommSocket = null
        rfcommOutput = null
    }
}
