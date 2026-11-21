package com.groundscreen.tv.server;

/**
 * Feature bits advertised in mDNS and {@code GET /info}: screen mirroring with FairPlay,
 * legacy pairing and RAOP audio (no HLS video URL playback).
 * Bit selection follows java-airplay (MIT) with HLS disabled.
 */
final class AirPlayFeatures {

    static final int VIDEO_FAIRPLAY = 2;
    static final int SCREEN = 7;
    static final int AUDIO = 9;
    static final int FPSAP_V2_5_AES_GCM = 12;
    static final int AUTHENTICATION_4 = 14;
    static final int AUDIO_FORMAT_2 = 19; // ALAC
    static final int AUDIO_FORMAT_3 = 20; // AAC-LC
    static final int AUDIO_FORMAT_4 = 21; // AAC-ELD
    static final int SUPPORTS_LEGACY_PAIRING = 27;
    static final int RAOP = 30;

    static final long MASK = bit(VIDEO_FAIRPLAY) | bit(SCREEN) | bit(AUDIO) | bit(FPSAP_V2_5_AES_GCM)
            | bit(AUTHENTICATION_4) | bit(AUDIO_FORMAT_2) | bit(AUDIO_FORMAT_3) | bit(AUDIO_FORMAT_4)
            | bit(SUPPORTS_LEGACY_PAIRING) | bit(RAOP);

    static final String SOURCE_VERSION = "220.68";
    static final String MODEL = "AppleTV3,2";
    static final String FLAGS = "0x44";
    static final int STATUS_FLAGS = 0x44;

    private AirPlayFeatures() {
    }

    private static long bit(int b) {
        return 1L << b;
    }

    static String txt() {
        return String.format("0x%X,0x%X", (int) MASK, (int) (MASK >>> 32));
    }
}
