package com.github.serezhka.airplay.protocol;

import com.github.serezhka.airplay.protocol.fairplay.FairPlayAudioDecryptor;
import com.github.serezhka.airplay.protocol.fairplay.FairPlayHandshake;
import com.github.serezhka.airplay.protocol.fairplay.FairPlayVideoDecryptor;
import com.github.serezhka.airplay.protocol.media.MediaStreamInfo;
import com.github.serezhka.airplay.protocol.media.VideoStreamInfo;
import com.github.serezhka.airplay.protocol.pairing.PairingHandshake;
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;
import com.github.serezhka.airplay.protocol.rtsp.RtspMediaSetup;

import java.io.InputStream;
import java.io.OutputStream;

/**
 * Crypto and RTSP media-setup state for one AirPlay connection.
 * Adapted from java-airplay (MIT, Sergei Fedorov) for Android (no Optional / Java 9 APIs).
 */
public final class FairPlaySession {

    private final PairingHandshake pairing;
    private final FairPlayHandshake fairPlay = new FairPlayHandshake();
    private final RtspMediaSetup rtsp = new RtspMediaSetup();

    private FairPlayVideoDecryptor fairPlayVideoDecryptor;
    private FairPlayAudioDecryptor fairPlayAudioDecryptor;

    public FairPlaySession(PairingIdentity identity) {
        this.pairing = new PairingHandshake(identity);
    }

    public void pairSetup(OutputStream out) throws Exception {
        pairing.pairSetup(out);
    }

    public void pairVerify(InputStream in, OutputStream out) throws Exception {
        pairing.pairVerify(in, out);
    }

    public boolean isPairVerified() {
        return pairing.isPairVerified();
    }

    public void fairPlaySetup(InputStream in, OutputStream out) throws Exception {
        fairPlay.fairPlaySetup(in, out);
    }

    public synchronized MediaStreamInfo rtspSetup(byte[] body) throws Exception {
        MediaStreamInfo info = rtsp.setup(body);
        if (info instanceof VideoStreamInfo) {
            fairPlayVideoDecryptor = null;
        }
        return info;
    }

    public synchronized MediaStreamInfo rtspTeardown(byte[] body) throws Exception {
        MediaStreamInfo info = rtsp.teardown(body);
        if (info instanceof VideoStreamInfo) {
            fairPlayVideoDecryptor = null;
        }
        return info;
    }

    public byte[] getFairPlayAesKey() {
        return fairPlay.decryptAesKey(rtsp.getEkey());
    }

    public boolean isFairPlayVideoDecryptorReady() {
        return rtsp.getEkey() != null && rtsp.getStreamConnectionID() != null;
    }

    public boolean isFairPlayAudioDecryptorReady() {
        return rtsp.getEkey() != null && rtsp.getEiv() != null;
    }

    /** Creates a fresh decryptor for one mirror TCP connection (CTR state is per stream). */
    public synchronized FairPlayVideoDecryptor newVideoDecryptor() throws Exception {
        if (!isFairPlayVideoDecryptorReady()) {
            throw new IllegalStateException("FairPlayVideoDecryptor not ready!");
        }
        byte[] secret = pairing.getSharedSecret() != null ? pairing.getSharedSecret() : new byte[0];
        fairPlayVideoDecryptor = new FairPlayVideoDecryptor(getFairPlayAesKey(), secret, rtsp.getStreamConnectionID());
        return fairPlayVideoDecryptor;
    }

    public synchronized void decryptAudio(byte[] audio, int audioLength) throws Exception {
        if (fairPlayAudioDecryptor == null) {
            if (!isFairPlayAudioDecryptorReady()) {
                throw new IllegalStateException("FairPlayAudioDecryptor not ready!");
            }
            byte[] secret = pairing.getSharedSecret() != null ? pairing.getSharedSecret() : new byte[0];
            fairPlayAudioDecryptor = new FairPlayAudioDecryptor(getFairPlayAesKey(), rtsp.getEiv(), secret);
        }
        fairPlayAudioDecryptor.decrypt(audio, audioLength);
    }
}
