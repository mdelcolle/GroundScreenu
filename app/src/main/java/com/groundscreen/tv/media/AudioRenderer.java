package com.groundscreen.tv.media;

import android.media.AudioFormat;
import android.media.AudioManager;
import android.media.AudioTrack;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.util.Log;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;

import java.nio.ByteBuffer;

/**
 * Decodes AirPlay audio and plays PCM through {@link AudioTrack}: AAC-ELD (mirroring) and AAC-LC
 * via {@link MediaCodec}, ALAC (music) via the bundled pure-Java {@link AlacDecoder}.
 */
public final class AudioRenderer {

    private static final String TAG = "AudioRenderer";

    private final Object lock = new Object();
    private MediaCodec codec;
    private AudioTrack track;
    private boolean pcmPassthrough;
    private AlacDecoder alac;
    private float volume = 1f;
    private byte[] pcmBuf = new byte[0];
    private int sampleRate = 44100;
    private int channels = 2;

    public void configure(AudioStreamInfo info) {
        synchronized (lock) {
            releaseLocked();
            AudioStreamInfo.CompressionType ct = info.getCompressionType();
            String formatName = info.getAudioFormat() != null ? info.getAudioFormat().name() : "";
            parseRateAndChannels(formatName);
            int spf = info.getSamplesPerFrame() > 0 ? info.getSamplesPerFrame() : 480;
            try {
                if (ct == AudioStreamInfo.CompressionType.LPCM) {
                    pcmPassthrough = true;
                } else if (ct == AudioStreamInfo.CompressionType.ALAC) {
                    alac = new AlacDecoder(alacCookie(spf == 480 ? 352 : spf));
                    pcmBuf = new byte[alac.maxOutputBytes()];
                } else {
                    boolean eld = ct == null || ct == AudioStreamInfo.CompressionType.AAC_ELD;
                    MediaFormat f = MediaFormat.createAudioFormat(MediaFormat.MIMETYPE_AUDIO_AAC, sampleRate, channels);
                    f.setByteBuffer("csd-0", ByteBuffer.wrap(eld ? eldConfig(sampleRate, channels, spf)
                            : lcConfig(sampleRate, channels)));
                    if (!eld) {
                        f.setInteger(MediaFormat.KEY_IS_ADTS, 0);
                    }
                    startCodec(f, MediaFormat.MIMETYPE_AUDIO_AAC);
                }
                createTrack();
                Log.i(TAG, "Audio " + ct + " " + sampleRate + "Hz x" + channels + " spf=" + spf);
            } catch (Exception e) {
                Log.e(TAG, "Audio decoder unavailable for " + ct + ": " + e);
                releaseLocked();
            }
        }
    }

    public void onFrame(byte[] data, int off, int len) {
        synchronized (lock) {
            if (track == null) {
                return;
            }
            if (pcmPassthrough) {
                // AirPlay LPCM is big-endian 16 bit
                for (int i = off; i + 1 < off + len; i += 2) {
                    byte t = data[i];
                    data[i] = data[i + 1];
                    data[i + 1] = t;
                }
                track.write(data, off, len);
                return;
            }
            if (alac != null) {
                try {
                    int n = alac.decode(data, off, len, pcmBuf);
                    track.write(pcmBuf, 0, n);
                } catch (RuntimeException e) {
                    Log.w(TAG, "ALAC frame dropped: " + e.getMessage());
                }
                return;
            }
            if (codec == null) {
                return;
            }
            try {
                int in = codec.dequeueInputBuffer(10_000);
                if (in >= 0) {
                    ByteBuffer b = codec.getInputBuffer(in);
                    b.clear();
                    b.put(data, off, Math.min(len, b.capacity()));
                    codec.queueInputBuffer(in, 0, Math.min(len, b.capacity()), 0, 0);
                }
                MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
                int out;
                while ((out = codec.dequeueOutputBuffer(info, 0)) != MediaCodec.INFO_TRY_AGAIN_LATER) {
                    if (out >= 0) {
                        if (info.size > 0) {
                            ByteBuffer ob = codec.getOutputBuffer(out);
                            if (pcmBuf.length < info.size) {
                                pcmBuf = new byte[info.size];
                            }
                            ob.position(info.offset);
                            ob.get(pcmBuf, 0, info.size);
                            track.write(pcmBuf, 0, info.size);
                        }
                        codec.releaseOutputBuffer(out, false);
                    } else if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                        MediaFormat f = codec.getOutputFormat();
                        int sr = f.getInteger(MediaFormat.KEY_SAMPLE_RATE);
                        int ch = f.getInteger(MediaFormat.KEY_CHANNEL_COUNT);
                        if (sr != sampleRate || ch != channels) {
                            sampleRate = sr;
                            channels = ch;
                            createTrack();
                        }
                    }
                }
            } catch (Exception e) {
                Log.w(TAG, "audio decode error: " + e);
            }
        }
    }

    public void setVolume(float v) {
        synchronized (lock) {
            volume = v;
            if (track != null) {
                track.setVolume(v);
            }
        }
    }

    public void release() {
        synchronized (lock) {
            releaseLocked();
        }
    }

    private void startCodec(MediaFormat f, String mime) throws Exception {
        codec = MediaCodec.createDecoderByType(mime);
        codec.configure(f, null, null, 0);
        codec.start();
    }

    @SuppressWarnings("deprecation")
    private void createTrack() {
        if (track != null) {
            track.release();
        }
        int chMask = channels == 1 ? AudioFormat.CHANNEL_OUT_MONO : AudioFormat.CHANNEL_OUT_STEREO;
        int min = AudioTrack.getMinBufferSize(sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT);
        int size = Math.max(min * 2, sampleRate * channels * 2 / 10); // ~100 ms
        track = new AudioTrack(AudioManager.STREAM_MUSIC, sampleRate, chMask, AudioFormat.ENCODING_PCM_16BIT,
                size, AudioTrack.MODE_STREAM);
        track.setVolume(volume);
        track.play();
    }

    private void releaseLocked() {
        pcmPassthrough = false;
        alac = null;
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
        if (track != null) {
            try {
                track.stop();
            } catch (Exception ignored) {
            }
            track.release();
            track = null;
        }
    }

    private void parseRateAndChannels(String name) {
        // e.g. AAC_ELD_44100_2, PCM_48000_16_2, ALAC_44100_16_2
        sampleRate = 44100;
        channels = 2;
        String[] p = name.split("_");
        for (int i = 0; i < p.length; i++) {
            try {
                int v = Integer.parseInt(p[i]);
                if (v >= 8000) {
                    sampleRate = v;
                    channels = Integer.parseInt(p[p.length - 1]);
                    break;
                }
            } catch (NumberFormatException ignored) {
                // not numeric
            }
        }
        if (channels < 1 || channels > 2) {
            channels = 2;
        }
    }

    private static int freqIndex(int rate) {
        int[] rates = {96000, 88200, 64000, 48000, 44100, 32000, 24000, 22050, 16000, 12000, 11025, 8000};
        for (int i = 0; i < rates.length; i++) {
            if (rates[i] == rate) {
                return i;
            }
        }
        return 4;
    }

    /** AudioSpecificConfig for AAC-ELD (AOT 39) with 480/512 frame length. */
    static byte[] eldConfig(int rate, int ch, int spf) {
        // 5 bits AOT escape (31) + 6 bits (39-32=7) + 4 bits freq + 4 bits channels + 1 bit frameLengthFlag
        long bits = 0;
        int n = 0;
        bits = (bits << 5) | 31;
        n += 5;
        bits = (bits << 6) | 7;
        n += 6;
        bits = (bits << 4) | freqIndex(rate);
        n += 4;
        bits = (bits << 4) | ch;
        n += 4;
        bits = (bits << 1) | (spf == 480 ? 1 : 0);
        n += 1;
        // resilience flags (3 bits) = 0, ldSbrPresent = 0, ELDEXT_TERM (4 bits) = 0
        bits <<= 8;
        n += 8;
        bits <<= (32 - n);
        return new byte[]{(byte) (bits >>> 24), (byte) (bits >>> 16), (byte) (bits >>> 8), (byte) bits};
    }

    /** AudioSpecificConfig for AAC-LC. */
    static byte[] lcConfig(int rate, int ch) {
        int v = (2 << 11) | (freqIndex(rate) << 7) | (ch << 3);
        return new byte[]{(byte) (v >> 8), (byte) v};
    }

    /** ALACSpecificConfig ("magic cookie") for 16-bit stereo as used by AirPlay. */
    private byte[] alacCookie(int frameLength) {
        ByteBuffer b = ByteBuffer.allocate(24);
        b.putInt(frameLength);
        b.put((byte) 0);   // compatible version
        b.put((byte) 16);  // bit depth
        b.put((byte) 40);  // pb
        b.put((byte) 10);  // mb
        b.put((byte) 14);  // kb
        b.put((byte) channels);
        b.putShort((short) 255);
        b.putInt(0);
        b.putInt(0);
        b.putInt(sampleRate);
        return b.array();
    }
}
