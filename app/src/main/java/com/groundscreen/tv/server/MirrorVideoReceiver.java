package com.groundscreen.tv.server;

import android.util.Log;

import com.github.serezhka.airplay.protocol.FairPlaySession;
import com.github.serezhka.airplay.protocol.fairplay.FairPlayVideoDecryptor;
import com.groundscreen.tv.util.DiagLog;
import com.groundscreen.tv.util.IoUtil;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;

/**
 * TCP data channel of a mirroring session. Each packet: 128-byte little-endian header
 * (payload size, payload type, NTP timestamp, ...) followed by the payload.
 * Type 0 = AES-CTR encrypted AVCC access unit, type 1 = SPS/PPS codec config (plaintext).
 */
final class MirrorVideoReceiver implements Runnable {

    private static final String TAG = "MirrorVideo";
    private static final int HEADER = 128;
    private static final int MAX_PAYLOAD = 16 * 1024 * 1024;

    private final FairPlaySession session;
    private final MirrorListener listener;

    private ServerSocket server;
    private volatile Socket client;
    private volatile boolean running;
    private Thread thread;

    MirrorVideoReceiver(FairPlaySession session, MirrorListener listener) {
        this.session = session;
        this.listener = listener;
    }

    int start() throws IOException {
        server = new ServerSocket();
        server.setReuseAddress(true);
        server.bind(new InetSocketAddress(0));
        running = true;
        thread = new Thread(this, "mirror-video");
        thread.start();
        return server.getLocalPort();
    }

    void stop() {
        running = false;
        try {
            if (server != null) {
                server.close();
            }
        } catch (IOException ignored) {
        }
        Socket c = client;
        if (c != null) {
            try {
                c.close();
            } catch (IOException ignored) {
            }
        }
    }

    @Override
    public void run() {
        try {
            Socket s = server.accept();
            client = s;
            server.close();
            s.setTcpNoDelay(true);
            s.setReceiveBufferSize(1024 * 1024);
            Log.i(TAG, "Mirror stream connected from " + s.getInetAddress().getHostAddress());
            DiagLog.add("Canal de video conectado");
            receive(new BufferedInputStream(s.getInputStream(), 256 * 1024));
        } catch (EOFException e) {
            Log.i(TAG, "Mirror stream closed by sender");
            DiagLog.add("Canal de video cerrado por el iPhone");
        } catch (IOException e) {
            if (running) {
                Log.w(TAG, "Mirror stream error: " + e);
            }
        } catch (Exception e) {
            Log.e(TAG, "Mirror stream failed", e);
        } finally {
            stop();
        }
    }

    private void receive(InputStream in) throws Exception {
        byte[] header = new byte[HEADER];
        FairPlayVideoDecryptor decryptor = null;
        int badFrames = 0;
        while (running) {
            IoUtil.readFully(in, header, 0, HEADER);
            int size = (header[0] & 0xff) | (header[1] & 0xff) << 8 | (header[2] & 0xff) << 16 | (header[3] & 0xff) << 24;
            int type = header[4] & 0xff;
            long ts = 0;
            for (int i = 15; i >= 8; i--) {
                ts = (ts << 8) | (header[i] & 0xff);
            }
            if (size < 0 || size > MAX_PAYLOAD) {
                throw new IOException("Bad mirror payload size " + size);
            }
            byte[] payload = new byte[size];
            IoUtil.readFully(in, payload, 0, size);

            if (type == 0) {
                DiagLog.videoPackets++;
                if (decryptor == null) {
                    decryptor = session.newVideoDecryptor();
                }
                decryptor.decrypt(payload);
                if (avccToAnnexB(payload)) {
                    badFrames = 0;
                    listener.onVideoFrame(payload, ts);
                } else if (++badFrames == 30) {
                    Log.e(TAG, "Many corrupted video frames: decryption probably failed");
                    DiagLog.add("ERROR: video corrupto (fallo de descifrado)");
                }
            } else if (type == 1) {
                float sw = Float.intBitsToFloat(le32(header, 16));
                float sh = Float.intBitsToFloat(le32(header, 20));
                DiagLog.add("Config. video (pantalla " + Math.round(sw) + "x" + Math.round(sh) + ")");
                parseCodecConfig(payload);
            } else if (type != 2) {
                DiagLog.add("Paquete video tipo " + type + " (" + size + " bytes)");
            }
            // type 2 = heartbeat, type 5 = streaming report: ignored
        }
    }

    private static int le32(byte[] b, int o) {
        return (b[o] & 0xff) | (b[o + 1] & 0xff) << 8 | (b[o + 2] & 0xff) << 16 | (b[o + 3] & 0xff) << 24;
    }

    /** Rewrites 4-byte big-endian NAL lengths to Annex-B start codes in place. */
    static boolean avccToAnnexB(byte[] p) {
        int idx = 0;
        if (p.length < 5) {
            return false;
        }
        while (idx + 4 <= p.length) {
            int n = (p[idx] & 0xff) << 24 | (p[idx + 1] & 0xff) << 16 | (p[idx + 2] & 0xff) << 8 | (p[idx + 3] & 0xff);
            if (n <= 0 || idx + 4 + n > p.length) {
                return false;
            }
            p[idx] = 0;
            p[idx + 1] = 0;
            p[idx + 2] = 0;
            p[idx + 3] = 1;
            idx += 4 + n;
        }
        return true;
    }

    /** avcC-like record: 6 bytes prefix, u16 spsLen, sps, u8 ppsCount, u16 ppsLen, pps. */
    private void parseCodecConfig(byte[] p) {
        if (p.length < 11) {
            return;
        }
        int spsLen = (p[6] & 0xff) << 8 | (p[7] & 0xff);
        if (spsLen <= 0 || 8 + spsLen + 3 > p.length) {
            return;
        }
        int ppsOff = 8 + spsLen + 1;
        int ppsLen = (p[ppsOff] & 0xff) << 8 | (p[ppsOff + 1] & 0xff);
        if (ppsLen <= 0 || ppsOff + 2 + ppsLen > p.length) {
            return;
        }
        byte[] sps = new byte[4 + spsLen];
        sps[3] = 1;
        System.arraycopy(p, 8, sps, 4, spsLen);
        byte[] pps = new byte[4 + ppsLen];
        pps[3] = 1;
        System.arraycopy(p, ppsOff + 2, pps, 4, ppsLen);
        Log.i(TAG, "Codec config: SPS " + spsLen + " bytes, PPS " + ppsLen + " bytes");
        listener.onVideoConfig(sps, pps);
    }
}
