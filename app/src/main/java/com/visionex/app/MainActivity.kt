package com.visionex.app

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.telephony.SmsManager
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import com.google.android.gms.location.LocationServices
import com.google.android.material.snackbar.Snackbar
import com.visionex.app.databinding.ActivityMainBinding
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity:AppCompatActivity(){
 private lateinit var binding:ActivityMainBinding
 private lateinit var ble:BleVisionExManager
 private val prefs by lazy{getSharedPreferences("visionex_prefs",MODE_PRIVATE)}
 private val location by lazy{LocationServices.getFusedLocationProviderClient(this)}
 private var alerts=0
 private val launcher=registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()){if(!hasBle())Snackbar.make(binding.root,getString(R.string.nearby_permission_needed),Snackbar.LENGTH_LONG).show()}
 override fun onCreate(savedInstanceState:Bundle?){super.onCreate(savedInstanceState);binding=ActivityMainBinding.inflate(layoutInflater);setContentView(binding.root);ble=BleVisionExManager(this,{runOnUiThread{binding.connectionText.text=it}},{runOnUiThread{handleMessage(it.trim())}});binding.contactInput.setText(prefs.getString("emergency_contact",""));binding.autoSmsSwitch.isChecked=prefs.getBoolean("auto_sms",false);alerts=prefs.getInt("alerts",0);updateAlerts();renderHistory();requestPermissionsNow();binding.connectButton.setOnClickListener{if(hasBle())ble.scanAndConnect() else requestPermissionsNow()};binding.ackButton.setOnClickListener{alerts=0;prefs.edit { putInt("alerts", 0) };setNormal();updateAlerts();ble.sendCommand("ACK");addEvent("Alert acknowledged")};binding.simulateButton.setOnClickListener{handleMessage("DROWSY")};binding.clearHistoryButton.setOnClickListener{prefs.edit { remove("history") };renderHistory()};binding.autoSmsSwitch.setOnCheckedChangeListener{_,v->prefs.edit { putBoolean("auto_sms", v) }};binding.testSmsButton.setOnClickListener{saveContact();sendEmergency(true)}}
 override fun onDestroy(){ble.disconnect();super.onDestroy()}
 private fun requestPermissionsNow(){val p=mutableListOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.SEND_SMS);if(Build.VERSION.SDK_INT>=31){p+=Manifest.permission.BLUETOOTH_SCAN;p+=Manifest.permission.BLUETOOTH_CONNECT};launcher.launch(p.toTypedArray())}
 private fun hasBle()=Build.VERSION.SDK_INT<31||(ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_SCAN)==PackageManager.PERMISSION_GRANTED&&ContextCompat.checkSelfPermission(this,Manifest.permission.BLUETOOTH_CONNECT)==PackageManager.PERMISSION_GRANTED)
 private fun handleMessage(raw:String){val m=raw.uppercase(Locale.US);when{m=="DROWSY"||m=="STATUS:DROWSY"||m=="ALERT:1"->drowsy();m=="NORMAL"||m=="STATUS:NORMAL"->setNormal();m.startsWith("BATTERY:")->binding.batteryText.text=getString(R.string.battery_percent,m.substringAfter(":").filter{it.isDigit()});else->addEvent("Device: $raw")}}
 private fun drowsy(){binding.drowsinessText.text=getString(R.string.drowsiness_detected);binding.drowsinessText.setTextColor(ContextCompat.getColor(this,R.color.visionex_warning));alerts++;prefs.edit { putInt("alerts", alerts) };updateAlerts();addEvent("Drowsiness detected (alert $alerts)");if(binding.autoSmsSwitch.isChecked&&alerts>=3)sendEmergency(false)}
 private fun setNormal(){binding.drowsinessText.text=getString(R.string.normal);binding.drowsinessText.setTextColor(ContextCompat.getColor(this,R.color.visionex_ok))}
 private fun updateAlerts(){binding.alertCountText.text=getString(R.string.unacknowledged_alerts,alerts)}
 private fun saveContact(){prefs.edit { putString("emergency_contact", binding.contactInput.text.toString().trim()) }}
 private fun sendEmergency(test:Boolean){saveContact();val number=binding.contactInput.text.toString().trim();if(number.isBlank()){Toast.makeText(this,getString(R.string.enter_emergency_contact),Toast.LENGTH_SHORT).show();return};if(ContextCompat.checkSelfPermission(this,Manifest.permission.ACCESS_FINE_LOCATION)!=PackageManager.PERMISSION_GRANTED){requestPermissionsNow();Toast.makeText(this,getString(R.string.location_permission_required),Toast.LENGTH_SHORT).show();return};location.lastLocation.addOnSuccessListener{loc->val start=if(test)"VISIONEX TEST: Emergency notification test." else "VISIONEX ALERT: Repeated drowsiness alerts were not acknowledged.";val where=if(loc!=null)" Last known location: https://maps.google.com/?q=${loc.latitude},${loc.longitude}" else " Last known location is unavailable.";sendSms(number,start+where);addEvent(if(test)"Test emergency SMS sent" else "Emergency SMS sent");if(!test){alerts=0;prefs.edit().putInt("alerts",0).apply();updateAlerts()}}.addOnFailureListener{sendSms(number,if(test)"VISIONEX TEST: Emergency notification test. Location unavailable." else "VISIONEX ALERT: Repeated drowsiness alerts were not acknowledged. Location unavailable.")}}
 @Suppress("DEPRECATION") private fun sendSms(number:String,message:String){
  val canSendDirectly=ContextCompat.checkSelfPermission(this,Manifest.permission.SEND_SMS)==PackageManager.PERMISSION_GRANTED
  if(canSendDirectly){
    try{
      val sm=if(Build.VERSION.SDK_INT>=31)getSystemService(SmsManager::class.java) else SmsManager.getDefault()
      sm.sendTextMessage(number,null,message,null,null)
      Toast.makeText(this,getString(R.string.sms_sent),Toast.LENGTH_SHORT).show()
      return
    }catch(e:Exception){
      addEvent("Direct SMS unavailable; opening messaging app")
    }
  }
  try{
    val intent=Intent(Intent.ACTION_SENDTO).apply{
      data=Uri.parse("smsto:"+Uri.encode(number))
      putExtra("sms_body",message)
    }
    startActivity(intent)
    Toast.makeText(this,getString(R.string.sms_opened),Toast.LENGTH_LONG).show()
  }catch(e:Exception){
    Toast.makeText(this,getString(R.string.sms_failed,e.message ?: ""),Toast.LENGTH_LONG).show()
  }
}
 private fun addEvent(desc:String){val h=getHistory();h.put(JSONObject().put("time",SimpleDateFormat("yyyy-MM-dd HH:mm:ss",Locale.getDefault()).format(Date())).put("description",desc));prefs.edit { putString("history", h.toString()) };renderHistory()}
 private fun getHistory():JSONArray{val r=prefs.getString("history",null);return try{if(r.isNullOrBlank())JSONArray() else JSONArray(r)}catch(_:Exception){JSONArray()}}
 private fun renderHistory(){val h=getHistory();if(h.length()==0){binding.historyText.text=getString(R.string.no_events_recorded);return};val lines=mutableListOf<String>();for(i in h.length()-1 downTo 0){val e=h.optJSONObject(i)?:continue;lines+="${e.optString("time")}  ${e.optString("description")}"};binding.historyText.text=lines.joinToString("\n")}
}
