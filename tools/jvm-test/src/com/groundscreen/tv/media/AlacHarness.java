package com.groundscreen.tv.media;

import java.io.DataInputStream;
import java.io.EOFException;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

/** Decodes cookie + .lp ALAC frames and compares with reference s16le PCM (from ffmpeg). */
public final class AlacHarness {
    public static void main(String[] a) throws Exception {
        byte[] cookie = Files.readAllBytes(Paths.get(a[0]));
        byte[] ref = Files.readAllBytes(Paths.get(a[2]));
        AlacDecoder dec = new AlacDecoder(cookie);
        byte[] out = new byte[dec.maxOutputBytes()];
        DataInputStream in = new DataInputStream(new FileInputStream(a[1]));
        int off = 0, frames = 0, diff = 0;
        long t0 = System.nanoTime();
        while (true) {
            int len;
            try {
                len = in.readInt();
            } catch (EOFException e) {
                break;
            }
            byte[] f = new byte[len];
            in.readFully(f);
            int n = dec.decode(f, 0, len, out);
            for (int i = 0; i < n && off + i < ref.length; i++) {
                if (out[i] != ref[off + i]) {
                    diff++;
                }
            }
            off += n;
            frames++;
        }
        long ms = (System.nanoTime() - t0) / 1_000_000;
        boolean ok = diff == 0 && off == ref.length;
        System.out.println((ok ? "PASS" : "FAIL") + " ALAC " + frames + " frames, " + off + "/" + ref.length
                + " bytes, " + diff + " differing bytes, " + ms + " ms");
        System.exit(ok ? 0 : 1);
    }
}
