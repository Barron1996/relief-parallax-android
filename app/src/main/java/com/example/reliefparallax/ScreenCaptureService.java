package com.example.reliefparallax;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.os.Build;
import android.os.IBinder;
import android.util.Log;
import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * 抓屏授权令牌持有者。
 *
 * 为什么不把 VirtualDisplay 放这里？
 *   MediaProjection 必须挂到「输出端那一侧的 GL 上下文」。本项目的
 *   输出端是 OverlayService 里的 GLSurfaceView，所以 VirtualDisplay 由
 *   OverlayService 创建，本服务只负责：
 *     1. 以 mediaProjection 类型做前台服务（Android 14 强制要求）；
 *     2. 持有 MediaProjection 实例，保证授权在系统层面有效；
 *     3. 把 resultCode / resultData 通过静态字段交给 OverlayService。
 *
 * 两个服务运行在同一进程（同一 uid），所以静态传递是安全的。
 */
public class ScreenCaptureService extends Service {

    private static final String TAG = "ScreenCaptureService";
    private static final String CH_ID = "relief_capture";
    private static final int NOTIF_ID = 1002;

    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";

    /** 最近一次抓屏授权结果（供 OverlayService 取用） */
    public static volatile int lastCode = 0;
    @Nullable public static volatile Intent lastData = null;
    private static volatile MediaProjection projection = null;

    public static boolean hasProjection() { return projection != null; }

    @Nullable
    public static MediaProjection peek() { return projection; }

    public static void stopProjection() {
        MediaProjection p = projection;
        projection = null;
        if (p != null) {
            try { p.stop(); } catch (Exception ignore) {}
        }
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForegroundCompat();

        if (intent != null && intent.hasExtra(EXTRA_CODE)) {
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            if (code == 0 || data == null) {
                Log.w(TAG, "抓屏授权数据无效，停止");
                stopSelf();
                return START_NOT_STICKY;
            }

            // 释放旧授权
            stopProjection();

            MediaProjectionManager mpm =
                    (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
            try {
                MediaProjection p = mpm.getMediaProjection(code, data);
                if (p == null) {
                    Log.e(TAG, "getMediaProjection 返回 null（授权可能被 ROM 拦截）");
                    stopSelf();
                    return START_NOT_STICKY;
                }
                projection = p;
                lastCode = code;
                lastData = data;
                Log.i(TAG, "MediaProjection 已获授权");
            } catch (Exception e) {
                Log.e(TAG, "创建 MediaProjection 失败", e);
                stopSelf();
                return START_NOT_STICKY;
            }

            // 通知覆盖层可以开始抓屏了
            Intent kick = new Intent(this, OverlayService.class);
            kick.setAction(OverlayService.ACTION_CAPTURE_READY);
            try { startService(kick); } catch (Exception e) {
                Log.w(TAG, "拉起 OverlayService 失败", e);
            }
        }

        return START_NOT_STICKY;
    }

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "Relief 抓屏", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);
        Notification n = new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("浮雕视差 · 抓屏中")
                .setContentText("屏幕内容正在实时处理")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }

    @Override
    public void onDestroy() {
        stopProjection();
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}
