package com.groundscreen.tv.util;

import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.math.BigInteger;

/** Small stream helpers (Android API 21 has no InputStream.readAllBytes). */
public final class IoUtil {

    private IoUtil() {
    }

    public static byte[] readAll(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        byte[] buf = new byte[4096];
        int n;
        while ((n = in.read(buf)) != -1) {
            out.write(buf, 0, n);
        }
        return out.toByteArray();
    }

    public static void readFully(InputStream in, byte[] dst, int off, int len) throws IOException {
        while (len > 0) {
            int n = in.read(dst, off, len);
            if (n < 0) {
                throw new EOFException();
            }
            off += n;
            len -= n;
        }
    }

    public static String unsignedLongToString(long v) {
        if (v >= 0) {
            return Long.toString(v);
        }
        return BigInteger.valueOf(v).add(BigInteger.ONE.shiftLeft(64)).toString();
    }

    public static String hex(byte[] data) {
        StringBuilder sb = new StringBuilder(data.length * 2);
        for (byte b : data) {
            sb.append(Character.forDigit((b >> 4) & 0xf, 16)).append(Character.forDigit(b & 0xf, 16));
        }
        return sb.toString();
    }
}
