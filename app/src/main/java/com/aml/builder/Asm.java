package com.aml.builder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public class Asm {
    static final Pattern LOCAL = Pattern.compile("^\\d+@$");
    static final Pattern GLOBAL = Pattern.compile("^\\$\\d+$");
    static final Pattern FLOAT = Pattern.compile("^-?(\\d+\\.\\d*|\\.\\d+)f?$");
    static final Pattern INT = Pattern.compile("^-?\\d+$");
    static final Pattern HEXINT = Pattern.compile("^0[xX][0-9A-Fa-f]{1,8}$");
    static final Pattern LABELREF = Pattern.compile("^@[A-Za-z_][A-Za-z0-9_]*$");
    static final Charset CS = Charset.forName("ISO-8859-1");

    /** Buang komentar // di luar tanda kutip. */
    public static String strip(String s) {
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { if (c == q) q = 0; }
            else if (c == '\'' || c == '"') q = c;
            else if (c == '/' && i + 1 < s.length() && s.charAt(i + 1) == '/') return s.substring(0, i);
        }
        return s;
    }

    public static List<String> tokens(String s) throws AsmException {
        List<String> out = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        char q = 0;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (q != 0) { cur.append(c); if (c == q) q = 0; }
            else if (c == '\'' || c == '"') { q = c; cur.append(c); }
            else if (Character.isWhitespace(c) || c == ',') {
                if (cur.length() > 0) { out.add(cur.toString()); cur.setLength(0); }
            } else cur.append(c);
        }
        if (q != 0) throw new AsmException("String tidak ditutup");
        if (cur.length() > 0) out.add(cur.toString());
        return out;
    }

    private static byte[] le(int n, int v) {
        ByteBuffer b = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN);
        if (n == 2) b.putShort((short) v); else b.putInt(v);
        return b.array();
    }

    private static byte[] cat(byte first, byte[] rest) {
        byte[] r = new byte[rest.length + 1];
        r[0] = first;
        System.arraycopy(rest, 0, r, 1, rest.length);
        return r;
    }

    /** Encode satu argumen ke bytecode CLEO. */
    public static byte[] encodeArg(String t, Map<String, Integer> labels, boolean resolve) throws AsmException {
        if (t.startsWith("@")) {
            if (!LABELREF.matcher(t).matches()) throw new AsmException("Nama label tidak valid: " + t);
            int off = 0;
            if (resolve) {
                Integer p = labels.get(t.substring(1).toUpperCase());
                if (p == null) throw new AsmException("Label tidak ditemukan: " + t.substring(1));
                off = -p;
            }
            return cat((byte) 0x01, le(4, off));
        }
        if (t.length() >= 2 && (t.charAt(0) == '\'' || t.charAt(0) == '"') && t.charAt(t.length() - 1) == t.charAt(0)) {
            String body = t.substring(1, t.length() - 1);
            byte[] bs = body.getBytes(CS);
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            if (t.charAt(0) == '\'') {
                if (bs.length > 8) throw new AsmException("String '" + body + "' lebih dari 8 karakter (pakai \"...\" untuk string panjang)");
                o.write(0x09);
                o.write(bs, 0, bs.length);
                for (int i = bs.length; i < 8; i++) o.write(0);
            } else {
                if (bs.length > 255) throw new AsmException("String terlalu panjang (maks 255)");
                o.write(0x0E);
                o.write(bs.length);
                o.write(bs, 0, bs.length);
            }
            return o.toByteArray();
        }
        if (LOCAL.matcher(t).matches()) {
            int n = parseSafe(t.substring(0, t.length() - 1));
            if (n < 0 || n > 65535) throw new AsmException("Variabel lokal di luar jangkauan: " + t);
            return cat((byte) 0x03, le(2, n));
        }
        if (GLOBAL.matcher(t).matches()) {
            int n = parseSafe(t.substring(1));
            if (n < 0 || n * 4 > 65535) throw new AsmException("Variabel global di luar jangkauan: " + t);
            return cat((byte) 0x02, le(2, n * 4));
        }
        if (FLOAT.matcher(t).matches()) {
            String f = t.endsWith("f") ? t.substring(0, t.length() - 1) : t;
            return cat((byte) 0x06, le(4, Float.floatToIntBits(Float.parseFloat(f))));
        }
        long v;
        if (INT.matcher(t).matches()) {
            try { v = Long.parseLong(t); } catch (NumberFormatException e) { throw new AsmException("Angka terlalu besar: " + t); }
            if (v < Integer.MIN_VALUE || v > 0xFFFFFFFFL) throw new AsmException("Angka di luar jangkauan 32-bit: " + t);
        } else if (HEXINT.matcher(t).matches()) {
            v = Long.parseLong(t.substring(2), 16);
        } else {
            throw new AsmException("Argumen tidak valid: " + t);
        }
        if (v >= -128 && v <= 127) return new byte[]{0x04, (byte) v};
        if (v >= -32768 && v <= 32767) return cat((byte) 0x05, le(2, (int) v));
        return cat((byte) 0x01, le(4, (int) v));
    }

    private static int parseSafe(String s) {
        try { return Integer.parseInt(s); } catch (Exception e) { return -1; }
    }
}
