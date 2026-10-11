package com.weblink.sdk;

import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.ParcelFileDescriptor;
import android.util.Log;

import java.io.FileDescriptor;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.util.List;

/**
 * Manages an active USB AOA session with the car stereo, handling framing,
 * heartbeat keep-alive, protocol handshaking, and packet dispatching.
 */
public class WebLinkSession {
    private static final String TAG = "WebLinkSession";

    private final UsbManager mUsbManager;
    private final UsbAccessory mAccessory;
    private final SessionCallback mCallback;

    private ParcelFileDescriptor mFileDescriptor;
    private FileInputStream mInputStream;
    private FileOutputStream mOutputStream;
    private final Object mWriteLock = new Object();

    private volatile boolean mRunning = false;
    private Thread mWorkerThread;
    private Thread mHeartbeatThread;
    private WebLinkVideoStreamer mStreamer;

    private int mWidth = 1024;
    private int mHeight = 600;
    private Integer mOverrideWidth = null;
    private Integer mOverrideHeight = null;
    private boolean mIdentityAnnounced = false;

    public interface SessionCallback {
        void onLog(String message);
        void onConnected(String model, int width, int height);
        void onDisconnected();
        void onTouch(WebLinkTouchPoint point);
        void onVideoControl(int action);
        void onKeyFrameRequested();
    }

    public WebLinkSession(UsbManager usbManager, UsbAccessory accessory, SessionCallback callback) {
        this.mUsbManager = usbManager;
        this.mAccessory = accessory;
        this.mCallback = callback;
    }

    private byte[] mInitialConfigSpsPps = null;

    public void setResolutionOverride(int width, int height) {
        this.mOverrideWidth = width;
        this.mOverrideHeight = height;
    }

    public void setInitialConfig(byte[] spsPps) {
        this.mInitialConfigSpsPps = spsPps;
        if (mStreamer != null && spsPps != null) {
            mStreamer.setCachedSpsPps(spsPps);
        }
    }

    public synchronized void start() {
        if (mRunning) return;
        mRunning = true;
        mWorkerThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runSessionLoop();
            }
        }, "WebLink-SessionLoop");
        mWorkerThread.start();
    }

    public synchronized void stop() {
        mRunning = false;
        if (mHeartbeatThread != null) {
            mHeartbeatThread.interrupt();
            mHeartbeatThread = null;
        }
        if (mStreamer != null) {
            mStreamer.stop();
            mStreamer = null;
        }
        if (mWorkerThread != null) {
            mWorkerThread.interrupt();
            mWorkerThread = null;
        }
        closeStreams();
    }

    public void sendPacket(byte[] data) throws IOException {
        if (data == null || !mRunning) return;
        synchronized (mWriteLock) {
            FileOutputStream out = mOutputStream;
            if (out != null) {
                out.write(data);
                out.flush();
            }
        }
    }

    public WebLinkVideoStreamer getStreamer() {
        return mStreamer;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public boolean isRunning() {
        return mRunning;
    }

    private void runSessionLoop() {
        try {
            log("Opening car stereo USB accessory: " + mAccessory.getModel() + " (" + mAccessory.getManufacturer() + ")");
            mFileDescriptor = mUsbManager.openAccessory(mAccessory);
            if (mFileDescriptor == null) {
                log("Failed to open USB accessory. Check USB permissions.");
                return;
            }

            FileDescriptor fd = mFileDescriptor.getFileDescriptor();
            mInputStream = new FileInputStream(fd);
            mOutputStream = new FileOutputStream(fd);

            if (mOverrideWidth != null && mOverrideHeight != null) {
                mWidth = mOverrideWidth;
                mHeight = mOverrideHeight;
            }

            initStreamer(mWidth, mHeight);

            log("Connected to stereo! Initiating WebLink handshake (" + mWidth + "x" + mHeight + ")...");

            // 1. Initial Handshake Proposal: Ping + DeviceIdentity + VideoConfig
            sendPacket(WebLinkProtocol.buildRegularPing());
            sendPacket(WebLinkProtocol.buildDeviceIdentityResponse("WebLinkPoC", "1.0.0"));
            mIdentityAnnounced = true;

            sendPacket(WebLinkProtocol.buildVideoConfigResponse(
                    mWidth, mHeight, mWidth, mHeight, 2, "2:bitrate=2500000,maxKeyFrameInterval=30"
            ));

            // 2. High-priority Heartbeat Ping loop (runs every 500ms)
            mHeartbeatThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    while (mRunning && !Thread.currentThread().isInterrupted()) {
                        try {
                            Thread.sleep(500);
                            sendPacket(WebLinkProtocol.buildRegularPing());
                        } catch (InterruptedException e) {
                            break;
                        } catch (Exception e) {
                            if (mRunning) {
                                log("Heartbeat notice: " + e.getMessage());
                            }
                            break;
                        }
                    }
                }
            }, "WebLink-Heartbeat");
            mHeartbeatThread.setPriority(Thread.MAX_PRIORITY);
            mHeartbeatThread.start();

            // 3. Main USB Read loop
            byte[] readBuffer = new byte[16384];
            WebLinkProtocol.PacketAccumulator accumulator = new WebLinkProtocol.PacketAccumulator();

            while (mRunning && !Thread.currentThread().isInterrupted()) {
                int count = mInputStream.read(readBuffer, 0, readBuffer.length);
                if (count <= 0) {
                    log("USB read returned " + count);
                    break;
                }

                accumulator.append(readBuffer, 0, count);
                List<WebLinkProtocol.Packet> packets = accumulator.extractPackets();
                for (WebLinkProtocol.Packet packet : packets) {
                    handlePacket(packet);
                }
            }
        } catch (IOException e) {
            log("USB connection closed: " + e.getMessage());
        } catch (Exception e) {
            log("Session exception: " + e.getMessage());
        } finally {
            closeStreams();
            if (mCallback != null) {
                mCallback.onDisconnected();
            }
            log("WebLink stereo session ended.");
        }
    }

    private void initStreamer(int width, int height) {
        if (mStreamer != null) {
            mStreamer.stop();
        }
        mStreamer = new WebLinkVideoStreamer(width, height, new WebLinkVideoStreamer.PacketSender() {
            @Override
            public void sendPacket(byte[] data) throws IOException {
                WebLinkSession.this.sendPacket(data);
            }
        }, new WebLinkVideoStreamer.StatusCallback() {
            @Override
            public void onStatus(String message) {
                log(message);
            }

            @Override
            public void onKeyFrameRequested() {
                if (mCallback != null) {
                    mCallback.onKeyFrameRequested();
                }
            }
        });
        if (mInitialConfigSpsPps != null) {
            mStreamer.setCachedSpsPps(mInitialConfigSpsPps);
        }
    }

    private void handlePacket(WebLinkProtocol.Packet packet) throws IOException {
        switch (packet.commandId) {
            case WebLinkProtocol.CMD_PING:
                boolean isResponse = (packet.payload != null && packet.payload.length > 0 && (packet.payload[0] & 1) != 0);
                if (!isResponse) {
                    sendPacket(WebLinkProtocol.buildPingResponse());
                }
                break;

            case WebLinkProtocol.CMD_DEVICE_IDENTITY:
                if (!mIdentityAnnounced) {
                    sendPacket(WebLinkProtocol.buildDeviceIdentityResponse("WebLinkPoC", "1.0.0"));
                    mIdentityAnnounced = true;
                }
                break;

            case WebLinkProtocol.CMD_VIDEO_CONFIG:
                WebLinkProtocol.VideoConfigData vc = WebLinkProtocol.parseVideoConfig(packet.payload);
                if (mOverrideWidth != null && mOverrideHeight != null) {
                    mWidth = mOverrideWidth;
                    mHeight = mOverrideHeight;
                } else if (vc.clientWidth > 0 && vc.clientHeight > 0) {
                    // Match stereo requested resolution directly (e.g. 1024x600)
                    mWidth = vc.clientWidth;
                    mHeight = vc.clientHeight;
                }
                log("VideoConfig: Stereo requested " + vc.clientWidth + "x" + vc.clientHeight + ", adopted " + mWidth + "x" + mHeight);

                String encParams = (vc.encoderParams != null && !vc.encoderParams.isEmpty())
                        ? vc.encoderParams
                        : "2:bitrate=2500000,maxKeyFrameInterval=30";

                sendPacket(WebLinkProtocol.buildVideoConfigResponse(
                        mWidth, mHeight, mWidth, mHeight, 2, encParams
                ));

                initStreamer(mWidth, mHeight);

                if (mCallback != null) {
                    mCallback.onConnected(mAccessory.getModel(), mWidth, mHeight);
                }
                break;

            case WebLinkProtocol.CMD_VIDEO_CONTROL:
                int action = (packet.payload != null && packet.payload.length >= 4) ? WebLinkProtocol.getInt(packet.payload, 0) : 0;
                log("Stereo VideoControl action=" + action + " (" + (action == 0 ? "START" : "STOP") + ")");
                if (mCallback != null) {
                    mCallback.onVideoControl(action);
                }
                break;

            case WebLinkProtocol.CMD_TOUCH:
                List<WebLinkTouchPoint> points = WebLinkProtocol.parseTouchPoints(packet.payload);
                if (mCallback != null && points != null) {
                    for (WebLinkTouchPoint pt : points) {
                        mCallback.onTouch(pt);
                    }
                }
                break;

            case WebLinkProtocol.CMD_CLIENT_FEATURES:
                log("Stereo sent ClientFeatures (length=" + (packet.payload != null ? packet.payload.length : 0) + ")");
                break;

            case WebLinkProtocol.CMD_AUDIO_CHANNEL_CONFIG:
            case WebLinkProtocol.CMD_AUDIO_CONTROL:
                log("Stereo sent AudioConfig cmd=" + packet.commandId + " (len=" + (packet.payload != null ? packet.payload.length : 0) + ")");
                break;

            case WebLinkProtocol.CMD_CHANNEL_CONTROL:
            case WebLinkProtocol.CMD_STATUS_QUERY:
                log("Stereo control packet cmd=" + packet.commandId + " (len=" + (packet.payload != null ? packet.payload.length : 0) + ")");
                break;

            default:
                String hex = bytesToHex(packet.payload);
                log("RX packet cmd=" + packet.commandId + " len=" + (packet.payload != null ? packet.payload.length : 0) + " hex=[" + hex + "]");
                break;
        }
    }

    private static String bytesToHex(byte[] bytes) {
        if (bytes == null) return "";
        StringBuilder sb = new StringBuilder();
        int max = Math.min(bytes.length, 16);
        for (int i = 0; i < max; i++) {
            sb.append(String.format("%02X ", bytes[i]));
        }
        return sb.toString().trim();
    }

    private void log(String msg) {
        Log.i(TAG, msg);
        if (mCallback != null) {
            mCallback.onLog(msg);
        }
    }

    private void closeStreams() {
        synchronized (mWriteLock) {
            try {
                if (mInputStream != null) mInputStream.close();
            } catch (Exception ignored) {}
            try {
                if (mOutputStream != null) mOutputStream.close();
            } catch (Exception ignored) {}
            try {
                if (mFileDescriptor != null) mFileDescriptor.close();
            } catch (Exception ignored) {}
            mInputStream = null;
            mOutputStream = null;
            mFileDescriptor = null;
        }
    }
}
