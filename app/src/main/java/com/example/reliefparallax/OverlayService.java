package com.example.reliefparallax;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.IBinder;
import android.view.Gravity;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * 悬浮窗前台服务（首版：全屏覆盖层）。
 *
 * 说明与限制：
 *  - 这是"盖一层"的形态，不是真正的屏内合成。它会把自身画面画在屏幕最上层，
 *    底下 App 的内容不可见。真正的全局滤镜需要 MediaProjection 抓屏回灌，
 *    首版先不做（国产 ROM 常拒绝硬件加速、导致黑屏或严重掉帧）。
 *  - 加 FLAG_SECURE 防止自己被录进屏幕录制，避免无限递归。
 *  - 加 FLAG_NOT_TOUCHABLE 让触摸穿透到下层，不会挡住操作。
 *  - 需要 SYSTEM_ALERT_WINDOW 权限，且部分 ROM 需在"后台弹出界面"里额外放行。
 */
public class OverlayService extends Service {

    public static volatile boolean isRunning = false;

    private static final String CH_ID = "relief_overlay";
    private static final int NOTIF_ID = 1001;

    private WindowManager wm;
    private GLSurfaceView glView;
    private ReliefRenderer renderer;
    private ParallaxDriver driver;
    private final FilterParams P = new FilterParams();

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        if (glView == null) addOverlayView();
        return START_STICKY;
    }

    private void addOverlayView() {
        wm = (WindowManager) getSystemService(WINDOW_SERVICE);

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        glView.setZOrderOnTop(true);

        driver = new ParallaxDriver(this);
        driver.start();
        P.gyro = true;
        P.drift = true;
        renderer = new ReliefRenderer(this, P, driver);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                Build.VERSION.SDK_INT >= 26
                        ? WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                        : WindowManager.LayoutParams.TYPE_PHONE,
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                        | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                        | WindowManager.LayoutParams.FLAG_SECURE
                        | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED,
                PixelFormat.TRANSLUCENT);
        lp.gravity = Gravity.TOP | Gravity.START;

        try {
            wm.addView(glView, lp);
        } catch (Exception e) {
            stopSelf();
        }
    }

    private Notification buildNotification() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            NotificationChannel ch = new NotificationChannel(
                    CH_ID, "Relief Overlay", NotificationManager.IMPORTANCE_LOW);
            ch.setShowBadge(false);
            nm.createNotificationChannel(ch);
        }
        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                Build.VERSION.SDK_INT >= 23 ? PendingIntent.FLAG_IMMUTABLE : 0);

        return new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle("浮雕视差已开启")
                .setContentText("点按返回 App 关闭")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        if (driver != null) driver.stop();
        if (glView != null && wm != null) {
            try { wm.removeView(glView); } catch (Exception ignore) {}
            glView = null;
        }
        super.onDestroy();
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }
}