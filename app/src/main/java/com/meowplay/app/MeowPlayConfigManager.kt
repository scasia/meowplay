package com.meowplay.app

import android.bluetooth.BluetoothManager
import android.content.Context
import android.content.SharedPreferences
import android.provider.Settings
import android.util.Base64
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayDisplayConfig
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.PairingStore
import com.shilapi.xcertplay.orchestration.CarPlayRuntimeConfig
import com.shilapi.xcertplay.orchestration.CarPlayTransport
import com.shilapi.xcertplay.orchestration.ManualHotspotBand
import com.shilapi.xcertplay.orchestration.ManualHotspotSecurity
import com.shilapi.xcertplay.orchestration.MfiTarget
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.shilapi.xcertplay.transport.Iap2IdentificationConfig
import java.security.MessageDigest
import java.util.UUID

object MeowPlayConfigManager {
    private const val PREFS_NAME = "meowplay_prefs"
    private const val KEY_PRIV_KEY = "identity_priv_key"
    private const val KEY_PUB_KEY = "identity_pub_key"
    private const val KEY_PAIRING_ID = "identity_pairing_id"
    private const val KEY_WIFI_MODE = "wifi_mode"
    private const val KEY_HOTSPOT_SSID = "hotspot_ssid"
    private const val KEY_HOTSPOT_PASS = "hotspot_password"

    const val DEFAULT_HOTSPOT_SSID = "MeowPlay"
    const val DEFAULT_HOTSPOT_PASS = "meowwwww"

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

    fun createRuntimeConfig(
        context: Context,
        identity: AirPlayIdentity,
    ): CarPlayRuntimeConfig {
        val deviceId = getDeviceId(identity)
        val macBytes = deviceId.split(":").map { it.toInt(16).toByte() }.toByteArray()
        val mode = getWifiMode(context)
        val ssid = getHotspotSsid(context)
        val pass = getHotspotPassword(context)

        return CarPlayRuntimeConfig(
            mfiTarget = MfiTarget.LOCAL,
            transport = CarPlayTransport.WIRELESS,
            wirelessHotspotMode = mode,
            manualHotspotSsid = if (mode == WirelessHotspotMode.MANUAL) ssid else null,
            manualHotspotPassphrase = if (mode == WirelessHotspotMode.MANUAL) pass else null,
            manualHotspotBand = ManualHotspotBand.AUTO,
            manualHotspotSecurity = ManualHotspotSecurity.WPA2,
            hostMac = macBytes,
            label = "MeowPlay",
            hostName = "meowplay-" + deviceId.replace(":", "").lowercase(),
            identification = Iap2IdentificationConfig(
                name = "MeowPlay Adapter",
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
        fps: Int = 60
    ): AirPlayConfig {
        val deviceId = getDeviceId(identity)
        val btMac = getLocalBtAddress(context) ?: deviceId

        val display = AirPlayDisplayConfig(
            widthPixels = width,
            heightPixels = height,
            widthPhysicalMm = 154,
            heightPhysicalMm = 90,
            fps = fps,
            primaryInputDevice = 1,
        )

        return AirPlayConfig(
            deviceName = "MeowPlay",
            deviceId = deviceId,
            btMac = btMac,
            sourceVersion = "950.7.1",
            main = display,
            hevc = false,
            disableAudioOutput = false,
            microphone = false,
            manufacturer = "Pioneer",
            model = "DMH-A240BT",
            oemLabel = "MeowPlay",
        )
    }
}
