package com.meowplay.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.shilapi.xcertplay.airplay.AirPlayContact
import com.shilapi.xcertplay.airplay.AirPlayDeviceInfo
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlaySession
import com.shilapi.xcertplay.airplay.AirPlaySessionListener
import com.shilapi.xcertplay.airplay.CarPlayMediaEngine
import com.shilapi.xcertplay.airplay.MediaSink
import com.shilapi.xcertplay.media.MediaCodecSupport
import com.shilapi.xcertplay.orchestration.CarPlayController
import com.shilapi.xcertplay.orchestration.CarPlayStatus
import com.shilapi.xcertplay.orchestration.WirelessHotspotMode
import com.weblink.sdk.WebLinkManager
import com.weblink.sdk.WebLinkTouchPoint
import com.weblink.sdk.WebLinkVideoStreamer
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

class MeowPlayBridge(private val context: Context) : WebLinkManager.Listener {

    companion object {
        private const val TAG = "MeowPlayBridge"
        @Volatile private var sInstance: MeowPlayBridge? = null

        fun getInstance(context: Context): MeowPlayBridge {
            return sInstance ?: synchronized(this) {
                sInstance ?: MeowPlayBridge(context.applicationContext).also { sInstance = it }
            }
        }
    }

    interface BridgeListener {
        fun onCarPlayStatusChanged(status: String)
        fun onStereoStatusChanged(model: String, width: Int, height: Int, connected: Boolean)
        fun onTouchReported(point: WebLinkTouchPoint, normX: Double, normY: Double)
        fun onLog(message: String)
        fun onActiveSessionChanged(active: Boolean, deviceName: String?)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArrayList<BridgeListener>()
    private val asyncExecutor = Executors.newSingleThreadExecutor()

    private val webLinkManager = WebLinkManager.getInstance()
    private val identity: AirPlayIdentity = MeowPlayConfigManager.loadOrGenerateIdentity(context)
    private val pairings = MeowPlayConfigManager.loadPairingStore(context)

    private var carPlayController: CarPlayController? = null
    @Volatile private var activeAirPlaySession: AirPlaySession? = null
    private var videoRecoveryHandler: (() -> Unit)? = null

    private var stereoConnected = false
    private var stereoModel = "DMH-A240BT"
    private var displayWidth = 1024
    private var displayHeight = 600
    private var running = false

    private val mediaSink = object : MediaSink {
        private var videoDiagnosticHandler: ((String) -> Unit)? = null
        private var firstFrameReported = false

        override fun onVideoConfig(type: Int, codecData: ByteArray) {
            log("CarPlay VideoConfig received (${codecData.size} bytes). Parsing SPS/PPS...")
            val annexBSpsPps = extractAnnexBSpsPps(codecData)
            if (annexBSpsPps != null) {
                log("Extracted Annex B SPS/PPS (${annexBSpsPps.size} bytes). Sending to stereo decoder.")
                webLinkManager.feedVideoConfig(annexBSpsPps)
            } else {
                log("Warning: Could not extract SPS/PPS from VideoConfig (${codecData.size} bytes).")
            }
        }

        override fun onVideoFrame(type: Int, naluBytes: ByteArray) {
            if (!firstFrameReported) {
                firstFrameReported = true
                videoDiagnosticHandler?.invoke("first frame rendered")
                log("First CarPlay video frame (${naluBytes.size} bytes) rendered and reported to controller!")
            }
            webLinkManager.feedAnnexBVideo(naluBytes)
        }

        override fun setVideoRecoveryHandler(type: Int, handler: () -> Unit) {
            videoRecoveryHandler = handler
        }

        override fun setVideoDiagnosticHandler(type: Int, handler: (String) -> Unit) {
            videoDiagnosticHandler = handler
        }

        fun reset() {
            firstFrameReported = false
        }
    }

    private fun extractAnnexBSpsPps(codecData: ByteArray): ByteArray? {
        val info = WebLinkVideoStreamer.inspectH264AnnexB(codecData)
        if (info.hasSps && info.hasPps) {
            val startCode = byteArrayOf(0, 0, 0, 1)
            return startCode + info.spsBytes + startCode + info.ppsBytes
        }

        val (sps, pps) = MediaCodecSupport.avcParameterSets(codecData)
        if (sps.isNotEmpty() && pps.isNotEmpty()) {
            val startCode = byteArrayOf(0, 0, 0, 1)
            return startCode + sps + startCode + pps
        }

        return null
    }

    private val airPlaySessionListener = object : AirPlaySessionListener {
        override fun onSessionActive(session: AirPlaySession) {
            activeAirPlaySession = session
            log("CarPlay Session ACTIVE! Video pass-through initiated.")
            postToListeners { it.onActiveSessionChanged(true, "iPhone") }
            requestKeyFrame()
        }

        override fun onSessionEnded(session: AirPlaySession) {
            if (activeAirPlaySession === session) {
                activeAirPlaySession = null
                mediaSink.reset()
                log("CarPlay Session ended.")
                postToListeners { it.onActiveSessionChanged(false, null) }
            }
        }

        override fun onDeviceInfo(session: AirPlaySession, info: AirPlayDeviceInfo) {
            log("Connected iPhone: ${info.name} (model: ${info.model})")
            postToListeners { it.onActiveSessionChanged(true, info.name) }
        }

        override fun onTransportError(message: String) {
            log("CarPlay transport error: $message")
        }

        override fun onDebugLog(message: String) {
            Log.d(TAG, "[AirPlay] $message")
        }
    }

    fun addListener(listener: BridgeListener) {
        listeners.add(listener)
        listener.onStereoStatusChanged(stereoModel, displayWidth, displayHeight, stereoConnected)
        listener.onActiveSessionChanged(activeAirPlaySession != null, null)
    }

    fun removeListener(listener: BridgeListener) {
        listeners.remove(listener)
    }

    fun start() {
        if (running) return
        running = true
        log("Starting MeowPlay Adapter Bridge...")

        webLinkManager.addListener(this)
        webLinkManager.scanNow()

        startCarPlayController()
    }

    fun stop() {
        if (!running) return
        running = false
        log("Stopping MeowPlay Adapter Bridge...")

        stopCarPlayController()
    }

    fun isRunning(): Boolean = running
    fun isStereoConnected(): Boolean = stereoConnected
    fun isCarPlayActive(): Boolean = activeAirPlaySession != null

    fun reloadConfiguration() {
        if (running) {
            log("Applying updated Wi-Fi settings and restarting CarPlay engine...")
            startCarPlayController()
        }
    }

    private fun startCarPlayController() {
        stopCarPlayController()

        val runtimeConfig = MeowPlayConfigManager.createRuntimeConfig(context, identity)
        val airPlayConfig = MeowPlayConfigManager.createAirPlayConfig(
            context = context,
            identity = identity,
            width = displayWidth,
            height = displayHeight,
            fps = MeowPlayConfigManager.getFps(context)
        )
        val mediaEngine = CarPlayMediaEngine(mediaSink)

        val modeDesc = when (runtimeConfig.wirelessHotspotMode) {
            WirelessHotspotMode.MANUAL -> "Hotspot SSID: ${runtimeConfig.manualHotspotSsid}"
            WirelessHotspotMode.WIFI_P2P -> "Wi-Fi Direct (P2P)"
            WirelessHotspotMode.LOCAL_ONLY_HOTSPOT -> "Local-Only Hotspot"
            else -> runtimeConfig.wirelessHotspotMode.name
        }
        log("Initializing CarPlay engine (${displayWidth}x${displayHeight}@60fps, Wi-Fi: $modeDesc, MFi: LOCAL)...")

        val controller = CarPlayController(
            context = context,
            config = runtimeConfig,
            airPlayConfig = airPlayConfig,
            identity = identity,
            pairings = pairings,
            listener = airPlaySessionListener,
            media = mediaEngine,
            reportStatus = { status ->
                handleCarPlayStatus(status)
            }
        )

        carPlayController = controller
        try {
            controller.start()
            log("CarPlay engine started. Advertising wireless services...")
        } catch (e: Exception) {
            log("Failed to start CarPlay controller: ${e.message}")
            Log.e(TAG, "Error starting CarPlayController", e)
        }
    }

    private fun stopCarPlayController() {
        try {
            carPlayController?.close()
        } catch (e: Exception) {
            Log.w(TAG, "Error closing CarPlayController", e)
        }
        carPlayController = null
        activeAirPlaySession = null
    }

    private fun handleCarPlayStatus(status: CarPlayStatus) {
        val statusText = when (status) {
            is CarPlayStatus.StartingHotspot -> "Starting Wi-Fi Hotspot…"
            is CarPlayStatus.HotspotReady -> "Hotspot Ready (${status.ssid})"
            is CarPlayStatus.WaitingForPairedIphone -> "Waiting for Paired iPhone…"
            is CarPlayStatus.ConnectingBluetooth -> "Connecting Bluetooth…"
            is CarPlayStatus.RunningWireless -> "Running Wireless CarPlay…"
            is CarPlayStatus.WirelessActive -> "Wireless CarPlay Active!"
            is CarPlayStatus.WirelessActiveFallback -> "Wireless CarPlay Active (Fallback)"
            is CarPlayStatus.Pairing -> "Pairing with iPhone…"
            is CarPlayStatus.ConnectingControl -> "Connecting Control Channel…"
            is CarPlayStatus.RunningControl -> "Control Channel Connected"
            is CarPlayStatus.ControlEnded -> "CarPlay Ended"
            is CarPlayStatus.Failed -> "Failed: ${status.message}"
            else -> status.javaClass.simpleName
        }
        log("CarPlay State: $statusText")
        postToListeners { it.onCarPlayStatusChanged(statusText) }
    }

    // ==========================================
    // WebLinkManager.Listener Callbacks
    // ==========================================

    override fun onStereoConnected(model: String, width: Int, height: Int) {
        stereoConnected = true
        stereoModel = model
        displayWidth = if (width > 0) width else 1024
        displayHeight = if (height > 0) height else 600

        log("Pioneer WebLink USB Connected: $model ($displayWidth x $displayHeight)")
        postToListeners { it.onStereoStatusChanged(model, displayWidth, displayHeight, true) }

        requestKeyFrame()
    }

    override fun onStereoDisconnected() {
        stereoConnected = false
        log("Pioneer WebLink USB Disconnected.")
        postToListeners { it.onStereoStatusChanged(stereoModel, displayWidth, displayHeight, false) }
    }

    override fun onTouch(point: WebLinkTouchPoint, normX: Double, normY: Double) {
        val controller = carPlayController
        if (controller != null) {
            val contact = AirPlayContact(
                id = 0,
                x = normX,
                y = normY,
                down = point.isDown
            )
            controller.sendTouch(listOf(contact))
            if (!point.isDown) {
                controller.sendTouch(emptyList())
            }
        }
        postToListeners { it.onTouchReported(point, normX, normY) }
    }

    override fun onKeyFrameRequested() {
        requestKeyFrame()
    }

    override fun onVideoControl(action: Int) {
        log("WebLink Video Control action: $action")
    }

    override fun onLog(message: String) {
        log("[WebLink] $message")
    }

    fun requestKeyFrame() {
        log("Requesting IDR Keyframe from iPhone...")
        asyncExecutor.execute {
            try {
                videoRecoveryHandler?.invoke()
            } catch (e: Exception) {
                Log.w(TAG, "Error invoking videoRecoveryHandler", e)
            }
        }
    }

    private fun log(msg: String) {
        Log.i(TAG, msg)
        postToListeners { it.onLog(msg) }
    }

    private fun postToListeners(action: (BridgeListener) -> Unit) {
        mainHandler.post {
            for (l in listeners) {
                try {
                    action(l)
                } catch (ignored: Exception) {}
            }
        }
    }
}
