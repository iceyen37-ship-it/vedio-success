package com.ansimjia.swallowcare;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.annotation.NonNull;
import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;

import com.google.firebase.messaging.FirebaseMessagingService;
import com.google.firebase.messaging.RemoteMessage;

/**
 * 手機通知：App 在背景時由系統直接顯示；App 正在使用時，由這裡顯示同一則通知。
 * 新的通知代碼不用在這裡處理，網頁每次打開時都會重新登記。
 */
public class PushService extends FirebaseMessagingService {

    static final String CHANNEL_ID = "general";

    static void ensureChannel(Context ctx) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        NotificationManager nm = ctx.getSystemService(NotificationManager.class);
        if (nm == null || nm.getNotificationChannel(CHANNEL_ID) != null) return;
        NotificationChannel ch = new NotificationChannel(CHANNEL_ID, "安心呷通知", NotificationManager.IMPORTANCE_HIGH);
        ch.setDescription("新紀錄、回診提醒、新版本");
        nm.createNotificationChannel(ch);
    }

    @Override
    public void onMessageReceived(@NonNull RemoteMessage msg) {
        RemoteMessage.Notification n = msg.getNotification();
        if (n == null) return;
        ensureChannel(this);
        Intent open = new Intent(this, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP | Intent.FLAG_ACTIVITY_CLEAR_TOP)
                .putExtra("link", msg.getData().get("link"));
        PendingIntent pi = PendingIntent.getActivity(this, (int) System.currentTimeMillis(), open,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE);
        NotificationCompat.Builder b = new NotificationCompat.Builder(this, CHANNEL_ID)
                .setSmallIcon(R.drawable.ic_stat_notify)
                .setColor(ContextCompat.getColor(this, R.color.primary))
                .setContentTitle(n.getTitle())
                .setContentText(n.getBody())
                .setStyle(new NotificationCompat.BigTextStyle().bigText(n.getBody()))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(pi);
        try {
            NotificationManagerCompat.from(this).notify((int) (System.currentTimeMillis() % 100000), b.build());
        } catch (SecurityException ignored) {
            // 使用者沒有允許通知
        }
    }

    @Override
    public void onNewToken(@NonNull String token) { }
}
