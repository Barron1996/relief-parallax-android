package com.example.reliefparallax;

import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.media.projection.MediaProjectionManager;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.provider.Settings;
import android.view.View;
import android.widget.Button;
import android.widget.ImageView;
import android.widget.TextView;
import android.widget.Toast;

import androidx.annotation.Nullable;
import androidx.appcompat.app.AppCompatActivity;
import androidx.core.content.ContextCompat;

/**
 * 授权引导页。
 *
 * 职责边界（刻意做得很薄）：
 *   - 只负责「把用户引导到能用的状态」，不承担任何采集/渲染职责
 *   - 采集与覆盖一律交给 OverlayService，避免出现两处抢 MediaProjection 的经典 bug
 *
 * 三个步骤：
 *   1. 悬浮窗权限（SYSTEM_ALERT_WINDOW）—— 覆盖层要铺满整屏（含状态栏）必须要有
 *   2. 屏幕录制授权（MediaProjection）—— 只在本机 GPU 内处理，不上传
 *   3. 启动 —— 拿到授权后直接把结果转交 OverlayService
 *
 * 额外做两件探测：Root 与无障碍服务。这两项只作为「提示」输出，
 * 不阻断流程 —— 因为它们都不是功能必需项，缺了也能跑，只是稳定性略差。
 */
public class GuideActivity extends AppCompatActivity {

    private static final int REQ_CAPTURE = 2001;

    /** 三个步骤的状态圆点，用颜色区分「已完成/待完成」。 */
    private View dot1;
    private View dot2;
    private View dot3;
    private TextView tvRootHint;
    private TextView tvA11yHint;

    @Override
    protected void onCreate(@Nullable Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_guide);

        // 引导页用淡入，避免和 MainActivity 切换时闪一下
        View root = findViewById(R.id.guide_root);
        if (root != null) {
            root.setAlpha(0f);
            root.animate().alpha(1f).setDuration(220).start();
        }

        dot1 = findViewById(R.id.dot1);
        dot2 = findViewById(R.id.dot2);
        dot3 = findViewById(R.id.dot3);
        tvRootHint = findViewById(R.id.tv_root_hint);
        tvA11yHint = findViewById(R.id.tv_a11y_hint);

        Button btnGrant = findViewById(R.id.btn_grant);
        if (btnGrant != null) btnGrant.setOnClickListener(v -> goOverlayPermission());

        Button btnStart = findViewById(R.id.btn_guide_start);
        if (btnStart != null) btnStart.setOnClickListener(v -> startFilter());

        Button btnClose = findViewById(R.id.btn_guide_close);
        if (btnClose != null) btnClose.setOnClickListener(v -> finish());

        TextView tvTitle = findViewById(R.id.tv_guide_title);
        if (tvTitle != null) tvTitle.setText(R.string.guide_title);

        ImageView ivLogo = findViewById(R.id.iv_guide_logo);
        if (ivLogo != null) ivLogo.setImageResource(R.drawable.ic_stat_relief);
    }

    @Override
    protected void onResume() {
        super.onResume();
        // 从系统设置返回后状态可能已变，每次回到前台都重新刷新
        refreshState();
    }

    // =========================================================
    // 状态刷新
    // =========================================================

    private void refreshState() {
        boolean canOverlay = Settings.canDrawOverlays(this);

        // 步骤 1：悬浮窗
        paintDot(dot1, canOverlay);

        // 步骤 2：抓屏授权无法「查询」，只能由用户当场确认，故显示为待完成
        paintDot(dot2, false);

        // 步骤 3：服务是否已在跑
        paintDot(dot3, OverlayService.isRunning);

        // Root 探测：只做提示，不阻断
        if (tvRootHint != null) {
            if (RootUtil.hasRoot()) {
                tvRootHint.setText(R.string.msg_root_hint);
                tvRootHint.setVisibility(View.VISIBLE);
            } else {
                tvRootHint.setVisibility(View.GONE);
            }
        }

        // 无障碍探测：同样只做提示
        if (tvA11yHint != null) {
            if (!A11yUtil.isEnabled(this)) {
                tvA11yHint.setText(R.string.msg_a11y_hint);
                tvA11yHint.setVisibility(View.VISIBLE);
            } else {
                tvA11yHint.setVisibility(View.GONE);
            }
        }
    }

    /** 已完成用金色实心，未完成用暗色空心。 */
    private void paintDot(View dot, boolean done) {
        if (dot == null) return;
        dot.setBackgroundResource(done ? R.drawable.bg_badge : R.drawable.bg_card);
        dot.setAlpha(done ? 1f : 0.45f);
    }

    // =========================================================
    // 步骤动作
    // =========================================================

    /** 跳系统「在其他应用上层显示」设置页 */
    private void goOverlayPermission() {
        if (Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.msg_overlay_on, Toast.LENGTH_SHORT).show();
            refreshState();
            return;
        }
        try {
            Intent i = new Intent(Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:" + getPackageName()));
            startActivity(i);
        } catch (ActivityNotFoundException e) {
            // 部分rom没有这个页面，退回到应用详情页
            try {
                startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                        Uri.parse("package:" + getPackageName())));
            } catch (Exception ignore) {
                Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
            }
        }
    }

    /** 前两项就绪后才允许启动，否则逐项提醒 */
    private void startFilter() {
        if (!Settings.canDrawOverlays(this)) {
            Toast.makeText(this, R.string.msg_need_overlay, Toast.LENGTH_SHORT).show();
            goOverlayPermission();
            return;
        }
        MediaProjectionManager mpm =
                (MediaProjectionManager) getSystemService(MEDIA_PROJECTION_SERVICE);
        if (mpm == null) {
            Toast.makeText(this, R.string.msg_need_capture, Toast.LENGTH_SHORT).show();
            return;
        }
        try {
            startActivityForResult(mpm.createScreenCaptureIntent(), REQ_CAPTURE);
        } catch (Exception e) {
            Toast.makeText(this, e.getMessage(), Toast.LENGTH_SHORT).show();
        }
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, @Nullable Intent data) {
        super.onActivityResult(requestCode, resultCode, data);
        if (requestCode != REQ_CAPTURE) return;

        if (resultCode != RESULT_OK || data == null) {
            Toast.makeText(this, R.string.msg_capture_denied, Toast.LENGTH_SHORT).show();
            return;
        }

        // 与 MainActivity 完全一致的交接方式：授权结果直接给 OverlayService
        Intent svc = new Intent(this, OverlayService.class)
                .setAction(OverlayService.ACTION_START_CAPTURE)
                .putExtra(OverlayService.EXTRA_CODE, resultCode)
                .putExtra(OverlayService.EXTRA_DATA, data);
        ContextCompat.startForegroundService(this, svc);

        Toast.makeText(this, R.string.msg_overlay_on, Toast.LENGTH_SHORT).show();
        finish();
    }

    // =========================================================
    // 极小的能力探测工具
    // =========================================================

    /** Root 探测：只看 su 是否存在且能执行。不做任何提权动作，纯读取。 */
    static final class RootUtil {
        private static Boolean cached;

        static boolean hasRoot() {
            if (cached != null) return cached;
            Process p = null;
            try {
                p = Runtime.getRuntime().exec(new String[]{"su", "-c", "id"});
                int code = p.waitFor();
                cached = code == 0;
            } catch (Exception e) {
                cached = false;
            } finally {
                if (p != null) try { p.destroy(); } catch (Exception ignore) { }
            }
            return cached;
        }
    }

    /**
     * 无障碍探测：读 Settings.Secure 里已启用的服务列表。
     * 注意这是「读配置」，不是「绑定服务」，不会造成任何副作用。
     */
    static final class A11yUtil {
        static boolean isEnabled(android.content.Context ctx) {
            try {
                String flat = Settings.Secure.getString(
                        ctx.getContentResolver(), Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES);
                if (flat == null || flat.isEmpty()) return false;
                String me = ctx.getPackageName() + "/";
                for (String s : flat.split(":")) {
                    if (s.startsWith(me)) return true;
                }
                return false;
            } catch (Exception e) {
                return false;
            }
        }
    }
}
