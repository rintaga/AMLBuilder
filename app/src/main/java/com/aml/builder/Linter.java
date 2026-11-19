package com.aml.builder;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class Linter {
    public static class Issue {
        public int line;
        public boolean error;
        public String msg;
        Issue(int l, boolean e, String m) { line = l; error = e; msg = m; }
    }

    static final Pattern LABEL = Pattern.compile("^:([A-Za-z_][A-Za-z0-9_]*)$");
    static final Pattern HEX = Pattern.compile("^([0-9A-Fa-f]{4})\\s*:(.*)$");

    public static List<Issue> run(String src) {
        List<Issue> res = new ArrayList<>();
        String[] lines = src.split("\n", -1);
        Map<String, Integer> defined = new HashMap<>();
        List<Object[]> refs = new ArrayList<>();
        boolean hasCleo = false;
        int lastOp = -1, lastLine = 0, opCount = 0;

        for (int i = 0; i < lines.length; i++) {
            String l = Asm.strip(lines[i]).trim();
            if (l.isEmpty()) continue;
            int ln = i + 1;
            if (l.startsWith("{")) {
                if (l.toUpperCase().startsWith("{$CLEO")) hasCleo = true;
                continue;
            }
            Matcher lm = LABEL.matcher(l);
            if (lm.matches()) {
                String n = lm.group(1).toUpperCase();
                if (defined.containsKey(n)) res.add(new Issue(ln, true, "Label ganda: " + lm.group(1)));
                else defined.put(n, ln);
                continue;
            }
            Matcher hm = HEX.matcher(l);
            if (!hm.matches()) {
                res.add(new Issue(ln, true, "Format tidak dikenal. Tulis: XXXX: argumen (contoh 0001: 500)"));
                continue;
            }
            int op = Integer.parseInt(hm.group(1), 16);
            List<String> toks;
            try {
                toks = Asm.tokens(hm.group(2));
            } catch (AsmException e) {
                res.add(new Issue(ln, true, e.getMessage()));
                continue;
            }
            for (String t : toks) {
                if (t.startsWith("@")) refs.add(new Object[]{ln, t});
                try { Asm.encodeArg(t, null, false); }
                catch (AsmException e) { res.add(new Issue(ln, true, e.getMessage())); }
            }
            Integer argc = OpcodeDb.argcOf(op & 0x7FFF);
            String id = String.format("%04X", op);
            if (argc == null) {
                res.add(new Issue(ln, false, "Opcode " + id + " tidak ada di daftar, jumlah argumen tidak bisa dicek"));
            } else if (argc >= 0 && argc != toks.size()) {
                res.add(new Issue(ln, false, id + " biasanya butuh " + argc + " argumen, ditemukan " + toks.size()));
            }
            lastOp = op & 0x7FFF;
            lastLine = ln;
            opCount++;
        }
        for (Object[] r : refs) {
            String n = ((String) r[1]).substring(1).toUpperCase();
            if (!defined.containsKey(n) && ((String) r[1]).matches("^@[A-Za-z_][A-Za-z0-9_]*$"))
                res.add(new Issue((Integer) r[0], true, "Label tidak ditemukan: " + ((String) r[1]).substring(1)));
        }
        if (!hasCleo) res.add(new Issue(1, false, "Tambahkan {$CLEO .csa} di baris pertama"));
        if (opCount > 0 && lastOp != 0x0A93 && lastOp != 0x004E && lastOp != 0x0002)
            res.add(new Issue(lastLine, false, "Script tidak diakhiri 0A93 (terminate) atau jump"));
        java.util.Collections.sort(res, (a, b) -> a.line - b.line);
        return res;
    }
}
