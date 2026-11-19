package com.aml.builder;

import android.content.ClipData;
import android.content.ClipboardManager;
import android.content.Context;
import android.graphics.Color;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.text.Editable;
import android.text.TextWatcher;
import android.text.style.ForegroundColorSpan;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ArrayAdapter;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ListView;
import android.widget.Toast;

import androidx.activity.result.ActivityResultLauncher;
import androidx.activity.result.contract.ActivityResultContracts;
import androidx.appcompat.app.ActionBarDrawerToggle;
import androidx.appcompat.app.AlertDialog;
import androidx.appcompat.app.AppCompatActivity;
import androidx.appcompat.widget.Toolbar;
import androidx.drawerlayout.widget.DrawerLayout;

import com.google.android.material.navigation.NavigationView;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class MainActivity extends AppCompatActivity {

    private static final String TEMPLATE =
            "{$CLEO .csa}\n"
                    + "// Script CLEO GTA SA Android - dibuat dengan AML Builder\n"
                    + "0000: // NOP\n"
                    + ":MAIN\n"
                    + "0001: 1000 // wait 1000\n"
                    + "0A93: // terminate_this_custom_script\n";

    private static final Pattern P_COMMENT = Pattern.compile("//.*|\\{[^}\\n]*\\}");
    private static final Pattern P_OP = Pattern.compile("(?m)^\\s*[0-9A-Fa-f]{4}(?=\\s*:)");
    private static final Pattern P_LABEL = Pattern.compile("(?m)^\\s*:[A-Za-z_]\\w*|@[A-Za-z_]\\w*");
    private static final Pattern P_STR = Pattern.compile("'[^'\\n]*'|\"[^\"\\n]*\"");
    private static final Pattern P_VAR = Pattern.compile("\\b\\d+@|\\$\\d+");

    private DrawerLayout drawer;
    private EditText editor;
    private View emptyView;
    private MenuItem statusItem;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private List<Linter.Issue> issues = new ArrayList<>();
    private byte[] pendingBin;
    private boolean editing = false;

    private final ActivityResultLauncher<String[]> openL = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) openUri(uri, false); });
    private final ActivityResultLauncher<String[]> decompL = registerForActivityResult(
            new ActivityResultContracts.OpenDocument(), uri -> { if (uri != null) openUri(uri, true); });
    private final ActivityResultLauncher<String> saveL = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("text/plain"),
            uri -> { if (uri != null) write(uri, editor.getText().toString().getBytes(StandardCharsets.UTF_8)); });
    private final ActivityResultLauncher<String> binL = registerForActivityResult(
            new ActivityResultContracts.CreateDocument("application/octet-stream"),
            uri -> { if (uri != null && pendingBin != null) write(uri, pendingBin); });

    private final Runnable lintRun = new Runnable() {
        @Override public void run() {
            highlight(editor.getText());
            issues = Linter.run(editor.getText().toString());
            updateStatus();
        }
    };

    @Override
    protected void onCreate(Bundle b) {
        super.onCreate(b);
        OpcodeDb.load(this);
        setContentView(R.layout.activity_main);

        Toolbar tb = findViewById(R.id.toolbar);
        setSupportActionBar(tb);
        getSupportActionBar().setTitle("AML Cleo Builder");
        showSplash();
        drawer = findViewById(R.id.drawer);
        ActionBarDrawerToggle toggle = new ActionBarDrawerToggle(this, drawer, tb, R.string.open_drawer, R.string.close_drawer);
        drawer.addDrawerListener(toggle);
        toggle.syncState();

        NavigationView nav = findViewById(R.id.nav);
        nav.setNavigationItemSelectedListener(item -> {
            drawer.closeDrawers();
            onNav(item.getItemId());
            return false;
        });

        emptyView = findViewById(R.id.empty);
        editor = findViewById(R.id.editor);
        findViewById(R.id.btnOpen).setOnClickListener(v -> openL.launch(new String[]{"*/*"}));
        editor.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) {
                handler.removeCallbacks(lintRun);
                handler.postDelayed(lintRun, 500);
            }
        });
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        getMenuInflater().inflate(R.menu.toolbar_menu, menu);
        statusItem = menu.findItem(R.id.action_status);
        statusItem.setVisible(editing);
        updateStatus();
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        if (item.getItemId() == R.id.action_status) { showIssues(); return true; }
        return super.onOptionsItemSelected(item);
    }

    private void onNav(int id) {
        if (id == R.id.nav_new) {
            if (editing && editor.getText().length() > 0) {
                new AlertDialog.Builder(this).setTitle("New")
                        .setMessage("Script yang sedang dibuka akan diganti. Lanjut?")
                        .setPositiveButton("Ya", (d, w) -> showEditor(TEMPLATE))
                        .setNegativeButton("Batal", null).show();
            } else showEditor(TEMPLATE);
        } else if (id == R.id.nav_open) {
            openL.launch(new String[]{"*/*"});
        } else if (id == R.id.nav_save) {
            if (!editing) toast("Buat atau buka script dulu"); else saveL.launch("script.txt");
        } else if (id == R.id.nav_search) {
            showSearch();
        } else if (id == R.id.nav_compile) {
            compile();
        } else if (id == R.id.nav_decompile) {
            decompL.launch(new String[]{"*/*"});
        } else if (id == R.id.nav_game) {
            new AlertDialog.Builder(this).setTitle("Select Game")
                    .setSingleChoiceItems(new String[]{"GTA San Andreas (Android)"}, 0, null)
                    .setPositiveButton("OK", null).show();
        }
    }

    private void showEditor(String text) {
        editing = true;
        emptyView.setVisibility(View.GONE);
        editor.setVisibility(View.VISIBLE);
        editor.setText(text);
        invalidateOptionsMenu();
        handler.removeCallbacks(lintRun);
        handler.post(lintRun);
    }

    private void updateStatus() {
        if (statusItem == null) return;
        statusItem.setVisible(editing);
        int e = 0, w = 0;
        for (Linter.Issue i : issues) { if (i.error) e++; else w++; }
        if (e > 0) { statusItem.setIcon(R.drawable.ic_error); statusItem.setTitle(e + " kesalahan"); }
        else if (w > 0) { statusItem.setIcon(R.drawable.ic_warning); statusItem.setTitle(w + " peringatan"); }
        else { statusItem.setIcon(R.drawable.ic_check); statusItem.setTitle("Tidak ada kesalahan"); }
    }

    private void showIssues() {
        if (issues.isEmpty()) {
            new AlertDialog.Builder(this).setTitle("Status").setMessage("Tidak ada kesalahan \u2713")
                    .setPositiveButton("OK", null).show();
            return;
        }
        String[] arr = new String[issues.size()];
        for (int i = 0; i < arr.length; i++) {
            Linter.Issue s = issues.get(i);
            arr[i] = (s.error ? "\u2716 " : "\u26A0 ") + "Baris " + s.line + ": " + s.msg;
        }
        new AlertDialog.Builder(this).setTitle("Deteksi kesalahan")
                .setItems(arr, (d, i) -> goToLine(issues.get(i).line))
                .setPositiveButton("OK", null).show();
    }

    private void goToLine(int line) {
        String t = editor.getText().toString();
        int off = 0;
        for (int i = 1; i < line; i++) {
            int n = t.indexOf('\n', off);
            if (n < 0) break;
            off = n + 1;
        }
        editor.requestFocus();
        editor.setSelection(Math.min(off, t.length()));
    }

    private void compile() {
        if (!editing) { toast("Buat atau buka script dulu"); return; }
        String src = editor.getText().toString();
        issues = Linter.run(src);
        updateStatus();
        for (Linter.Issue i : issues) {
            if (i.error) { showIssues(); return; }
        }
        try {
            pendingBin = Compiler.compile(src);
            binL.launch("script.csa");
        } catch (AsmException e) {
            new AlertDialog.Builder(this).setTitle("Compile gagal")
                    .setMessage("Baris " + e.line + ": " + e.getMessage())
                    .setPositiveButton("OK", null).show();
        }
    }

    private void openUri(Uri uri, boolean forceBin) {
        try (InputStream in = getContentResolver().openInputStream(uri)) {
            ByteArrayOutputStream bo = new ByteArrayOutputStream();
            byte[] buf = new byte[8192];
            int n;
            while ((n = in.read(buf)) > 0) bo.write(buf, 0, n);
            byte[] data = bo.toByteArray();
            boolean bin = forceBin;
            for (int i = 0; i < Math.min(512, data.length) && !bin; i++) if (data[i] == 0) bin = true;
            if (bin) {
                showEditor(Compiler.decompile(data));
                toast("Didekompilasi");
            } else {
                showEditor(new String(data, StandardCharsets.UTF_8));
            }
        } catch (Exception e) {
            toast("Gagal membuka file: " + e.getMessage());
        }
    }

    private void write(Uri uri, byte[] data) {
        try (OutputStream os = getContentResolver().openOutputStream(uri, "wt")) {
            os.write(data);
            toast("Tersimpan");
        } catch (Exception e) {
            toast("Gagal menyimpan: " + e.getMessage());
        }
    }

    private void showSearch() {
        LinearLayout box = new LinearLayout(this);
        box.setOrientation(LinearLayout.VERTICAL);
        int p = dp(16);
        box.setPadding(p, p / 2, p, 0);
        EditText q = new EditText(this);
        q.setHint("Cari opcode (ID atau nama)");
        q.setSingleLine(true);
        ListView lv = new ListView(this);
        box.addView(q);
        box.addView(lv, new LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(360)));
        final List<OpcodeDb.Op> shown = new ArrayList<>();
        final List<String> labels = new ArrayList<>();
        final ArrayAdapter<String> ad = new ArrayAdapter<>(this, android.R.layout.simple_list_item_1, labels);
        lv.setAdapter(ad);
        final Runnable filter = () -> {
            String s = q.getText().toString().trim().toLowerCase();
            shown.clear();
            labels.clear();
            for (OpcodeDb.Op o : OpcodeDb.MAP.values()) {
                String id = String.format("%04X", o.id);
                if (s.isEmpty() || id.toLowerCase().contains(s) || o.pattern.toLowerCase().contains(s)) {
                    shown.add(o);
                    labels.add(id + ": " + o.pattern);
                    if (shown.size() >= 300) break;
                }
            }
            ad.notifyDataSetChanged();
        };
        q.addTextChangedListener(new TextWatcher() {
            @Override public void beforeTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void onTextChanged(CharSequence s, int a, int c, int d) { }
            @Override public void afterTextChanged(Editable s) { filter.run(); }
        });
        filter.run();
        final AlertDialog dlg = new AlertDialog.Builder(this)
                .setTitle("Search opcode (" + OpcodeDb.MAP.size() + ")")
                .setView(box).setNegativeButton("Tutup", null).create();
        lv.setOnItemClickListener((a, v, pos, id) -> { insertOpcode(shown.get(pos)); dlg.dismiss(); });
        dlg.show();
    }

    private void insertOpcode(OpcodeDb.Op o) {
        String ins = "// " + o.pattern + "\n" + String.format("%04X", o.id) + ": ";
        if (editing) {
            int pos = Math.max(0, editor.getSelectionStart());
            editor.getText().insert(pos, ins);
        } else {
            ClipboardManager cm = (ClipboardManager) getSystemService(Context.CLIPBOARD_SERVICE);
            cm.setPrimaryClip(ClipData.newPlainText("opcode", ins));
            toast("Disalin ke clipboard");
        }
    }

    private void highlight(Editable e) {
        for (ForegroundColorSpan s : e.getSpans(0, e.length(), ForegroundColorSpan.class)) e.removeSpan(s);
        apply(e, P_OP, Color.parseColor("#64B5F6"));
        apply(e, P_LABEL, Color.parseColor("#FFB74D"));
        apply(e, P_VAR, Color.parseColor("#BA68C8"));
        apply(e, P_STR, Color.parseColor("#81C784"));
        apply(e, P_COMMENT, Color.parseColor("#6C7A86"));
    }

    private void apply(Editable e, Pattern p, int color) {
        Matcher m = p.matcher(e);
        while (m.find()) e.setSpan(new ForegroundColorSpan(color), m.start(), m.end(), 0);
    }

    private void showSplash() {
        final View splash = findViewById(R.id.splash);
        android.widget.TextView t = findViewById(R.id.splashText);
        android.text.SpannableStringBuilder sb = new android.text.SpannableStringBuilder("AML Builder");
        sb.setSpan(new ForegroundColorSpan(Color.parseColor("#00E676")), 0, 1, 0);
        sb.setSpan(new ForegroundColorSpan(Color.parseColor("#2979FF")), 1, 2, 0);
        sb.setSpan(new ForegroundColorSpan(Color.parseColor("#FF1744")), 2, 3, 0);
        sb.setSpan(new ForegroundColorSpan(Color.WHITE), 3, sb.length(), 0);
        t.setText(sb);
        handler.postDelayed(() -> splash.animate().alpha(0f).setDuration(350)
                .withEndAction(() -> splash.setVisibility(View.GONE)).start(), 1300);
    }

    private int dp(int v) { return (int) (v * getResources().getDisplayMetrics().density); }

    private void toast(String s) { Toast.makeText(this, s, Toast.LENGTH_SHORT).show(); }
}
