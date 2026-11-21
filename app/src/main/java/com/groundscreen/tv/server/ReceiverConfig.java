package com.groundscreen.tv.server;

import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;

/** Immutable receiver settings. */
public final class ReceiverConfig {

    public final String name;
    /** Pseudo MAC address "AA:BB:CC:DD:EE:FF", used as AirPlay deviceid. */
    public final String deviceId;
    public final int width;
    public final int height;
    public final int fps;
    public final PairingIdentity identity;

    public ReceiverConfig(String name, String deviceId, int width, int height, int fps, PairingIdentity identity) {
        this.name = name;
        this.deviceId = deviceId;
        this.width = width;
        this.height = height;
        this.fps = fps;
        this.identity = identity;
    }
}
