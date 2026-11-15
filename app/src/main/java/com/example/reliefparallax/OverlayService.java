package com.example.reliefparallax;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.Context;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.graphics.PixelFormat;
import android.hardware.display.DisplayManager;
import android.hardware.display.VirtualDisplay;
import android.media.projection.MediaProjection;
import android.media.projection.MediaProjectionManager;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Handler;
import android.os.IBinder;
import android.os.Looper;
import android.util.DisplayMetrics;
import android.util.Log;
import android.view.Display;
import android.view.Gravity;
import android.view.Surface;
import android.view.WindowManager;

import androidx.annotation.Nullable;
import androidx.core.app.NotificationCompat;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 覆盖层服务：唯一持有 MediaProjection / GLSurfaceView / VirtualDisplay 的组件。
 *
 * 与旧版（双 Service + 静态交接令牌）的区别：
 *   1. 不再有 ScreenCaptureService。授权数据由 MainActivity 直接以 Intent 传入，
 *      服务内一次性换取 MediaProjection，生命周期完全自洽。
 *   2. 用显式状态机取代 60×50ms 轮询：
 *        IDLE → WAIT_GL（等 SurfaceCreated）→ CAPTURING → STOPPING
 *   3. 前台服务类型声明为 mediaProjection|specialUse（Manifest 同步），
 *      避免 Android 14 因类型不匹配抛 SecurityException。
 *   4. 覆盖层尺寸取 getRealMetrics（含状态栏/导航栏），并加
 *      FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS，使滤镜画面真正铺满
 *      含状态栏的整块屏幕。
 */
public class OverlayService extends Service {

    private static final String TAG = "OverlayService";

    public static final String ACTION_START_CAPTURE = "com.example.reliefparallax.START_CAPTURE";
    public static final String ACTION_STOP          = "com.example.reliefparallax.STOP";
    public static final String EXTRA_CODE = "code";
    public static final String EXTRA_DATA = "data";

    private static final String CH_ID    = "relief_overlay";
    private static final int    NOTIF_ID = 1001;

    /** 供 UI 查询当前是否在跑（仅用于显示状态，不参与逻辑） */
    public static volatile boolean isRunning = false;

    private enum State { IDLE, WAIT_GL, CAPTURING, STOPPING }

    private State state = State.IDLE;
    private final Handler main = new Handler(Looper.getMainLooper());

    private WindowManager wm;
    private GLSurfaceView glView;
    private ReliefRenderer renderer;
    private ParallaxDriver driver;
    private final FilterParams P = FilterParams.I;

    private MediaProjection projection;
    private VirtualDisplay  virtualDisplay;
    private int screenW, screenH, screenDpi, screenHz = 60;

    // =========================================================
    // 生命周期
    // =========================================================

    @Override
    public void onCreate() {
        super.onCreate();
        wm = (WindowManager) getSystemService(Context.WINDOW_SERVICE);
        driver = new ParallaxDriver(this);
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        // 必须先以前台服务身份启动，再谈其它
        startForegroundCompat();

        String action = intent != null ? intent.getAction() : null;

        if (ACTION_STOP.equals(action)) {
            Log.i(TAG, "收到停止指令");
            shutdown();
            stopSelf();
            return START_NOT_STICKY;
        }

        if (ACTION_START_CAPTURE.equals(action)) {
            if (state != State.IDLE) {
                Log.w(TAG, "已在运行中（state=" + state + "），忽略重复启动");
                return START_NOT_STICKY;
            }
            if (intent == null || !intent.hasExtra(EXTRA_CODE)) {
                Log.e(TAG, "缺少授权数据");
                stopSelf();
                return START_NOT_STICKY;
            }
            int code = intent.getIntExtra(EXTRA_CODE, 0);
            Intent data = intent.getParcelableExtra(EXTRA_DATA);
            if (code == 0 || data == null) {
                Log.e(TAG, "授权数据无效");
                stopSelf();
                return START_NOT_STICKY;
            }
            if (!startCaptureInternal(code, data)) {
                stopSelf();
                return START_NOT_STICKY;
            }
        }

        // 服务被系统重启且无 action：不要凭空重建抓屏（授权已失效）
        return START_NOT_STICKY;
    }

    @Nullable
    @Override
    public IBinder onBind(Intent intent) { return null; }

    @Override
    public void onDestroy() {
        Log.i(TAG, "onDestroy");
        shutdown();
        super.onDestroy();
    }

    // =========================================================
    // 核心流程
    // =========================================================

    private boolean startCaptureInternal(int code, Intent data) {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(Context.MEDIA_PROJECTION_SERVICE);
        try {
            projection = mpm.getMediaProjection(code, data);
        } catch (Exception e) {
            Log.e(TAG, "getMediaProjection 抛异常（ROM 可能拦截）", e);
            projection = null;
        }
        if (projection == null) {
            Log.e(TAG, "getMediaProjection 返回 null");
            return false;
        }

        // 立即注册回调，否则部分 ROM 在首次使用前会意外失效
        projection.registerCallback(new MediaProjection.Callback() {
            @Override public void onStop() {
                Log.w(TAG, "系统侧结束了投屏会话");
                main.post(() -> {
                    shutdown();
                    stopSelf();
                });
            }
        }, main);

        measureScreen();
        addOverlayView();

        state = State.WAIT_GL;
        isRunning = true;
        Log.i(TAG, "覆盖层已添加，等待 GL 就绪；屏幕 " + screenW + "x" + screenH
                + " @" + screenHz + "Hz  dpi=" + screenDpi);
        return true;
    }

    /** 取含状态栏/导航栏的真实尺寸与刷新率 */
    private void measureScreen() {
        Display d = wm.getDefaultDisplay();
        DisplayMetrics real = new DisplayMetrics();
        if (Build.VERSION.SDK_INT >= 17) {
            d.getRealMetrics(real);
        } else {
            d.getMetrics(real);
        }
        screenW = real.widthPixels;
        screenH = real.heightPixels;
        screenDpi = real.densityDpi;

        float hz = d.getRefreshRate();
        if (hz >= 20f && hz <= 200f) {
            screenHz = Math.round(hz);
        }
    }

    private void addOverlayView() {
        if (glView != null) return;

        glView = new GLSurfaceView(this);
        glView.setEGLContextClientVersion(2);
        glView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
        glView.setZOrderOnTop(true);
        glView.getHolder().setFormat(PixelFormat.TRANSLUCENT);
        glView.setPreserveEGLContextOnPause(true);

        renderer = new ReliefRenderer(this, P, driver) {
            @Override public void onSurfaceCreated(GL10 gl, EGLConfig cfg) {
                super.onSurfaceCreated(gl, cfg);
                // GL 就绪后的第一帧：此时才可以创建 VirtualDisplay
                main.post(() -> {
                    if (state == State.WAIT_GL) {
                        state = State.CAPTURING;
                        createVirtualDisplay();
                    }
                });
            }

            @Override public void onSurfaceChanged(GL10 gl, int w, int h) {
                super.onSurfaceChanged(gl, w, h);
                // 覆盖层实际尺寸可能与 getRealMetrics 有出入，用实测值再校正一次
                main.post(() -> {
                    if (state == State.CAPTURING && virtualDisplay != null
                            && (w != screenW || h != screenH) && w > 0 && h > 0) {
                        Log.i(TAG, "校正尺寸 " + screenW + "x" + screenH + " -> " + w + "x" + h);
                        screenW = w;
                        screenH = h;
                        virtualDisplay.resize(w, h, screenDpi);
                        // 注意：不需要额外通知 renderer —— onSurfaceChanged 已经
                        // 在 GL 线程重建了 FBO 与纹理，这里只同步 VirtualDisplay。
                    }
                });
            }
        };
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
        glView.onResume();

        WindowManager.LayoutParams lp = buildLayoutParams();

        try {
            wm.addView(glView, lp);
        } catch (Exception e) {
            Log.e(TAG, "addView 失败（悬浮窗权限被拒？）", e);
            glView = null;
            renderer = null;
        }
    }

    private WindowManager.LayoutParams buildLayoutParams() {
        // 关键：TYPE_APPLICATION_OVERLAY 是第三方可用的最高层级；
        // 再配合 FLAG_LAYOUT_IN_SCREEN | FLAG_LAYOUT_NO_LIMITS，
        // 让窗口尺寸取整块屏幕（含状态栏区域），从而形成一个整体画面。
        int flags = WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE
                | WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                | WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
                | WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS
                | WindowManager.LayoutParams.FLAG_HARDWARE_ACCELERATED
                | WindowManager.LayoutParams.FLAG_SECURE;

        WindowManager.LayoutParams lp = new WindowManager.LayoutParams(
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.MATCH_PARENT,
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
                flags,
                PixelFormat.TRANSLUCENT);

        lp.gravity = Gravity.TOP | Gravity.START;
        lp.x = 0;
        lp.y = 0;

        if (Build.VERSION.SDK_INT >= 28) {
            lp.layoutInDisplayCutoutMode =
                    WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES;
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // 完全忽略系统栏 insets，让内容铺到状态栏底下
            lp.setFitInsetsTypes(0);
            lp.setFitInsetsSides(0);
            lp.setFitInsetsIgnoringVisibility(true);
        }
        return lp;
    }

    private void createVirtualDisplay() {
        if (projection == null || renderer == null) return;

        Surface surface = renderer.getScreenSurface();
        if (surface == null || !surface.isValid()) {
            Log.w(TAG, "GL Surface 尚未就绪，100ms 后重试");
            main.postDelayed(() -> {
                if (state == State.CAPTURING) createVirtualDisplay();
            }, 100);
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= 34) {
                // API 34 起显式传刷新率，避免高刷屏（120/144Hz）被降帧导致抖动
                virtualDisplay = projection.createVirtualDisplay(
                        "relief-screen",
                        screenW, screenH, screenDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, null, screenHz);
            } else {
                virtualDisplay = projection.createVirtualDisplay(
                        "relief-screen",
                        screenW, screenH, screenDpi,
                        DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
                        surface, null, null);
            }
            Log.i(TAG, "VirtualDisplay 创建成功 " + screenW + "x" + screenH);
            driver.start();
        } catch (Exception e) {
            Log.e(TAG, "createVirtualDisplay 失败", e);
            shutdown();
            stopSelf();
        }
    }

    /** 统一收尾：保证任何路径都释放干净，不留悬挂 GL / 投屏会话 */
    private void shutdown() {
        if (state == State.STOPPING) return;
        state = State.STOPPING;
        isRunning = false;

        if (driver != null) {
            try { driver.stop(); } catch (Exception ignore) {}
        }

        if (virtualDisplay != null) {
            try { virtualDisplay.release(); } catch (Exception ignore) {}
            virtualDisplay = null;
        }

        if (projection != null) {
            try { projection.stop(); } catch (Exception ignore) {}
            projection = null;
        }

        if (renderer != null) {
            renderer.detachScreen();
        }

        if (glView != null) {
            try {
                glView.onPause();
                wm.removeView(glView);
            } catch (Exception ignore) {}
            glView = null;
        }
        renderer = null;

        try { stopForeground(true); } catch (Exception ignore) {}
        state = State.IDLE;
        Log.i(TAG, "已完全停止");
    }

    // =========================================================
    // 通知
    // =========================================================

    private void startForegroundCompat() {
        NotificationManager nm = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (Build.VERSION.SDK_INT >= 26 && nm != null) {
            if (nm.getNotificationChannel(CH_ID) == null) {
                NotificationChannel ch = new NotificationChannel(
                        CH_ID, getString(R.string.notif_channel), NotificationManager.IMPORTANCE_LOW);
                ch.setShowBadge(false);
                ch.setDescription(getString(R.string.notif_channel_desc));
                nm.createNotificationChannel(ch);
            }
        }

        PendingIntent pi = PendingIntent.getActivity(this, 0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_IMMUTABLE | PendingIntent.FLAG_UPDATE_CURRENT);

        Notification n = new NotificationCompat.Builder(this, CH_ID)
                .setContentTitle(getString(R.string.notif_title))
                .setContentText(getString(R.string.notif_text))
                .setSmallIcon(R.drawable.ic_stat_relief)
                .setContentIntent(pi)
                .setOngoing(true)
                .setPriority(NotificationCompat.PRIORITY_LOW)
                .build();

        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(NOTIF_ID, n,
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                            | ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE);
        } else {
            startForeground(NOTIF_ID, n);
        }
    }
}