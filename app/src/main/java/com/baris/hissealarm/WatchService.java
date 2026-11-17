package com.baris.hissealarm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Build;
import android.os.IBinder;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import org.json.JSONArray;
import org.json.JSONObject;

// Uygulama kapalıyken de çalışır: fiyatı çeker, limit ± aralık aşılınca bildirim gönderir.
public class WatchService extends Service {
    static final long PERIOD_MS = 30000;
    volatile boolean run = false;
    Thread t;

    @Override public IBinder onBind(Intent i) { return null; }

    @Override public int onStartCommand(Intent intent, int flags, int startId) {
        channels();
        Notification n = builder("watch").setContentTitle("Hisse Alarm çalışıyor")
                .setContentText("Fiyatlar arka planda izleniyor").setOngoing(true)
                .setSmallIcon(android.R.drawable.ic_menu_info_details).build();
        if (Build.VERSION.SDK_INT >= 34) startForeground(1, n, android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        else startForeground(1, n);
        if (t == null || !t.isAlive()) {
            run = true;
            t = new Thread(this::loop);
            t.start();
        }
        return START_STICKY;
    }

    @Override public void onDestroy() { run = false; super.onDestroy(); }

    void loop() {
        while (run) {
            try { check(); } catch (Exception e) { /* sonraki turda tekrar dene */ }
            try { Thread.sleep(PERIOD_MS); } catch (InterruptedException e) { return; }
        }
    }

    void check() throws Exception {
        SharedPreferences p = getSharedPreferences("hisse", MODE_PRIVATE);
        JSONArray list = new JSONArray(p.getString("list", "[]"));
        JSONObject states = new JSONObject(p.getString("states", "{}"));
        for (int i = 0; i < list.length(); i++) {
            JSONObject it = list.getJSONObject(i);
            String sym = it.getString("symbol");
            double limit = it.getDouble("limit"), band = it.getDouble("band");
            double price;
            try { price = fetch(sym); } catch (Exception e) { continue; }
            String key = sym + "|" + limit + "|" + band;
            String z = price >= limit + band ? "up" : price <= limit - band ? "down" : "in";
            if (!z.equals("in") && !z.equals(states.optString(key, "in"))) {
                String yon = z.equals("up") ? "YUKARI" : "AŞAĞI";
                notifyAlert(i, sym + " " + yon + " çıktı",
                        String.format(java.util.Locale.US, "Fiyat %.2f (limit %.2f, aralık ±%.2f)", price, limit, band));
            }
            states.put(key, z);
        }
        p.edit().putString("states", states.toString()).apply();
    }

    double fetch(String sym) throws Exception {
        URL u = new URL("https://query1.finance.yahoo.com/v8/finance/chart/" + URLEncoder.encode(sym, "UTF-8") + "?interval=1m&range=1d");
        HttpURLConnection c = (HttpURLConnection) u.openConnection();
        c.setConnectTimeout(10000); c.setReadTimeout(10000);
        c.setRequestProperty("User-Agent", "Mozilla/5.0 (HisseAlarm)");
        try {
            if (c.getResponseCode() != 200) throw new Exception("HTTP " + c.getResponseCode());
            BufferedReader r = new BufferedReader(new InputStreamReader(c.getInputStream(), "UTF-8"));
            StringBuilder sb = new StringBuilder(); String l;
            while ((l = r.readLine()) != null) sb.append(l);
            return new JSONObject(sb.toString()).getJSONObject("chart").getJSONArray("result")
                    .getJSONObject(0).getJSONObject("meta").getDouble("regularMarketPrice");
        } finally { c.disconnect(); }
    }

    void notifyAlert(int id, String title, String text) {
        PendingIntent pi = PendingIntent.getActivity(this, 0, new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);
        Notification n = builder("alert").setContentTitle(title).setContentText(text)
                .setSmallIcon(android.R.drawable.ic_dialog_alert).setAutoCancel(true)
                .setContentIntent(pi).setPriority(Notification.PRIORITY_HIGH)
                .setDefaults(Notification.DEFAULT_ALL).build();
        ((NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE)).notify(100 + id, n);
    }

    Notification.Builder builder(String ch) {
        return Build.VERSION.SDK_INT >= 26 ? new Notification.Builder(this, ch) : new Notification.Builder(this);
    }

    void channels() {
        if (Build.VERSION.SDK_INT < 26) return;
        NotificationManager nm = (NotificationManager) getSystemService(Context.NOTIFICATION_SERVICE);
        nm.createNotificationChannel(new NotificationChannel("watch", "Arka plan takibi", NotificationManager.IMPORTANCE_LOW));
        nm.createNotificationChannel(new NotificationChannel("alert", "Fiyat uyarıları", NotificationManager.IMPORTANCE_HIGH));
    }
}
