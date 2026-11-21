package com.groundscreen.tv.crypto;

import java.math.BigInteger;
import java.security.SecureRandom;

/**
 * X25519 Diffie-Hellman (RFC 7748, section 5) with a constant-sequence Montgomery ladder.
 * Uses BigInteger: a few milliseconds per operation, which is fine for one key exchange
 * per AirPlay connection, and avoids bundling a GPL-licensed native/Java library.
 */
public final class X25519 {

    private static final BigInteger P = BigInteger.ONE.shiftLeft(255).subtract(BigInteger.valueOf(19));
    private static final BigInteger A24 = BigInteger.valueOf(121665);
    private static final byte[] BASE_POINT = new byte[32];

    static {
        BASE_POINT[0] = 9;
    }

    private X25519() {
    }

    public static byte[] generatePrivateKey(SecureRandom random) {
        byte[] k = new byte[32];
        random.nextBytes(k);
        return k;
    }

    public static byte[] publicKey(byte[] privateKey) {
        return scalarMult(privateKey, BASE_POINT);
    }

    public static byte[] sharedSecret(byte[] privateKey, byte[] peerPublicKey) {
        return scalarMult(privateKey, peerPublicKey);
    }

    public static byte[] scalarMult(byte[] scalar, byte[] uCoordinate) {
        byte[] k = scalar.clone();
        k[0] &= (byte) 248;
        k[31] &= 127;
        k[31] |= 64;
        BigInteger kn = decodeLittleEndian(k);
        byte[] u = uCoordinate.clone();
        u[31] &= 127;
        BigInteger x1 = decodeLittleEndian(u).mod(P);

        BigInteger x2 = BigInteger.ONE;
        BigInteger z2 = BigInteger.ZERO;
        BigInteger x3 = x1;
        BigInteger z3 = BigInteger.ONE;
        int swap = 0;
        for (int t = 254; t >= 0; t--) {
            int kt = kn.testBit(t) ? 1 : 0;
            swap ^= kt;
            if (swap == 1) {
                BigInteger tmp = x2;
                x2 = x3;
                x3 = tmp;
                tmp = z2;
                z2 = z3;
                z3 = tmp;
            }
            swap = kt;

            BigInteger a = x2.add(z2).mod(P);
            BigInteger aa = a.multiply(a).mod(P);
            BigInteger b = x2.subtract(z2).mod(P);
            BigInteger bb = b.multiply(b).mod(P);
            BigInteger e = aa.subtract(bb).mod(P);
            BigInteger c = x3.add(z3).mod(P);
            BigInteger d = x3.subtract(z3).mod(P);
            BigInteger da = d.multiply(a).mod(P);
            BigInteger cb = c.multiply(b).mod(P);
            BigInteger s = da.add(cb).mod(P);
            x3 = s.multiply(s).mod(P);
            BigInteger df = da.subtract(cb).mod(P);
            z3 = x1.multiply(df.multiply(df)).mod(P);
            x2 = aa.multiply(bb).mod(P);
            z2 = e.multiply(aa.add(A24.multiply(e))).mod(P);
        }
        if (swap == 1) {
            x2 = x3;
            z2 = z3;
        }
        BigInteger result = x2.multiply(z2.modPow(P.subtract(BigInteger.valueOf(2)), P)).mod(P);
        return encodeLittleEndian(result);
    }

    private static BigInteger decodeLittleEndian(byte[] b) {
        byte[] be = new byte[33];
        for (int i = 0; i < 32; i++) {
            be[32 - i] = b[i];
        }
        return new BigInteger(be);
    }

    private static byte[] encodeLittleEndian(BigInteger n) {
        byte[] be = n.toByteArray();
        byte[] out = new byte[32];
        for (int i = 0; i < 32 && i < be.length; i++) {
            out[i] = be[be.length - 1 - i];
        }
        return out;
    }
}
