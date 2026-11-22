package com.groundscreen.tv.media;

/** H.264 Annex-B helpers without Android dependencies. */
public final class H264Util {

    private H264Util() {
    }

    /**
     * Reads the cropped picture size from an SPS (Annex-B start code allowed).
     * Adapted from java-airplay's H264SpsDimensions (MIT).
     *
     * @return {width, height} or null if it cannot be parsed
     */
    public static int[] spsSize(byte[] sps) {
        int start = 0;
        while (start + 3 < sps.length && !(sps[start] == 0 && sps[start + 1] == 0 && sps[start + 2] == 1)) {
            start++;
        }
        start = start + 3 < sps.length ? start + 3 : 0;
        if (start >= sps.length || (sps[start] & 0x1f) != 7) {
            return null;
        }
        java.io.ByteArrayOutputStream rbsp = new java.io.ByteArrayOutputStream(sps.length);
        for (int i = start + 1; i < sps.length; i++) {
            if (i + 2 < sps.length && sps[i] == 0 && sps[i + 1] == 0 && sps[i + 2] == 3) {
                rbsp.write(0);
                rbsp.write(0);
                i += 2;
            } else {
                rbsp.write(sps[i]);
            }
        }
        try {
            Bits br = new Bits(rbsp.toByteArray());
            int profileIdc = br.bits(8);
            br.bits(16);
            br.ue();
            int chroma = 1;
            if (profileIdc == 100 || profileIdc == 110 || profileIdc == 122 || profileIdc == 244
                    || profileIdc == 44 || profileIdc == 83 || profileIdc == 86 || profileIdc == 118
                    || profileIdc == 128 || profileIdc == 138 || profileIdc == 139 || profileIdc == 134
                    || profileIdc == 153) {
                chroma = br.ue();
                if (chroma == 3) {
                    br.bit();
                }
                br.ue();
                br.ue();
                br.bit();
                if (br.bit() == 1) {
                    int limit = chroma == 3 ? 12 : 8;
                    for (int i = 0; i < limit; i++) {
                        if (br.bit() == 1) {
                            int size = i < 6 ? 16 : 64;
                            int last = 8;
                            int next = 8;
                            for (int j = 0; j < size; j++) {
                                if (next != 0) {
                                    next = (last + br.se() + 256) % 256;
                                }
                                if (next != 0) {
                                    last = next;
                                }
                            }
                        }
                    }
                }
            }
            br.ue();
            int pocType = br.ue();
            if (pocType == 0) {
                br.ue();
            } else if (pocType == 1) {
                br.bit();
                br.se();
                br.se();
                int num = br.ue();
                for (int i = 0; i < num; i++) {
                    br.se();
                }
            }
            br.ue();
            br.bit();
            int wMbs = br.ue() + 1;
            int hMap = br.ue() + 1;
            int frameMbsOnly = br.bit();
            if (frameMbsOnly == 0) {
                br.bit();
            }
            br.bit();
            int cl = 0, cr = 0, ct = 0, cb = 0;
            if (br.bit() == 1) {
                cl = br.ue();
                cr = br.ue();
                ct = br.ue();
                cb = br.ue();
            }
            int subW = chroma == 1 || chroma == 2 ? 2 : 1;
            int subH = chroma == 1 ? 2 : 1;
            int w = wMbs * 16 - (cl + cr) * subW;
            int h = (2 - frameMbsOnly) * hMap * 16 - (ct + cb) * subH * (2 - frameMbsOnly);
            return w > 0 && h > 0 ? new int[]{w, h} : null;
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static final class Bits {
        private final byte[] d;
        private int pos;

        Bits(byte[] d) {
            this.d = d;
        }

        int bit() {
            if (pos >= d.length * 8) {
                throw new IllegalStateException("eof");
            }
            int v = (d[pos >> 3] >> (7 - (pos & 7))) & 1;
            pos++;
            return v;
        }

        int bits(int n) {
            int v = 0;
            for (int i = 0; i < n; i++) {
                v = (v << 1) | bit();
            }
            return v;
        }

        int ue() {
            int zeros = 0;
            while (bit() == 0) {
                if (++zeros > 31) {
                    throw new IllegalStateException("ue");
                }
            }
            return (1 << zeros) - 1 + (zeros > 0 ? bits(zeros) : 0);
        }

        int se() {
            int k = ue() + 1;
            return (k % 2 == 0) ? k / 2 : -(k / 2);
        }
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
