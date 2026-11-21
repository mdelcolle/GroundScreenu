package com.groundscreen.tv.util;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigInteger;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.IdentityHashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal Apple binary property list ("bplist00") reader and writer.
 * <p>
 * Decoded types: {@link Map} (String keys), {@link List}, {@link String}, {@link Long}
 * (or {@link BigInteger} for 128-bit values), {@link Double}, {@link Boolean}, {@code byte[]}.
 * The writer accepts the same types plus {@link Integer}.
 */
public final class BinaryPlist {

    private static final Charset ASCII = Charset.forName("US-ASCII");
    private static final Charset UTF16BE = Charset.forName("UTF-16BE");
    private static final Charset UTF8 = Charset.forName("UTF-8");

    private BinaryPlist() {
    }

    // ------------------------------------------------------------------ parsing

    public static Object parse(byte[] data) throws IOException {
        if (data.length < 40 || !new String(data, 0, 8, ASCII).startsWith("bplist")) {
            throw new IOException("Not a binary plist");
        }
        int t = data.length - 32;
        int offsetSize = data[t + 6] & 0xff;
        int refSize = data[t + 7] & 0xff;
        int numObjects = (int) readUInt(data, t + 8, 8);
        int top = (int) readUInt(data, t + 16, 8);
        int tableOffset = (int) readUInt(data, t + 24, 8);
        int[] offsets = new int[numObjects];
        for (int i = 0; i < numObjects; i++) {
            offsets[i] = (int) readUInt(data, tableOffset + i * offsetSize, offsetSize);
        }
        return new Parser(data, offsets, refSize).object(top, 0);
    }

    private static long readUInt(byte[] d, int off, int len) {
        long v = 0;
        for (int i = 0; i < len; i++) {
            v = (v << 8) | (d[off + i] & 0xff);
        }
        return v;
    }

    private static final class Parser {
        final byte[] d;
        final int[] offsets;
        final int refSize;

        Parser(byte[] d, int[] offsets, int refSize) {
            this.d = d;
            this.offsets = offsets;
            this.refSize = refSize;
        }

        Object object(int ref, int depth) throws IOException {
            if (depth > 64) {
                throw new IOException("plist too deep");
            }
            int off = offsets[ref];
            int marker = d[off] & 0xff;
            int hi = marker >> 4;
            int lo = marker & 0xf;
            switch (hi) {
                case 0x0:
                    if (lo == 0x8) return Boolean.FALSE;
                    if (lo == 0x9) return Boolean.TRUE;
                    return null;
                case 0x1: {
                    int len = 1 << lo;
                    if (len == 16) {
                        byte[] mag = new byte[16];
                        System.arraycopy(d, off + 1, mag, 0, 16);
                        BigInteger bi = new BigInteger(mag);
                        return bi.bitLength() < 64 ? (Object) bi.longValue() : bi;
                    }
                    long v = readUInt(d, off + 1, len);
                    return v;
                }
                case 0x2: {
                    int len = 1 << lo;
                    long bits = readUInt(d, off + 1, len);
                    return len == 4 ? (double) Float.intBitsToFloat((int) bits) : Double.longBitsToDouble(bits);
                }
                case 0x3:
                    return Double.longBitsToDouble(readUInt(d, off + 1, 8));
                case 0x4: {
                    int[] lp = lengthAndStart(off, lo);
                    byte[] out = new byte[lp[0]];
                    System.arraycopy(d, lp[1], out, 0, lp[0]);
                    return out;
                }
                case 0x5: {
                    int[] lp = lengthAndStart(off, lo);
                    return new String(d, lp[1], lp[0], ASCII);
                }
                case 0x6: {
                    int[] lp = lengthAndStart(off, lo);
                    return new String(d, lp[1], lp[0] * 2, UTF16BE);
                }
                case 0x7: {
                    int[] lp = lengthAndStart(off, lo);
                    return new String(d, lp[1], lp[0], UTF8);
                }
                case 0x8:
                    return readUInt(d, off + 1, lo + 1);
                case 0xA:
                case 0xC: {
                    int[] lp = lengthAndStart(off, lo);
                    List<Object> list = new ArrayList<Object>(lp[0]);
                    for (int i = 0; i < lp[0]; i++) {
                        list.add(object((int) readUInt(d, lp[1] + i * refSize, refSize), depth + 1));
                    }
                    return list;
                }
                case 0xD: {
                    int[] lp = lengthAndStart(off, lo);
                    int n = lp[0];
                    Map<String, Object> map = new LinkedHashMap<String, Object>();
                    for (int i = 0; i < n; i++) {
                        Object key = object((int) readUInt(d, lp[1] + i * refSize, refSize), depth + 1);
                        Object val = object((int) readUInt(d, lp[1] + (n + i) * refSize, refSize), depth + 1);
                        map.put(String.valueOf(key), val);
                    }
                    return map;
                }
                default:
                    throw new IOException("Unsupported plist marker 0x" + Integer.toHexString(marker));
            }
        }

        /** @return {length, startOfContent} */
        int[] lengthAndStart(int off, int lo) {
            if (lo != 0xf) {
                return new int[]{lo, off + 1};
            }
            int intMarker = d[off + 1] & 0xff;
            int intLen = 1 << (intMarker & 0xf);
            int len = (int) readUInt(d, off + 2, intLen);
            return new int[]{len, off + 2 + intLen};
        }
    }

    // ------------------------------------------------------------------ writing

    public static byte[] write(Object root) throws IOException {
        List<Object> objects = new ArrayList<Object>();
        IdentityHashMap<Object, Integer> ids = new IdentityHashMap<Object, Integer>();
        collect(root, objects, ids);
        int refSize = objects.size() < 256 ? 1 : 2;

        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write("bplist00".getBytes(ASCII));
        long[] offsets = new long[objects.size()];
        for (int i = 0; i < objects.size(); i++) {
            offsets[i] = out.size();
            writeObject(out, objects.get(i), ids, refSize);
        }
        long tableOffset = out.size();
        int offsetSize = tableOffset < 256 ? 1 : tableOffset < 65536 ? 2 : 4;
        for (long o : offsets) {
            writeUInt(out, o, offsetSize);
        }
        byte[] trailer = new byte[6];
        out.write(trailer);
        out.write(offsetSize);
        out.write(refSize);
        writeUInt(out, objects.size(), 8);
        writeUInt(out, 0, 8);
        writeUInt(out, tableOffset, 8);
        return out.toByteArray();
    }

    @SuppressWarnings("unchecked")
    private static void collect(Object o, List<Object> objects, IdentityHashMap<Object, Integer> ids) {
        // Boxed numbers/booleans may be shared instances; wrap them so every occurrence gets an id.
        Holder h = new Holder(o);
        ids.put(h, objects.size());
        objects.add(h);
        if (o instanceof Map) {
            Map<String, Object> m = (Map<String, Object>) o;
            List<Holder> keys = new ArrayList<Holder>();
            List<Holder> vals = new ArrayList<Holder>();
            for (Map.Entry<String, Object> e : m.entrySet()) {
                keys.add(collectChild(e.getKey(), objects, ids));
            }
            for (Map.Entry<String, Object> e : m.entrySet()) {
                vals.add(collectChild(e.getValue(), objects, ids));
            }
            h.children = new ArrayList<Holder>(keys);
            h.children.addAll(vals);
        } else if (o instanceof List) {
            h.children = new ArrayList<Holder>();
            for (Object c : (List<Object>) o) {
                h.children.add(collectChild(c, objects, ids));
            }
        }
    }

    private static Holder collectChild(Object o, List<Object> objects, IdentityHashMap<Object, Integer> ids) {
        int index = objects.size();
        collect(o, objects, ids);
        return (Holder) objects.get(index);
    }

    private static final class Holder {
        final Object value;
        List<Holder> children;

        Holder(Object value) {
            this.value = value;
        }
    }

    private static void writeObject(ByteArrayOutputStream out, Object obj, IdentityHashMap<Object, Integer> ids,
                                    int refSize) throws IOException {
        Holder h = (Holder) obj;
        Object o = h.value;
        if (o == null) {
            out.write(0x00);
        } else if (o instanceof Boolean) {
            out.write(((Boolean) o) ? 0x09 : 0x08);
        } else if (o instanceof Integer || o instanceof Long || o instanceof Short || o instanceof Byte) {
            long v = ((Number) o).longValue();
            if (v < 0) {
                out.write(0x13);
                writeUInt(out, v, 8);
            } else if (v < 0x100) {
                out.write(0x10);
                writeUInt(out, v, 1);
            } else if (v < 0x10000) {
                out.write(0x11);
                writeUInt(out, v, 2);
            } else if (v < 0x100000000L) {
                out.write(0x12);
                writeUInt(out, v, 4);
            } else {
                out.write(0x13);
                writeUInt(out, v, 8);
            }
        } else if (o instanceof Float || o instanceof Double) {
            out.write(0x23);
            writeUInt(out, Double.doubleToLongBits(((Number) o).doubleValue()), 8);
        } else if (o instanceof byte[]) {
            byte[] b = (byte[]) o;
            writeMarker(out, 0x4, b.length);
            out.write(b);
        } else if (o instanceof String) {
            String s = (String) o;
            boolean ascii = true;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) > 0x7f) {
                    ascii = false;
                    break;
                }
            }
            if (ascii) {
                writeMarker(out, 0x5, s.length());
                out.write(s.getBytes(ASCII));
            } else {
                writeMarker(out, 0x6, s.length());
                out.write(s.getBytes(UTF16BE));
            }
        } else if (o instanceof Map) {
            int n = h.children.size() / 2;
            writeMarker(out, 0xD, n);
            for (Holder c : h.children) {
                writeUInt(out, ids.get(c), refSize);
            }
        } else if (o instanceof List) {
            writeMarker(out, 0xA, h.children.size());
            for (Holder c : h.children) {
                writeUInt(out, ids.get(c), refSize);
            }
        } else {
            throw new IOException("Unsupported plist type " + o.getClass());
        }
    }

    private static void writeMarker(ByteArrayOutputStream out, int type, int len) {
        if (len < 15) {
            out.write((type << 4) | len);
        } else {
            out.write((type << 4) | 0xf);
            if (len < 0x100) {
                out.write(0x10);
                writeUInt(out, len, 1);
            } else if (len < 0x10000) {
                out.write(0x11);
                writeUInt(out, len, 2);
            } else {
                out.write(0x12);
                writeUInt(out, len, 4);
            }
        }
    }

    private static void writeUInt(ByteArrayOutputStream out, long v, int len) {
        for (int i = len - 1; i >= 0; i--) {
            out.write((int) (v >>> (8 * i)) & 0xff);
        }
    }

    // ------------------------------------------------------------------ helpers

    public static Map<String, Object> dict(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<String, Object>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    public static long asLong(Object o, long def) {
        if (o instanceof Number) {
            return ((Number) o).longValue();
        }
        if (o instanceof String) {
            try {
                return Long.parseLong((String) o);
            } catch (NumberFormatException ignored) {
                return def;
            }
        }
        return def;
    }
}
