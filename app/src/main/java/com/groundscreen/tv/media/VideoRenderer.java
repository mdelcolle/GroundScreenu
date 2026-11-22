package com.groundscreen.tv.media;

import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;
import android.view.Surface;

import com.groundscreen.tv.util.DiagLog;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Hardware H.264 decoding of the mirror stream onto a {@link Surface}.
 * <p>
 * Frames since the last IDR are kept so decoding can resume instantly when the surface
 * (re)appears, e.g. when the activity is brought to the front after mirroring started.
 */
public final class VideoRenderer {

    public interface SizeListener {
        void onVideoSize(int width, int height);
    }

    private static final String TAG = "VideoRenderer";
    private static final String MIME = "video/avc";
    private static final int MAX_GOP_FRAMES = 600;
    private static final long MAX_GOP_BYTES = 48L * 1024 * 1024;

    private final Object lock = new Object();
    private final SizeListener sizeListener;

    private Surface surface;
    private byte[] sps;
    private byte[] pps;
    private final List<byte[]> gop = new ArrayList<byte[]>();
    private long gopBytes;
    private boolean waitingForIdr = true;
    /** Decoder failed; recreate it at the next IDR. */
    private boolean codecFailed;

    private MediaCodec codec;
    private Thread drainThread;
    private volatile boolean draining;
    private long ptsUs;

    public VideoRenderer(SizeListener sizeListener) {
        this.sizeListener = sizeListener;
    }

    public void setSurface(Surface s) {
        synchronized (lock) {
            if (surface == s) {
                return;
            }
            stopCodecLocked();
            surface = s;
            if (surface != null) {
                startCodecLocked();
            }
        }
    }

    public void onConfig(byte[] newSps, byte[] newPps) {
        synchronized (lock) {
            boolean changed = !Arrays.equals(newSps, sps) || !Arrays.equals(newPps, pps);
            sps = newSps;
            pps = newPps;
            if (changed) {
                int[] size = H264Util.spsSize(newSps);
                DiagLog.add("Nuevo SPS/PPS " + (size != null ? size[0] + "x" + size[1] : "?"));
                codecFailed = false;
                gop.clear();
                gopBytes = 0;
                waitingForIdr = true;
                stopCodecLocked();
                if (surface != null) {
                    startCodecLocked();
                }
            }
        }
    }

    public void onFrame(byte[] annexB) {
        synchronized (lock) {
            if (sps == null) {
                return;
            }
            boolean idr = H264Util.containsIdr(annexB);
            if (idr) {
                gop.clear();
                gopBytes = 0;
                waitingForIdr = false;
            } else if (waitingForIdr) {
                return;
            }
            if (idr && codecFailed && codec == null && surface != null) {
                codecFailed = false;
                DiagLog.add("Reintentando decodificador");
                gop.add(annexB);
                gopBytes += annexB.length;
                startCodecLocked();
                return;
            }
            if (gop.size() < MAX_GOP_FRAMES && gopBytes + annexB.length < MAX_GOP_BYTES) {
                gop.add(annexB);
                gopBytes += annexB.length;
            }
            if (codec != null) {
                queueLocked(annexB, 0);
            }
        }
    }

    /** Stream ended: forget everything. */
    public void reset() {
        synchronized (lock) {
            stopCodecLocked();
            sps = null;
            pps = null;
            gop.clear();
            gopBytes = 0;
            waitingForIdr = true;
        }
    }

    public void release() {
        synchronized (lock) {
            stopCodecLocked();
            surface = null;
        }
    }

    // ------------------------------------------------------------------ codec

    private void startCodecLocked() {
        if (sps == null || pps == null || surface == null || !surface.isValid()) {
            return;
        }
        try {
            int[] size = H264Util.spsSize(sps);
            int w = size != null ? size[0] : 1920;
            int h = size != null ? size[1] : 1080;
            DiagLog.videoSize = w + "x" + h;
            MediaFormat format = MediaFormat.createVideoFormat(MIME, w, h);
            format.setByteBuffer("csd-0", ByteBuffer.wrap(sps));
            format.setByteBuffer("csd-1", ByteBuffer.wrap(pps));
            // Allow rotation / portrait video without reallocating where the decoder supports it.
            int max = Math.max(1920, Math.max(w, h));
            format.setInteger(MediaFormat.KEY_MAX_WIDTH, max);
            format.setInteger(MediaFormat.KEY_MAX_HEIGHT, max);
            format.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, 4 * 1024 * 1024);
            format.setInteger("low-latency", 1); // honoured on Android 11+, ignored before
            format.setInteger(MediaFormat.KEY_PRIORITY, 0);
            codec = MediaCodec.createDecoderByType(MIME);
            codec.configure(format, surface, null, 0);
            codec.start();
            draining = true;
            final MediaCodec c = codec;
            drainThread = new Thread(new Runnable() {
                @Override
                public void run() {
                    drainLoop(c);
                }
            }, "video-drain");
            drainThread.start();
            Log.i(TAG, "Decoder started (" + codec.getName() + "), replaying " + gop.size() + " frames");
            DiagLog.add("Decodificador " + codec.getName() + " " + w + "x" + h);
            for (byte[] f : gop) {
                queueLocked(f, 0);
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to start decoder", e);
            DiagLog.add("ERROR al iniciar decodificador: " + e);
            releaseCodecQuietly();
            codecFailed = true;
        }
    }

    private void queueLocked(byte[] data, int flags) {
        try {
            int index = codec.dequeueInputBuffer(200_000);
            if (index < 0) {
                Log.w(TAG, "Decoder input full, dropping frame");
                return;
            }
            ByteBuffer buf = codec.getInputBuffer(index);
            buf.clear();
            if (data.length > buf.capacity()) {
                codec.queueInputBuffer(index, 0, 0, 0, 0);
                return;
            }
            buf.put(data);
            ptsUs += 16_666;
            codec.queueInputBuffer(index, 0, data.length, ptsUs, flags);
        } catch (Exception e) {
            Log.e(TAG, "queueInputBuffer failed", e);
            DiagLog.add("ERROR decodificador: " + e);
            stopCodecLocked();
            codecFailed = true;
            waitingForIdr = true;
        }
    }

    private void drainLoop(MediaCodec c) {
        MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
        while (draining) {
            try {
                int index = c.dequeueOutputBuffer(info, 20_000);
                if (index >= 0) {
                    c.releaseOutputBuffer(index, true);
                    DiagLog.framesDecoded++;
                } else if (index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    MediaFormat f = c.getOutputFormat();
                    int w = f.getInteger(MediaFormat.KEY_WIDTH);
                    int h = f.getInteger(MediaFormat.KEY_HEIGHT);
                    if (f.containsKey("crop-left") && f.containsKey("crop-right")) {
                        w = f.getInteger("crop-right") + 1 - f.getInteger("crop-left");
                    }
                    if (f.containsKey("crop-top") && f.containsKey("crop-bottom")) {
                        h = f.getInteger("crop-bottom") + 1 - f.getInteger("crop-top");
                    }
                    Log.i(TAG, "Video size " + w + "x" + h);
                    DiagLog.add("Salida de video " + w + "x" + h);
                    if (sizeListener != null) {
                        sizeListener.onVideoSize(w, h);
                    }
                }
            } catch (IllegalStateException e) {
                if (draining) {
                    DiagLog.add("ERROR salida decodificador: " + e);
                }
                break; // codec stopped or failed
            }
        }
    }

    private void stopCodecLocked() {
        draining = false;
        if (drainThread != null) {
            try {
                drainThread.join(500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            drainThread = null;
        }
        releaseCodecQuietly();
    }

    private void releaseCodecQuietly() {
        if (codec != null) {
            try {
                codec.stop();
            } catch (Exception ignored) {
            }
            try {
                codec.release();
            } catch (Exception ignored) {
            }
            codec = null;
        }
    }
}
