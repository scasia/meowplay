package com.weblink.sdk;

import android.app.PendingIntent;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.hardware.usb.UsbAccessory;
import android.hardware.usb.UsbManager;
import android.os.Build;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;

/**
 * High-level manager coordinating USB accessory detection, auto-connection,
 * touch calibration, and video streaming.
 */
public class WebLinkManager {
    private static final String TAG = "WebLinkManager";
    public static final String ACTION_USB_PERMISSION = "com.weblink.sdk.USB_PERMISSION";

    private static WebLinkManager sInstance;

    private Context mContext;
    private UsbManager mUsbManager;
    private WebLinkTouchCalibration mCalibration;
    private WebLinkSession mSession;
    private final Handler mMainHandler = new Handler(Looper.getMainLooper());
    private final SimpleDateFormat mTimeFmt = new SimpleDateFormat("HH:mm:ss", Locale.US);

    private boolean mConnected = false;
    private String mModel = "None";
    private int mWidth = 800;
    private int mHeight = 480;

    private final List<Listener> mListeners = new ArrayList<>();
    private final List<String> mLogHistory = new ArrayList<>();

    public interface Listener {
        void onStereoConnected(String model, int width, int height);
        void onStereoDisconnected();
        void onTouch(WebLinkTouchPoint point, double normX, double normY);
        void onLog(String message);
        void onKeyFrameRequested();
        void onVideoControl(int action);
    }

    private final Runnable mWatchdogRunnable = new Runnable() {
        @Override
        public void run() {
            if (!mConnected && mSession == null && mUsbManager != null) {
                try {
                    UsbAccessory[] list = mUsbManager.getAccessoryList();
                    if (list != null && list.length > 0) {
                        UsbAccessory acc = list[0];
                        log("Auto-detected USB stereo accessory: " + acc.getModel());
                        checkAndStart(acc);
                    }
                } catch (Throwable ignored) {}
            }
            mMainHandler.postDelayed(this, 1200);
        }
    };

    private final BroadcastReceiver mUsbReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            String action = intent.getAction();
            if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(action)) {
                UsbAccessory acc = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (acc != null) {
                    log("Accessory attached intent: " + acc.getModel());
                    checkAndStart(acc);
                } else {
                    scanNow();
                }
            } else if (UsbManager.ACTION_USB_ACCESSORY_DETACHED.equals(action)) {
                log("USB accessory detached.");
                stopSession();
            } else if (ACTION_USB_PERMISSION.equals(action)) {
                UsbAccessory acc = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
                if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                    if (acc != null) {
                        log("USB permission granted for: " + acc.getModel());
                        startSession(acc);
                    }
                } else {
                    log("USB permission denied by user.");
                }
            }
        }
    };

    public static synchronized WebLinkManager getInstance() {
        if (sInstance == null) {
            sInstance = new WebLinkManager();
        }
        return sInstance;
    }

    public synchronized void init(Context context) {
        if (mContext != null) return;
        mContext = context.getApplicationContext();
        mUsbManager = (UsbManager) mContext.getSystemService(Context.USB_SERVICE);
        mCalibration = new WebLinkTouchCalibration();
        mCalibration.load(mContext);

        IntentFilter filter = new IntentFilter();
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_ATTACHED);
        filter.addAction(UsbManager.ACTION_USB_ACCESSORY_DETACHED);
        filter.addAction(ACTION_USB_PERMISSION);
        if (Build.VERSION.SDK_INT >= 33) {
            mContext.registerReceiver(mUsbReceiver, filter, 4); // Context.RECEIVER_NOT_EXPORTED
        } else {
            mContext.registerReceiver(mUsbReceiver, filter);
        }

        mMainHandler.removeCallbacks(mWatchdogRunnable);
        mMainHandler.post(mWatchdogRunnable);
        scanNow();
    }

    public synchronized void addListener(Listener l) {
        if (l != null && !mListeners.contains(l)) {
            mListeners.add(l);
            if (mConnected) {
                l.onStereoConnected(mModel, mWidth, mHeight);
            }
        }
    }

    public synchronized void removeListener(Listener l) {
        mListeners.remove(l);
    }

    private byte[] mLastConfigSpsPps = null;

    public synchronized void scanNow() {
        if (mUsbManager == null) return;
        if (mSession != null && mSession.isRunning()) {
            log("WebLink USB session is already active (" + mModel + " " + mWidth + "x" + mHeight + ").");
            if (mConnected) {
                for (Listener l : mListeners) {
                    l.onStereoConnected(mModel, mWidth, mHeight);
                }
            }
            return;
        }
        UsbAccessory[] list = mUsbManager.getAccessoryList();
        if (list == null || list.length == 0) {
            log("No USB stereo accessory detected.");
            return;
        }
        for (UsbAccessory acc : list) {
            log("Found stereo accessory: " + acc.getModel() + " (" + acc.getManufacturer() + ")");
            checkAndStart(acc);
            break;
        }
    }

    public synchronized void handleActivityIntent(Intent intent) {
        if (intent == null) return;
        UsbAccessory acc = intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY);
        if (acc != null) {
            log("Handling accessory intent: " + acc.getModel());
            checkAndStart(acc);
        } else if (UsbManager.ACTION_USB_ACCESSORY_ATTACHED.equals(intent.getAction())) {
            scanNow();
        }
    }

    private void checkAndStart(UsbAccessory acc) {
        if (mSession != null && mSession.isRunning()) {
            log("WebLink USB session already active for " + acc.getModel() + ", ignoring re-start.");
            return;
        }
        if (mUsbManager.hasPermission(acc)) {
            startSession(acc);
        } else {
            log("Requesting USB permission for " + acc.getModel());
            Intent permIntent = new Intent(ACTION_USB_PERMISSION);
            permIntent.setPackage(mContext.getPackageName());
            int flag = Build.VERSION.SDK_INT >= 31 ? 0x02000000 : 0; // FLAG_MUTABLE
            PendingIntent pi = PendingIntent.getBroadcast(mContext, 0, permIntent, flag);
            mUsbManager.requestPermission(acc, pi);
        }
    }

    private synchronized void startSession(UsbAccessory acc) {
        if (mSession != null && mSession.isRunning()) {
            log("Session already active, not re-starting.");
            return;
        }
        stopSession();
        mSession = new WebLinkSession(mUsbManager, acc, new WebLinkSession.SessionCallback() {
            @Override
            public void onLog(String message) {
                log(message);
            }

            @Override
            public void onConnected(final String model, final int width, final int height) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mConnected = true;
                        mModel = model;
                        mWidth = width;
                        mHeight = height;
                        for (Listener l : mListeners) {
                            l.onStereoConnected(model, width, height);
                        }
                    }
                });
            }

            @Override
            public void onDisconnected() {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        mConnected = false;
                        mModel = "None";
                        for (Listener l : mListeners) {
                            l.onStereoDisconnected();
                        }
                    }
                });
            }

            @Override
            public void onTouch(final WebLinkTouchPoint point) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        double[] norm = mCalibration.mapNormalized(point, mWidth, mHeight);
                        for (Listener l : mListeners) {
                            l.onTouch(point, norm[0], norm[1]);
                        }
                    }
                });
            }

            @Override
            public void onVideoControl(final int action) {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        log("VideoControl action=" + action);
                        for (Listener l : mListeners) {
                            l.onVideoControl(action);
                        }
                    }
                });
            }

            @Override
            public void onKeyFrameRequested() {
                mMainHandler.post(new Runnable() {
                    @Override
                    public void run() {
                        for (Listener l : mListeners) {
                            l.onKeyFrameRequested();
                        }
                    }
                });
            }
        });
        if (mLastConfigSpsPps != null) {
            mSession.setInitialConfig(mLastConfigSpsPps);
        }
        mSession.start();
    }

    public synchronized void feedVideoConfig(byte[] spsPps) {
        if (spsPps != null && spsPps.length > 0) {
            mLastConfigSpsPps = spsPps.clone();
            if (mSession != null) {
                mSession.setInitialConfig(mLastConfigSpsPps);
            }
        }
    }

    public synchronized void stopSession() {
        if (mSession != null) {
            mSession.stop();
            mSession = null;
        }
        mConnected = false;
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : mListeners) {
                    l.onStereoDisconnected();
                }
            }
        });
    }

    public synchronized void startCanvasStreamer(int fps, int bitrateBps, WebLinkVideoStreamer.FrameDrawer drawer) {
        if (mSession != null && mSession.getStreamer() != null) {
            mSession.getStreamer().startCanvasStreamer(fps, bitrateBps, drawer);
        }
    }

    public synchronized void stopVideoStream() {
        if (mSession != null && mSession.getStreamer() != null) {
            mSession.getStreamer().stop();
        }
    }

    public synchronized void feedAnnexBVideo(byte[] naluBytes) {
        if (mSession != null && mSession.getStreamer() != null) {
            mSession.getStreamer().feedAnnexBFrame(naluBytes);
        }
    }

    public synchronized void resetVideoStream() {
        if (mSession != null && mSession.getStreamer() != null) {
            mSession.getStreamer().resetStream();
        }
    }

    public synchronized WebLinkSession getSession() {
        return mSession;
    }

    public synchronized WebLinkVideoStreamer getStreamer() {
        return mSession != null ? mSession.getStreamer() : null;
    }

    public WebLinkTouchCalibration getCalibration() {
        return mCalibration;
    }

    public boolean isConnected() {
        return mConnected;
    }

    public String getModel() {
        return mModel;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public synchronized void log(final String msg) {
        Log.i(TAG, msg);
        final String formatted = "[" + mTimeFmt.format(new Date()) + "] " + msg;
        mLogHistory.add(formatted);
        if (mLogHistory.size() > 80) mLogHistory.remove(0);
        mMainHandler.post(new Runnable() {
            @Override
            public void run() {
                for (Listener l : mListeners) {
                    l.onLog(formatted);
                }
            }
        });
    }

    public synchronized List<String> getLogHistory() {
        return new ArrayList<>(mLogHistory);
    }
}
