package com.xunflash.bandlock;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.CheckBox;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class MainActivity extends Activity {
    private final Map<String, Row> rows = new LinkedHashMap<>();
    private TextView current;
    private TextView status;
    private ProgressBar progress;
    private Button rescan;
    private Button lockNr;
    private Button lockLte;
    private boolean started;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        current = findViewById(R.id.current);
        status = findViewById(R.id.status);
        progress = findViewById(R.id.progress);
        rescan = findViewById(R.id.btn_rescan);
        lockNr = findViewById(R.id.btn_nr);
        lockLte = findViewById(R.id.btn_lte);
        Button reset = findViewById(R.id.btn_reset);
        seed(findViewById(R.id.nr_list), true, Band.NR);
        seed(findViewById(R.id.lte_list), false, Band.LTE);
        if (Build.VERSION.SDK_INT >= 33
                && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.POST_NOTIFICATIONS}, 1);
        }
        rescan.setOnClickListener(v -> beginScan());
        lockNr.setOnClickListener(v -> lock(true));
        lockLte.setOnClickListener(v -> lock(false));
        reset.setOnClickListener(v -> {
            Intent i = new Intent(this, ScanService.class);
            i.putExtra("action", "reset");
            startForegroundService(i);
            setBusy(true);
            status.setText("正在恢复系统默认");
        });
        attach();
        if (ScanService.engine == null || !ScanService.engine.running()) beginScan();
        else setBusy(true);
    }

    @Override
    protected void onResume() {
        super.onResume();
        attach();
    }

    private void beginScan() {
        Intent i = new Intent(this, ScanService.class);
        i.putExtra("action", "scan");
        startForegroundService(i);
        setBusy(true);
        status.setText("准备测试。请允许 root。");
        started = true;
    }

    private void attach() {
        if (ScanService.engine == null) ScanService.engine = new BandEngine(this);
        ScanService.engine.setUi(new BandEngine.Ui() {
            @Override public void onCurrent(String text) { run(() -> current.setText(text)); }
            @Override public void onStatus(String text, int value) {
                run(() -> {
                    status.setText(text);
                    progress.setProgress(value);
                    if (ScanService.instance != null) ScanService.instance.note(text);
                    setBusy(true);
                });
            }
            @Override public void onBand(Band band) { run(() -> paint(band)); }
            @Override public void onIdle(String text) {
                run(() -> {
                    status.setText(text);
                    progress.setProgress(100);
                    current.setText(text);
                    setBusy(false);
                    markFastest();
                    if (ScanService.instance != null) {
                        ScanService.instance.stopForeground(true);
                        ScanService.instance.stopSelf();
                    }
                });
            }
        });
    }

    private void seed(LinearLayout parent, boolean nr, int[] bands) {
        LayoutInflater inf = LayoutInflater.from(this);
        for (int n : bands) {
            Band band = new Band(nr, n);
            View view = inf.inflate(R.layout.row_band, parent, false);
            Row row = new Row(band, view.findViewById(R.id.check), view.findViewById(R.id.name), view.findViewById(R.id.detail));
            row.name.setText(band.title());
            row.detail.setText("等待");
            parent.addView(view);
            rows.put(band.key(), row);
        }
    }

    private void paint(Band band) {
        Row row = rows.get(band.key());
        if (row == null) return;
        row.band = band;
        row.check.setChecked(band.checked);
        row.name.setText(band.title());
        row.detail.setText(band.detail());
        int color = !band.camped ? 0xFF1b1e18 : (band.rsrp != null && band.rsrp >= -100 ? 0xFF24301a : 0xFF2a2618);
        ((View) row.name.getParent().getParent()).setBackgroundColor(color);
    }

    private void markFastest() {
        Band bestNr = null;
        Band bestLte = null;
        for (Row row : rows.values()) {
            if (row.band.mbps == null) continue;
            if (row.band.nr) {
                if (bestNr == null || row.band.mbps > bestNr.mbps) bestNr = row.band;
            } else if (bestLte == null || row.band.mbps > bestLte.mbps) bestLte = row.band;
        }
        checkBest(bestNr);
        checkBest(bestLte);
    }

    private void checkBest(Band band) {
        if (band == null) return;
        Row row = rows.get(band.key());
        if (row == null) return;
        row.band.checked = true;
        row.check.setChecked(true);
        row.name.setText(band.title() + "  最快");
    }

    private void lock(boolean nr) {
        List<Band> picked = new ArrayList<>();
        for (Row row : rows.values()) {
            if (row.band.nr == nr && row.check.isChecked()) picked.add(row.band);
        }
        if (ScanService.engine == null) ScanService.engine = new BandEngine(this);
        attach();
        setBusy(true);
        ScanService.engine.lock(nr, picked);
    }

    private void setBusy(boolean busy) {
        rescan.setEnabled(!busy);
        lockNr.setEnabled(!busy);
        lockLte.setEnabled(!busy);
    }

    private void run(Runnable r) {
        runOnUiThread(r);
    }

    private static final class Row {
        Band band;
        final CheckBox check;
        final TextView name;
        final TextView detail;
        Row(Band band, CheckBox check, TextView name, TextView detail) {
            this.band = band;
            this.check = check;
            this.name = name;
            this.detail = detail;
        }
    }
}
