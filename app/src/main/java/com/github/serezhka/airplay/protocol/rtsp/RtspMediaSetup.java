package com.github.serezhka.airplay.protocol.rtsp;

import android.util.Log;

import com.github.serezhka.airplay.protocol.media.AirPlayStreamType;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.media.MediaStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.groundscreen.tv.util.BinaryPlist;
import com.groundscreen.tv.util.IoUtil;

import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/**
 * Parses RTSP SETUP / TEARDOWN binary plists.
 * Adapted from java-airplay (MIT, Sergei Fedorov) to use a dependency-free plist codec.
 */
public class RtspMediaSetup {

    private static final String TAG = "RtspMediaSetup";

    private byte[] ekey;
    private byte[] eiv;

    private String streamConnectionID;

    /** @return stream info for a stream SETUP, or {@code null} for the initial key SETUP */
    @SuppressWarnings("unchecked")
    public MediaStreamInfo setup(byte[] payload) throws Exception {
        Map<String, Object> setup = (Map<String, Object>) BinaryPlist.parse(payload);
        MediaStreamInfo result = null;
        if (setup.containsKey("ekey") || setup.containsKey("eiv")) {
            ekey = (byte[]) setup.get("ekey");
            eiv = (byte[]) setup.get("eiv");
        }
        if (setup.containsKey("streams")) {
            result = getMediaStreamInfo(setup);
        } else if (ekey == null) {
            Log.w(TAG, "Unknown RTSP setup content: " + setup.keySet());
        }
        return result;
    }

    @SuppressWarnings("unchecked")
    public MediaStreamInfo teardown(byte[] payload) throws Exception {
        if (payload == null || payload.length == 0) {
            return null;
        }
        Map<String, Object> teardown = (Map<String, Object>) BinaryPlist.parse(payload);
        if (teardown.containsKey("streams")) {
            return getMediaStreamInfo(teardown);
        }
        return null;
    }

    @SuppressWarnings("unchecked")
    private MediaStreamInfo getMediaStreamInfo(Map<String, Object> request) {
        List<Object> streams = (List<Object>) request.get("streams");
        if (streams == null || streams.isEmpty()) {
            return null;
        }
        Map<String, Object> stream = (Map<String, Object>) streams.get(0);
        int type = (int) BinaryPlist.asLong(stream.get("type"), -1);
        switch (type) {
            case AirPlayStreamType.SCREEN: {
                Object rawId = stream.get("streamConnectionID");
                if (rawId instanceof BigInteger) {
                    streamConnectionID = IoUtil.unsignedLongToString(((BigInteger) rawId).longValue());
                } else if (rawId != null) {
                    streamConnectionID = IoUtil.unsignedLongToString(BinaryPlist.asLong(rawId, 0));
                }
                return new VideoStreamInfo(streamConnectionID);
            }
            case AirPlayStreamType.AUDIO_REALTIME:
            case AirPlayStreamType.AUDIO_BUFFERED: {
                AudioStreamInfo.AudioStreamInfoBuilder builder = new AudioStreamInfo.AudioStreamInfoBuilder();
                builder.streamType(type);
                if (stream.containsKey("ct")) {
                    try {
                        builder.compressionType(AudioStreamInfo.CompressionType.fromCode(
                                BinaryPlist.asLong(stream.get("ct"), 0)));
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, e.getMessage());
                    }
                }
                if (stream.containsKey("audioFormat")) {
                    try {
                        builder.audioFormat(AudioStreamInfo.AudioFormat.fromCode(
                                BinaryPlist.asLong(stream.get("audioFormat"), 0)));
                    } catch (IllegalArgumentException e) {
                        Log.w(TAG, e.getMessage());
                    }
                }
                if (stream.containsKey("spf")) {
                    builder.samplesPerFrame((int) BinaryPlist.asLong(stream.get("spf"), 0));
                }
                return builder.build();
            }
            default:
                Log.w(TAG, "Unknown stream type: " + type);
                return null;
        }
    }

    public String getStreamConnectionID() {
        return streamConnectionID;
    }

    public byte[] getEkey() {
        return ekey;
    }

    public byte[] getEiv() {
        return eiv;
    }
}
