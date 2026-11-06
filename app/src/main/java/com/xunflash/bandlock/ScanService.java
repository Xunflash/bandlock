package com.xunflash.bandlock;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.Build;
import android.os.IBinder;

public class ScanService extends Service {
    static final String CHANNEL = "scan";
    static ScanService instance;
    static BandEngine engine;

    @Override
    public void onCreate() {
        super.onCreate();
        instance = this;
        if (engine == null) engine = new BandEngine(this);
        NotificationManager nm = getSystemService(NotificationManager.class);
        NotificationChannel ch = new NotificationChannel(CHANNEL, "测频段", NotificationManager.IMPORTANCE_LOW);
        nm.createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("频段")
                .setContentText("正在测频段")
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC);
        } else {
            startForeground(1, n);
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent == null ? "scan" : intent.getStringExtra("action");
        if ("reset".equals(action)) engine.stopToDefault();
        else engine.startScan();
        return START_NOT_STICKY;
    }

    void note(String text) {
        Notification n = new Notification.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("频段")
                .setContentText(text)
                .setOngoing(true)
                .build();
        getSystemService(NotificationManager.class).notify(1, n);
    }

    @Override
    public void onDestroy() {
        if (instance == this) instance = null;
        super.onDestroy();
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
