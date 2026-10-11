package com.meowplay.app

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Base64
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.AirPlayViewArea
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.security.MessageDigest

object MeowPlayConfigManager {
    private const val PREFS_NAME = "meowplay_prefs"
    private const val KEY_PRIV_KEY = "identity_priv_key"
    private const val KEY_PUB_KEY = "identity_pub_key"
    private const val KEY_PAIRING_ID = "identity_pairing_id"

    // Wi-Fi & Hotspot
    private const val KEY_WIFI_MODE = "wifi_mode"
    private const val KEY_HOTSPOT_SSID = "hotspot_ssid"
    private const val KEY_HOTSPOT_PASS = "hotspot_password"
    private const val KEY_HOTSPOT_BAND = "hotspot_band"

    // Display & Interface
    private const val KEY_RIGHT_HAND_DRIVE = "right_hand_drive"
    private const val KEY_DOCK_POSITION = "dock_position" // 0 = Auto, 1 = Driver Side, 2 = Bottom
    private const val KEY_FPS = "display_fps" // 30, 60
    private const val KEY_WIDTH_PHYSICAL_MM = "width_physical_mm" // 140, 154, 180, 210

    // Audio & Behavior
    private const val KEY_CAR_BT_AUDIO = "car_bluetooth_audio"
    private const val KEY_MAIN_BUFFERED_AUDIO = "main_buffered_audio"
    private const val KEY_SPEECH_HIGH_QUALITY = "speech_without_8_16_khz"
    private const val KEY_AUTO_START_BOOT = "auto_start_on_boot"

    // Hardware & Identity
    private const val KEY_MFI_TARGET = "mfi_target"
    private const val KEY_ADAPTER_NAME = "adapter_name"
    private const val KEY_OEM_LABEL = "oem_label"

    const val DEFAULT_HOTSPOT_SSID = "MeowPlay"
    const val DEFAULT_HOTSPOT_PASS = "meowwwww"
    const val DEFAULT_ADAPTER_NAME = "MeowPlay"
    const val DEFAULT_OEM_LABEL = "WebLink"

    fun loadOrGenerateIdentity(context: Context): AirPlayIdentity {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val privStr = prefs.getString(KEY_PRIV_KEY, null)
        val pubStr = prefs.getString(KEY_PUB_KEY, null)
        val pairStr = prefs.getString(KEY_PAIRING_ID, null)

        if (privStr != null && pubStr != null && pairStr != null) {
            try {
                val priv = Base64.decode(privStr, Base64.DEFAULT)
                val pub = Base64.decode(pubStr, Base64.DEFAULT)
                return AirPlayIdentity(privateKey = priv, publicKey = pub, pairingId = pairStr)
            } catch (ignored: Exception) {}
        }

        val generated = AirPlayIdentity.generate()
        prefs.edit()
            .putString(KEY_PRIV_KEY, Base64.encodeToString(generated.privateKey, Base64.NO_WRAP))
            .putString(KEY_PUB_KEY, Base64.encodeToString(generated.publicKey, Base64.NO_WRAP))
            .putString(KEY_PAIRING_ID, generated.pairingId)
            .apply()

        return generated
    }

    fun loadPairingStore(context: Context): PairingStore {
        val prefs = context.getSharedPreferences("meowplay_pairings", Context.MODE_PRIVATE)
        val store = PairingStore { id, key ->
            prefs.edit().putString(id, Base64.encodeToString(key, Base64.NO_WRAP)).apply()
        }
        for ((key, value) in prefs.all) {
            if (value is String) {
                store.save(key, Base64.decode(value, Base64.NO_WRAP))
            }
        }
        return store
    }

    fun getDeviceId(identity: AirPlayIdentity): String {
        val hash = MessageDigest.getInstance("SHA-256").digest(identity.publicKey)
        val macBytes = hash.copyOfRange(0, 6)
        macBytes[0] = ((macBytes[0].toInt() and 0xFE) or 0x02).toByte()
        return macBytes.joinToString(":") { "%02X".format(it) }
    }

    // -------------------------------------------------------------------------
    // Wi-Fi Settings
    // -------------------------------------------------------------------------

    fun getWifiMode(context: Context): WirelessHotspotMode {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val modeStr = prefs.getString(KEY_WIFI_MODE, WirelessHotspotMode.MANUAL.name)
        return try {
            WirelessHotspotMode.valueOf(modeStr ?: WirelessHotspotMode.MANUAL.name)
        } catch (ignored: Exception) {
            WirelessHotspotMode.MANUAL
        }
    }

    fun setWifiMode(context: Context, mode: WirelessHotspotMode) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_WIFI_MODE, mode.name).apply()
    }

    fun getHotspotSsid(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HOTSPOT_SSID, DEFAULT_HOTSPOT_SSID) ?: DEFAULT_HOTSPOT_SSID
    }

    fun setHotspotSsid(context: Context, ssid: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HOTSPOT_SSID, ssid.trim()).apply()
    }

    fun getHotspotPassword(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_HOTSPOT_PASS, DEFAULT_HOTSPOT_PASS) ?: DEFAULT_HOTSPOT_PASS
    }

    fun setHotspotPassword(context: Context, pass: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HOTSPOT_PASS, pass.trim()).apply()
    }

    fun getHotspotBand(context: Context): ManualHotspotBand {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val bandStr = prefs.getString(KEY_HOTSPOT_BAND, ManualHotspotBand.GHZ_5.name)
        return runCatching { ManualHotspotBand.valueOf(bandStr ?: ManualHotspotBand.GHZ_5.name) }
            .getOrDefault(ManualHotspotBand.GHZ_5)
    }

    fun setHotspotBand(context: Context, band: ManualHotspotBand) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_HOTSPOT_BAND, band.name).apply()
    }

    // -------------------------------------------------------------------------
    // Display & Interface Settings
    // -------------------------------------------------------------------------

    fun getRightHandDrive(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_RIGHT_HAND_DRIVE, false)
    }

    fun setRightHandDrive(context: Context, rhd: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_RIGHT_HAND_DRIVE, rhd).apply()
    }

    fun getDockPosition(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_DOCK_POSITION, 0).coerceIn(0, 2)
    }

    fun setDockPosition(context: Context, position: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_DOCK_POSITION, position.coerceIn(0, 2)).apply()
    }

    fun getFps(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_FPS, 60).let { if (it == 30) 30 else 60 }
    }

    fun setFps(context: Context, fps: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_FPS, if (fps == 30) 30 else 60).apply()
    }

    fun getWidthPhysicalMm(context: Context): Int {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getInt(KEY_WIDTH_PHYSICAL_MM, 154).coerceIn(100, 300)
    }

    fun setWidthPhysicalMm(context: Context, widthMm: Int) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putInt(KEY_WIDTH_PHYSICAL_MM, widthMm.coerceIn(100, 300)).apply()
    }

    // -------------------------------------------------------------------------
    // Audio & Streaming Settings
    // -------------------------------------------------------------------------

    fun getCarBluetoothAudio(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_CAR_BT_AUDIO, false)
    }

    fun setCarBluetoothAudio(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_CAR_BT_AUDIO, enabled).apply()
    }

    fun getMainBufferedAudio(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_MAIN_BUFFERED_AUDIO, true)
    }

    fun setMainBufferedAudio(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_MAIN_BUFFERED_AUDIO, enabled).apply()
    }

    fun getSpeechWithout8And16Khz(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_SPEECH_HIGH_QUALITY, true)
    }

    fun setSpeechWithout8And16Khz(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_SPEECH_HIGH_QUALITY, enabled).apply()
    }

    fun getAutoStartOnBoot(context: Context): Boolean {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getBoolean(KEY_AUTO_START_BOOT, false)
    }

    fun setAutoStartOnBoot(context: Context, enabled: Boolean) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putBoolean(KEY_AUTO_START_BOOT, enabled).apply()
    }

    // -------------------------------------------------------------------------
    // Hardware & Identity Settings
    // -------------------------------------------------------------------------

    fun getMfiTarget(context: Context): MfiTarget {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val stored = prefs.getString(KEY_MFI_TARGET, MfiTarget.LOCAL.name)
        return runCatching { MfiTarget.valueOf(stored ?: MfiTarget.LOCAL.name) }
            .getOrDefault(MfiTarget.LOCAL)
    }

    fun setMfiTarget(context: Context, target: MfiTarget) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_MFI_TARGET, target.name).apply()
    }

    fun getAdapterName(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_ADAPTER_NAME, DEFAULT_ADAPTER_NAME)?.ifBlank { DEFAULT_ADAPTER_NAME } ?: DEFAULT_ADAPTER_NAME
    }

    fun setAdapterName(context: Context, name: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_ADAPTER_NAME, name.trim()).apply()
    }

    fun getOemLabel(context: Context): String {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        return prefs.getString(KEY_OEM_LABEL, DEFAULT_OEM_LABEL)?.ifBlank { DEFAULT_OEM_LABEL } ?: DEFAULT_OEM_LABEL
    }

    fun setOemLabel(context: Context, label: String) {
        val prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        prefs.edit().putString(KEY_OEM_LABEL, label.trim()).apply()
    }

    // -------------------------------------------------------------------------
    // Config Builders
    // -------------------------------------------------------------------------

    fun createRuntimeConfig(
        context: Context,
        identity: AirPlayIdentity,
    ): CarPlayRuntimeConfig {
        val deviceId = getDeviceId(identity)
        val macBytes = deviceId.split(":").map { it.toInt(16).toByte() }.toByteArray()
        val mode = getWifiMode(context)
        val ssid = getHotspotSsid(context)
        val pass = getHotspotPassword(context)
        val band = getHotspotBand(context)
        val mfi = getMfiTarget(context)
        val adapterName = getAdapterName(context)

        return CarPlayRuntimeConfig(
            mfiTarget = mfi,
            transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = mode,
            manualHotspotSsid = if (mode == WirelessHotspotMode.MANUAL) ssid else null,
            manualHotspotPassphrase = if (mode == WirelessHotspotMode.MANUAL) pass else null,
            manualHotspotBand = band,
            manualHotspotSecurity = ManualHotspotSecurity.WPA2,
            hostMac = macBytes,
            label = adapterName,
            hostName = "meowplay-" + deviceId.replace(":", "").lowercase(),
            identification = Iap2IdentificationConfig(
                name = "$adapterName Adapter",
                modelIdentifier = "DMH-A240BT",
                manufacturer = "Pioneer",
                serialNumber = "WL-" + deviceId.replace(":", ""),
                firmwareVersion = "1.0.0",
                hardwareVersion = "1.0.0",
                carPlayUsbInterfaceNumber = 3,
            ),
            locationReportingEnabled = false,
        )
    }

    private fun getLocalBtAddress(context: Context): String? {
        val adapter = runCatching { context.getSystemService(BluetoothManager::class.java)?.adapter?.address }.getOrNull()
        val setting = runCatching { Settings.Secure.getString(context.contentResolver, "bluetooth_address") }.getOrNull()
        return listOfNotNull(adapter, setting).firstOrNull {
            Regex("(?i)([0-9a-f]{2}:){5}[0-9a-f]{2}").matches(it) &&
                !it.startsWith("02:00:00:00:00:") && it != "00:00:00:00:00:00"
        }
    }

    fun createAirPlayConfig(
        context: Context,
        identity: AirPlayIdentity,
        width: Int = 1024,
        height: Int = 600,
        fps: Int = getFps(context)
    ): AirPlayConfig {
        val deviceId = getDeviceId(identity)
        val btMac = getLocalBtAddress(context) ?: deviceId
        val isRhd = getRightHandDrive(context)
        val dockPos = getDockPosition(context)
        val widthMm = getWidthPhysicalMm(context)
        val heightMm = ((widthMm.toDouble() * height) / width).toInt().coerceAtLeast(50)
        val carBtAudio = getCarBluetoothAudio(context)
        val bufferedAudio = getMainBufferedAudio(context)
        val highQualitySpeech = getSpeechWithout8And16Khz(context)
        val adapterName = getAdapterName(context)
        val oemLabel = getOemLabel(context)

        val viewAreas = when (dockPos) {
            1 -> listOf(AirPlayViewArea(width, height, originX = 0, originY = 0, dockEdge = AirPlayInfoPlist.DOCK_EDGE_DRIVER_SIDE))
            2 -> listOf(AirPlayViewArea(width, height, originX = 0, originY = 0, dockEdge = AirPlayInfoPlist.DOCK_EDGE_BOTTOM))
            else -> null
        }

        val display = AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            widthPhysicalMm = widthMm,
            heightPhysicalMm = heightMm,
            fps = fps,
            primaryInputDevice = 1,
            viewAreas = viewAreas,
        )

        return AirPlayConfig(
            deviceName = adapterName,
            deviceId = deviceId,
            btMac = btMac,
            sourceVersion = "950.7.1",
            main = display,
            rightHandDrive = isRhd,
            hevc = false,
            disableAudioOutput = carBtAudio,
            mainBufferedAudio = bufferedAudio,
            speechWithout8And16Khz = highQualitySpeech,
            microphone = false,
            manufacturer = "Pioneer",
            model = "DMH-A240BT",
            oemLabel = oemLabel,
        )
    }
}
