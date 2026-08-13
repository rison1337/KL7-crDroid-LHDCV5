package com.rison.lhdccontrol;

import android.app.Activity;
import android.graphics.Color;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.util.Base64;
import android.view.View;
import android.view.WindowInsets;
import android.widget.ArrayAdapter;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.Spinner;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.io.OutputStreamWriter;
import java.io.PrintWriter;

public final class MainActivity extends Activity {
    private static final String BRIDGE =
            "/data/adb/modules/lhdcv5_kl7/controller/LhdcControlBridge.jar";
    private static final String TOKEN = "KL7-1776559493-LHDC-114";
    private static final long LHDC_FEATURE_MAGIC = 0x5c000000L;
    private static final long LHDC_QUALITY_MAGIC = 0x8000L;

    private final Handler handler = new Handler(Looper.getMainLooper());
    private final ArrayList<DeviceModel> devices = new ArrayList<>();
    private boolean rebuilding;
    private TextView state;
    private TextView qualityLabel;
    private Spinner deviceSpinner;
    private Spinner codecSpinner;
    private Spinner rateSpinner;
    private Spinner bitsSpinner;
    private Spinner qualitySpinner;
    private Switch lowLatencySwitch;

    private static final class DeviceModel {
        String address;
        String name;
        CodecModel current;
        final ArrayList<CodecModel> codecs = new ArrayList<>();
        @Override public String toString() { return name; }
    }

    private static final class CodecModel {
        long id;
        String name;
        int type;
        int rateMask;
        int bitsMask;
        int channelMask;
        long c1, c2, c3, c4;
        @Override public String toString() { return name; }
    }

    private static final class IntChoice {
        final String label;
        final int value;
        IntChoice(String label, int value) { this.label = label; this.value = value; }
        @Override public String toString() { return label; }
    }

    private static final class LongChoice {
        final String label;
        final long value;
        LongChoice(String label, long value) { this.label = label; this.value = value; }
        @Override public String toString() { return label; }
    }

    private static final class CommandResult {
        int exitCode;
        String output;
    }

    @Override protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle("Bluetooth Codec Control");
        buildUi();
        if (android.os.Build.VERSION.SDK_INT != 36) {
            setState("Этот контроллер собран для Android 16 (API 36).", true);
            return;
        }
        refreshAsync();
    }

    private int dp(int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }

    private TextView label(String text) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(14);
        view.setPadding(0, dp(14), 0, dp(4));
        return view;
    }

    private void buildUi() {
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(dp(20), dp(16), dp(20), dp(24));
        root.setOnApplyWindowInsetsListener((view, insets) -> {
            android.graphics.Insets safe = insets.getInsets(
                    WindowInsets.Type.statusBars() | WindowInsets.Type.displayCutout());
            view.setPadding(dp(20), safe.top + dp(16), dp(20), dp(24));
            return insets;
        });
        scroll.addView(root);

        TextView title = new TextView(this);
        title.setText("Bluetooth Codec Control");
        title.setTextSize(24);
        title.setTextColor(Color.rgb(30, 100, 210));
        title.setPadding(0, 0, 0, dp(8));
        root.addView(title);

        TextView warning = new TextView(this);
        warning.setText("Показываются только режимы, заявленные подключёнными наушниками. "
                + "LHDC ABR в этом бэкпорте остаётся "
                + "на 400 Кбит/с без автоматической ступенчатой адаптации.");
        warning.setTextSize(13);
        warning.setPadding(0, 0, 0, dp(10));
        root.addView(warning);

        state = new TextView(this);
        state.setText("Подключение к модулю и чтение Bluetooth…");
        state.setTextSize(16);
        state.setPadding(dp(12), dp(10), dp(12), dp(10));
        state.setBackgroundColor(Color.rgb(235, 242, 255));
        root.addView(state);

        root.addView(label("Устройство"));
        deviceSpinner = new Spinner(this);
        root.addView(deviceSpinner);
        root.addView(label("Кодек"));
        codecSpinner = new Spinner(this);
        root.addView(codecSpinner);
        root.addView(label("Частота"));
        rateSpinner = new Spinner(this);
        root.addView(rateSpinner);
        root.addView(label("Разрядность"));
        bitsSpinner = new Spinner(this);
        root.addView(bitsSpinner);

        qualityLabel = label("Качество / битрейт");
        root.addView(qualityLabel);
        qualitySpinner = new Spinner(this);
        root.addView(qualitySpinner);

        lowLatencySwitch = new Switch(this);
        lowLatencySwitch.setText("LHDC Low Latency (нативный режим)");
        lowLatencySwitch.setPadding(0, dp(12), 0, dp(12));
        root.addView(lowLatencySwitch);

        LinearLayout buttons = new LinearLayout(this);
        buttons.setOrientation(LinearLayout.HORIZONTAL);
        Button refresh = new Button(this);
        refresh.setText("Обновить");
        refresh.setOnClickListener(v -> refreshAsync());
        buttons.addView(refresh, new LinearLayout.LayoutParams(0, dp(52), 1));
        Button apply = new Button(this);
        apply.setText("Применить");
        apply.setOnClickListener(v -> applyAsync());
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(0, dp(52), 1);
        params.setMarginStart(dp(8));
        buttons.addView(apply, params);
        root.addView(buttons);

        deviceSpinner.setOnItemSelectedListener(new SelectionListener() {
            @Override public void selected(int position) {
                if (!rebuilding) loadDevice(position);
            }
        });
        codecSpinner.setOnItemSelectedListener(new SelectionListener() {
            @Override public void selected(int position) {
                if (!rebuilding) configureCodec(position);
            }
        });
        setContentView(scroll);
    }

    private abstract static class SelectionListener
            implements android.widget.AdapterView.OnItemSelectedListener {
        abstract void selected(int position);
        @Override public final void onItemSelected(android.widget.AdapterView<?> parent, View view,
                int position, long id) { selected(position); }
        @Override public final void onNothingSelected(android.widget.AdapterView<?> parent) { }
    }

    private void refreshAsync() {
        setState("Чтение Bluetooth через модуль…", false);
        new Thread(() -> {
            CommandResult result = bridge("list");
            handler.post(() -> {
                if (result.exitCode != 0) {
                    setState(explainRootError(result), true);
                    return;
                }
                try {
                    parseList(result.output);
                    populateDevices();
                } catch (Throwable error) {
                    showError("Ошибка разбора ответа", error);
                }
            });
        }, "codec-refresh").start();
    }

    private void applyAsync() {
        int di = deviceSpinner.getSelectedItemPosition();
        int ci = codecSpinner.getSelectedItemPosition();
        if (di < 0 || di >= devices.size()) { setState("Нет подключённых наушников.", true); return; }
        DeviceModel device = devices.get(di);
        if (ci < 0 || ci >= device.codecs.size()) { setState("Нет доступного кодека.", true); return; }
        CodecModel codec = device.codecs.get(ci);
        IntChoice rate = (IntChoice) rateSpinner.getSelectedItem();
        IntChoice bits = (IntChoice) bitsSpinner.getSelectedItem();
        if (rate == null || bits == null) { setState("Нет доступного PCM-режима.", true); return; }

        long c1 = 0, c2 = 0, c3 = 0, c4 = 0;
        if (device.current != null && device.current.id == codec.id) {
            c1 = device.current.c1; c2 = device.current.c2;
            c3 = device.current.c3; c4 = device.current.c4;
        }
        String upper = codec.name.toUpperCase(Locale.ROOT);
        if (upper.contains("LHDC")) {
            LongChoice q = (LongChoice) qualitySpinner.getSelectedItem();
            c1 = q == null ? (LHDC_QUALITY_MAGIC | 9) : q.value;
            c2 = lowLatencySwitch.isChecked() ? 1 : 0;
            if ((c3 & 0xff000000L) != LHDC_FEATURE_MAGIC) c3 = LHDC_FEATURE_MAGIC;
        } else if (upper.contains("LDAC")) {
            LongChoice q = (LongChoice) qualitySpinner.getSelectedItem();
            c1 = q == null ? 1003 : q.value;
        }
        String command = "set " + device.address + " " + codec.id + " " + rate.value + " "
                + bits.value + " " + c1 + " " + c2 + " " + c3 + " " + c4;
        setState("Применение: " + codec.name + "…", false);
        new Thread(() -> {
            CommandResult result = bridge(command);
            handler.post(() -> {
                if (result.exitCode != 0 || !result.output.contains("APPLIED\t")) {
                    setState("Режим не применён:\n" + compact(result.output), true);
                } else {
                    setState("Применено ✓ Проверка фактического режима…", false);
                    refreshAsync();
                }
            });
        }, "codec-apply").start();
    }

    private synchronized CommandResult bridge(String arguments) {
        CommandResult result = new CommandResult();
        try {
            File directory = getFilesDir();
            File request = new File(directory, "codec-request");
            File response = new File(directory, "codec-response");
            String id = Long.toHexString(System.nanoTime())
                    + Integer.toHexString(android.os.Process.myPid());
            new FileOutputStream(response, false).close();
            try (PrintWriter writer = new PrintWriter(new OutputStreamWriter(
                    new FileOutputStream(request, false), StandardCharsets.UTF_8), true)) {
                writer.println(TOKEN + "\t" + id + "\t" + arguments);
            }

            long deadline = android.os.SystemClock.elapsedRealtime() + 65000;
            while (android.os.SystemClock.elapsedRealtime() < deadline) {
                if (response.length() > 0) {
                    StringBuilder output = new StringBuilder();
                    boolean matching = false;
                    boolean complete = false;
                    try (BufferedReader reader = new BufferedReader(new InputStreamReader(
                            new FileInputStream(response), StandardCharsets.UTF_8))) {
                        String line;
                        while ((line = reader.readLine()) != null) {
                            if (line.equals("RESULT\t" + id)) {
                                matching = true;
                            } else if (matching && line.startsWith("EXIT\t")) {
                                result.exitCode = Integer.parseInt(line.substring(5));
                                complete = true;
                            } else if (matching) {
                                output.append(line).append('\n');
                            }
                        }
                    }
                    if (complete) {
                        result.output = output.toString();
                        return result;
                    }
                }
                Thread.sleep(50);
            }
            result.exitCode = 124;
            result.output = "Timeout waiting for controller service";
        } catch (Throwable error) {
            result.exitCode = 127;
            result.output = error.getClass().getSimpleName() + ": " + error.getMessage();
        }
        return result;
    }

    private void parseList(String output) {
        devices.clear();
        for (String line : output.split("\\r?\\n")) {
            String[] p = line.split("\\t", -1);
            if (p.length < 1) continue;
            if ("DEVICE".equals(p[0]) && p.length >= 3) {
                DeviceModel d = new DeviceModel();
                d.address = p[1]; d.name = decode(p[2]); devices.add(d);
            } else if (("CURRENT".equals(p[0]) || "CAP".equals(p[0])) && p.length >= 13) {
                DeviceModel d = findDevice(p[1]);
                if (d == null) continue;
                CodecModel c = new CodecModel();
                c.id = Long.parseLong(p[2]); c.name = decode(p[3]);
                c.type = Integer.parseInt(p[4]); c.rateMask = Integer.parseInt(p[5]);
                c.bitsMask = Integer.parseInt(p[6]); c.channelMask = Integer.parseInt(p[7]);
                c.c1 = Long.parseLong(p[8]); c.c2 = Long.parseLong(p[9]);
                c.c3 = Long.parseLong(p[10]); c.c4 = Long.parseLong(p[11]);
                if ("CURRENT".equals(p[0])) d.current = c; else d.codecs.add(c);
            }
        }
    }

    private DeviceModel findDevice(String address) {
        for (DeviceModel d : devices) if (d.address.equals(address)) return d;
        return null;
    }

    private void populateDevices() {
        rebuilding = true;
        deviceSpinner.setAdapter(adapter(devices));
        rebuilding = false;
        if (devices.isEmpty()) {
            setState("Нет подключённого A2DP-устройства.", true);
        } else {
            loadDevice(0);
        }
    }

    private void loadDevice(int position) {
        if (position < 0 || position >= devices.size()) return;
        DeviceModel device = devices.get(position);
        int selected = 0;
        for (int i = 0; i < device.codecs.size(); i++)
            if (device.current != null && device.codecs.get(i).id == device.current.id) selected = i;
        rebuilding = true;
        codecSpinner.setAdapter(adapter(device.codecs));
        if (!device.codecs.isEmpty()) codecSpinner.setSelection(selected);
        rebuilding = false;
        if (!device.codecs.isEmpty()) configureCodec(selected);
        setState(formatCurrent(device), false);
    }

    private void configureCodec(int position) {
        int di = deviceSpinner.getSelectedItemPosition();
        if (di < 0 || di >= devices.size()) return;
        DeviceModel device = devices.get(di);
        if (position < 0 || position >= device.codecs.size()) return;
        CodecModel codec = device.codecs.get(position);
        boolean isCurrent = device.current != null && device.current.id == codec.id;
        ArrayList<IntChoice> rates = sampleRates(codec.rateMask);
        ArrayList<IntChoice> bits = bitDepths(codec.bitsMask);
        rebuilding = true;
        rateSpinner.setAdapter(adapter(rates)); bitsSpinner.setAdapter(adapter(bits));
        if (isCurrent) {
            selectInt(rateSpinner, rates, device.current.rateMask);
            selectInt(bitsSpinner, bits, device.current.bitsMask);
        }
        String upper = codec.name.toUpperCase(Locale.ROOT);
        boolean lhdc = upper.contains("LHDC");
        boolean ldac = upper.contains("LDAC");
        qualityLabel.setVisibility(lhdc || ldac ? View.VISIBLE : View.GONE);
        qualitySpinner.setVisibility(lhdc || ldac ? View.VISIBLE : View.GONE);
        lowLatencySwitch.setVisibility(lhdc ? View.VISIBLE : View.GONE);
        if (lhdc) {
            ArrayList<LongChoice> q = lhdcQualities(); qualitySpinner.setAdapter(adapter(q));
            selectLong(qualitySpinner, q, isCurrent ? device.current.c1 : 0x8009L);
            lowLatencySwitch.setChecked(isCurrent && (device.current.c2 & 1) != 0);
        } else if (ldac) {
            ArrayList<LongChoice> q = ldacQualities(); qualitySpinner.setAdapter(adapter(q));
            selectLong(qualitySpinner, q, isCurrent ? device.current.c1 : 1003);
        }
        rebuilding = false;
    }

    private String formatCurrent(DeviceModel d) {
        if (d.current == null) return d.name + "\nТекущий кодек неизвестен";
        CodecModel c = d.current;
        StringBuilder s = new StringBuilder(d.name).append('\n').append(c.name).append(" · ")
                .append(rateLabel(c.rateMask)).append(" · ").append(bitsLabel(c.bitsMask));
        String upper = c.name.toUpperCase(Locale.ROOT);
        if (upper.contains("LHDC")) s.append('\n').append(lhdcLabel(c.c1))
                .append((c.c2 & 1) != 0 ? " · Low Latency ON" : " · Low Latency OFF");
        else if (upper.contains("LDAC")) s.append('\n').append(ldacLabel(c.c1));
        return s.toString();
    }

    private ArrayList<IntChoice> sampleRates(int mask) {
        ArrayList<IntChoice> out = new ArrayList<>();
        add(out, mask, 1, "44,1 кГц"); add(out, mask, 2, "48 кГц");
        add(out, mask, 4, "88,2 кГц"); add(out, mask, 8, "96 кГц");
        add(out, mask, 16, "176,4 кГц"); add(out, mask, 32, "192 кГц");
        if (out.isEmpty()) out.add(new IntChoice("Авто", 0)); return out;
    }

    private ArrayList<IntChoice> bitDepths(int mask) {
        ArrayList<IntChoice> out = new ArrayList<>();
        add(out, mask, 1, "16 бит"); add(out, mask, 2, "24 бит");
        add(out, mask, 4, "32 бит"); if (out.isEmpty()) out.add(new IntChoice("Авто", 0));
        return out;
    }

    private void add(ArrayList<IntChoice> out, int mask, int value, String label) {
        if ((mask & value) != 0) out.add(new IntChoice(label, value));
    }

    private ArrayList<LongChoice> lhdcQualities() {
        ArrayList<LongChoice> out = new ArrayList<>();
        String[] names = { "64 Кбит/с", "160 Кбит/с", "192 Кбит/с", "256 Кбит/с",
                "320 Кбит/с", "400 Кбит/с", "500 Кбит/с", "900 Кбит/с",
                "1000 Кбит/с (96 кГц)", "ABR (400; автоадаптации нет)" };
        for (int i = 0; i < names.length; i++) out.add(new LongChoice(names[i], 0x8000L | i));
        return out;
    }

    private ArrayList<LongChoice> ldacQualities() {
        ArrayList<LongChoice> out = new ArrayList<>();
        out.add(new LongChoice("990 Кбит/с", 1000)); out.add(new LongChoice("660 Кбит/с", 1001));
        out.add(new LongChoice("330 Кбит/с", 1002)); out.add(new LongChoice("Adaptive / ABR", 1003));
        return out;
    }

    private String lhdcLabel(long v) {
        String[] n = { "64 Кбит/с", "160 Кбит/с", "192 Кбит/с", "256 Кбит/с",
                "320 Кбит/с", "400 Кбит/с", "500 Кбит/с", "900 Кбит/с",
                "1000 Кбит/с", "ABR (фактически 400 Кбит/с)" };
        int i = (int)(v & 0xf); return i >= 0 && i < n.length ? n[i] : "LHDC quality=" + v;
    }

    private String ldacLabel(long v) {
        switch ((int)(v % 10)) { case 0: return "990 Кбит/с"; case 1: return "660 Кбит/с";
            case 2: return "330 Кбит/с"; case 3: return "Adaptive / ABR";
            default: return "LDAC quality=" + v; }
    }

    private String rateLabel(int v) {
        switch (v) { case 1: return "44,1 кГц"; case 2: return "48 кГц";
            case 4: return "88,2 кГц"; case 8: return "96 кГц";
            case 16: return "176,4 кГц"; case 32: return "192 кГц";
            default: return "частота=" + v; }
    }

    private String bitsLabel(int v) {
        switch (v) { case 1: return "16 бит"; case 2: return "24 бит";
            case 4: return "32 бит"; default: return "битность=" + v; }
    }

    private static String decode(String value) {
        return new String(Base64.decode(value, Base64.NO_WRAP | Base64.URL_SAFE),
                StandardCharsets.UTF_8);
    }

    private String explainRootError(CommandResult r) {
        return "Сервис контроллера не отвечает (код " + r.exitCode + "). "
                + "Убедитесь, что модуль 1.1.4 включён, и перезагрузите телефон.\n" + compact(r.output);
    }

    private String compact(String value) {
        if (value == null || value.trim().isEmpty()) return "нет вывода";
        value = value.trim(); return value.length() > 400 ? value.substring(0, 400) : value;
    }

    private <T> ArrayAdapter<T> adapter(List<T> values) {
        ArrayAdapter<T> a = new ArrayAdapter<>(this, android.R.layout.simple_spinner_item, values);
        a.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item); return a;
    }

    private void selectInt(Spinner s, ArrayList<IntChoice> a, int v) {
        for (int i = 0; i < a.size(); i++) if (a.get(i).value == v) { s.setSelection(i); return; }
    }

    private void selectLong(Spinner s, ArrayList<LongChoice> a, long v) {
        for (int i = 0; i < a.size(); i++) if (a.get(i).value == v) { s.setSelection(i); return; }
    }

    private void setState(String message, boolean error) {
        state.setText(message);
        state.setBackgroundColor(error ? Color.rgb(255, 232, 232) : Color.rgb(235, 242, 255));
    }

    private void showError(String prefix, Throwable error) {
        String message = prefix + ": " + error.getClass().getSimpleName() + " — " + error.getMessage();
        setState(message, true); Toast.makeText(this, message, Toast.LENGTH_LONG).show();
    }

    @Override protected void onDestroy() {
        handler.removeCallbacksAndMessages(null);
        super.onDestroy();
    }
}
