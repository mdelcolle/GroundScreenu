package com.groundscreen.tv;

import android.content.Context;
import android.content.SharedPreferences;
import android.util.Base64;

import java.security.SecureRandom;
import java.util.Locale;

/** Persistent receiver settings: name, stable device id and pairing key, quality. */
public final class Prefs {

    public static final String DEFAULT_NAME = "GroundScreen TV";

    /** width, height, fps */
    public static final int[][] QUALITIES = {{1920, 1080, 60}, {1920, 1080, 30}, {1280, 720, 30}};

    private static final String FILE = "groundscreen";
    private final SharedPreferences sp;

    public Prefs(Context c) {
        sp = c.getApplicationContext().getSharedPreferences(FILE, Context.MODE_PRIVATE);
    }

    public String name() {
        return sp.getString("name", DEFAULT_NAME);
    }

    public void setName(String name) {
        sp.edit().putString("name", name).apply();
    }

    public int qualityIndex() {
        int q = sp.getInt("quality", 0);
        return q >= 0 && q < QUALITIES.length ? q : 0;
    }

    public void setQualityIndex(int q) {
        sp.edit().putInt("quality", q).apply();
    }

    public boolean startOnBoot() {
        return sp.getBoolean("boot", true);
    }

    public void setStartOnBoot(boolean b) {
        sp.edit().putBoolean("boot", b).apply();
    }

    /** Random but stable locally-administered MAC used as AirPlay deviceid. */
    public String deviceId() {
        String id = sp.getString("deviceid", null);
        if (id == null) {
            byte[] mac = new byte[6];
            new SecureRandom().nextBytes(mac);
            mac[0] = (byte) ((mac[0] & 0xFC) | 0x02); // unicast, locally administered
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < 6; i++) {
                if (i > 0) {
                    sb.append(':');
                }
                sb.append(String.format(Locale.US, "%02X", mac[i] & 0xff));
            }
            id = sb.toString();
            sp.edit().putString("deviceid", id).apply();
        }
        return id;
    }

    /** 32-byte Ed25519 seed so the advertised public key never changes. */
    public byte[] pairingSeed() {
        String s = sp.getString("seed", null);
        if (s != null) {
            byte[] seed = Base64.decode(s, Base64.NO_WRAP);
            if (seed.length == 32) {
                return seed;
            }
        }
        byte[] seed = new byte[32];
        new SecureRandom().nextBytes(seed);
        sp.edit().putString("seed", Base64.encodeToString(seed, Base64.NO_WRAP)).apply();
        return seed;
    }
}
