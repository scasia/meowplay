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
import androidx.core.content.ContextCompat
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

    // Wi-Fi Controls
    private lateinit var rgWifiMode: RadioGroup
    private lateinit var rbModeHotspot: RadioButton
    private lateinit var rbModeDirect: RadioButton
    private lateinit var layoutHotspotCredentials: LinearLayout
    private lateinit var etHotspotSsid: EditText
    private lateinit var etHotspotPassword: EditText
    private lateinit var btnSaveHotspot: Button
    private lateinit var btnOpenHotspotSettings: Button

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

        // Wi-Fi Controls
        rgWifiMode = findViewById(R.id.rgWifiMode)
        rbModeHotspot = findViewById(R.id.rbModeHotspot)
        rbModeDirect = findViewById(R.id.rbModeDirect)
        layoutHotspotCredentials = findViewById(R.id.layoutHotspotCredentials)
        etHotspotSsid = findViewById(R.id.etHotspotSsid)
        etHotspotPassword = findViewById(R.id.etHotspotPassword)
        btnSaveHotspot = findViewById(R.id.btnSaveHotspot)
        btnOpenHotspotSettings = findViewById(R.id.btnOpenHotspotSettings)

        bridge = MeowPlayBridge.getInstance(this)

        initWifiControls()

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

    private fun initWifiControls() {
        val currentMode = MeowPlayConfigManager.getWifiMode(this)
        etHotspotSsid.setText(MeowPlayConfigManager.getHotspotSsid(this))
        etHotspotPassword.setText(MeowPlayConfigManager.getHotspotPassword(this))

        if (currentMode == WirelessHotspotMode.WIFI_P2P) {
            rbModeDirect.isChecked = true
            layoutHotspotCredentials.visibility = View.GONE
        } else {
            rbModeHotspot.isChecked = true
            layoutHotspotCredentials.visibility = View.VISIBLE
        }

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
            val ssid = etHotspotSsid.text.toString().trim()
            val pass = etHotspotPassword.text.toString().trim()

            if (ssid.isEmpty()) {
                Toast.makeText(this, "SSID cannot be empty", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            if (pass.length < 8 || pass.length > 63) {
                Toast.makeText(this, "Password must be 8 to 63 characters", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }

            MeowPlayConfigManager.setHotspotSsid(this, ssid)
            MeowPlayConfigManager.setHotspotPassword(this, pass)
            MeowPlayConfigManager.setWifiMode(this, WirelessHotspotMode.MANUAL)

            appendLog("Saved Wi-Fi Hotspot credentials: SSID='$ssid', Key='$pass'")
            Toast.makeText(this, "Hotspot credentials saved & applied!", Toast.LENGTH_SHORT).show()
            bridge.reloadConfiguration()
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
        }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
            permissionsNeeded.add(Manifest.permission.ACCESS_FINE_LOCATION)
        }

        if (permissionsNeeded.isNotEmpty()) {
            permissionLauncher.launch(permissionsNeeded.toTypedArray())
        } else {
            MeowPlayService.start(this)
        }
    }

    override fun onResume() {
        super.onResume()
        bridge.addListener(this)
        updateUiState()
    }

    override fun onPause() {
        bridge.removeListener(this)
        super.onPause()
    }

    private fun updateUiState() {
        val running = bridge.isRunning()
        val stereoConnected = bridge.isStereoConnected()
        val carPlayActive = bridge.isCarPlayActive()

        btnToggle.text = if (running) getString(R.string.stop_service) else getString(R.string.start_service)
        btnToggle.setBackgroundColor(
            ContextCompat.getColor(
                this,
                if (running) R.color.card_border else R.color.accent_blue
            )
        )

        val mode = MeowPlayConfigManager.getWifiMode(this)
        val modeDesc = if (mode == WirelessHotspotMode.WIFI_P2P) {
            "Wi-Fi Direct (P2P)"
        } else {
            "Hotspot: ${MeowPlayConfigManager.getHotspotSsid(this)}"
        }

        when {
            carPlayActive -> {
                tvGlobalBadge.text = "ACTIVE"
                tvGlobalBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_green))
                tvGlobalBadge.setBackgroundColor(0x3330D158)
            }
            stereoConnected -> {
                tvGlobalBadge.text = "STEREO READY"
                tvGlobalBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_blue))
                tvGlobalBadge.setBackgroundColor(0x330A84FF)
            }
            running -> {
                tvGlobalBadge.text = "LISTENING"
                tvGlobalBadge.setTextColor(ContextCompat.getColor(this, R.color.accent_orange))
                tvGlobalBadge.setBackgroundColor(0x33FF9F0A)
            }
            else -> {
                tvGlobalBadge.text = "STOPPED"
                tvGlobalBadge.setTextColor(ContextCompat.getColor(this, R.color.text_muted))
                tvGlobalBadge.setBackgroundColor(0x22636366)
            }
        }

        if (!carPlayActive) {
            tvCarPlayDetails.text = "Wireless Mode: $modeDesc | MFi: P-256 Offline"
        }
    }

    // ==========================================
    // MeowPlayBridge.BridgeListener
    // ==========================================

    override fun onStereoStatusChanged(model: String, width: Int, height: Int, connected: Boolean) {
        runOnUiThread {
            if (connected) {
                tvStereoStatus.text = "Connected: $model"
                tvStereoDetails.text = "Canvas: ${width}x${height} | Protocol: WebLink AOA 2.0"
            } else {
                tvStereoStatus.text = getString(R.string.status_not_connected)
                tvStereoDetails.text = "Awaiting USB connection to Pioneer DMH-A240BT…"
            }
            updateUiState()
        }
    }

    override fun onCarPlayStatusChanged(status: String) {
        runOnUiThread {
            tvCarPlayStatus.text = status
            updateUiState()
        }
    }

    override fun onActiveSessionChanged(active: Boolean, deviceName: String?) {
        runOnUiThread {
            if (active) {
                tvCarPlayStatus.text = "Connected: ${deviceName ?: "iPhone"}"
                tvCarPlayDetails.text = "AirPlay 1024x600@60fps | Zero-Transcode Annex B Pass-through"
            } else {
                tvCarPlayStatus.text = getString(R.string.carplay_waiting)
                val mode = MeowPlayConfigManager.getWifiMode(this)
                val modeDesc = if (mode == WirelessHotspotMode.WIFI_P2P) "Wi-Fi Direct" else "Hotspot (${MeowPlayConfigManager.getHotspotSsid(this)})"
                tvCarPlayDetails.text = "Mode: $modeDesc | MFi Local P-256 Auth: Active"
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
            tvTouchStatus.text = String.format(
                Locale.US,
                "ID:%d State:%s Raw:(%d, %d) Norm:(%.3f, %.3f)",
                point.id, stateName, point.posX, point.posY, normX, normY
            )
        }
    }

    override fun onLog(message: String) {
        runOnUiThread {
            appendLog(message)
        }
    }

    private fun appendLog(msg: String) {
        val timestamp = timeFmt.format(Date())
        val line = "[$timestamp] $msg"
        logLines.add(line)
        if (logLines.size > 150) {
            logLines.removeAt(0)
        }
        val sb = StringBuilder()
        for (l in logLines) {
            sb.append(l).append("\n")
        }
        tvLogs.text = sb.toString()
        scrollLogs.post { scrollLogs.fullScroll(ScrollView.FOCUS_DOWN) }
    }
}
