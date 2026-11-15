package com.example.reliefparallax;

import android.Manifest;
import android.app.Activity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.MediaMetadataRetriever;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.opengl.GLSurfaceView;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.view.View;
import android.view.WindowManager;
import android.widget.Button;
import android.widget.SeekBar;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import java.io.InputStream;

/**
 * 主界面：
 *   - 全屏 GLSurfaceView 预览（首版形态：App 内全屏）
 *   - 下方参数面板：浮雕强度 / 视差强度 / 假深度 / 光晕抑制 / 光源角度 / 高度
 *   - 一键关闭（uKill 直通原图）、原图对比、漂移开关、陀螺仪开关
 *   - 换素材：内置图 / 相册图片 / 相册视频
 *   - 悬浮窗入口（需 SYSTEM_ALERT_WINDOW 授权）
 */
public class MainActivity extends AppCompatActivity {

    private static final int REQ_IMG  = 1001;
    private static final int REQ_CAPTURE = 1004;
    private static final int REQ_VID  = 1002;
    private static final int REQ_PERM = 1003;

    private GLSurfaceView glView;
    private ReliefRenderer renderer;
    private final FilterParams P = FilterParams.I;
    private ParallaxDriver driver;

    private TextView tvState;
    private final Handler ui = new Handler(Looper.getMainLooper());

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        getWindow().addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);

        glView = findViewById(R.id.gl_view);
        tvState = findViewById(R.id.tv_state);

        glView.setEGLContextClientVersion(2);
        driver = new ParallaxDriver(this);
        renderer = new ReliefRenderer(this, P, driver);
        glView.setRenderer(renderer);
        glView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);

        bindControls();
        ensurePermissions();
    }

    // =========================================================
    // UI 绑定
    // =========================================================

    private void bindControls() {
        bindSeek(R.id.sb_relief, 0, 200, (int) (P.relief * 100), v -> {
            P.relief = v / 100f;
        });
        bindSeek(R.id.sb_parallax, 0, 200, (int) (P.parallax * 100), v -> {
            P.parallax = v / 100f;
        });
        bindSeek(R.id.sb_depth, 0, 150, (int) (P.depth * 100), v -> {
            P.depth = v / 100f;
        });
        bindSeek(R.id.sb_halo, 0, 100, (int) (P.halo * 100), v -> {
            P.halo = v / 100f;
        });
        bindSeek(R.id.sb_sharp, 0, 200, (int) (P.sharp * 100), v -> {
            P.sharp = v / 100f;
        });
        bindSeek(R.id.sb_light, 0, 360, (int) P.lightDeg, v -> {
            P.lightDeg = v;
        });

        // 开关类
        Button btnKill = findViewById(R.id.btn_kill);
        btnKill.setOnClickListener(v -> {
            P.killed = !P.killed;
            btnKill.setText(P.killed ? R.string.act_on : R.string.act_off);
            setState(P.killed ? getString(R.string.act_on) : getString(R.string.act_off));
        });

        Button btnSplit = findViewById(R.id.btn_split);
        btnSplit.setOnTouchListener((v, e) -> {
            switch (e.getAction()) {
                case android.view.MotionEvent.ACTION_DOWN:
                    P.split = true;
                    break;
                case android.view.MotionEvent.ACTION_UP:
                case android.view.MotionEvent.ACTION_CANCEL:
                    P.split = false;
                    break;
            }
            return false;
        });

        Button btnDrift = findViewById(R.id.btn_drift);
        btnDrift.setOnClickListener(v -> {
            P.drift = !P.drift;
            btnDrift.setText(P.drift ? R.string.act_on : R.string.act_off);
        });

        Button btnGyro = findViewById(R.id.btn_gyro);
        btnGyro.setOnClickListener(v -> {
            P.gyro = !P.gyro;
            btnGyro.setText(P.gyro ? R.string.act_on : R.string.act_off);
            if (P.gyro) {
                if (!driver.start()) {
                    P.gyro = false;
                    btnGyro.setText(R.string.act_off);
                    toast(getString(R.string.gyro_unavailable));
                }
            } else {
                driver.stop();
            }
        });

        Button btnReset = findViewById(R.id.btn_reset);
        btnReset.setOnClickListener(v -> {
            renderer.useDefaultImage();
            toast(getString(R.string.msg_default_restored));
        });

        Button btnPick = findViewById(R.id.btn_pick);
        btnPick.setOnClickListener(v -> pickMedia());

        Button btnOverlay = findViewById(R.id.btn_overlay);
        btnOverlay.setOnClickListener(v -> toggleOverlay());

        Button btnPanel = findViewById(R.id.btn_panel);
        View panel = findViewById(R.id.panel);
        btnPanel.setOnClickListener(v ->
                panel.setVisibility(panel.getVisibility() == View.VISIBLE ? View.GONE : View.VISIBLE));
    }

    private interface IntConsumer { void accept(int v); }

    private void bindSeek(int id, int min, int max, int progress, IntConsumer cb) {
        SeekBar sb = findViewById(id);
        sb.setMax(max - min);
        sb.setProgress(Math.max(0, Math.min(max - min, progress - min)));
        sb.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            @Override public void onProgressChanged(SeekBar s, int p, boolean fromUser) {
                cb.accept(p + min);
            }
            @Override public void onStartTrackingTouch(SeekBar s) { }
            @Override public void onStopTrackingTouch(SeekBar s) { }
        });
    }

    // =========================================================
    // 素材选择
    // =========================================================

    private void pickMedia() {
        Intent i = new Intent(Intent.ACTION_OPEN_DOCUMENT);
        i.addCategory(Intent.CATEGORY_OPENABLE);
        i.setType("*/*");
        i.putExtra(Intent.EXTRA_MIME_TYPES, new String[]{"image/*", "video/*"});
        startActivityForResult(i, REQ_IMG);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);

        // 1) 抓屏授权回调
        if (requestCode == REQ_CAPTURE) {
            if (resultCode == RESULT_OK && data != null) {
                Intent s = new Intent(this, ScreenCaptureService.class);
                s.putExtra(ScreenCaptureService.EXTRA_CODE, resultCode);
                s.putExtra(ScreenCaptureService.EXTRA_DATA, data);
                ContextCompat.startForegroundService(this, s);
                toast(getString(R.string.msg_overlay_on));
            } else {
                toast("\u5df2\u53d6\u6d88\u6293\u5c4f\u6388\u6743\uff0c\u5168\u5c4f\u6ee4\u955c\u65e0\u6cd5\u5f00\u542f");
            }
            return;
        }

        // 2) 选图 / 选视频回调
        if (resultCode != Activity.RESULT_OK || data == null || data.getData() == null) return;
        Uri uri = data.getData();
        String type = getContentResolver().getType(uri);
        if (type != null && type.startsWith("video/")) {
            loadVideo(uri);
        } else {
            loadImage(uri);
        }
    }

    private void loadImage(Uri uri) {
        try (InputStream is = getContentResolver().openInputStream(uri)) {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inSampleSize = 2;             // 限制解码尺寸，省内存
            o.inPreferredConfig = Bitmap.Config.ARGB_8888;
            Bitmap bmp = BitmapFactory.decodeStream(is, null, o);
            if (bmp == null) {
                toast(getString(R.string.msg_load_fail));
                return;
            }
            // 裁到不超过 2048，避免纹理过大
            int max = 2048;
            if (bmp.getWidth() > max || bmp.getHeight() > max) {
                float s = Math.min(max / (float) bmp.getWidth(), max / (float) bmp.getHeight());
                Bitmap nb = Bitmap.createScaledBitmap(bmp,
                        Math.max(2, (int) (bmp.getWidth() * s)),
                        Math.max(2, (int) (bmp.getHeight() * s)), true);
                if (nb != bmp) bmp.recycle();
                bmp = nb;
            }
            renderer.setImage(bmp);
            setState(getString(R.string.msg_image_loaded, bmp.getWidth(), bmp.getHeight()));
        } catch (Exception e) {
            toast(getString(R.string.msg_load_fail));
        }
    }

    private void loadVideo(Uri uri) {
        String path = resolvePath(uri);
        if (path == null) {
            // content:// 无法直接给 MediaPlayer 时，退化为直接 setDataSource(context, uri)
            toast(getString(R.string.msg_video_hint));
            return;
        }
        String dur = "";
        MediaMetadataRetriever r = new MediaMetadataRetriever();
        try {
            r.setDataSource(this, uri);
            dur = r.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);
        } catch (Exception ignore) {
        } finally {
            try { r.release(); } catch (Exception ignore) {}
        }
        renderer.setVideo(path);
        setState(getString(R.string.msg_video_loaded) + (dur == null ? "" : " " + dur + "ms"));
    }

    /** content:// → 文件路径。失败返回 null，走提示分支（首版不复制文件，保持简单）。 */
    @Nullable
    private String resolvePath(Uri uri) {
        if ("file".equals(uri.getScheme())) return uri.getPath();
        return null;
    }

    // =========================================================
    // 悬浮窗入口
    // =========================================================

    private void toggleOverlay() {
        if (!Settings.canDrawOverlays(this)) {
            toast(getString(R.string.msg_need_overlay));
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION, Uri.parse("package:" + getPackageName()));
            startActivity(i);
            return;
        }
        Intent svc = new Intent(this, OverlayService.class);
        if (OverlayService.isRunning) {
            // 关闭：先撤抓屏，再停覆盖层
            ContextCompat.startForegroundService(this,
                    new Intent(this, OverlayService.class).setAction(OverlayService.ACTION_STOP_CAPTURE));
            stopService(new Intent(this, ScreenCaptureService.class));
            stopService(svc);
            toast(getString(R.string.msg_overlay_off));
        } else {
            // 开启：必须先拿到 MediaProjection 授权，否则抓不到屏幕
            requestCapturePermission();
        }
    }

    /** 弹出系统「开始录制或投放」授权框 */
    private void requestCapturePermission() {
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            toast("本机不支持屏幕抓取");
            return;
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            toast("无法申请抓屏权限：" + e.getMessage());
        }
    }


    // =========================================================
    // 权限
    // =========================================================

    private void ensurePermissions() {
        if (Build.VERSION.SDK_INT >= 33) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                    != PackageManager.PERMISSION_GRANTED) {
                ActivityCompat.requestPermissions(this,
                        new String[]{Manifest.permission.POST_NOTIFICATIONS}, REQ_PERM);
            }
        }
    }

    // =========================================================
    // 生命周期
    // =========================================================

    @Override
    protected void onResume() {
        super.onResume();
        glView.onResume();
        if (P.gyro) driver.start();
    }

    @Override
    protected void onPause() {
        super.onPause();
        glView.onPause();
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        driver.stop();
    }

    // =========================================================
    // 小工具
    // =========================================================

    private void setState(String s) {
        ui.post(() -> { if (tvState != null) tvState.setText(s); });
    }

    private void toast(String s) {
        ui.post(() -> Toast.makeText(this, s, Toast.LENGTH_SHORT).show());
    }
}
