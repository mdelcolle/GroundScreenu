package com.github.serezhka.airplay.protocol.pairing;

import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;

import java.security.KeyPair;
import net.i2p.crypto.eddsa.EdDSAPrivateKey;
import net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable;
import net.i2p.crypto.eddsa.spec.EdDSAParameterSpec;
import net.i2p.crypto.eddsa.spec.EdDSAPrivateKeySpec;
import net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec;

/**
 * Receiver Ed25519 identity for one AirPlay process (legacy FairPlay pair-setup / pair-verify).
 * The public key is what mDNS advertises as {@code pk} and what {@code /pair-setup} returns.
 * <p>
 * Not the HKP controller identity saved by the cast client ({@code pairing_id} + seed) —
 * that lives in the client credential store.
 */
public final class PairingIdentity {

    private final KeyPair keyPair;

    private PairingIdentity(KeyPair keyPair) {
        this.keyPair = keyPair;
    }

    public static PairingIdentity generate() {
        return new PairingIdentity(new KeyPairGenerator().generateKeyPair());
    }

    public byte[] publicKey() {
        return ((EdDSAPublicKey) keyPair.getPublic()).getAbyte();
    }

    /** Deterministic identity from a persisted 32-byte Ed25519 seed (stable {@code pk} across restarts). */
    public static PairingIdentity fromSeed(byte[] seed) {
        EdDSAParameterSpec spec = EdDSANamedCurveTable.getByName(EdDSANamedCurveTable.ED_25519);
        EdDSAPrivateKeySpec privSpec = new EdDSAPrivateKeySpec(seed, spec);
        EdDSAPublicKeySpec pubSpec = new EdDSAPublicKeySpec(privSpec.getA(), spec);
        return new PairingIdentity(new KeyPair(new EdDSAPublicKey(pubSpec), new EdDSAPrivateKey(privSpec)));
    }

    public String publicKeyHex() {
        byte[] pk = publicKey();
        StringBuilder sb = new StringBuilder(pk.length * 2);
        for (byte b : pk) {
            sb.append(String.format("%02x", b & 0xff));
        }
        return sb.toString();
    }

    KeyPair keyPair() {
        return keyPair;
    }
}
