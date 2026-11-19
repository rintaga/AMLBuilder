package com.aml.builder;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;

public class Compiler {

    /** Kompilasi format hex (XXXX: argumen) + label ke bytecode CLEO. */
    public static byte[] compile(String src) throws AsmException {
        String[] lines = src.split("\n", -1);
        Map<String, Integer> labels = new HashMap<>();
        int pos = 0;
        // pass 1: hitung posisi label
        for (int i = 0; i < lines.length; i++) {
            String l = Asm.strip(lines[i]).trim();
            if (l.isEmpty() || l.startsWith("{")) continue;
            Matcher lm = Linter.LABEL.matcher(l);
            if (lm.matches()) {
                String n = lm.group(1).toUpperCase();
                if (labels.containsKey(n)) throw new AsmException(i + 1, "Label ganda: " + lm.group(1));
                labels.put(n, pos);
                continue;
            }
            Matcher hm = Linter.HEX.matcher(l);
            if (!hm.matches()) throw new AsmException(i + 1, "Format tidak dikenal. Gunakan: XXXX: argumen");
            int op = Integer.parseInt(hm.group(1), 16);
            try {
                pos += 2;
                for (String t : Asm.tokens(hm.group(2))) pos += Asm.encodeArg(t, null, false).length;
                Integer ac = OpcodeDb.argcOf(op & 0x7FFF);
                if (ac != null && ac == -1) pos += 1;
            } catch (AsmException e) { throw new AsmException(i + 1, e.getMessage()); }
        }
        // pass 2: tulis bytecode
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < lines.length; i++) {
            String l = Asm.strip(lines[i]).trim();
            if (l.isEmpty() || l.startsWith("{") || Linter.LABEL.matcher(l).matches()) continue;
            Matcher hm = Linter.HEX.matcher(l);
            hm.matches();
            int op = Integer.parseInt(hm.group(1), 16);
            try {
                out.write(op & 0xFF);
                out.write((op >> 8) & 0xFF);
                for (String t : Asm.tokens(hm.group(2))) {
                    byte[] a = Asm.encodeArg(t, labels, true);
                    out.write(a, 0, a.length);
                }
                Integer ac = OpcodeDb.argcOf(op & 0x7FFF);
                if (ac != null && ac == -1) out.write(0);
            } catch (AsmException e) { throw new AsmException(i + 1, e.getMessage()); }
        }
        return out.toByteArray();
    }

    /** Dekompilasi bytecode ke format hex. Opcode tak dikenal -> sisa byte dibuang sebagai komentar hex. */
    public static String decompile(byte[] d) {
        StringBuilder sb = new StringBuilder("{$CLEO .csa}\n// didekompilasi oleh AML Builder\n");
        ByteBuffer bb = ByteBuffer.wrap(d).order(ByteOrder.LITTLE_ENDIAN);
        while (bb.remaining() >= 2) {
            int start = bb.position();
            int op = bb.getShort() & 0xFFFF;
            Integer argc = OpcodeDb.argcOf(op & 0x7FFF);
            if (argc == null) {
                sb.append(String.format("// opcode %04X tidak dikenal di offset %d, sisa byte:\n", op, start));
                dump(sb, d, start);
                return sb.toString();
            }
            StringBuilder line = new StringBuilder(String.format("%04X:", op));
            try {
                if (argc >= 0) {
                    for (int k = 0; k < argc; k++) line.append(' ').append(readArg(bb));
                } else {
                    while (true) {
                        int t = bb.get(bb.position()) & 0xFF;
                        if (t == 0) { bb.get(); break; }
                        line.append(' ').append(readArg(bb));
                    }
                }
            } catch (RuntimeException e) {
                sb.append(String.format("// gagal membaca argumen opcode %04X di offset %d, sisa byte:\n", op, start));
                dump(sb, d, start);
                return sb.toString();
            }
            sb.append(line).append('\n');
        }
        return sb.toString();
    }

    private static void dump(StringBuilder sb, byte[] d, int from) {
        sb.append("// ");
        for (int i = from; i < d.length; i++) {
            sb.append(String.format("%02X ", d[i] & 0xFF));
            if ((i - from) % 16 == 15) sb.append("\n// ");
        }
        sb.append('\n');
    }

    private static String readArg(ByteBuffer bb) {
        int t = bb.get() & 0xFF;
        switch (t) {
            case 0x01: return String.valueOf(bb.getInt());
            case 0x02: return "$" + ((bb.getShort() & 0xFFFF) / 4);
            case 0x03: return (bb.getShort() & 0xFFFF) + "@";
            case 0x04: return String.valueOf(bb.get());
            case 0x05: return String.valueOf(bb.getShort());
            case 0x06: {
                float f = bb.getFloat();
                String s = String.valueOf(f);
                return s.contains("E") ? String.format(java.util.Locale.US, "%.6f", f) : s;
            }
            case 0x09: {
                byte[] b = new byte[8];
                bb.get(b);
                int n = 0;
                while (n < 8 && b[n] != 0) n++;
                return "'" + new String(b, 0, n, Asm.CS) + "'";
            }
            case 0x0E: {
                int n = bb.get() & 0xFF;
                byte[] b = new byte[n];
                bb.get(b);
                return "\"" + new String(b, Asm.CS) + "\"";
            }
            default: throw new IllegalStateException("tipe argumen " + t);
        }
    }
}
