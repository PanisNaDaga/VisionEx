package com.visionex.app

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Build
import android.os.Handler
import android.os.Looper
import java.util.ArrayDeque
import java.util.UUID

class BleVisionExManager(
    private val context: Context,
    private val onStatus: (String) -> Unit,
    private val onMessage: (String) -> Unit
) {
    companion object {
        val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
        val TX_NOTIFY_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ac")
        val RX_WRITE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ad")
        val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
        const val TARGET_NAME = "VISIONEX"
    }

    private val manager = context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
    private val adapter: BluetoothAdapter? = manager.adapter
    private val handler = Handler(Looper.getMainLooper())
    private val commandQueue = ArrayDeque<String>()

    private var gatt: BluetoothGatt? = null
    private var writeCharacteristic: BluetoothGattCharacteristic? = null
    private var scanning = false
    private var writeInProgress = false

    @SuppressLint("MissingPermission")
    fun scanAndConnect() {
        val bluetoothAdapter = adapter
        if (bluetoothAdapter == null || !bluetoothAdapter.isEnabled) {
            onStatus("Bluetooth is disabled.")
            return
        }
        if (scanning) return

        scanning = true
        onStatus("Scanning for $TARGET_NAME...")
        bluetoothAdapter.bluetoothLeScanner.startScan(scanCallback)
        handler.postDelayed({
            if (scanning) {
                scanning = false
                try {
                    bluetoothAdapter.bluetoothLeScanner.stopScan(scanCallback)
                } catch (_: Exception) {
                }
                onStatus("VISIONEX not found. Tap scan to try again.")
            }
        }, 12_000)
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    fun disconnect() {
        scanning = false
        try {
            adapter?.bluetoothLeScanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }
        commandQueue.clear()
        writeInProgress = false
        gatt?.disconnect()
        gatt?.close()
        gatt = null
        writeCharacteristic = null
        onStatus("Disconnected")
    }

    /**
     * Queue each BLE write. Android GATT permits one outstanding write operation at a time.
     */
    @SuppressLint("MissingPermission")
    @Synchronized
    fun sendCommand(command: String) {
        if (gatt == null || writeCharacteristic == null) return
        commandQueue.addLast(command)
        writeNextCommand()
    }

    @SuppressLint("MissingPermission")
    @Synchronized
    private fun writeNextCommand() {
        if (writeInProgress) return
        val currentGatt = gatt ?: return
        val characteristic = writeCharacteristic ?: return

        while (commandQueue.isNotEmpty() && !writeInProgress) {
            val command = commandQueue.removeFirst()
            val bytes = command.toByteArray(Charsets.UTF_8)
            writeInProgress = true

            val accepted = if (Build.VERSION.SDK_INT >= 33) {
                currentGatt.writeCharacteristic(
                    characteristic,
                    bytes,
                    BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                ) == BluetoothStatusCodes.SUCCESS
            } else {
                @Suppress("DEPRECATION")
                characteristic.value = bytes
                @Suppress("DEPRECATION")
                currentGatt.writeCharacteristic(characteristic)
            }

            if (!accepted) {
                writeInProgress = false
                onStatus("Bluetooth could not queue command: $command")
            }
        }
    }

    private val scanCallback = object : ScanCallback() {
        @SuppressLint("MissingPermission")
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            val name = result.device.name ?: result.scanRecord?.deviceName ?: return
            if (!name.contains(TARGET_NAME, ignoreCase = true)) return

            scanning = false
            try {
                adapter?.bluetoothLeScanner?.stopScan(this)
            } catch (_: Exception) {
            }
            onStatus("Found $name. Connecting...")
            gatt = result.device.connectGatt(context, false, gattCallback)
        }

        override fun onScanFailed(errorCode: Int) {
            scanning = false
            onStatus("BLE scan failed ($errorCode)")
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        @SuppressLint("MissingPermission")
        override fun onConnectionStateChange(
            currentGatt: BluetoothGatt,
            status: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                onStatus("Connected. Discovering services...")
                currentGatt.discoverServices()
            } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                synchronized(this@BleVisionExManager) {
                    writeCharacteristic = null
                    commandQueue.clear()
                    writeInProgress = false
                }
                onStatus("Disconnected")
            }
        }

        @SuppressLint("MissingPermission")
        override fun onServicesDiscovered(currentGatt: BluetoothGatt, status: Int) {
            synchronized(this@BleVisionExManager) {
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onStatus("Bluetooth service discovery failed ($status).")
                    return
                }
                val service = currentGatt.getService(SERVICE_UUID)
                if (service == null) {
                    onStatus("Connected, but VisionEx BLE service was not found.")
                    return
                }

                val notifyCharacteristic = service.getCharacteristic(TX_NOTIFY_UUID)
                writeCharacteristic = service.getCharacteristic(RX_WRITE_UUID)

                if (notifyCharacteristic != null) {
                    currentGatt.setCharacteristicNotification(notifyCharacteristic, true)
                    val descriptor = notifyCharacteristic.getDescriptor(CCCD_UUID)
                    if (descriptor != null) {
                        writeInProgress = true
                        val accepted = if (Build.VERSION.SDK_INT >= 33) {
                            currentGatt.writeDescriptor(
                                descriptor,
                                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            ) == BluetoothStatusCodes.SUCCESS
                        } else {
                            @Suppress("DEPRECATION")
                            descriptor.value = BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                            @Suppress("DEPRECATION")
                            currentGatt.writeDescriptor(descriptor)
                        }
                        if (!accepted) {
                            writeInProgress = false
                            onStatus("Could not enable VisionEx notifications.")
                            writeNextCommand()
                        }
                    } else {
                        writeNextCommand()
                    }
                } else {
                    writeNextCommand()
                }
                onStatus("VISIONEX connected")
            }
        }

        override fun onDescriptorWrite(
            currentGatt: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            status: Int
        ) {
            synchronized(this@BleVisionExManager) {
                writeInProgress = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onStatus("Could not enable VisionEx notifications ($status).")
                }
                writeNextCommand()
            }
        }

        override fun onCharacteristicWrite(
            currentGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            status: Int
        ) {
            synchronized(this@BleVisionExManager) {
                writeInProgress = false
                if (status != BluetoothGatt.GATT_SUCCESS) {
                    onStatus("Bluetooth command write failed ($status).")
                }
                writeNextCommand()
            }
        }

        @Deprecated("Deprecated in API 33")
        override fun onCharacteristicChanged(
            currentGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            onMessage(characteristic.value.toString(Charsets.UTF_8))
        }

        override fun onCharacteristicChanged(
            currentGatt: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            onMessage(value.toString(Charsets.UTF_8))
        }
    }
}
