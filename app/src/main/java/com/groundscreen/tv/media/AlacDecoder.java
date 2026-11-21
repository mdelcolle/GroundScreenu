package com.groundscreen.tv.media;

/**
 * Pure-Java Apple Lossless (ALAC) decoder producing interleaved 16-bit little-endian PCM.
 * <p>
 * Port of Apple's ALAC reference decoder (ALACDecoder.cpp, ag_dec.c, dp_dec.c, matrix_dec.c;
 * Copyright (c) 2011 Apple Inc., Apache License 2.0, https://github.com/macosforge/alac),
 * following the structure of the audiojs "alac.js" port (Apache-2.0).
 * Needed because Android's MediaCodec only gained an ALAC decoder in recent releases.
 */
public final class AlacDecoder {

    private static final int QBSHIFT = 9;
    private static final int QB = 1 << QBSHIFT;
    private static final int MMULSHIFT = 2;
    private static final int MDENSHIFT = QBSHIFT - MMULSHIFT - 1;
    private static final int MOFF = 1 << (MDENSHIFT - 2);
    private static final int BITOFF = 24;
    private static final int MAX_PREFIX_16 = 9;
    private static final int MAX_PREFIX_32 = 9;
    private static final int MAX_DATATYPE_BITS_16 = 16;
    private static final int N_MAX_MEAN_CLAMP = 0xffff;
    private static final int N_MEAN_CLAMP_VAL = 0xffff;

    private static final int ID_SCE = 0;
    private static final int ID_CPE = 1;
    private static final int ID_LFE = 3;
    private static final int ID_DSE = 4;
    private static final int ID_FIL = 6;
    private static final int ID_END = 7;

    private final int frameLength;
    private final int bitDepth;
    private final int pbCfg;
    private final int mbCfg;
    private final int kbCfg;
    private final int numChannels;

    private final int[] mixU;
    private final int[] mixV;
    private final int[] pred;
    private final int[] shiftBuf;
    private final int[][] chans;

    // bit reader state
    private byte[] d;
    private int pos;
    private int realLen;

    /** @param cookie 24-byte ALACSpecificConfig */
    public AlacDecoder(byte[] cookie) {
        frameLength = u32(cookie, 0);
        bitDepth = cookie[5] & 0xff;
        pbCfg = cookie[6] & 0xff;
        mbCfg = cookie[7] & 0xff;
        kbCfg = cookie[8] & 0xff;
        numChannels = Math.max(1, Math.min(2, cookie[9] & 0xff));
        if (frameLength <= 0 || frameLength > 16384 || bitDepth != 16) {
            throw new IllegalArgumentException("Unsupported ALAC config: frame=" + frameLength + " bits=" + bitDepth);
        }
        mixU = new int[frameLength];
        mixV = new int[frameLength];
        pred = new int[frameLength];
        shiftBuf = new int[frameLength * 2];
        chans = new int[numChannels][frameLength];
    }

    public int channels() {
        return numChannels;
    }

    public int maxOutputBytes() {
        return frameLength * numChannels * 2;
    }

    /**
     * Decodes one frame into {@code out} (interleaved s16le).
     *
     * @return number of bytes written
     */
    public int decode(byte[] frame, int off, int len, byte[] out) {
        d = new byte[len + 8];
        System.arraycopy(frame, off, d, 0, len);
        pos = 0;
        realLen = len;
        int numSamples = decodeElements(frameLength);
        int o = 0;
        for (int i = 0; i < numSamples; i++) {
            for (int c = 0; c < numChannels; c++) {
                int s = chans[c][i];
                out[o++] = (byte) s;
                out[o++] = (byte) (s >> 8);
            }
        }
        return o;
    }

    // ------------------------------------------------------------------ bit reading

    private static int u32(byte[] b, int o) {
        return (b[o] & 0xff) << 24 | (b[o + 1] & 0xff) << 16 | (b[o + 2] & 0xff) << 8 | (b[o + 3] & 0xff);
    }

    private int read(int n) {
        int p = pos;
        int o = p >> 3;
        int v = ((((d[o] & 0xff) << 16) | ((d[o + 1] & 0xff) << 8) | (d[o + 2] & 0xff)) << (p & 7)) & 0xFFFFFF;
        pos = p + n;
        return v >>> (24 - n);
    }

    private int readOne() {
        int p = pos;
        pos = p + 1;
        return ((d[p >> 3] & 0xff) >> (7 - (p & 7))) & 1;
    }

    private void byteAlign() {
        pos = (pos + 7) & ~7;
    }

    private int read32At(int byteOffset) {
        return u32(d, byteOffset);
    }

    private int getStreamBits(int bitoffset, int numbits) {
        int byteoffset = bitoffset >>> 3;
        int load1 = read32At(byteoffset);
        int result;
        if ((numbits + (bitoffset & 7)) > 32) {
            result = load1 << (bitoffset & 7);
            int load2 = d[byteoffset + 4] & 0xff;
            load2 = load2 >>> (8 - (numbits + (bitoffset & 7) - 32));
            result = result >>> (32 - numbits);
            result = result | load2;
        } else {
            result = load1 >>> (32 - numbits - (bitoffset & 7));
        }
        if (numbits != 32) {
            result = result & ((1 << numbits) - 1);
        }
        return result;
    }

    // ------------------------------------------------------------------ adaptive Golomb (ag_dec.c)

    private static int lg3a(int x) {
        return 31 - Integer.numberOfLeadingZeros(x + 3);
    }

    private int bitPos; // local position used by the entropy decoder

    private int dynGet32(int m, int k, int maxbits) {
        int tempbits = bitPos;
        int streamlong = read32At(tempbits >> 3) << (tempbits & 7);
        int result = Integer.numberOfLeadingZeros(~streamlong);
        if (result >= MAX_PREFIX_32) {
            result = getStreamBits(tempbits + MAX_PREFIX_32, maxbits);
            tempbits += MAX_PREFIX_32 + maxbits;
        } else {
            tempbits += result + 1;
            if (k != 1) {
                streamlong = streamlong << (result + 1);
                int v = streamlong >>> (32 - k);
                tempbits += k - 1;
                result = result * m;
                if (v >= 2) {
                    result += v - 1;
                    tempbits += 1;
                }
            }
        }
        bitPos = tempbits;
        return result;
    }

    private int dynGet(int m, int k) {
        int tempbits = bitPos;
        int streamlong = read32At(tempbits >> 3) << (tempbits & 7);
        int pre = Integer.numberOfLeadingZeros(~streamlong);
        int result;
        if (pre >= MAX_PREFIX_16) {
            pre = MAX_PREFIX_16;
            tempbits += pre;
            streamlong = streamlong << pre;
            result = streamlong >>> (32 - MAX_DATATYPE_BITS_16);
            tempbits += MAX_DATATYPE_BITS_16;
        } else {
            tempbits += pre + 1;
            streamlong = streamlong << (pre + 1);
            int v = streamlong >>> (32 - k);
            tempbits += k;
            result = pre * m + v - 1;
            if (v < 2) {
                result -= (v - 1);
                tempbits -= 1;
            }
        }
        bitPos = tempbits;
        return result;
    }

    private void dynDecomp(int pbFactor, int[] pc, int numSamples, int maxSize) {
        bitPos = pos;
        int maxPos = realLen * 8;
        int mb = mbCfg;
        int pb = (pbCfg * pbFactor) / 4;
        int kb = kbCfg;
        int wb = (1 << kbCfg) - 1;
        int zmode = 0;
        int c = 0;
        int o = 0;
        while (c < numSamples) {
            if (bitPos >= maxPos) {
                throw new IllegalStateException("ALAC: ran past frame");
            }
            int m = mb >>> QBSHIFT;
            int k = Math.min(lg3a(m), kb);
            m = (1 << k) - 1;
            int n = dynGet32(m, k, maxSize);
            int ndecode = n + zmode;
            int mult = (-(ndecode & 1)) | 1;
            pc[o++] = ((ndecode + 1) >> 1) * mult;
            c++;
            mb = pb * (n + zmode) + mb - ((pb * mb) >>> QBSHIFT);
            if (n > N_MAX_MEAN_CLAMP) {
                mb = N_MEAN_CLAMP_VAL;
            }
            zmode = 0;
            if (((mb << MMULSHIFT) < QB) && (c < numSamples)) {
                zmode = 1;
                k = Integer.numberOfLeadingZeros(mb) - BITOFF + ((mb + MOFF) >> MDENSHIFT);
                int mz = ((1 << k) - 1) & wb;
                n = dynGet(mz, k);
                if (c + n > numSamples) {
                    throw new IllegalStateException("ALAC: zero-run overflow");
                }
                for (int j = 0; j < n; j++) {
                    pc[o++] = 0;
                    c++;
                }
                if (n >= 65535) {
                    zmode = 0;
                }
                mb = 0;
            }
        }
        pos = bitPos;
    }

    // ------------------------------------------------------------------ predictor (dp_dec.c)

    private static int signOf(int i) {
        return ((-i) >>> 31) | (i >> 31);
    }

    private static void unpcBlock(int[] pc1, int[] out, int num, short[] coefs, int numactive, int chanbits,
                                  int denshift) {
        int chanshift = 32 - chanbits;
        int denhalf = denshift > 0 ? 1 << (denshift - 1) : 0;
        out[0] = pc1[0];
        if (numactive == 0) {
            if (num > 1) {
                System.arraycopy(pc1, 1, out, 1, num - 1);
            }
            return;
        }
        if (numactive == 31) {
            int prev = out[0];
            for (int j = 1; j < num; j++) {
                int del = pc1[j] + prev;
                prev = (del << chanshift) >> chanshift;
                out[j] = prev;
            }
            return;
        }
        for (int j = 1; j <= numactive && j < num; j++) {
            int del = pc1[j] + out[j - 1];
            out[j] = (del << chanshift) >> chanshift;
        }
        int lim = numactive + 1;
        for (int j = lim; j < num; j++) {
            int sum1 = 0;
            int pb = j - 1;
            int top = out[j - lim];
            for (int kk = 0; kk < numactive; kk++) {
                sum1 += coefs[kk] * (out[pb - kk] - top);
            }
            int del = pc1[j];
            int del0 = del;
            int sg = signOf(del);
            del = del + top + ((sum1 + denhalf) >> denshift);
            out[j] = (del << chanshift) >> chanshift;
            if (sg > 0) {
                for (int kk = numactive - 1; kk >= 0; kk--) {
                    int dd = top - out[pb - kk];
                    int sgn = signOf(dd);
                    coefs[kk] = (short) (coefs[kk] - sgn);
                    del0 -= (numactive - kk) * ((sgn * dd) >> denshift);
                    if (del0 <= 0) {
                        break;
                    }
                }
            } else if (sg < 0) {
                for (int kk = numactive - 1; kk >= 0; kk--) {
                    int dd = top - out[pb - kk];
                    int sgn = signOf(dd);
                    coefs[kk] = (short) (coefs[kk] + sgn);
                    del0 -= (numactive - kk) * ((-sgn * dd) >> denshift);
                    if (del0 >= 0) {
                        break;
                    }
                }
            }
        }
    }

    // ------------------------------------------------------------------ elements (ALACDecoder.cpp)

    private int decodeElements(int requested) {
        int chanIndex = 0;
        int numSamples = requested;
        while (true) {
            if ((pos >> 3) >= realLen) {
                break;
            }
            int tag = read(3);
            if (tag == ID_SCE || tag == ID_LFE) {
                read(4);
                if (read(12) != 0) {
                    throw new IllegalStateException("ALAC: bad header");
                }
                int hb = read(4);
                int partial = hb >> 3;
                int bytesShifted = (hb >> 1) & 3;
                int escape = hb & 1;
                if (bytesShifted == 3) {
                    throw new IllegalStateException("ALAC: bad shift");
                }
                int shift = bytesShifted * 8;
                int chanBits = bitDepth - shift;
                if (partial != 0) {
                    numSamples = (read(16) << 16) | read(16);
                    if (numSamples > frameLength) {
                        throw new IllegalStateException("ALAC: too many samples");
                    }
                }
                if (escape == 0) {
                    read(8);
                    read(8);
                    int hu = read(8);
                    int modeU = hu >> 4;
                    int denShiftU = hu & 0xf;
                    int pu = read(8);
                    int pbFactorU = pu >> 5;
                    int numU = pu & 0x1f;
                    short[] coefsU = new short[32];
                    for (int i = 0; i < numU; i++) {
                        coefsU[i] = (short) read(16);
                    }
                    int shiftPos = pos;
                    if (bytesShifted != 0) {
                        pos += shift * numSamples;
                    }
                    dynDecomp(pbFactorU, pred, numSamples, chanBits);
                    if (modeU == 0) {
                        unpcBlock(pred, mixU, numSamples, coefsU, numU, chanBits, denShiftU);
                    } else {
                        unpcBlock(pred, pred, numSamples, null, 31, chanBits, 0);
                        unpcBlock(pred, mixU, numSamples, coefsU, numU, chanBits, denShiftU);
                    }
                    readShift(shiftPos, shift, numSamples, 1);
                } else {
                    int s = 32 - chanBits;
                    for (int i = 0; i < numSamples; i++) {
                        mixU[i] = (read(chanBits) << s) >> s;
                    }
                    bytesShifted = 0;
                }
                if (chanIndex >= numChannels) {
                    break;
                }
                int[] out = chans[chanIndex++];
                for (int i = 0; i < numSamples; i++) {
                    out[i] = bytesShifted != 0 ? ((mixU[i] << shift) | shiftBuf[i]) : mixU[i];
                }
            } else if (tag == ID_CPE) {
                read(4);
                if (read(12) != 0) {
                    throw new IllegalStateException("ALAC: bad header");
                }
                int hb = read(4);
                int partial = hb >> 3;
                int bytesShifted = (hb >> 1) & 3;
                int escape = hb & 1;
                if (bytesShifted == 3) {
                    throw new IllegalStateException("ALAC: bad shift");
                }
                int shift = bytesShifted * 8;
                int chanBits = bitDepth - shift + 1;
                if (partial != 0) {
                    numSamples = (read(16) << 16) | read(16);
                    if (numSamples > frameLength) {
                        throw new IllegalStateException("ALAC: too many samples");
                    }
                }
                int mixBits = 0;
                int mixRes = 0;
                if (escape == 0) {
                    mixBits = read(8);
                    mixRes = (read(8) << 24) >> 24;
                    int hu = read(8);
                    int modeU = hu >> 4;
                    int denShiftU = hu & 0xf;
                    int pu = read(8);
                    int pbFactorU = pu >> 5;
                    int numU = pu & 0x1f;
                    short[] coefsU = new short[32];
                    for (int i = 0; i < numU; i++) {
                        coefsU[i] = (short) read(16);
                    }
                    int hv = read(8);
                    int modeV = hv >> 4;
                    int denShiftV = hv & 0xf;
                    int pv = read(8);
                    int pbFactorV = pv >> 5;
                    int numV = pv & 0x1f;
                    short[] coefsV = new short[32];
                    for (int i = 0; i < numV; i++) {
                        coefsV[i] = (short) read(16);
                    }
                    int shiftPos = pos;
                    if (bytesShifted != 0) {
                        pos += shift * 2 * numSamples;
                    }
                    dynDecomp(pbFactorU, pred, numSamples, chanBits);
                    if (modeU == 0) {
                        unpcBlock(pred, mixU, numSamples, coefsU, numU, chanBits, denShiftU);
                    } else {
                        unpcBlock(pred, pred, numSamples, null, 31, chanBits, 0);
                        unpcBlock(pred, mixU, numSamples, coefsU, numU, chanBits, denShiftU);
                    }
                    dynDecomp(pbFactorV, pred, numSamples, chanBits);
                    if (modeV == 0) {
                        unpcBlock(pred, mixV, numSamples, coefsV, numV, chanBits, denShiftV);
                    } else {
                        unpcBlock(pred, pred, numSamples, null, 31, chanBits, 0);
                        unpcBlock(pred, mixV, numSamples, coefsV, numV, chanBits, denShiftV);
                    }
                    readShift(shiftPos, shift, numSamples, 2);
                } else {
                    chanBits = bitDepth;
                    int s = 32 - chanBits;
                    for (int i = 0; i < numSamples; i++) {
                        mixU[i] = (read(chanBits) << s) >> s;
                        mixV[i] = (read(chanBits) << s) >> s;
                    }
                    bytesShifted = 0;
                }
                int[] l = chans[chanIndex++ % numChannels];
                int[] r = numChannels > 1 ? chans[chanIndex++] : null;
                for (int j = 0; j < numSamples; j++) {
                    int a;
                    int b;
                    if (mixRes != 0) {
                        a = mixU[j] + mixV[j] - ((mixRes * mixV[j]) >> mixBits);
                        b = a - mixV[j];
                    } else {
                        a = mixU[j];
                        b = mixV[j];
                    }
                    if (bytesShifted != 0) {
                        a = (a << shift) | shiftBuf[2 * j];
                        b = (b << shift) | shiftBuf[2 * j + 1];
                    }
                    l[j] = a;
                    if (r != null) {
                        r[j] = b;
                    }
                }
            } else if (tag == ID_DSE) {
                read(4);
                int align = readOne();
                int count = read(8);
                if (count == 255) {
                    count += read(8);
                }
                if (align != 0) {
                    byteAlign();
                }
                pos += count * 8;
            } else if (tag == ID_FIL) {
                int count = read(4);
                if (count == 15) {
                    count += read(8) - 1;
                }
                pos += count * 8;
            } else if (tag == ID_END) {
                byteAlign();
                break;
            } else {
                throw new IllegalStateException("ALAC: unsupported element " + tag);
            }
            if (chanIndex >= numChannels) {
                break;
            }
        }
        return numSamples;
    }

    private void readShift(int shiftPos, int shift, int numSamples, int nch) {
        if (shift == 0) {
            return;
        }
        int saved = pos;
        pos = shiftPos;
        int count = numSamples * nch;
        for (int i = 0; i < count; i++) {
            shiftBuf[i] = read(shift);
        }
        pos = saved;
    }
}
