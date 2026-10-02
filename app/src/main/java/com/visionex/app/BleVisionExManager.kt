package com.visionex.app

import android.annotation.SuppressLint
import android.bluetooth.*
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.content.Context
import android.os.Handler
import android.os.Looper
import java.util.UUID

class BleVisionExManager(private val context: Context, private val onStatus:(String)->Unit, private val onMessage:(String)->Unit) {
 companion object {
  val SERVICE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ab")
  val TX_NOTIFY_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ac")
  val RX_WRITE_UUID: UUID = UUID.fromString("12345678-1234-1234-1234-1234567890ad")
  val CCCD_UUID: UUID = UUID.fromString("00002902-0000-1000-8000-00805f9b34fb")
  const val TARGET_NAME = "VISIONEX"
 }
 private val manager=context.getSystemService(Context.BLUETOOTH_SERVICE) as BluetoothManager
 private val adapter=manager.adapter
 private val handler=Handler(Looper.getMainLooper())
 private var gatt:BluetoothGatt?=null
 private var writeCharacteristic:BluetoothGattCharacteristic?=null
 private var scanning=false
 @SuppressLint("MissingPermission") fun scanAndConnect(){ if(adapter==null||!adapter.isEnabled){onStatus("Bluetooth is disabled.");return}; if(scanning)return; scanning=true; onStatus("Scanning for $TARGET_NAME..."); adapter.bluetoothLeScanner.startScan(scanCallback); handler.postDelayed({if(scanning){scanning=false;adapter.bluetoothLeScanner.stopScan(scanCallback);onStatus("VISIONEX not found. Tap scan to try again.")}},12000)}
 @SuppressLint("MissingPermission") fun disconnect(){scanning=false;try{adapter.bluetoothLeScanner?.stopScan(scanCallback)}catch(_:Exception){};gatt?.disconnect();gatt?.close();gatt=null;writeCharacteristic=null;onStatus("Disconnected")}
 @SuppressLint("MissingPermission") fun sendCommand(command:String){val c=writeCharacteristic?:return; val d=command.toByteArray(); if(android.os.Build.VERSION.SDK_INT>=33)gatt?.writeCharacteristic(c,d,BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) else {@Suppress("DEPRECATION") c.value=d; @Suppress("DEPRECATION") gatt?.writeCharacteristic(c)}}
 private val scanCallback=object:ScanCallback(){@SuppressLint("MissingPermission") override fun onScanResult(callbackType:Int,result:ScanResult){val n=result.device.name?:result.scanRecord?.deviceName?:return;if(n.contains(TARGET_NAME,true)){scanning=false;adapter.bluetoothLeScanner.stopScan(this);onStatus("Found $n. Connecting...");gatt=result.device.connectGatt(context,false,gattCallback)}}; override fun onScanFailed(errorCode:Int){scanning=false;onStatus("BLE scan failed ($errorCode)")}}
 private val gattCallback=object:BluetoothGattCallback(){
  @SuppressLint("MissingPermission") override fun onConnectionStateChange(g:BluetoothGatt,status:Int,newState:Int){if(newState==BluetoothProfile.STATE_CONNECTED){onStatus("Connected. Discovering services...");g.discoverServices()}else if(newState==BluetoothProfile.STATE_DISCONNECTED){onStatus("Disconnected");writeCharacteristic=null}}
  @SuppressLint("MissingPermission") override fun onServicesDiscovered(g:BluetoothGatt,status:Int){val s=g.getService(SERVICE_UUID);if(s==null){onStatus("Connected, but VisionEx BLE service was not found.");return};val n=s.getCharacteristic(TX_NOTIFY_UUID);writeCharacteristic=s.getCharacteristic(RX_WRITE_UUID);if(n!=null){g.setCharacteristicNotification(n,true);val d=n.getDescriptor(CCCD_UUID);if(d!=null){if(android.os.Build.VERSION.SDK_INT>=33)g.writeDescriptor(d,BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE) else {@Suppress("DEPRECATION") d.value=BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE; @Suppress("DEPRECATION") g.writeDescriptor(d)}}};onStatus("VISIONEX connected")}
  @Deprecated("Deprecated in API 33") override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic){onMessage(c.value.toString(Charsets.UTF_8))}
  override fun onCharacteristicChanged(g:BluetoothGatt,c:BluetoothGattCharacteristic,value:ByteArray){onMessage(value.toString(Charsets.UTF_8))}
 }
}
