package com.groundscreen.tv.server;

import android.util.Log;

import com.github.serezhka.airplay.protocol.FairPlaySession;

import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.SocketException;

/**
 * RTP audio over UDP (data port) plus the RTCP-like control port.
 * Payloads are AES-CBC encrypted (whole 16-byte blocks only); decrypted frames are passed on
 * in sequence order, dropping late duplicates.
 */
final class AudioReceiver {

    private static final String TAG = "AudioReceiver";

    private final FairPlaySession session;
    private final MirrorListener listener;

    private DatagramSocket data;
    private DatagramSocket control;
    private volatile boolean running;
    private int lastSeq = -1;

    AudioReceiver(FairPlaySession session, MirrorListener listener) {
        this.session = session;
        this.listener = listener;
    }

    /** @return {dataPort, controlPort} */
    int[] start() throws SocketException {
        data = new DatagramSocket(0);
        data.setReceiveBufferSize(512 * 1024);
        control = new DatagramSocket(0);
        running = true;
        new Thread(new Runnable() {
            @Override
            public void run() {
                dataLoop();
            }
        }, "audio-data").start();
        new Thread(new Runnable() {
            @Override
            public void run() {
                controlLoop();
            }
        }, "audio-control").start();
        return new int[]{data.getLocalPort(), control.getLocalPort()};
    }

    void stop() {
        running = false;
        if (data != null) {
            data.close();
        }
        if (control != null) {
            control.close();
        }
    }

    private void dataLoop() {
        byte[] buf = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                packet.setLength(buf.length);
                data.receive(packet);
                handleRtp(buf, 0, packet.getLength());
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "audio receive: " + e);
                }
            } catch (Exception e) {
                Log.e(TAG, "audio packet failed", e);
            }
        }
    }

    private void controlLoop() {
        byte[] buf = new byte[4096];
        DatagramPacket packet = new DatagramPacket(buf, buf.length);
        while (running) {
            try {
                packet.setLength(buf.length);
                control.receive(packet);
                int type = buf[1] & 0x7f;
                // 0x56: retransmitted audio packet wrapped in a 4-byte header
                if (type == 0x56 && packet.getLength() > 16) {
                    handleRtp(buf, 4, packet.getLength() - 4);
                }
            } catch (IOException e) {
                if (running) {
                    Log.w(TAG, "control receive: " + e);
                }
            } catch (Exception e) {
                Log.e(TAG, "control packet failed", e);
            }
        }
    }

    private synchronized void handleRtp(byte[] buf, int off, int len) throws Exception {
        if (len <= 12) {
            return;
        }
        int seq = (buf[off + 2] & 0xff) << 8 | (buf[off + 3] & 0xff);
        if (lastSeq >= 0) {
            int diff = (seq - lastSeq) & 0xffff;
            if (diff == 0 || diff > 0x8000) {
                return; // duplicate or late
            }
        }
        lastSeq = seq;
        com.groundscreen.tv.util.DiagLog.audioPackets++;
        int payloadLen = len - 12;
        byte[] audio = new byte[payloadLen];
        System.arraycopy(buf, off + 12, audio, 0, payloadLen);
        session.decryptAudio(audio, payloadLen);
        listener.onAudioFrame(audio, 0, payloadLen);
    }
}
