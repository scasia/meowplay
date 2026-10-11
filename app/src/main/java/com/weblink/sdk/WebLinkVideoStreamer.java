package com.weblink.sdk;

import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.media.MediaCodec;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.SystemClock;
import android.util.Log;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Handles H.264 video encoding, SPS/PPS caching, IDR keyframe gating,
 * and delivery of CMD_FILL_RECTANGLE packets to the car stereo.
 */
public class WebLinkVideoStreamer {
    private static final String TAG = "WebLinkVideoStreamer";

    private final int mWidth;
    private final int mHeight;
    private final PacketSender mSender;
    private final StatusCallback mCallback;

    private volatile boolean mRunning = false;
    private byte[] mCachedSpsPps = null;
    private int mFrameCount = 0;

    // Self-contained canvas encoder thread
    private Thread mEncodingThread;
    private MediaCodec mEncoder;
    private FrameDrawer mDrawer;

    public interface PacketSender {
        void sendPacket(byte[] data) throws IOException;
    }

    public interface StatusCallback {
        void onStatus(String message);
        void onKeyFrameRequested();
    }

    public interface FrameDrawer {
        void onDrawFrame(Canvas canvas, int width, int height, long frameIndex);
    }

    public WebLinkVideoStreamer(int width, int height, PacketSender sender, StatusCallback callback) {
        this.mWidth = (width > 0) ? (width & ~1) : 1024;
        this.mHeight = (height > 0) ? (height & ~1) : 600;
        this.mSender = sender;
        this.mCallback = callback;
    }

    public int getWidth() {
        return mWidth;
    }

    public int getHeight() {
        return mHeight;
    }

    public int getFrameCount() {
        return mFrameCount;
    }

    public boolean isRunning() {
        return mRunning;
    }

    /**
     * Starts continuous rendering and encoding loop.
     */
    public synchronized void startCanvasStreamer(final int targetFps, final int bitrateBps, final FrameDrawer drawer) {
        if (mRunning) return;
        this.mDrawer = drawer;
        this.mRunning = true;
        this.mFrameCount = 0;

        mEncodingThread = new Thread(new Runnable() {
            @Override
            public void run() {
                runEncodingLoop(targetFps > 0 ? targetFps : 30, bitrateBps > 0 ? bitrateBps : 2500000);
            }
        }, "WebLink-CanvasStreamer");
        mEncodingThread.setPriority(Thread.MAX_PRIORITY);
        mEncodingThread.start();
    }

    public synchronized void stop() {
        mRunning = false;
        if (mEncodingThread != null) {
            mEncodingThread.interrupt();
            mEncodingThread = null;
        }
        if (mEncoder != null) {
            try {
                mEncoder.stop();
                mEncoder.release();
            } catch (Exception ignored) {}
            mEncoder = null;
        }
    }

    public synchronized void resetStream() {
        requestKeyFrame();
        if (mCallback != null) {
            mCallback.onStatus("Video stream reset: requested keyframe");
        }
    }

    public void setCachedSpsPps(byte[] spsPps) {
        if (spsPps != null && spsPps.length > 0) {
            this.mCachedSpsPps = spsPps.clone();
            try {
                byte[] configPacket = WebLinkProtocol.buildFillRectangle(
                        mWidth, mHeight, 2, 1, mCachedSpsPps, 0, mCachedSpsPps.length
                );
                mSender.sendPacket(configPacket);
                if (mCallback != null) {
                    mCallback.onStatus("Sent codec config (SPS/PPS " + mCachedSpsPps.length + "B) to stereo decoder");
                }
            } catch (IOException e) {
                Log.w(TAG, "Config packet send error: " + e.getMessage());
            }
        }
    }

    /**
     * Feed an external H.264 Annex B frame into WebLink.
     */
    public void feedAnnexBFrame(byte[] naluBytes) {
        if (naluBytes == null || naluBytes.length == 0) return;

        H264Inspection info = inspectH264AnnexB(naluBytes);

        if (info.hasSps && info.hasPps) {
            byte[] extracted = buildAnnexBSpsPps(info.spsBytes, info.ppsBytes);
            if (extracted != null) {
                mCachedSpsPps = extracted;
            }
        }

        byte[] sendData = naluBytes;
        if (info.hasIdr && !info.hasSps && mCachedSpsPps != null) {
            sendData = new byte[mCachedSpsPps.length + naluBytes.length];
            System.arraycopy(mCachedSpsPps, 0, sendData, 0, mCachedSpsPps.length);
            System.arraycopy(naluBytes, 0, sendData, mCachedSpsPps.length, naluBytes.length);
        }

        try {
            byte[] packet = WebLinkProtocol.buildFillRectangle(
                    mWidth, mHeight, 2, 1, sendData, 0, sendData.length
            );
            mSender.sendPacket(packet);
            mFrameCount++;
            if (mFrameCount == 1) {
                if (mCallback != null) {
                    mCallback.onStatus("First video frame sent to stereo (" + sendData.length + "B, IDR=" + info.hasIdr + ")");
                }
            } else if (mFrameCount % 60 == 0 && mCallback != null) {
                mCallback.onStatus("Streaming: sent " + mFrameCount + " frames to stereo (" + sendData.length + "B/frame)");
            }
        } catch (IOException e) {
            Log.e(TAG, "Error sending video frame: " + e.getMessage());
        }
    }

    private void requestKeyFrame() {
        if (mEncoder != null) {
            try {
                Bundle b = new Bundle();
                b.putInt(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0);
                mEncoder.setParameters(b);
            } catch (Throwable ignored) {}
        }
        if (mCallback != null) {
            mCallback.onKeyFrameRequested();
        }
    }

    private void runEncodingLoop(int fps, int bitrateBps) {
        int colorFormat = MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar;
        try {
            MediaCodecInfo codecInfo = selectAvcEncoder();
            if (codecInfo != null) {
                MediaCodecInfo.CodecCapabilities caps = codecInfo.getCapabilitiesForType("video/avc");
                for (int format : caps.colorFormats) {
                    if (format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar ||
                        format == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Planar) {
                        colorFormat = format;
                        break;
                    }
                }
            }

            MediaFormat format = MediaFormat.createVideoFormat("video/avc", mWidth, mHeight);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat);
            format.setInteger(MediaFormat.KEY_BIT_RATE, bitrateBps);
            format.setInteger(MediaFormat.KEY_FRAME_RATE, fps);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, 1);

            // Automotive headunits require H.264 Baseline Profile (Profile 66)
            try {
                format.setInteger(MediaFormat.KEY_PROFILE, MediaCodecInfo.CodecProfileLevel.AVCProfileBaseline);
                format.setInteger(MediaFormat.KEY_LEVEL, MediaCodecInfo.CodecProfileLevel.AVCLevel31);
            } catch (Throwable ignored) {}

            mEncoder = MediaCodec.createEncoderByType("video/avc");
            mEncoder.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE);
            mEncoder.start();

            if (mCallback != null) {
                mCallback.onStatus("H.264 Baseline encoder started: " + mWidth + "x" + mHeight + " @ " + fps + "fps");
            }

            Bitmap bitmap = Bitmap.createBitmap(mWidth, mHeight, Bitmap.Config.ARGB_8888);
            Canvas canvas = new Canvas(bitmap);

            int yuvSize = mWidth * mHeight * 3 / 2;
            byte[] yuvBuffer = new byte[yuvSize];
            int[] argbPixels = new int[mWidth * mHeight];
            boolean isSemiPlanar = (colorFormat == MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420SemiPlanar);

            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            byte[] spsPps = null;
            long frameIntervalMs = 1000 / fps;
            long frameIndex = 0;

            while (mRunning && !Thread.currentThread().isInterrupted()) {
                long frameStart = SystemClock.uptimeMillis();

                // 1. Draw frame
                if (mDrawer != null) {
                    mDrawer.onDrawFrame(canvas, mWidth, mHeight, frameIndex);
                }
                bitmap.getPixels(argbPixels, 0, mWidth, 0, 0, mWidth, mHeight);
                rgbToYuv(argbPixels, mWidth, mHeight, yuvBuffer, isSemiPlanar);

                // 2. Feed into MediaCodec
                int inIndex = mEncoder.dequeueInputBuffer(10000);
                if (inIndex >= 0) {
                    ByteBuffer inBuf = mEncoder.getInputBuffer(inIndex);
                    if (inBuf != null) {
                        inBuf.clear();
                        inBuf.put(yuvBuffer);
                        long ptsUs = frameIndex * (1000000L / fps);
                        mEncoder.queueInputBuffer(inIndex, 0, yuvBuffer.length, ptsUs, 0);
                    }
                }

                // 3. Drain output frames from MediaCodec
                int outIndex = mEncoder.dequeueOutputBuffer(info, 10000);
                while (outIndex >= 0) {
                    ByteBuffer outBuf = mEncoder.getOutputBuffer(outIndex);
                    if (outBuf != null && info.size > 0) {
                        outBuf.position(info.offset);
                        outBuf.limit(info.offset + info.size);
                        byte[] frame = new byte[info.size];
                        outBuf.get(frame);

                        if ((info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0) {
                            spsPps = frame.clone();
                            mCachedSpsPps = spsPps;

                            // CRITICAL: Transmit initial SPS/PPS frame so headunit decoder un-blanks and initializes
                            try {
                                byte[] configPacket = WebLinkProtocol.buildFillRectangle(
                                        mWidth, mHeight, 2, 1, spsPps, 0, spsPps.length
                                );
                                mSender.sendPacket(configPacket);
                                if (mCallback != null) {
                                    mCallback.onStatus("Sent codec config (SPS/PPS) to stereo decoder");
                                }
                            } catch (IOException e) {
                                Log.w(TAG, "Config packet error: " + e.getMessage());
                            }
                        } else {
                            boolean isKey = (info.flags & MediaCodec.BUFFER_FLAG_KEY_FRAME) != 0;
                            byte[] sendData = frame;

                            // Prepend SPS/PPS to keyframe if not already present
                            if (isKey && spsPps != null && !hasSpsHeader(frame)) {
                                sendData = new byte[spsPps.length + frame.length];
                                System.arraycopy(spsPps, 0, sendData, 0, spsPps.length);
                                System.arraycopy(frame, 0, sendData, spsPps.length, frame.length);
                            }

                            try {
                                byte[] packet = WebLinkProtocol.buildFillRectangle(
                                        mWidth, mHeight, 2, 1, sendData, 0, sendData.length
                                );
                                mSender.sendPacket(packet);
                                mFrameCount++;
                                if (mFrameCount % 30 == 0 && mCallback != null) {
                                    mCallback.onStatus("Streaming: sent " + mFrameCount + " frames to stereo");
                                }
                            } catch (IOException e) {
                                if (mCallback != null) {
                                    mCallback.onStatus("Stream send error: " + e.getMessage());
                                }
                                break;
                            }
                        }
                    }
                    mEncoder.releaseOutputBuffer(outIndex, false);
                    outIndex = mEncoder.dequeueOutputBuffer(info, 0);
                }

                frameIndex++;
                long elapsed = SystemClock.uptimeMillis() - frameStart;
                long sleepTime = frameIntervalMs - elapsed;
                if (sleepTime > 0) {
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Video encoder loop exception: " + e.getMessage(), e);
            if (mCallback != null) {
                mCallback.onStatus("Stream error: " + e.getMessage());
            }
        } finally {
            if (mEncoder != null) {
                try {
                    mEncoder.stop();
                    mEncoder.release();
                } catch (Exception ignored) {}
                mEncoder = null;
            }
            if (mCallback != null) {
                mCallback.onStatus("Video streaming stopped");
            }
        }
    }

    private static boolean hasSpsHeader(byte[] data) {
        if (data == null || data.length < 5) return false;
        for (int i = 0; i < data.length - 4; i++) {
            if (data[i] == 0 && data[i + 1] == 0 && ((data[i + 2] == 1 && (data[i + 3] & 0x1F) == 7) ||
                    (data[i + 2] == 0 && data[i + 3] == 1 && (data[i + 4] & 0x1F) == 7))) {
                return true;
            }
        }
        return false;
    }

    private static MediaCodecInfo selectAvcEncoder() {
        int numCodecs = MediaCodecList.getCodecCount();
        for (int i = 0; i < numCodecs; i++) {
            MediaCodecInfo codecInfo = MediaCodecList.getCodecInfoAt(i);
            if (!codecInfo.isEncoder()) continue;
            String[] types = codecInfo.getSupportedTypes();
            for (String type : types) {
                if (type.equalsIgnoreCase("video/avc")) {
                    return codecInfo;
                }
            }
        }
        return null;
    }

    private static void rgbToYuv(int[] argb, int width, int height, byte[] yuv, boolean isSemiPlanar) {
        int frameSize = width * height;
        int yIndex = 0;
        int uIndex = frameSize;
        int vIndex = frameSize + (frameSize / 4);

        int R, G, B, Y, U, V;
        int index = 0;
        for (int j = 0; j < height; j++) {
            for (int i = 0; i < width; i++) {
                int c = argb[index++];
                R = (c >> 16) & 0xff;
                G = (c >> 8) & 0xff;
                B = c & 0xff;

                Y = ((66 * R + 129 * G + 25 * B + 128) >> 8) + 16;
                U = ((-38 * R - 74 * G + 112 * B + 128) >> 8) + 128;
                V = ((112 * R - 94 * G - 18 * B + 128) >> 8) + 128;

                yuv[yIndex++] = (byte) Math.max(0, Math.min(255, Y));
                if (j % 2 == 0 && i % 2 == 0) {
                    if (isSemiPlanar) {
                        yuv[uIndex++] = (byte) Math.max(0, Math.min(255, U));
                        yuv[uIndex++] = (byte) Math.max(0, Math.min(255, V));
                    } else {
                        yuv[uIndex++] = (byte) Math.max(0, Math.min(255, U));
                        yuv[vIndex++] = (byte) Math.max(0, Math.min(255, V));
                    }
                }
            }
        }
    }

    public static class H264Inspection {
        public boolean hasIdr = false;
        public boolean hasSps = false;
        public boolean hasPps = false;
        public boolean hasSlice = false;
        public byte[] spsBytes = null;
        public byte[] ppsBytes = null;
    }

    public static H264Inspection inspectH264AnnexB(byte[] data) {
        H264Inspection info = new H264Inspection();
        if (data == null || data.length < 4) return info;

        List<Integer> startCodePositions = new ArrayList<>();
        List<Integer> nalHeaderPositions = new ArrayList<>();

        int len = data.length;
        for (int i = 0; i < len - 3; i++) {
            if (data[i] == 0 && data[i + 1] == 0) {
                if (data[i + 2] == 1) {
                    startCodePositions.add(i);
                    nalHeaderPositions.add(i + 3);
                    i += 2;
                } else if (i + 3 < len && data[i + 2] == 0 && data[i + 3] == 1) {
                    startCodePositions.add(i);
                    nalHeaderPositions.add(i + 4);
                    i += 3;
                }
            }
        }

        if (startCodePositions.isEmpty()) return info;

        for (int k = 0; k < startCodePositions.size(); k++) {
            int nalStart = nalHeaderPositions.get(k);
            if (nalStart >= len) continue;
            int nalType = data[nalStart] & 0x1F;

            int nalEnd = (k + 1 < startCodePositions.size()) ? startCodePositions.get(k + 1) : len;
            int nalLen = nalEnd - nalStart;

            if (nalType == 7) {
                info.hasSps = true;
                info.spsBytes = new byte[nalLen];
                System.arraycopy(data, nalStart, info.spsBytes, 0, nalLen);
            } else if (nalType == 8) {
                info.hasPps = true;
                info.ppsBytes = new byte[nalLen];
                System.arraycopy(data, nalStart, info.ppsBytes, 0, nalLen);
            } else if (nalType == 5) {
                info.hasIdr = true;
            } else if (nalType == 1) {
                info.hasSlice = true;
            }
        }
        return info;
    }

    private static byte[] buildAnnexBSpsPps(byte[] sps, byte[] pps) {
        if (sps == null || pps == null) return null;
        byte[] startCode = new byte[]{ 0, 0, 0, 1 };
        byte[] out = new byte[startCode.length + sps.length + startCode.length + pps.length];
        int pos = 0;
        System.arraycopy(startCode, 0, out, pos, startCode.length);
        pos += startCode.length;
        System.arraycopy(sps, 0, out, pos, sps.length);
        pos += sps.length;
        System.arraycopy(startCode, 0, out, pos, startCode.length);
        pos += startCode.length;
        System.arraycopy(pps, 0, out, pos, pps.length);
        return out;
    }
}
