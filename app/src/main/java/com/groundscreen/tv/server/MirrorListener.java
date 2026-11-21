package com.groundscreen.tv.server;

import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;

/** Callbacks from the AirPlay receiver into the playback layer. Called from network threads. */
public interface MirrorListener {

    /** A sender opened a control connection (pairing started). */
    void onClientConnected(String clientAddress);

    /** Screen mirroring stream SETUP was accepted. */
    void onMirrorStarted();

    /** Annex-B SPS + PPS (each prefixed with 00 00 00 01). */
    void onVideoConfig(byte[] sps, byte[] pps);

    /** One decrypted Annex-B access unit. */
    void onVideoFrame(byte[] annexB, long ntpTimestamp);

    void onAudioFormat(AudioStreamInfo info);

    /** Decrypted compressed audio frame (AAC-ELD / AAC / ALAC). */
    void onAudioFrame(byte[] data, int offset, int length);

    /** Linear volume 0..1. */
    void onVolume(float volume);

    void onAudioStopped();

    void onMirrorStopped();

    /** The sender went away (control connection closed). */
    void onClientDisconnected();
}
