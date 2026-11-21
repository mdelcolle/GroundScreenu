package com.groundscreen.tv.media;

/** H.264 Annex-B helpers without Android dependencies. */
public final class H264Util {

    private H264Util() {
    }

    /** True if the access unit contains an IDR slice (NAL type 5) before any non-IDR slice. */
    public static boolean containsIdr(byte[] d) {
        int n = d.length;
        for (int i = 0; i + 3 < n; i++) {
            if (d[i] == 0 && d[i + 1] == 0 && d[i + 2] == 1) {
                int type = d[i + 3] & 0x1f;
                if (type == 5) {
                    return true;
                }
                if (type == 1) {
                    return false;
                }
                i += 3;
            }
        }
        return false;
    }
}
