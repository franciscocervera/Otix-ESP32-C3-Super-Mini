package com.mechrobotix.otix

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import java.nio.charset.StandardCharsets
import java.util.ArrayDeque
import java.util.UUID

class BleMotorController(
    context: Context,
    private val onStateChanged: (ConnectionState, String) -> Unit,
    private val onTelemetry: (String) -> Unit
) : MotorController {
    enum class ConnectionState {
        DISCONNECTED,
        SCANNING,
        CONNECTING,
        DISCOVERING,
        READY,
        ERROR
    }

    private val appContext = context.applicationContext
    private val handler = Handler(Looper.getMainLooper())
    private val bluetoothManager = appContext.getSystemService(BluetoothManager::class.java)
    private val bluetoothAdapter: BluetoothAdapter?
        get() = bluetoothManager?.adapter
    private val writeQueue = ArrayDeque<ByteArray>()
    private var bluetoothGatt: BluetoothGatt? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var stateCharacteristic: BluetoothGattCharacteristic? = null
    private var writeInProgress = false
    private var scanning = false
    private var ready = false
    private var currentDeviceAddress: String? = null

    private val scanTimeout = Runnable {
        if (scanning) {
            stopScan()
            publishState(ConnectionState.ERROR, "No se encontró un dispositivo OTIX")
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            if (!isOtixDevice(result)) return
            stopScan()
            connect(result.device)
        }

        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            results.firstOrNull(::isOtixDevice)?.let {
                stopScan()
                connect(it.device)
            }
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            handler.removeCallbacks(scanTimeout)
            publishState(ConnectionState.ERROR, "Error de búsqueda BLE: $errorCode")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(gatt: BluetoothGatt, status: Int, newState: Int) {
            if (gatt !== bluetoothGatt) {
                gatt.close()
                return
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                clearConnection(gatt)
                publishState(ConnectionState.ERROR, "Error de conexión BLE: $status")
                return
            }
            when (newState) {
                BluetoothProfile.STATE_CONNECTED -> {
                    publishState(ConnectionState.DISCOVERING, "Dispositivo conectado, preparando servicio")
                    try {
                        if (!gatt.discoverServices()) {
                            failConnection("No se pudo iniciar la detección de servicios")
                        }
                    } catch (_: SecurityException) {
                        failConnection("Falta permiso para usar Bluetooth")
                    }
                }
                BluetoothProfile.STATE_DISCONNECTED -> {
                    clearConnection(gatt)
                    if (status == BluetoothGatt.GATT_SUCCESS) {
                        publishState(ConnectionState.DISCONNECTED, "Desconectado")
                    } else {
                        publishState(ConnectionState.ERROR, "La conexión BLE se interrumpió: $status")
                    }
                }
                else -> Unit
            }
        }

        override fun onServicesDiscovered(gatt: BluetoothGatt, status: Int) {
            if (gatt !== bluetoothGatt) return
            if (status != BluetoothGatt.GATT_SUCCESS) {
                failConnection("No se pudieron detectar los servicios BLE")
                return
            }
            val service: BluetoothGattService = gatt.getService(SERVICE_UUID) ?: run {
                failConnection("El dispositivo no expone el servicio OTIX")
                return
            }
            commandCharacteristic = service.getCharacteristic(COMMAND_UUID)
            stateCharacteristic = service.getCharacteristic(STATE_UUID)
            if (commandCharacteristic == null) {
                failConnection("No se encontró el canal de comandos OTIX")
                return
            }
            val state = stateCharacteristic
            if (state == null) {
                markReady()
                return
            }
            try {
                gatt.setCharacteristicNotification(state, true)
                val descriptor = state.getDescriptor(CCCD_UUID)
                if (descriptor == null) {
                    markReady()
                    return
                }
                val started = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                    gatt.writeDescriptor(descriptor, BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) == BluetoothGatt.GATT_SUCCESS
                } else {
                    descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    gatt.writeDescriptor(descriptor)
                }
                if (!started) markReady()
            } catch (_: SecurityException) {
                failConnection("Falta permiso para preparar las notificaciones BLE")
            }
        }

        override fun onDescriptorWrite(gatt: BluetoothGatt, descriptor: BluetoothGattDescriptor, status: Int) {
            if (gatt === bluetoothGatt) markReady()
        }

        override fun onCharacteristicChanged(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic) {
            if (gatt === bluetoothGatt) publishTelemetry(characteristic.value ?: byteArrayOf())
        }

        override fun onCharacteristicChanged(
            gatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            if (gatt === bluetoothGatt) publishTelemetry(value)
        }

        override fun onCharacteristicWrite(gatt: BluetoothGatt, characteristic: BluetoothGattCharacteristic, status: Int) {
            if (gatt !== bluetoothGatt) return
            synchronized(writeQueue) {
                writeInProgress = false
            }
            if (status != BluetoothGatt.GATT_SUCCESS) {
                publishTelemetry("ERROR:No se pudo enviar un comando")
            }
            writeNext()
        }
    }

    fun isBluetoothAvailable(): Boolean = bluetoothAdapter != null

    fun isBluetoothEnabled(): Boolean = bluetoothAdapter?.isEnabled == true

    fun startScan() {
        if (!hasScanPermission()) {
            publishState(ConnectionState.ERROR, "Falta permiso para buscar dispositivos Bluetooth")
            return
        }
        if (!isBluetoothEnabled()) {
            publishState(ConnectionState.ERROR, "Bluetooth está desactivado")
            return
        }
        disconnectInternal(false)
        val scanner = bluetoothAdapter?.bluetoothLeScanner ?: run {
            publishState(ConnectionState.ERROR, "Bluetooth LE no está disponible")
            return
        }
        val settings = ScanSettings.Builder()
            .setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY)
            .build()
        try {
            scanning = true
            publishState(ConnectionState.SCANNING, "Buscando OTIX")
            scanner.startScan(null, settings, scanCallback)
            handler.removeCallbacks(scanTimeout)
            handler.postDelayed(scanTimeout, SCAN_TIMEOUT_MS)
        } catch (_: SecurityException) {
            scanning = false
            publishState(ConnectionState.ERROR, "Falta permiso para buscar dispositivos Bluetooth")
        }
    }


    private fun isOtixDevice(result: ScanResult): Boolean {
        val scanRecord = result.scanRecord
        val advertisedName = scanRecord?.deviceName.orEmpty()
        val deviceName = try {
            result.device.name.orEmpty()
        } catch (_: SecurityException) {
            ""
        }
        val advertisesService = scanRecord
            ?.serviceUuids
            ?.any { it.uuid == SERVICE_UUID } == true

        return advertisedName.equals(DEVICE_NAME, ignoreCase = true) ||
            deviceName.equals(DEVICE_NAME, ignoreCase = true) ||
            advertisesService
    }

    fun stopScan() {
        if (!scanning) return
        scanning = false
        handler.removeCallbacks(scanTimeout)
        try {
            bluetoothAdapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: SecurityException) {
            Unit
        }
    }

    fun disconnect() {
        disconnectInternal(true)
    }

    private fun disconnectInternal(publish: Boolean) {
        stopScan()
        ready = false
        commandCharacteristic = null
        stateCharacteristic = null
        synchronized(writeQueue) {
            writeQueue.clear()
            writeInProgress = false
        }
        val gatt = bluetoothGatt
        bluetoothGatt = null
        currentDeviceAddress = null
        if (gatt != null) {
            try {
                gatt.disconnect()
            } catch (_: SecurityException) {
                Unit
            }
            gatt.close()
        }
        if (publish) publishState(ConnectionState.DISCONNECTED, "Desconectado")
    }

    private fun connect(device: BluetoothDevice) {
        if (!hasConnectPermission()) {
            publishState(ConnectionState.ERROR, "Falta permiso para conectar por Bluetooth")
            return
        }
        val address = device.address
        if (currentDeviceAddress == address && bluetoothGatt != null) return
        disconnectInternal(false)
        currentDeviceAddress = address
        publishState(ConnectionState.CONNECTING, "Conectando con OTIX")
        try {
            bluetoothGatt = device.connectGatt(appContext, false, gattCallback, BluetoothDevice.TRANSPORT_LE)
        } catch (_: SecurityException) {
            failConnection("Falta permiso para conectar por Bluetooth")
        }
    }

    override fun isReady(): Boolean = ready

    override fun send(command: MovementCommand): Boolean = enqueue(command.wire, false)

    override fun sendSpeed(speed: SpeedLevel): Boolean = enqueue(speed.wire, false)

    override fun sendHeartbeat(): Boolean = enqueue(HEARTBEAT, true)

    private fun enqueue(value: String, heartbeat: Boolean): Boolean {
        if (!ready || commandCharacteristic == null || bluetoothGatt == null) return false
        val payload = value.toByteArray(StandardCharsets.UTF_8)
        synchronized(writeQueue) {
            if (heartbeat && (writeInProgress || writeQueue.isNotEmpty())) return true
            if (writeQueue.size >= MAX_QUEUE_SIZE) writeQueue.removeFirst()
            writeQueue.addLast(payload)
        }
        writeNext()
        return true
    }

    private fun writeNext() {
        val gatt = bluetoothGatt ?: return
        val characteristic = commandCharacteristic ?: return
        val payload = synchronized(writeQueue) {
            if (writeInProgress || writeQueue.isEmpty()) return
            writeInProgress = true
            writeQueue.removeFirst()
        }
        val started = try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                gatt.writeCharacteristic(
                    characteristic,
                    payload,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothGatt.GATT_SUCCESS
            } else {
                characteristic.writeType = BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                characteristic.value = payload
                gatt.writeCharacteristic(characteristic)
            }
        } catch (_: SecurityException) {
            false
        }
        if (!started) {
            synchronized(writeQueue) {
                writeInProgress = false
            }
            publishTelemetry("ERROR:No se pudo iniciar el envío del comando")
            handler.postDelayed({ writeNext() }, WRITE_RETRY_MS)
        }
    }

    private fun markReady() {
        ready = true
        publishState(ConnectionState.READY, "OTIX conectado")
    }

    private fun failConnection(message: String) {
        disconnectInternal(false)
        publishState(ConnectionState.ERROR, message)
    }

    private fun clearConnection(gatt: BluetoothGatt) {
        if (gatt !== bluetoothGatt) {
            gatt.close()
            return
        }
        ready = false
        commandCharacteristic = null
        stateCharacteristic = null
        currentDeviceAddress = null
        synchronized(writeQueue) {
            writeQueue.clear()
            writeInProgress = false
        }
        bluetoothGatt = null
        gatt.close()
    }

    private fun publishState(state: ConnectionState, message: String) {
        handler.post { onStateChanged(state, message) }
    }

    private fun publishTelemetry(value: ByteArray) {
        publishTelemetry(value.toString(StandardCharsets.UTF_8))
    }

    private fun publishTelemetry(value: String) {
        val text = value.trim()
        if (text.isNotEmpty()) handler.post { onTelemetry(text) }
    }

    private fun hasScanPermission(): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED
        } else {
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        }
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    }

    override fun close() {
        disconnect()
        handler.removeCallbacksAndMessages(null)
    }

    companion object {
        const val DEVICE_NAME = "OTIX"
        val SERVICE_UUID: UUID = UUID.fromString("7b183224-9168-443e-a927-7aeea07e8105")
        val COMMAND_UUID: UUID = UUID.fromString("7b183225-9168-443e-a927-7aeea07e8105")
        val STATE_UUID: UUID = UUID.fromString("7b183226-9168-443e-a927-7aeea07e8105")
        private val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        private const val HEARTBEAT = "P"
        private const val SCAN_TIMEOUT_MS = 12000L
        private const val WRITE_RETRY_MS = 120L
        private const val MAX_QUEUE_SIZE = 32
    }
}
