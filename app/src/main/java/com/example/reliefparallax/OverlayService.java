package com.example.reliefparallax;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

/**
 * 全场景实时浮雕视差滤镜 - 屏幕覆盖层服务。
 *
 * 数据链路：
 *   MediaProjection(系统屏幕) -> VirtualDisplay -> Surface
 *      -> SurfaceTexture(GL_TEXTURE_EXTERNAL_OES) -> blit 到 texScreen(2D)
 *      -> Sobel 法线 pass -> 浮雕视差 compose pass -> 全屏透明悬浮层显示
 *
 * 关键设计：
 *  - 悬浮层加 FLAG_SECURE，使自身不在抓屏内容里，避免递归套娃。
 *  - FLAG_NOT_TOUCHABLE 让触摸穿透到下层真实 App。
 *  - VirtualDisplay 必须挂在本服务的 GL 上下文输出端，因此由本服务创建；
 *    MediaProjection 令牌由 ScreenCaptureService 持有并通过静态字段交接。
 */
public class OverlayService extends Service {

    private static final String TAG = "ReliefOverlay";

    /** 抓屏授权就绪，可以开始建立抓屏链路 */
    public static final String ACTION_CAPTURE_READY = "com.example.reliefparallax.CAPTURE_READY";
    /** 停止抓屏 */
    public static final String ACTION_STOP_CAPTURE = "com.example.reliefparallax.STOP_CAPTURE";

    public static volatile boolean isRunning = false;

    private static final String CH_ID = "relief_overlay";
    private static final int NOTIF_ID = 1001;

    private WindowManager wm;
    private GLSurfaceView glView;
    private ReliefRenderer renderer;
    private ParallaxDriver driver;
    private final FilterParams P = FilterParams.I;

    private VirtualDisplay vDisplay;
    private MediaProjection projection;
    private final Handler main = new Handler(Looper.getMainLooper());
    private int screenW = 1080;
    private int screenH = 2400;
    private int screenDpi = 440;

    @Override
    public void onCreate() {
        super.onCreate();
        isRunning = true;
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        startForeground(NOTIF_ID, buildNotification());
        String action = intent != null ? intent.getAction() : null;

        if (glView == null) addOverlayView();

        if (ACTION_STOP_CAPTURE.equals(action)) {
            stopCapture();
        } else if (ACTION_CAPTURE_READY.equals(action) || ScreenCaptureService.hasProjection()) {
            main.post(this::startCaptureWhenReady);
        }
        return START_STICKY;
    }

    // ---------------------------------------------------------------- 覆盖层

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
            Log.e(TAG, "addView 失败", e);
            stopSelf();
        }
    }

    // ---------------------------------------------------------------- 抓屏

    /** GL 线程建好外部纹理之前 getScreenSurface() 会返回 null，这里做有限次重试 */
    private void startCaptureWhenReady() { startCaptureWhenReady(0); }

    private void startCaptureWhenReady(int attempt) {
        if (vDisplay != null) return; // 已在抓屏

        MediaProjection mp = ScreenCaptureService.peek();
        if (mp == null) {
            Log.w(TAG, "没有有效的抓屏授权，忽略");
            return;
        }
        if (renderer == null) {
            Log.w(TAG, "renderer 尚未创建");
            return;
        }

        // 让 Renderer 进入抓屏模式，并在 GL 线程创建 OES 纹理
        renderer.attachScreenSurface();
        Surface surface = renderer.getScreenSurface();

        if (surface == null) {
            if (attempt < 60) { // 最多等约 3 秒
                main.postDelayed(() -> startCaptureWhenReady(attempt + 1), 50);
            } else {
                Log.e(TAG, "等待 GL 外部纹理超时");
            }
            return;
        }

        measureScreen();
        projection = mp;
        try {
            vDisplay = mp.createVirtualDisplay(
                    "relief-capture",
                    screenW, screenH, screenDpi,
                    DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                    surface, null, null);
            Log.i(TAG, "VirtualDisplay 已建立 " + screenW + "x" + screenH);
        } catch (Exception e) {
            Log.e(TAG, "createVirtualDisplay 失败", e);
            renderer.detachScreen();
            vDisplay = null;
        }
    }

    private void measureScreen() {
        WindowManager w = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        if (w == null) return;
        DisplayMetrics dm = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= 30) {
            android.view.Display d = getDisplay();
            if (d != null) d.getRealMetrics(dm);
        } else {
            w.getDefaultDisplay().getRealMetrics(dm);
        }
        if (dm.widthPixels > 0) screenW = dm.widthPixels;
        if (dm.heightPixels > 0) screenH = dm.heightPixels;
        if (dm.densityDpi > 0) screenDpi = dm.densityDpi;
    }

    private void stopCapture() {
        if (vDisplay != null) {
            try { vDisplay.release(); } catch (Exception ignore) {}
            vDisplay = null;
        }
        if (renderer != null) renderer.detachScreen();
        projection = null;
        ScreenCaptureService.stopProjection();
        Log.i(TAG, "抓屏已停止");
    }

    // ---------------------------------------------------------------- 通知

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
                .setContentText("全屏实时滤镜运行中 · 点按返回 App")
                .setSmallIcon(android.R.drawable.ic_menu_view)
                .setContentIntent(pi)
                .setOngoing(true)
                .build();
    }

    @Override
    public void onDestroy() {
        isRunning = false;
        stopCapture();
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
