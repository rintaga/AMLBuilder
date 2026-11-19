package com.aml.builder;

import android.content.Context;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

public class OpcodeDb {
    public static class Op {
        public int id;
        public int argc;
        public String pattern;
    }

    public static final Map<Integer, Op> MAP = new LinkedHashMap<>();
    private static final Map<Integer, Integer> BASE = new HashMap<>();

    static {
        int[][] b = {{0x0000, 0}, {0x0001, 1}, {0x0002, 1}, {0x0004, 2}, {0x0005, 2}, {0x0006, 2},
                {0x0007, 2}, {0x0008, 2}, {0x0009, 2}, {0x000A, 2}, {0x000B, 2}, {0x004D, 1},
                {0x004E, 0}, {0x0050, 1}, {0x0051, 0}, {0x00D6, 1}};
        for (int[] e : b) BASE.put(e[0], e[1]);
    }

    public static void load(Context c) {
        if (!MAP.isEmpty()) return;
        try (BufferedReader r = new BufferedReader(new InputStreamReader(c.getAssets().open("opcodes.txt"), "UTF-8"))) {
            String l;
            while ((l = r.readLine()) != null) {
                l = l.trim();
                if (l.length() < 6 || l.charAt(4) != '=') continue;
                try {
                    Op o = new Op();
                    o.id = Integer.parseInt(l.substring(0, 4), 16);
                    String rest = l.substring(5).trim();
                    int comma = rest.indexOf(',');
                    if (comma < 0) continue;
                    o.argc = Integer.parseInt(rest.substring(0, comma).trim());
                    o.pattern = rest.substring(comma + 1).trim();
                    MAP.put(o.id, o);
                } catch (Exception ignored) { }
            }
        } catch (IOException ignored) { }
    }

    /** Jumlah argumen opcode (-1 = variadic), null jika tidak dikenal. */
    public static Integer argcOf(int base) {
        Op o = MAP.get(base);
        if (o != null) return o.argc;
        return BASE.get(base);
    }
}
