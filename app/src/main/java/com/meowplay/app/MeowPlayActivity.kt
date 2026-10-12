package com.meowplay.app

import android.Manifest
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.widget.SwitchCompat
import androidx.core.content.ContextCompat
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.weblink.sdk.WebLinkTouchPoint
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MeowPlayActivity : AppCompatActivity(), MeowPlayBridge.BridgeListener {

    private lateinit var tvGlobalBadge: TextView
    private lateinit var tvStereoStatus: TextView
    private lateinit var tvStereoDetails: TextView
    private lateinit var tvCarPlayStatus: TextView
    private lateinit var tvCarPlayDetails: TextView
    private lateinit var tvTouchStatus: TextView
    private lateinit var tvLogs: TextView
    private lateinit var scrollLogs: ScrollView
    private lateinit var btnToggle: Button
    private lateinit var btnKeyFrame: Button

    // 1. Wi-Fi & Hotspot Controls
    private lateinit var rgWifiMode: RadioGroup
    private lateinit var rbModeHotspot: RadioButton
    private lateinit var rbModeDirect: RadioButton
    private lateinit var layoutHotspotCredentials: LinearLayout
    private lateinit var etHotspotSsid: EditText
    private lateinit var etHotspotPassword: EditText
    private lateinit var rgHotspotBand: RadioGroup
    private lateinit var rbBand5Ghz: RadioButton
    private lateinit var rbBand24Ghz: RadioButton
    private lateinit var rbBandAuto: RadioButton
    private lateinit var btnSaveHotspot: Button
    private lateinit var btnOpenHotspotSettings: Button

    // 2. Display & Interface Controls
    private lateinit var swRightHandDrive: SwitchCompat
    private lateinit var rgDockPosition: RadioGroup
    private lateinit var rbDockAuto: RadioButton
    private lateinit var rbDockDriver: RadioButton
    private lateinit var rbDockBottom: RadioButton
    private lateinit var rgFps: RadioGroup
    private lateinit var rbFps60: RadioButton
    private lateinit var rbFps30: RadioButton
    private lateinit var rgDisplayScale: RadioGroup
    private lateinit var rbScale140: RadioButton
    private lateinit var rbScale154: RadioButton
    private lateinit var rbScale180: RadioButton

    // 3. Audio & System Controls
    private lateinit var rgAudioRouting: RadioGroup
    private lateinit var rbAudioUsb: RadioButton
    private lateinit var rbAudioBt: RadioButton
    private lateinit var swBufferedAudio: SwitchCompat
    private lateinit var swSpeechHighQuality: SwitchCompat
    private lateinit var swAutoStartBoot: SwitchCompat
    private lateinit var swStopCarPlayOnDisconnect: SwitchCompat

    // 4. Hardware & Identity Controls
    private lateinit var rgMfiTarget: RadioGroup
    private lateinit var rbMfiLocal: RadioButton
    private lateinit var rbMfiUsb: RadioButton
    private lateinit var etAdapterName: EditText
    private lateinit var etOemLabel: EditText
    private lateinit var btnSaveAllSettings: Button

    private lateinit var bridge: MeowPlayBridge
    private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.US)
    private val logLines = ArrayList<String>()

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        var allGranted = true
        for ((_, granted) in permissions) {
            if (!granted) allGranted = false
        }
        appendLog(if (allGranted) "All runtime permissions granted." else "Some permissions were not granted.")
        if (allGranted) {
            MeowPlayService.start(this)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_adapter)

        tvGlobalBadge = findViewById(R.id.tvGlobalStateBadge)
        tvStereoStatus = findViewById(R.id.tvStereoStatus)
        tvStereoDetails = findViewById(R.id.tvStereoDetails)
        tvCarPlayStatus = findViewById(R.id.tvCarPlayStatus)
        tvCarPlayDetails = findViewById(R.id.tvCarPlayDetails)
        tvTouchStatus = findViewById(R.id.tvTouchStatus)
        tvLogs = findViewById(R.id.tvLogs)
        scrollLogs = findViewById(R.id.scrollLogs)
        btnToggle = findViewById(R.id.btnToggleService)
        btnKeyFrame = findViewById(R.id.btnForceKeyFrame)

        val btnCopyLogs = findViewById<Button>(R.id.btnCopyLogs)
        val btnClearLogs = findViewById<Button>(R.id.btnClearLogs)

        btnCopyLogs.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val clip = ClipData.newPlainText("MeowPlay Diagnostic Logs", tvLogs.text)
            clipboard.setPrimaryClip(clip)
            Toast.makeText(this, "Logs copied to clipboard!", Toast.LENGTH_SHORT).show()
        }

        btnClearLogs.setOnClickListener {
            logLines.clear()
            tvLogs.text = ""
            Toast.makeText(this, "Logs cleared", Toast.LENGTH_SHORT).show()
        }

        bridge = MeowPlayBridge.getInstance(this)

        initAllSettingsControls()

        btnToggle.setOnClickListener {
            if (bridge.isRunning()) {
                MeowPlayService.stop(this)
                btnToggle.text = getString(R.string.start_service)
                btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
            } else {
                checkPermissionsAndStart()
            }
        }

        btnKeyFrame.setOnClickListener {
            bridge.requestKeyFrame()
            appendLog("Manual IDR Keyframe requested.")
        }

        handleUsbIntent(intent)
        checkPermissionsAndStart()
    }

    private fun initAllSettingsControls() {
        // 1. Wi-Fi
        rgWifiMode = findViewById(R.id.rgWifiMode)
        rbModeHotspot = findViewById(R.id.rbModeHotspot)
        rbModeDirect = findViewById(R.id.rbModeDirect)
        layoutHotspotCredentials = findViewById(R.id.layoutHotspotCredentials)
        etHotspotSsid = findViewById(R.id.etHotspotSsid)
        etHotspotPassword = findViewById(R.id.etHotspotPassword)
        rgHotspotBand = findViewById(R.id.rgHotspotBand)
        rbBand5Ghz = findViewById(R.id.rbBand5Ghz)
        rbBand24Ghz = findViewById(R.id.rbBand24Ghz)
        rbBandAuto = findViewById(R.id.rbBandAuto)
        btnSaveHotspot = findViewById(R.id.btnSaveHotspot)
        btnOpenHotspotSettings = findViewById(R.id.btnOpenHotspotSettings)

        // 2. Display
        swRightHandDrive = findViewById(R.id.swRightHandDrive)
        rgDockPosition = findViewById(R.id.rgDockPosition)
        rbDockAuto = findViewById(R.id.rbDockAuto)
        rbDockDriver = findViewById(R.id.rbDockDriver)
        rbDockBottom = findViewById(R.id.rbDockBottom)
        rgFps = findViewById(R.id.rgFps)
        rbFps60 = findViewById(R.id.rbFps60)
        rbFps30 = findViewById(R.id.rbFps30)
        rgDisplayScale = findViewById(R.id.rgDisplayScale)
        rbScale140 = findViewById(R.id.rbScale140)
        rbScale154 = findViewById(R.id.rbScale154)
        rbScale180 = findViewById(R.id.rbScale180)

        // 3. Audio & System
        rgAudioRouting = findViewById(R.id.rgAudioRouting)
        rbAudioUsb = findViewById(R.id.rbAudioUsb)
        rbAudioBt = findViewById(R.id.rbAudioBt)
        swBufferedAudio = findViewById(R.id.swBufferedAudio)
        swSpeechHighQuality = findViewById(R.id.swSpeechHighQuality)
        swAutoStartBoot = findViewById(R.id.swAutoStartBoot)
        swStopCarPlayOnDisconnect = findViewById(R.id.swStopCarPlayOnDisconnect)

        // 4. Hardware & Identity
        rgMfiTarget = findViewById(R.id.rgMfiTarget)
        rbMfiLocal = findViewById(R.id.rbMfiLocal)
        rbMfiUsb = findViewById(R.id.rbMfiUsb)
        etAdapterName = findViewById(R.id.etAdapterName)
        etOemLabel = findViewById(R.id.etOemLabel)
        btnSaveAllSettings = findViewById(R.id.btnSaveAllSettings)

        // Populate saved values
        val currentWifiMode = MeowPlayConfigManager.getWifiMode(this)
        etHotspotSsid.setText(MeowPlayConfigManager.getHotspotSsid(this))
        etHotspotPassword.setText(MeowPlayConfigManager.getHotspotPassword(this))
        if (currentWifiMode == WirelessHotspotMode.WIFI_P2P) {
            rbModeDirect.isChecked = true
            layoutHotspotCredentials.visibility = View.GONE
        } else {
            rbModeHotspot.isChecked = true
            layoutHotspotCredentials.visibility = View.VISIBLE
        }

        when (MeowPlayConfigManager.getHotspotBand(this)) {
            ManualHotspotBand.GHZ_2_4 -> rbBand24Ghz.isChecked = true
            ManualHotspotBand.AUTO -> rbBandAuto.isChecked = true
            else -> rbBand5Ghz.isChecked = true
        }

        swRightHandDrive.isChecked = MeowPlayConfigManager.getRightHandDrive(this)

        when (MeowPlayConfigManager.getDockPosition(this)) {
            1 -> rbDockDriver.isChecked = true
            2 -> rbDockBottom.isChecked = true
            else -> rbDockAuto.isChecked = true
        }

        if (MeowPlayConfigManager.getFps(this) == 30) {
            rbFps30.isChecked = true
        } else {
            rbFps60.isChecked = true
        }

        when (MeowPlayConfigManager.getWidthPhysicalMm(this)) {
            140 -> rbScale140.isChecked = true
            180 -> rbScale180.isChecked = true
            else -> rbScale154.isChecked = true
        }

        if (MeowPlayConfigManager.getCarBluetoothAudio(this)) {
            rbAudioBt.isChecked = true
        } else {
            rbAudioUsb.isChecked = true
        }

        swBufferedAudio.isChecked = MeowPlayConfigManager.getMainBufferedAudio(this)
        swSpeechHighQuality.isChecked = MeowPlayConfigManager.getSpeechWithout8And16Khz(this)
        swAutoStartBoot.isChecked = MeowPlayConfigManager.getAutoStartOnBoot(this)
        swStopCarPlayOnDisconnect.isChecked = MeowPlayConfigManager.getStopCarPlayOnDisconnect(this)
        swStopCarPlayOnDisconnect.setOnCheckedChangeListener { _, isChecked ->
            MeowPlayConfigManager.setStopCarPlayOnDisconnect(this, isChecked)
        }

        if (MeowPlayConfigManager.getMfiTarget(this) == MfiTarget.USB_CH341) {
            rbMfiUsb.isChecked = true
        } else {
            rbMfiLocal.isChecked = true
        }

        etAdapterName.setText(MeowPlayConfigManager.getAdapterName(this))
        etOemLabel.setText(MeowPlayConfigManager.getOemLabel(this))

        // Listeners
        rgWifiMode.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbModeHotspot) {
                layoutHotspotCredentials.visibility = View.VISIBLE
                MeowPlayConfigManager.setWifiMode(this, WirelessHotspotMode.MANUAL)
                appendLog("Switched to Wi-Fi Hotspot mode.")
                bridge.reloadConfiguration()
            } else if (checkedId == R.id.rbModeDirect) {
                layoutHotspotCredentials.visibility = View.GONE
                MeowPlayConfigManager.setWifiMode(this, WirelessHotspotMode.WIFI_P2P)
                appendLog("Switched to Wi-Fi Direct (P2P) mode.")
                bridge.reloadConfiguration()
            }
        }

        btnSaveHotspot.setOnClickListener {
            saveHotspotCredentials()
        }

        btnOpenHotspotSettings.setOnClickListener {
            val intents = listOf(
                Intent("android.settings.WIFI_TETHER_SETTINGS"),
                Intent(Settings.ACTION_WIRELESS_SETTINGS),
                Intent(Settings.ACTION_WIFI_SETTINGS)
            )
            var opened = false
            for (i in intents) {
                try {
                    startActivity(i)
                    opened = true
                    break
                } catch (ignored: Exception) {}
            }
            if (!opened) {
                Toast.makeText(this, "Please open Hotspot settings in Android Settings", Toast.LENGTH_SHORT).show()
            }
        }

        btnSaveAllSettings.setOnClickListener {
            saveAllSettings()
        }
    }

    private fun saveHotspotCredentials() {
        val ssid = etHotspotSsid.text.toString().trim()
        val pass = etHotspotPassword.text.toString().trim()

        if (ssid.isEmpty()) {
            Toast.makeText(this, "SSID cannot be empty", Toast.LENGTH_SHORT).show()
            return
        }
        if (pass.length < 8 || pass.length > 63) {
            Toast.makeText(this, "Password must be 8 to 63 characters", Toast.LENGTH_SHORT).show()
            return
        }

        val band = when (rgHotspotBand.checkedRadioButtonId) {
            R.id.rbBand24Ghz -> ManualHotspotBand.GHZ_2_4
            R.id.rbBandAuto -> ManualHotspotBand.AUTO
            else -> ManualHotspotBand.GHZ_5
        }

        MeowPlayConfigManager.setHotspotSsid(this, ssid)
        MeowPlayConfigManager.setHotspotPassword(this, pass)
        MeowPlayConfigManager.setHotspotBand(this, band)
        MeowPlayConfigManager.setWifiMode(this, WirelessHotspotMode.MANUAL)

        appendLog("Saved Wi-Fi Hotspot: SSID='$ssid', Band=${band.name}")
        Toast.makeText(this, "Wi-Fi Hotspot settings saved & applied!", Toast.LENGTH_SHORT).show()
        bridge.reloadConfiguration()
    }

    private fun saveAllSettings() {
        // Save Wi-Fi
        val ssid = etHotspotSsid.text.toString().trim()
        val pass = etHotspotPassword.text.toString().trim()
        if (ssid.isNotEmpty()) MeowPlayConfigManager.setHotspotSsid(this, ssid)
        if (pass.length in 8..63) MeowPlayConfigManager.setHotspotPassword(this, pass)

        val band = when (rgHotspotBand.checkedRadioButtonId) {
            R.id.rbBand24Ghz -> ManualHotspotBand.GHZ_2_4
            R.id.rbBandAuto -> ManualHotspotBand.AUTO
            else -> ManualHotspotBand.GHZ_5
        }
        MeowPlayConfigManager.setHotspotBand(this, band)

        // Save Display & Interface
        MeowPlayConfigManager.setRightHandDrive(this, swRightHandDrive.isChecked)

        val dockPos = when (rgDockPosition.checkedRadioButtonId) {
            R.id.rbDockDriver -> 1
            R.id.rbDockBottom -> 2
            else -> 0
        }
        MeowPlayConfigManager.setDockPosition(this, dockPos)

        val fps = if (rgFps.checkedRadioButtonId == R.id.rbFps30) 30 else 60
        MeowPlayConfigManager.setFps(this, fps)

        val widthMm = when (rgDisplayScale.checkedRadioButtonId) {
            R.id.rbScale140 -> 140
            R.id.rbScale180 -> 180
            else -> 154
        }
        MeowPlayConfigManager.setWidthPhysicalMm(this, widthMm)

        // Save Audio & System
        val carBtAudio = rgAudioRouting.checkedRadioButtonId == R.id.rbAudioBt
        MeowPlayConfigManager.setCarBluetoothAudio(this, carBtAudio)
        MeowPlayConfigManager.setMainBufferedAudio(this, swBufferedAudio.isChecked)
        MeowPlayConfigManager.setSpeechWithout8And16Khz(this, swSpeechHighQuality.isChecked)
        MeowPlayConfigManager.setAutoStartOnBoot(this, swAutoStartBoot.isChecked)
        MeowPlayConfigManager.setStopCarPlayOnDisconnect(this, swStopCarPlayOnDisconnect.isChecked)

        // Save Hardware & Identity
        val mfiTarget = if (rgMfiTarget.checkedRadioButtonId == R.id.rbMfiUsb) MfiTarget.USB_CH341 else MfiTarget.LOCAL
        MeowPlayConfigManager.setMfiTarget(this, mfiTarget)

        val adapterName = etAdapterName.text.toString().trim().ifBlank { MeowPlayConfigManager.DEFAULT_ADAPTER_NAME }
        val oemLabel = etOemLabel.text.toString().trim().ifBlank { MeowPlayConfigManager.DEFAULT_OEM_LABEL }
        MeowPlayConfigManager.setAdapterName(this, adapterName)
        MeowPlayConfigManager.setOemLabel(this, oemLabel)

        appendLog("Applied Settings: FPS=$fps, RHD=${swRightHandDrive.isChecked}, Dock=$dockPos, Scale=${widthMm}mm, Audio=${if (carBtAudio) "DirectBT" else "MeowPlayUSB"}, MFi=${mfiTarget.name}")
        Toast.makeText(this, "All settings saved and applied! 🐱", Toast.LENGTH_SHORT).show()
        bridge.reloadConfiguration()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    private fun handleUsbIntent(intent: Intent?) {
        if (intent == null) return
        val action = intent.action
        if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED == action) {
            val accessory = intent.getParcelableExtra<UsbAccessory>(UsbManager.EXTRA_ACCESSORY)
            appendLog("USB Accessory Attached: ${accessory?.model ?: "Unknown"}")
            MeowPlayService.start(this)
        }
    }

    private fun checkPermissionsAndStart() {
        val permissionsNeeded = ArrayList<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.NEARBY_WIFI_DEVICES) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.NEARBY_WIFI_DEVICES)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.POST_NOTIFICATIONS)
            }
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_CONNECT)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_SCAN) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_SCAN)
            }
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_ADVERTISE) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.BLUETOOTH_ADVERTISE)
            }
        } else {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
            }
        }

        if (permissionsNeeded.isNotEmpty()) {
            appendLog("Requesting runtime permissions: ${permissionsNeeded.joinToString()}")
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            MeowPlayService.start(this)
        }
    }

    override fun onStart() {
        super.onStart()
        bridge.addListener(this)
        updateUiState()
    }

    override fun onStop() {
        bridge.removeListener(this)
        super.onStop()
    }

    private fun updateUiState() {
        val isRunning = bridge.isRunning()
        val isStereo = bridge.isStereoConnected()
        val isCarPlay = bridge.isCarPlayActive()

        if (isRunning) {
            btnToggle.text = getString(R.string.stop_service)
            btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.card_border))
        } else {
            btnToggle.text = getString(R.string.start_service)
            btnToggle.setBackgroundColor(ContextCompat.getColor(this, R.color.accent_blue))
        }

        when {
            isStereo && isCarPlay -> {
                tvGlobalBadge.text = "STREAMING"
                tvGlobalBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                tvGlobalBadge.setBackgroundColor(0x2230D158)
            }
            isStereo -> {
                tvGlobalBadge.text = "STEREO READY"
                tvGlobalBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
                tvGlobalBadge.setBackgroundColor(0x220A84FF)
            }
            isCarPlay -> {
                tvGlobalBadge.text = "CARPLAY READY"
                tvGlobalBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
                tvGlobalBadge.setBackgroundColor(0x22FF9F0A)
            }
            isRunning -> {
                tvGlobalBadge.text = "WAITING"
                tvGlobalBadge.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                tvGlobalBadge.setBackgroundColor(0x228E8E93)
            }
            else -> {
                tvGlobalBadge.text = "STOPPED"
                tvGlobalBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                tvGlobalBadge.setBackgroundColor(0x22636366)
            }
        }
    }

    private fun appendLog(msg: String) {
        val timestamp = timeFmt.format(Date())
        val formatted = "[$timestamp] $msg"
        logLines.add(formatted)
        if (logLines.size > 250) {
            logLines.removeAt(0)
        }

        runOnUiThread {
            tvLogs.text = logLines.joinToString("\n")
            scrollLogs.post { scrollLogs.fullScroll(View.FOCUS_DOWN) }
        }
    }

    // ==========================================
    // BridgeListener callbacks
    // ==========================================

    override fun onStereoStatusChanged(model: String, width: Int, height: Int, connected: Boolean) {
        runOnUiThread {
            if (connected) {
                tvStereoStatus.text = getString(R.string.status_connected, model, width, height)
                tvStereoStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                tvStereoDetails.text = "Pioneer WebLink USB Connected ($width x $height). Video stream output active."
                appendLog("Pioneer WebLink USB Connected: $model ($width x $height)")
            } else {
                tvStereoStatus.text = getString(R.string.status_not_connected)
                tvStereoStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                tvStereoDetails.text = "Awaiting USB connection to Pioneer stereo…"
                appendLog("Stereo disconnected.")
            }
            updateUiState()
        }
    }

    override fun onCarPlayStatusChanged(status: String) {
        runOnUiThread {
            tvCarPlayDetails.text = status
            appendLog("CarPlay State: $status")
            updateUiState()
        }
    }

    override fun onActiveSessionChanged(active: Boolean, deviceName: String?) {
        runOnUiThread {
            if (active) {
                tvCarPlayStatus.text = getString(R.string.carplay_connected)
                tvCarPlayStatus.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                tvCarPlayDetails.text = "Connected iPhone: ${deviceName ?: "iOS Device"}"
                appendLog("Wireless CarPlay Active! Connected to ${deviceName ?: "iPhone"}.")
            } else {
                tvCarPlayStatus.text = getString(R.string.carplay_waiting)
                tvCarPlayStatus.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                val mode = MeowPlayConfigManager.getWifiMode(this)
                val ssid = MeowPlayConfigManager.getHotspotSsid(this)
                tvCarPlayDetails.text = if (mode == WirelessHotspotMode.MANUAL) "Hotspot SSID: $ssid" else "Wi-Fi Direct (P2P)"
            }
            updateUiState()
        }
    }

    override fun onTouchReported(point: WebLinkTouchPoint, normX: Double, normY: Double) {
        runOnUiThread {
            val stateName = when (point.state) {
                WebLinkTouchPoint.STATE_DOWN -> "DOWN"
                WebLinkTouchPoint.STATE_MOVE -> "MOVE"
                WebLinkTouchPoint.STATE_UP -> "UP"
                else -> "${point.state}"
            }
            tvTouchStatus.text = "Touch #${point.id} [$stateName] at (${point.posX}, ${point.posY}) -> norm (%.3f, %.3f)".format(normX, normY)
        }
    }

    override fun onLog(message: String) {
        appendLog(message)
    }
}
