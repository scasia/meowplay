package com.weblink.sdk;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * Low-level packet serialization and framing for the WebLink automotive protocol.
 * All multi-byte integer fields are Little-Endian.
 */
public class WebLinkProtocol {
    public static final byte MAGIC_W = 0x57; // 'W'
    public static final byte MAGIC_L = 0x4C; // 'L'

    public static final short CMD_FILL_RECTANGLE = 1;
    public static final short CMD_VIDEO_CONFIG = 32;
    public static final short CMD_VIDEO_CONTROL = 33;
    public static final short CMD_STATUS_QUERY = 49;
    public static final short CMD_CHANNEL_CONTROL = 71;
    public static final short CMD_TOUCH = 72;
    public static final short CMD_CLIENT_FEATURES = 75;
    public static final short CMD_PING = 76;
    public static final short CMD_AUDIO_CHANNEL_CONFIG = 82;
    public static final short CMD_AUDIO_CONTROL = 83;
    public static final short CMD_DEVICE_IDENTITY = 128;

    public static class Packet {
        public final short commandId;
        public final byte[] payload;

        public Packet(short commandId, byte[] payload) {
            this.commandId = commandId;
            this.payload = payload;
        }

        @Override
        public String toString() {
            return "Packet{cmd=" + commandId + ", len=" + (payload != null ? payload.length : 0) + "}";
        }
    }

    public static class PacketAccumulator {
        private final ByteArrayOutputStream mBuffer = new ByteArrayOutputStream();

        public synchronized void append(byte[] data, int offset, int length) {
            if (data == null || length <= 0) return;
            // Prevent unbounded memory growth if stream gets de-synced
            if (mBuffer.size() > 5 * 1024 * 1024) {
                mBuffer.reset();
            }
            mBuffer.write(data, offset, length);
        }

        public synchronized List<Packet> extractPackets() {
            List<Packet> list = new ArrayList<>();
            byte[] all = mBuffer.toByteArray();
            int pos = 0;

            while (pos + 8 <= all.length) {
                if (all[pos] != MAGIC_W || all[pos + 1] != MAGIC_L) {
                    pos++;
                    continue;
                }

                short cmdId = getShort(all, pos + 2);
                int payloadLen = getInt(all, pos + 4);

                if (payloadLen < 0 || payloadLen > 10 * 1024 * 1024) {
                    pos++;
                    continue;
                }

                if (pos + 8 + payloadLen > all.length) {
                    break;
                }

                byte[] payload = new byte[payloadLen];
                System.arraycopy(all, pos + 8, payload, 0, payloadLen);
                list.add(new Packet(cmdId, payload));
                pos += (8 + payloadLen);
            }

            mBuffer.reset();
            if (pos < all.length) {
                mBuffer.write(all, pos, all.length - pos);
            }
            return list;
        }

        public synchronized void clear() {
            mBuffer.reset();
        }
    }

    public static byte[] buildCommand(short cmdId, byte[] payload) {
        int payloadLen = (payload != null) ? payload.length : 0;
        byte[] buffer = new byte[8 + payloadLen];
        buffer[0] = MAGIC_W;
        buffer[1] = MAGIC_L;
        putShort(buffer, 2, cmdId);
        putInt(buffer, 4, payloadLen);

        if (payloadLen > 0) {
            System.arraycopy(payload, 0, buffer, 8, payloadLen);
        }
        return buffer;
    }

    public static byte[] buildRegularPing() {
        byte[] payload = new byte[2];
        payload[0] = 2; // Regular ping flag
        payload[1] = 0;
        return buildCommand(CMD_PING, payload);
    }

    public static byte[] buildPingResponse() {
        byte[] payload = new byte[2];
        payload[0] = 1; // Ping response flag
        payload[1] = 0;
        return buildCommand(CMD_PING, payload);
    }

    public static byte[] buildDeviceIdentityResponse(String appName, String clientVersion) {
        ByteArrayOutputStream baos = new ByteArrayOutputStream();
        try {
            addStringProp(baos, (short) 1, "WebLinkHost");
            addStringProp(baos, (short) 2, (appName != null ? appName : "WebLinkPoC"));
            addStringProp(baos, (short) 4, "Android");
            addStringProp(baos, (short) 5, "Automotive Adapter");
            addStringProp(baos, (short) 8, "Android");
            addStringProp(baos, (short) 9, "11.0");
            addStringProp(baos, (short) 10, (appName != null ? appName : "WebLinkPoC"));
            addStringProp(baos, (short) 11, "WebLink");
            addStringProp(baos, (short) 12, (clientVersion != null ? clientVersion : "1.0.0"));
            addStringProp(baos, (short) 13, "2.4.6");
        } catch (IOException ignored) {}
        return buildCommand(CMD_DEVICE_IDENTITY, baos.toByteArray());
    }

    private static void addStringProp(ByteArrayOutputStream baos, short id, String val) throws IOException {
        if (val == null) return;
        byte[] bytes = val.getBytes(StandardCharsets.UTF_8);
        byte[] hdr = new byte[6];
        putShort(hdr, 0, id);
        putInt(hdr, 2, bytes.length);
        baos.write(hdr);
        baos.write(bytes);
    }

    public static class VideoConfigData {
        public int sourceWidth;
        public int sourceHeight;
        public int clientWidth;
        public int clientHeight;
        public int frameEncoding;
        public String encoderParams;
    }

    public static VideoConfigData parseVideoConfig(byte[] payload) {
        VideoConfigData vc = new VideoConfigData();
        if (payload != null && payload.length >= 20) {
            vc.sourceWidth = getInt(payload, 0);
            vc.sourceHeight = getInt(payload, 4);
            vc.clientWidth = getInt(payload, 8);
            vc.clientHeight = getInt(payload, 12);
            vc.frameEncoding = getInt(payload, 16);
            if (payload.length > 20) {
                vc.encoderParams = new String(payload, 20, payload.length - 20, StandardCharsets.UTF_8);
            }
        }
        return vc;
    }

    public static byte[] buildVideoConfigResponse(int srcW, int srcH, int clientW, int clientH, int codec, String params) {
        byte[] paramBytes = (params != null) ? params.getBytes(StandardCharsets.UTF_8) : new byte[0];
        byte[] payload = new byte[20 + paramBytes.length];
        putInt(payload, 0, srcW);
        putInt(payload, 4, srcH);
        putInt(payload, 8, clientW);
        putInt(payload, 12, clientH);
        putInt(payload, 16, codec); // 2 = H.264
        if (paramBytes.length > 0) {
            System.arraycopy(paramBytes, 0, payload, 20, paramBytes.length);
        }
        return buildCommand(CMD_VIDEO_CONFIG, payload);
    }

    public static byte[] buildFillRectangle(int width, int height, int codecType, int appId, byte[] frameData, int offset, int length) {
        int payloadLen = 16 + length;
        byte[] packet = new byte[8 + payloadLen];
        packet[0] = MAGIC_W;
        packet[1] = MAGIC_L;
        putShort(packet, 2, CMD_FILL_RECTANGLE);
        putInt(packet, 4, payloadLen);

        putInt(packet, 8, width);
        putInt(packet, 12, height);
        putInt(packet, 16, codecType);
        putInt(packet, 20, appId); // appId=1 indicates primary active application
        System.arraycopy(frameData, offset, packet, 24, length);
        return packet;
    }

    public static List<WebLinkTouchPoint> parseTouchPoints(byte[] payload) {
        List<WebLinkTouchPoint> list = new ArrayList<>();
        if (payload == null || payload.length < 8) return list;

        int count = getInt(payload, 4);
        int offset = 8;
        for (int i = 0; i < count && (offset + 20) <= payload.length; i++) {
            int id = getInt(payload, offset);
            int posX = getInt(payload, offset + 4);
            int posY = getInt(payload, offset + 8);
            int state = getInt(payload, offset + 12);
            float pressure = Float.intBitsToFloat(getInt(payload, offset + 16));
            list.add(new WebLinkTouchPoint(id, posX, posY, state, pressure));
            offset += 20;
        }
        return list;
    }

    public static short getShort(byte[] b, int offset) {
        return (short) ((b[offset] & 0xFF) | ((b[offset + 1] & 0xFF) << 8));
    }

    public static void putShort(byte[] b, int offset, short val) {
        b[offset] = (byte) (val & 0xFF);
        b[offset + 1] = (byte) ((val >> 8) & 0xFF);
    }

    public static int getInt(byte[] b, int offset) {
        return (b[offset] & 0xFF) |
               ((b[offset + 1] & 0xFF) << 8) |
               ((b[offset + 2] & 0xFF) << 16) |
               ((b[offset + 3] & 0xFF) << 24);
    }

    public static void putInt(byte[] b, int offset, int val) {
        b[offset] = (byte) (val & 0xFF);
        b[offset + 1] = (byte) ((val >> 8) & 0xFF);
        b[offset + 2] = (byte) ((val >> 16) & 0xFF);
        b[offset + 3] = (byte) ((val >> 24) & 0xFF);
    }
}
