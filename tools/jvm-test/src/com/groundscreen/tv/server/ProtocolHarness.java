package com.groundscreen.tv.server;

import com.dd.plist.BinaryPropertyListParser;
import com.dd.plist.BinaryPropertyListWriter;
import com.dd.plist.NSArray;
import com.dd.plist.NSData;
import com.dd.plist.NSDictionary;
import com.github.serezhka.airplay.protocol.crypto.AirPlayKeyLabels;
import com.github.serezhka.airplay.protocol.fairplay.FairPlayHandshake;
import com.github.serezhka.airplay.protocol.fairplay.FairPlayVideoCipher;
import com.github.serezhka.airplay.protocol.media.AudioStreamInfo;
import com.github.serezhka.airplay.protocol.pairing.PairingIdentity;
import net.i2p.crypto.eddsa.EdDSAEngine;
import net.i2p.crypto.eddsa.EdDSAPublicKey;
import net.i2p.crypto.eddsa.KeyPairGenerator;
import org.whispersystems.curve25519.Curve25519;
import org.whispersystems.curve25519.Curve25519KeyPair;

import javax.crypto.Cipher;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * End-to-end JVM test of the receiver protocol stack: acts as a legacy AirPlay sender
 * (pair-setup, pair-verify, fp-setup, SETUP, encrypted mirror video and audio) against
 * {@link RtspConnection}, and checks that the receiver hands back the exact plaintext.
 * Plists on the sender side are built/parsed with dd-plist to cross-check BinaryPlist.
 */
public final class ProtocolHarness {

    // Sender-side fixtures from java-airplay's legacy client (MIT).
    public static final byte[] FIXTURE_EKEY = {
            70, 80, 76, 89, 1, 2, 1, 0, 0, 0, 0, 60, 0, 0, 0, 0, 63, 121, 70, -69, 3, -8, 117, -13, 83, 72,
            105, -51, -11, -43, -1, 17, 0, 0, 0, 16, 24, -109, 13, 105, -32, -125, -73, -128, 21, 29, -31, 72,
            -41, 112, -36, -75, 57, 110, 71, -72, -25, -59, 102, 22, 19, -43, 35, 74, -20, 86, 15, 16, 126, 5,
            15, -45};
    static final byte[] FP_SETUP_1 = {70, 80, 76, 89, 3, 1, 1, 0, 0, 0, 0, 4, 2, 0, 0, -69};
    public static final byte[] FP_SETUP_2 = {
            70, 80, 76, 89, 3, 1, 3, 0, 0, 0, 0, -104, 0, -113, 26, -100, -40, -92, -10, 52, 109, 20, 120, 6,
            -62, -67, -118, 75, -47, -71, -109, -45, -61, 106, -95, 1, 36, -104, -7, 78, -1, -13, 70, 123,
            -49, 27, 49, -104, 98, 92, -94, 69, -114, 62, -48, 30, -35, 53, -25, 41, 53, 125, -7, 75, -128,
            -51, 10, -50, 35, 84, -42, -116, -29, 127, 94, 24, -16, -49, -46, 109, 65, 103, 21, 63, -64, -76,
            54, 35, 22, 111, 8, -58, 111, -45, 1, 56, 14, -80, -98, -97, -115, -24, 59, -46, -82, -57, -92,
            1, -15, -5, -67, -13, 46, 10, -43, 81, -24, 121, 63, -25, -63, 25, 35, 51, -103, -91, 53, 76,
            -59, 67, 7, 30, -68, -50, -32, -84, -123, 34, -82, 27, -85, 51, -44, 65, -60, 120, -11, 99, -50,
            -3, 66, 117, -5, 85, 90, 58, -29, 58, -40, -71, -7, -108, -7, -75};
    static final byte[] EIV = new byte[16];
    static final long STREAM_CONNECTION_ID = -3907568444900622110L; // > 2^63 unsigned

    static int cseq = 1;
    static int failures = 0;

    public static void main(String[] args) throws Exception {
        new SecureRandom().nextBytes(EIV);
        x25519Checks();
        final Recorder rec = new Recorder();
        PairingIdentity identity = PairingIdentity.fromSeed(new byte[32]);
        final ReceiverConfig cfg = new ReceiverConfig("Test TV", "02:11:22:33:44:55", 1920, 1080, 60, identity);

        final ServerSocket ss = new ServerSocket(0);
        Thread acceptor = new Thread(new Runnable() {
            @Override
            public void run() {
                try {
                    Socket s = ss.accept();
                    new Thread(new RtspConnection(s, cfg, rec, ss.getLocalPort(), new RtspConnection.Owner() {
                        @Override
                        public boolean claimMirror(RtspConnection c) {
                            return true;
                        }

                        @Override
                        public void released(RtspConnection c) {
                        }
                    })).start();
                } catch (Exception e) {
                    e.printStackTrace();
                }
            }
        });
        acceptor.start();

        Socket ctl = new Socket("127.0.0.1", ss.getLocalPort());
        InputStream in = ctl.getInputStream();
        OutputStream out = ctl.getOutputStream();

        // 1. GET /info, parsed with dd-plist
        byte[] info = request(in, out, "GET", "/info", null, null);
        NSDictionary infoDict = (NSDictionary) BinaryPropertyListParser.parse(info);
        check("info name", "Test TV".equals(infoDict.get("name").toJavaObject()));
        check("info pk", Arrays.equals(identity.publicKey(), ((NSData) infoDict.get("pk")).bytes()));
        check("info features", ((Number) infoDict.get("features").toJavaObject()).longValue() == AirPlayFeatures.MASK);
        NSDictionary display = (NSDictionary) ((NSArray) infoDict.get("displays")).objectAtIndex(0);
        check("info width", ((Number) display.get("width").toJavaObject()).intValue() == 1920);

        // 2. pair-setup
        byte[] pk = request(in, out, "POST", "/pair-setup", new byte[32], "application/octet-stream");
        check("pair-setup returns pk", Arrays.equals(pk, identity.publicKey()));

        // 3. pair-verify (sender side reimplemented here)
        KeyPair ed = new KeyPairGenerator().generateKeyPair();
        Curve25519 curve = Curve25519.getInstance(Curve25519.BEST);
        Curve25519KeyPair ecdh = curve.generateKeyPair();
        byte[] pv1 = new byte[68];
        pv1[0] = 1;
        System.arraycopy(ecdh.getPublicKey(), 0, pv1, 4, 32);
        System.arraycopy(((EdDSAPublicKey) ed.getPublic()).getAbyte(), 0, pv1, 36, 32);
        byte[] pv1r = request(in, out, "POST", "/pair-verify", pv1, "application/octet-stream");
        check("pair-verify M2 length", pv1r.length == 96);
        byte[] atvPub = Arrays.copyOfRange(pv1r, 0, 32);
        byte[] shared = curve.calculateAgreement(atvPub, ecdh.getPrivateKey());
        MessageDigest sha = MessageDigest.getInstance("SHA-512");
        sha.update(AirPlayKeyLabels.PAIR_VERIFY_AES_KEY.getBytes(StandardCharsets.UTF_8));
        sha.update(shared);
        byte[] k = Arrays.copyOf(sha.digest(), 16);
        sha.update(AirPlayKeyLabels.PAIR_VERIFY_AES_IV.getBytes(StandardCharsets.UTF_8));
        sha.update(shared);
        byte[] iv = Arrays.copyOf(sha.digest(), 16);
        Cipher ctr = Cipher.getInstance("AES/CTR/NoPadding");
        ctr.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(k, "AES"), new IvParameterSpec(iv));
        // verify receiver signature
        byte[] recvSig = ctr.update(Arrays.copyOfRange(pv1r, 32, 96));
        EdDSAEngine verifier = new EdDSAEngine();
        verifier.initVerify(new EdDSAPublicKey(new net.i2p.crypto.eddsa.spec.EdDSAPublicKeySpec(identity.publicKey(),
                net.i2p.crypto.eddsa.spec.EdDSANamedCurveTable.getByName("Ed25519"))));
        byte[] signed = new byte[64];
        System.arraycopy(atvPub, 0, signed, 0, 32);
        System.arraycopy(ecdh.getPublicKey(), 0, signed, 32, 32);
        check("receiver pair-verify signature valid", verifier.verifyOneShot(signed, recvSig));
        EdDSAEngine signer = new EdDSAEngine();
        signer.initSign(ed.getPrivate());
        byte[] toSign = new byte[64];
        System.arraycopy(ecdh.getPublicKey(), 0, toSign, 0, 32);
        System.arraycopy(atvPub, 0, toSign, 32, 32);
        byte[] encSig = ctr.update(signer.signOneShot(toSign));
        byte[] pv2 = new byte[68];
        System.arraycopy(encSig, 0, pv2, 4, 64);
        request(in, out, "POST", "/pair-verify", pv2, "application/octet-stream");

        // 4. fp-setup
        byte[] fp1 = request(in, out, "POST", "/fp-setup", FP_SETUP_1, "application/octet-stream");
        check("fp-setup 1 reply 142 bytes", fp1.length == 142);
        byte[] fp2 = request(in, out, "POST", "/fp-setup", FP_SETUP_2, "application/octet-stream");
        check("fp-setup 2 reply 32 bytes", fp2.length == 32);
        FairPlayHandshake senderFp = new FairPlayHandshake();
        senderFp.fairPlaySetup(new ByteArrayInputStream(FP_SETUP_2), new ByteArrayOutputStream());
        byte[] aesKey = senderFp.decryptAesKey(FIXTURE_EKEY);

        // 5. SETUP ekey/eiv
        NSDictionary s1 = new NSDictionary();
        s1.put("ekey", FIXTURE_EKEY);
        s1.put("eiv", EIV);
        s1.put("timingProtocol", "NTP");
        request(in, out, "SETUP", "rtsp://127.0.0.1/1", BinaryPropertyListWriter.writeToArray(s1),
                "application/x-apple-binary-plist");

        // 6. SETUP video
        NSDictionary vs = new NSDictionary();
        vs.put("type", 110);
        vs.put("streamConnectionID", STREAM_CONNECTION_ID);
        NSDictionary s2 = new NSDictionary();
        s2.put("streams", new NSArray(vs));
        byte[] r2 = request(in, out, "SETUP", "rtsp://127.0.0.1/1", BinaryPropertyListWriter.writeToArray(s2),
                "application/x-apple-binary-plist");
        NSDictionary r2d = (NSDictionary) BinaryPropertyListParser.parse(r2);
        int dataPort = ((Number) ((NSDictionary) ((NSArray) r2d.get("streams")).objectAtIndex(0))
                .get("dataPort").toJavaObject()).intValue();
        check("video SETUP dataPort", dataPort > 0);
        check("mirror started callback", rec.mirrorStarted);

        // 7. Stream encrypted video
        Socket video = new Socket("127.0.0.1", dataPort);
        OutputStream vout = video.getOutputStream();
        Random rnd = new Random(42);
        byte[] sps = randomNal(rnd, 0x67, 23);
        byte[] pps = randomNal(rnd, 0x68, 4);
        vout.write(codecPacket(sps, pps));
        FairPlayVideoCipher enc = new FairPlayVideoCipher(aesKey, shared, unsigned(STREAM_CONNECTION_ID));
        List<byte[]> expected = new ArrayList<byte[]>();
        int frames = 200;
        rec.framesLatch = new CountDownLatch(frames);
        for (int f = 0; f < frames; f++) {
            List<byte[]> nals = new ArrayList<byte[]>();
            if (f % 50 == 0) {
                nals.add(randomNal(rnd, 0x06, 10 + rnd.nextInt(30))); // SEI
                nals.add(randomNal(rnd, 0x65, 2000 + rnd.nextInt(60000))); // IDR
            } else {
                nals.add(randomNal(rnd, 0x41, 1 + rnd.nextInt(20000)));
            }
            ByteArrayOutputStream avcc = new ByteArrayOutputStream();
            ByteArrayOutputStream annexB = new ByteArrayOutputStream();
            for (byte[] n : nals) {
                avcc.write(ByteBuffer.allocate(4).putInt(n.length).array());
                avcc.write(n);
                annexB.write(new byte[]{0, 0, 0, 1});
                annexB.write(n);
            }
            expected.add(annexB.toByteArray());
            byte[] payload = avcc.toByteArray();
            enc.crypt(payload);
            vout.write(header(payload.length, 0, f * 1000L));
            vout.write(payload);
            if (f == 77) {
                // heartbeat (type 2) and streaming report (type 5) must be skipped
                vout.write(header(0, 2, 0));
                byte[] report = "report".getBytes(StandardCharsets.US_ASCII);
                vout.write(header(report.length, 5, 0));
                vout.write(report);
            }
        }
        vout.flush();
        check("all frames received", rec.framesLatch.await(10, TimeUnit.SECONDS));
        check("SPS/PPS passed through", rec.sps != null && Arrays.equals(rec.sps, annexB(sps))
                && Arrays.equals(rec.pps, annexB(pps)));
        int mismatches = 0;
        for (int i = 0; i < frames && i < rec.frames.size(); i++) {
            if (!Arrays.equals(expected.get(i), rec.frames.get(i))) {
                mismatches++;
            }
        }
        check("decrypted frames identical (" + rec.frames.size() + " frames, " + mismatches + " mismatches)",
                mismatches == 0 && rec.frames.size() == frames);
        check("IDR detection", com.groundscreen.tv.media.H264Util.containsIdr(expected.get(0))
                && !com.groundscreen.tv.media.H264Util.containsIdr(expected.get(1)));

        // 8. Audio SETUP + one encrypted RTP packet
        NSDictionary as = new NSDictionary();
        as.put("type", 96);
        as.put("ct", 8);
        as.put("audioFormat", 0x1000000);
        as.put("spf", 480);
        as.put("controlPort", 6001);
        NSDictionary s3 = new NSDictionary();
        s3.put("streams", new NSArray(as));
        byte[] r3 = request(in, out, "SETUP", "rtsp://127.0.0.1/1", BinaryPropertyListWriter.writeToArray(s3),
                "application/x-apple-binary-plist");
        NSDictionary a0 = (NSDictionary) ((NSArray) ((NSDictionary) BinaryPropertyListParser.parse(r3)).get("streams")).objectAtIndex(0);
        int audioPort = ((Number) a0.get("dataPort").toJavaObject()).intValue();
        check("audio SETUP ports", audioPort > 0 && ((Number) a0.get("controlPort").toJavaObject()).intValue() > 0);
        check("audio format parsed", rec.audioInfo != null
                && rec.audioInfo.getCompressionType() == AudioStreamInfo.CompressionType.AAC_ELD
                && rec.audioInfo.getAudioFormat() == AudioStreamInfo.AudioFormat.AAC_ELD_44100_2
                && rec.audioInfo.getSamplesPerFrame() == 480);

        byte[] plainAudio = new byte[203];
        rnd.nextBytes(plainAudio);
        sha.reset();
        sha.update(aesKey);
        sha.update(shared);
        byte[] audioKey = Arrays.copyOf(sha.digest(), 16);
        Cipher cbc = Cipher.getInstance("AES/CBC/NoPadding");
        cbc.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(audioKey, "AES"), new IvParameterSpec(EIV));
        byte[] encAudio = plainAudio.clone();
        cbc.doFinal(plainAudio, 0, 192, encAudio, 0); // trailing 11 bytes stay in the clear
        byte[] rtp = new byte[12 + encAudio.length];
        rtp[0] = (byte) 0x80;
        rtp[1] = (byte) 0xE0;
        rtp[3] = 5;
        System.arraycopy(encAudio, 0, rtp, 12, encAudio.length);
        rec.audioLatch = new CountDownLatch(1);
        DatagramSocket udp = new DatagramSocket();
        udp.send(new DatagramPacket(rtp, rtp.length, InetAddress.getByName("127.0.0.1"), audioPort));
        udp.send(new DatagramPacket(rtp, rtp.length, InetAddress.getByName("127.0.0.1"), audioPort)); // dup
        check("audio frame received", rec.audioLatch.await(5, TimeUnit.SECONDS));
        Thread.sleep(300);
        check("audio decrypted correctly", rec.audio.size() == 1 && Arrays.equals(rec.audio.get(0), plainAudio));

        // 9. volume + misc
        request(in, out, "SET_PARAMETER", "rtsp://127.0.0.1/1", "volume: -15.000000\r\n".getBytes(StandardCharsets.US_ASCII),
                "text/parameters");
        check("volume applied", Math.abs(rec.volume - Math.pow(10, -15 / 20.0)) < 1e-3);
        request(in, out, "RECORD", "rtsp://127.0.0.1/1", null, null);
        request(in, out, "POST", "/feedback", null, null);
        request(in, out, "GET_PARAMETER", "rtsp://127.0.0.1/1", "volume\r\n".getBytes(StandardCharsets.US_ASCII), "text/parameters");

        // 10. TEARDOWN video then close
        NSDictionary td = new NSDictionary();
        NSDictionary tdv = new NSDictionary();
        tdv.put("type", 110);
        td.put("streams", new NSArray(tdv));
        request(in, out, "TEARDOWN", "rtsp://127.0.0.1/1", BinaryPropertyListWriter.writeToArray(td),
                "application/x-apple-binary-plist");
        check("mirror stopped on TEARDOWN", rec.mirrorStopped);
        ctl.close();
        Thread.sleep(300);
        check("disconnect callback", rec.disconnected);

        // 11. BinaryPlist round trip with large/odd values via dd-plist
        NSDictionary big = new NSDictionary();
        big.put("streamConnectionID", STREAM_CONNECTION_ID);
        big.put("unicode", "Teléfono de Ana ✓");
        byte[] blob = new byte[70000];
        rnd.nextBytes(blob);
        big.put("blob", blob);
        java.util.Map<?, ?> parsed = (java.util.Map<?, ?>) com.groundscreen.tv.util.BinaryPlist.parse(
                BinaryPropertyListWriter.writeToArray(big));
        check("plist parse 64-bit id", ((Number) parsed.get("streamConnectionID")).longValue() == STREAM_CONNECTION_ID);
        check("plist parse unicode", "Teléfono de Ana ✓".equals(parsed.get("unicode")));
        check("plist parse big data", Arrays.equals(blob, (byte[]) parsed.get("blob")));
        java.util.List<Object> many = new java.util.ArrayList<Object>();
        for (int i = 0; i < 400; i++) {
            many.add("item" + i);
        }
        byte[] w = com.groundscreen.tv.util.BinaryPlist.write(com.groundscreen.tv.util.BinaryPlist.dict(
                "many", many, "neg", -5L, "big", 1L << 40, "name", "Sala ñandú", "data", blob));
        NSDictionary back = (NSDictionary) BinaryPropertyListParser.parse(w);
        check("plist write >255 objects", ((NSArray) back.get("many")).count() == 400
                && "item399".equals(((NSArray) back.get("many")).objectAtIndex(399).toJavaObject()));
        check("plist write ints", ((Number) back.get("neg").toJavaObject()).longValue() == -5L
                && ((Number) back.get("big").toJavaObject()).longValue() == (1L << 40));
        check("plist write unicode+data", "Sala ñandú".equals(back.get("name").toJavaObject())
                && Arrays.equals(blob, ((NSData) back.get("data")).bytes()));

        System.out.println(failures == 0 ? "\nALL CHECKS PASSED" : "\n" + failures + " CHECK(S) FAILED");
        System.exit(failures == 0 ? 0 : 1);
    }

    static byte[] unhex(String h) {
        byte[] b = new byte[h.length() / 2];
        for (int i = 0; i < b.length; i++) {
            b[i] = (byte) Integer.parseInt(h.substring(2 * i, 2 * i + 2), 16);
        }
        return b;
    }

    static void x25519Checks() {
        // RFC 7748 section 5.2 and 6.1 test vectors
        check("X25519 RFC7748 vector 1", Arrays.equals(com.groundscreen.tv.crypto.X25519.scalarMult(
                unhex("a546e36bf0527c9d3b16154b82465edd62144c0ac1fc5a18506a2244ba449ac4"),
                unhex("e6db6867583030db3594c1a424b15f7c726624ec26b3353b10a903a6d0ab1c4c")),
                unhex("c3da55379de9c6908e94ea4df28d084f32eccf03491c71f754b4075577a28552")));
        check("X25519 RFC7748 vector 2", Arrays.equals(com.groundscreen.tv.crypto.X25519.scalarMult(
                unhex("4b66e9d4d1b4673c5ad22691957d6af5c11b6421e0ea01d42ca4169e7918ba0d"),
                unhex("e5210f12786811d3f4b7959d0538ae2c31dbe7106fc03c3efc4cd549c715a493")),
                unhex("95cbde9476e8907d7aade45cb4b873f88b595a68799fa152e6f8f7647aac7957")));
        byte[] alicePriv = unhex("77076d0a7318a57d3c16c17251b26645df4c2f87ebc0992ab177fba51db92c2a");
        byte[] bobPub = unhex("de9edb7d7b7dc1b4d35b61c2ece435373f8343c85b78674dadfc7e146f882b4f");
        check("X25519 RFC7748 public key", Arrays.equals(com.groundscreen.tv.crypto.X25519.publicKey(alicePriv),
                unhex("8520f0098930a754748b7ddcb43ef75a0dbf3a0d26381af4eba4a98eaa9b4e6a")));
        check("X25519 RFC7748 shared secret", Arrays.equals(com.groundscreen.tv.crypto.X25519.sharedSecret(alicePriv, bobPub),
                unhex("4a5d9d5ba4ce2de1728e3bf480350f25e07e21c947d19e3376f09b3c1e161742")));
        // cross-check against curve25519-java (Signal) with random keys
        Curve25519 c = Curve25519.getInstance(Curve25519.JAVA);
        boolean all = true;
        long t0 = System.nanoTime();
        for (int i = 0; i < 20; i++) {
            Curve25519KeyPair kp = c.generateKeyPair();
            byte[] mine = com.groundscreen.tv.crypto.X25519.generatePrivateKey(new SecureRandom());
            byte[] minePub = com.groundscreen.tv.crypto.X25519.publicKey(mine);
            byte[] s1 = com.groundscreen.tv.crypto.X25519.sharedSecret(mine, kp.getPublicKey());
            byte[] s2 = c.calculateAgreement(minePub, kp.getPrivateKey());
            all &= Arrays.equals(s1, s2);
        }
        check("X25519 agrees with curve25519-java (20 random pairs, "
                + (System.nanoTime() - t0) / 40_000_000 + " ms/op)", all);
    }

    static String unsigned(long v) {
        return com.groundscreen.tv.util.IoUtil.unsignedLongToString(v);
    }

    static void check(String what, boolean ok) {
        System.out.println((ok ? "PASS " : "FAIL ") + what);
        if (!ok) {
            failures++;
        }
    }

    static byte[] annexB(byte[] nal) {
        byte[] o = new byte[nal.length + 4];
        o[3] = 1;
        System.arraycopy(nal, 0, o, 4, nal.length);
        return o;
    }

    static byte[] randomNal(Random rnd, int header, int len) {
        byte[] n = new byte[len];
        rnd.nextBytes(n);
        n[0] = (byte) header;
        return n;
    }

    static byte[] header(int size, int type, long ts) {
        ByteBuffer h = ByteBuffer.allocate(128).order(ByteOrder.LITTLE_ENDIAN);
        h.putInt(0, size);
        h.put(4, (byte) type);
        h.putLong(8, ts);
        return h.array();
    }

    static byte[] codecPacket(byte[] sps, byte[] pps) {
        ByteBuffer b = ByteBuffer.allocate(6 + 2 + sps.length + 1 + 2 + pps.length);
        b.put((byte) 1).put(sps[1]).put(sps[2]).put(sps[3]).put((byte) 0xff).put((byte) 0xe1);
        b.putShort((short) sps.length).put(sps).put((byte) 1).putShort((short) pps.length).put(pps);
        byte[] payload = b.array();
        ByteBuffer h = ByteBuffer.wrap(header(payload.length, 1, 0)).order(ByteOrder.LITTLE_ENDIAN);
        h.putShort(6, (short) 0x1e);
        h.putFloat(16, 1280f);
        h.putFloat(20, 720f);
        byte[] all = new byte[128 + payload.length];
        System.arraycopy(h.array(), 0, all, 0, 128);
        System.arraycopy(payload, 0, all, 128, payload.length);
        return all;
    }

    static byte[] request(InputStream in, OutputStream out, String method, String uri, byte[] body, String ct)
            throws Exception {
        StringBuilder sb = new StringBuilder();
        sb.append(method).append(' ').append(uri).append(" RTSP/1.0\r\n");
        sb.append("CSeq: ").append(cseq++).append("\r\n");
        sb.append("User-Agent: AirPlay/790.10.1\r\n");
        sb.append("Active-Remote: 12345\r\n");
        if (ct != null) {
            sb.append("Content-Type: ").append(ct).append("\r\n");
        }
        sb.append("Content-Length: ").append(body == null ? 0 : body.length).append("\r\n\r\n");
        out.write(sb.toString().getBytes(StandardCharsets.US_ASCII));
        if (body != null) {
            out.write(body);
        }
        out.flush();
        DataInputStream din = new DataInputStream(in);
        String status = readLine(din);
        int len = 0;
        String line;
        boolean cseqOk = false;
        while (!(line = readLine(din)).isEmpty()) {
            if (line.toLowerCase().startsWith("content-length:")) {
                len = Integer.parseInt(line.substring(15).trim());
            }
            if (line.equals("CSeq: " + (cseq - 1))) {
                cseqOk = true;
            }
        }
        byte[] resp = new byte[len];
        din.readFully(resp);
        boolean ok = status.startsWith("RTSP/1.0 200") && cseqOk;
        check(method + " " + uri + " -> " + status, ok);
        return resp;
    }

    static String readLine(DataInputStream in) throws Exception {
        ByteArrayOutputStream b = new ByteArrayOutputStream();
        int c;
        while ((c = in.read()) != '\n') {
            if (c < 0) {
                throw new java.io.EOFException();
            }
            if (c != '\r') {
                b.write(c);
            }
        }
        return new String(b.toByteArray(), StandardCharsets.US_ASCII);
    }

    static final class Recorder implements MirrorListener {
        volatile boolean mirrorStarted, mirrorStopped, disconnected;
        volatile byte[] sps, pps;
        final List<byte[]> frames = new CopyOnWriteArrayList<byte[]>();
        final List<byte[]> audio = new CopyOnWriteArrayList<byte[]>();
        volatile CountDownLatch framesLatch = new CountDownLatch(1), audioLatch = new CountDownLatch(1);
        volatile AudioStreamInfo audioInfo;
        volatile float volume = -1;

        public void onClientConnected(String a) { }
        public void onMirrorStarted() { mirrorStarted = true; }
        public void onVideoConfig(byte[] s, byte[] p) { sps = s; pps = p; }
        public void onVideoFrame(byte[] f, long ts) { frames.add(f.clone()); framesLatch.countDown(); }
        public void onAudioFormat(AudioStreamInfo i) { audioInfo = i; }
        public void onAudioFrame(byte[] d, int o, int l) { audio.add(Arrays.copyOfRange(d, o, o + l)); audioLatch.countDown(); }
        public void onVolume(float v) { volume = v; }
        public void onAudioStopped() { }
        public void onMirrorStopped() { mirrorStopped = true; }
        public void onClientDisconnected() { disconnected = true; }
    }
}
