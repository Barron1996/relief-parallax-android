package com.example.reliefparallax;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.RadialGradient;
import android.graphics.Shader;
import android.graphics.SurfaceTexture;
import android.media.MediaPlayer;
import android.opengl.GLES11Ext;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.GLUtils;
import android.opengl.Matrix;
import android.view.Surface;
import android.util.Log;

import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * 核心渲染器。
 *
 * 两 Pass 管线：
 *   Pass 1 — 原图 → Sobel → 假法线 + 高度，写入半分辨率 FBO
 *   Pass 2 — 原图 + 法线 → 视差采样 + 浮雕光照 + 光晕抑制 → 屏幕
 *
 * 素材来源三种：内置生成图、用户图片、用户视频。
 */
public class ReliefRenderer implements GLSurfaceView.Renderer {

    private static final String TAG = "ReliefRenderer";

    private final Context ctx;
    private final FilterParams P;
    private final ParallaxDriver driver;

    // ---- program ----
    private int progNormal = 0;
    private int progCompose = 0;
    private int progBlit   = 0;   // OES -> 2D

    // ---- uniform 位置 ----
    private int uN_tex, uN_texel, uN_sharp, uN_hasTex;
    private int uC_color, uC_normal, uC_res, uC_relief, uC_parallax,
                uC_depth, uC_halo, uC_light, uC_shift, uC_split, uC_kill;
    private int uB_oes, uB_matrix;

    // ---- 外部屏幕输入通道（MediaProjection）----
    private int texOes    = 0;        // GL_TEXTURE_EXTERNAL_OES
    private int texScreen = 0;        // blit 后的普通 2D 纹理
    private int fboScreen = 0;
    private SurfaceTexture screenTexture = null;
    private Surface screenSurface = null;
    private volatile boolean screenMode = false;      // true = 走抓屏输入
    private volatile boolean screenFrameNew = false;
    private final float[] screenMatrix = new float[16];
    private boolean screenMatrixReady = false;

    // ---- 资源 ----
    private FloatBuffer quad;
    private int texColor = 0;          // 原图纹理
    private int texNormal = 0;         // 法线 RTT 纹理
    private int fbo = 0;
    private int normalW = 0, normalH = 0;

    private int viewW = 0, viewH = 0;

    // ---- 素材 ----
    public enum Source { NONE, IMAGE, VIDEO }
    private volatile Source source = Source.NONE;
    private volatile Bitmap pendingImage = null;   // 待上传的静态图
    private volatile boolean uploadRequested = false;

    // 视频
    private MediaPlayer mediaPlayer = null;
    private SurfaceTexture videoTexture = null;
    private int videoTexId = 0;
    private volatile boolean videoFrameAvailable = false;
    private volatile boolean videoRequested = false;
    private String pendingVideoPath = null;

    private long lastFrameNanos = 0L;

    public ReliefRenderer(Context ctx, FilterParams params, ParallaxDriver driver) {
        this.ctx = ctx;
        this.P = params;
        this.driver = driver;
    }

    // =========================================================
    // 素材接口（UI 线程调用）
    // =========================================================

    /** 加载静态图片 */
    public void setImage(Bitmap bmp) {
        pendingImage = bmp;
        uploadRequested = true;
        source = Source.IMAGE;
    }

    /** 加载视频文件路径 */
    public void setVideo(String path) {
        pendingVideoPath = path;
        videoRequested = true;
        source = Source.VIDEO;
    }

    /** 恢复内置测试图 */
    public void useDefaultImage() {
        pendingImage = makeDefaultBitmap();
        uploadRequested = true;
        source = Source.IMAGE;
    }

    // =========================================================
    // GLSurfaceView.Renderer
    // =========================================================

    @Override
    public void onSurfaceCreated(GL10 unused, EGLConfig config) {
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glDisable(GLES20.GL_DEPTH_TEST);
        GLES20.glDisable(GLES20.GL_BLEND);

        try {
            String vs = GlUtil.readAsset(ctx, "vs_quad.glsl");
            String fsN = GlUtil.readAsset(ctx, "fs_normal.glsl");
            String fsC = GlUtil.readAsset(ctx, "fs_compose.glsl");

            progNormal = GlUtil.linkProgram(vs, fsN, new String[]{"aPos"});
            progCompose = GlUtil.linkProgram(vs, fsC, new String[]{"aPos"});
            // OES 输入 blit：顶点用带 uTexMatrix 的版本，片元吃 samplerExternalOES
            String vsOes = GlUtil.readAsset(ctx, "vs_oes.glsl");
            String fsOes = GlUtil.readAsset(ctx, "fs_oes.glsl");
            progBlit = GlUtil.linkProgram(vsOes, fsOes, new String[]{"aPos"});
            uB_oes    = GLES20.glGetUniformLocation(progBlit, "uOES");
            uB_matrix = GLES20.glGetUniformLocation(progBlit, "uTexMatrix");

            uN_tex    = GLES20.glGetUniformLocation(progNormal, "uTex");
            uN_texel  = GLES20.glGetUniformLocation(progNormal, "uTexel");
            uN_sharp  = GLES20.glGetUniformLocation(progNormal, "uSharp");
            uN_hasTex = GLES20.glGetUniformLocation(progNormal, "uHasTex");

            uC_color    = GLES20.glGetUniformLocation(progCompose, "uColor");
            uC_normal   = GLES20.glGetUniformLocation(progCompose, "uNormal");
            uC_res      = GLES20.glGetUniformLocation(progCompose, "uRes");
            uC_relief   = GLES20.glGetUniformLocation(progCompose, "uRelief");
            uC_parallax = GLES20.glGetUniformLocation(progCompose, "uParallax");
            uC_depth    = GLES20.glGetUniformLocation(progCompose, "uDepth");
            uC_halo     = GLES20.glGetUniformLocation(progCompose, "uHalo");
            uC_light    = GLES20.glGetUniformLocation(progCompose, "uLightAng");
            uC_shift    = GLES20.glGetUniformLocation(progCompose, "uShift");
            uC_split    = GLES20.glGetUniformLocation(progCompose, "uSplit");
            uC_kill     = GLES20.glGetUniformLocation(progCompose, "uKill");

        } catch (Exception e) {
            Log.e(TAG, "Shader 初始化失败", e);
            throw new RuntimeException(e);
        }

        quad = GlUtil.createQuadBuffer();
        texColor = GlUtil.createTexture(1, 1);

        // 首次进入给一张内置图，保证一打开就能看到效果
        pendingImage = makeDefaultBitmap();
        uploadRequested = true;
        source = Source.IMAGE;
    }

    @Override
    public void onSurfaceChanged(GL10 unused, int width, int height) {
        viewW = width;
        viewH = height;
        GLES20.glViewport(0, 0, width, height);

        // 法线缓冲用半分辨率：成本减 4 倍，且块面感更像浮雕
        int nw = Math.max(2, width >> 1);
        int nh = Math.max(2, height >> 1);

        if (texNormal != 0) {
            GLES20.glDeleteTextures(1, new int[]{texNormal}, 0);
            GLES20.glDeleteFramebuffers(1, new int[]{fbo}, 0);
        }
        texNormal = GlUtil.createTexture(nw, nh);
        fbo = GlUtil.createFbo(texNormal);
        normalW = nw;
        normalH = nh;

        // 抓屏结果落地的 2D 纹理 + FBO（全分辨率，避免二次模糊）
        if (texScreen != 0) {
            GLES20.glDeleteTextures(1, new int[]{texScreen}, 0);
            GLES20.glDeleteFramebuffers(1, new int[]{fboScreen}, 0);
        }
        texScreen = GlUtil.createTexture(width, height);
        fboScreen = GlUtil.createFbo(texScreen);
    }

    @Override
    public void onDrawFrame(GL10 unused) {
        // ---- 帧间隔 ----
        long now = System.nanoTime();
        float dt = lastFrameNanos == 0 ? 0.016f : (now - lastFrameNanos) / 1e9f;
        if (dt > 0.1f) dt = 0.1f;   // 卡顿时钳制，避免跳变
        lastFrameNanos = now;

        // ---- 抳屏通道的建立/销毁（必须在 GL 线程）----
        if (pendingAttachScreen) {
            pendingAttachScreen = false;
            createScreenChannel();
        }
        if (pendingDetachScreen) {
            pendingDetachScreen = false;
            releaseScreenChannel();
        }

        // ---- 处理待上传素材 ----
        handlePendingUploads();

        // ---- 抓屏模式：先把 OES 转成 2D ----
        if (screenMode) runBlitPass();

        // ---- 更新视差 ----
        driver.update(dt);
        float shiftX = driver.getX();
        float shiftY = driver.getY();

        // ---- Pass 1: 法线 ----
        runNormalPass();

        // ---- Pass 2: 合成 ----
        runComposePass(shiftX, shiftY);
    }

    // =========================================================
    // 内部实现
    // =========================================================

    private void handlePendingUploads() {
        // 视频创建请求
        if (videoRequested) {
            videoRequested = false;
            createVideo(pendingVideoPath);
        }

        // 静态图上传
        if (uploadRequested) {
            uploadRequested = false;
            Bitmap bmp = pendingImage;
            pendingImage = null;
            if (bmp != null) {
                // 释放旧视频
                if (mediaPlayer != null) {
                    try { mediaPlayer.release(); } catch (Exception ignore) {}
                    mediaPlayer = null;
                }
                if (videoTexture != null) {
                    videoTexture.release();
                    videoTexture = null;
                }
                uploadBitmap(bmp);
                bmp.recycle();
            }
        }

        // 视频帧更新
        if (videoTexture != null && videoFrameAvailable) {
            videoFrameAvailable = false;
            try {
                videoTexture.updateTexImage();
            } catch (Exception e) {
                Log.w(TAG, "updateTexImage 失败", e);
            }
        }
    }

    private void uploadBitmap(Bitmap bmp) {
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texColor);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        // Android Bitmap 原点在左上，GL 纹理原点在左下 → 需要翻转
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bmp, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);
    }

    private void createVideo(String path) {
        try {
            if (mediaPlayer != null) {
                try { mediaPlayer.release(); } catch (Exception ignore) {}
                mediaPlayer = null;
            }
            if (videoTexture != null) {
                videoTexture.release();
                videoTexture = null;
            }

            int[] tex = new int[1];
            GLES20.glGenTextures(1, tex, 0);
            videoTexId = tex[0];
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, videoTexId);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, 0);

            videoTexture = new SurfaceTexture(videoTexId);
            videoTexture.setOnFrameAvailableListener(st -> videoFrameAvailable = true);

            mediaPlayer = new MediaPlayer();
            mediaPlayer.setSurface(new android.view.Surface(videoTexture));
            mediaPlayer.setDataSource(path);
            mediaPlayer.setLooping(true);
            mediaPlayer.setVolume(0f, 0f);
            mediaPlayer.setOnPreparedListener(MediaPlayer::start);
            mediaPlayer.setOnErrorListener((mp, what, extra) -> {
                Log.e(TAG, "视频播放错误 what=" + what + " extra=" + extra);
                return false;
            });
            mediaPlayer.prepareAsync();

            Log.i(TAG, "视频已加载: " + path);
        } catch (Exception e) {
            Log.e(TAG, "创建视频失败", e);
        }
    }

    private void bindQuad(int program, int attribLoc) {
        GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER, 0);
        quad.position(0);
        GLES20.glEnableVertexAttribArray(attribLoc);
        GLES20.glVertexAttribPointer(attribLoc, 2, GLES20.GL_FLOAT, false, 0, quad);
    }

    // =========================================================
    // 抳屏输入：blit OES -> 2D
    // =========================================================
    private void runBlitPass() {
        if (screenTexture == null) return;
        try {
            if (screenFrameNew) {
                screenFrameNew = false;
                screenTexture.updateTexImage();
                screenTexture.getTransformMatrix(screenMatrix);
                screenMatrixReady = true;
            }
        } catch (Exception e) {
            Log.w(TAG, "updateTexImage(屏幕) 失败", e);
            return;
        }
        if (!screenMatrixReady) return;

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fboScreen);
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);
        GLES20.glUseProgram(progBlit);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes);
        GLES20.glUniform1i(uB_oes, 0);
        GLES20.glUniformMatrix4fv(uB_matrix, 1, false, screenMatrix, 0);
        bindQuad(progBlit, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    /**
     * 把一块外部 Surface 作为画面源（MediaProjection 的 VirtualDisplay 用）。
     * 必须在 GL 线程创建 OES 纹理；这里用 runOnGl 排队。
     */
    public void attachScreenSurface() {
        pendingAttachScreen = true;
    }

    /** 当前用于接收屏内容的 Surface（可能在 GL 线程还没创建完，返回 null 时请稍后重试） */
    public Surface getScreenSurface() { return screenSurface; }

    /** 退出抳屏模式，恢复自绘素材 */
    public void detachScreen() {
        pendingDetachScreen = true;
    }

    public boolean isScreenMode() { return screenMode; }

    private volatile boolean pendingAttachScreen = false;
    private volatile boolean pendingDetachScreen = false;

    private void createScreenChannel() {
        releaseScreenChannel();
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        texOes = tex[0];
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texOes);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0);

        screenTexture = new SurfaceTexture(texOes);
        screenTexture.setDefaultBufferSize(Math.max(2, viewW), Math.max(2, viewH));
        screenTexture.setOnFrameAvailableListener(st -> screenFrameNew = true);
        screenMatrixReady = false;
        screenSurface = new Surface(screenTexture);
        screenMode = true;
        Log.i(TAG, "抳屏通道已创建 " + viewW + "x" + viewH);
    }

    private void releaseScreenChannel() {
        screenMode = false;
        screenFrameNew = false;
        screenMatrixReady = false;
        if (screenSurface != null) {
            try { screenSurface.release(); } catch (Exception ignore) {}
            screenSurface = null;
        }
        if (screenTexture != null) {
            try { screenTexture.release(); } catch (Exception ignore) {}
            screenTexture = null;
        }
        if (texOes != 0) {
            GLES20.glDeleteTextures(1, new int[]{texOes}, 0);
            texOes = 0;
        }
    }

    private void runNormalPass() {
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo);
        GLES20.glViewport(0, 0, normalW, normalH);
        GLES20.glClearColor(0.5f, 0.5f, 1f, 0.5f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(progNormal);

        // 绑定素材纹理
        int srcTex;
        int srcW, srcH;
        if (screenMode) {
            // 抓屏模式：用 blit 后的 2D 纹理
            srcTex = texScreen;
            srcW = viewW; srcH = viewH;
        } else if (source == Source.VIDEO && videoTexId != 0) {
            srcTex = videoTexId;
            srcW = 1920; srcH = 1080;    // 视频尺寸未知时用近似值，Sobel 采样间距影响不大
        } else {
            srcTex = texColor;
            srcW = viewW; srcH = viewH;
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
        GLES20.glUniform1i(uN_tex, 0);

        // Sobel 采样步长基于法线缓冲分辨率，保证 1 像素偏移
        GLES20.glUniform2f(uN_texel, 1f / normalW, 1f / normalH);
        GLES20.glUniform1f(uN_sharp, P.sharp);
        GLES20.glUniform1f(uN_hasTex, srcTex == 0 ? 0f : 1f);

        bindQuad(progNormal, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);

        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
    }

    private void runComposePass(float shiftX, float shiftY) {
        GLES20.glViewport(0, 0, viewW, viewH);
        GLES20.glClearColor(0f, 0f, 0f, 1f);
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT);

        GLES20.glUseProgram(progCompose);

        int srcTex;
        if (screenMode) {
            srcTex = texScreen;
        } else if (source == Source.VIDEO && videoTexId != 0) {
            srcTex = videoTexId;
        } else {
            srcTex = texColor;
        }

        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, srcTex);
        GLES20.glUniform1i(uC_color, 0);

        GLES20.glActiveTexture(GLES20.GL_TEXTURE1);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texNormal);
        GLES20.glUniform1i(uC_normal, 1);

        GLES20.glUniform2f(uC_res, viewW, viewH);
        GLES20.glUniform1f(uC_relief, P.reliefEffective());
        GLES20.glUniform1f(uC_parallax, P.parallaxEffective());
        GLES20.glUniform1f(uC_depth, P.depth);
        GLES20.glUniform1f(uC_halo, P.halo);
        GLES20.glUniform1f(uC_light, (float) Math.toRadians(P.lightDeg));
        GLES20.glUniform2f(uC_shift, shiftX, shiftY);
        GLES20.glUniform1f(uC_split, P.split ? 1f : 0f);
        GLES20.glUniform1f(uC_kill, P.killed ? 1f : 0f);

        bindQuad(progCompose, 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
    }

    // =========================================================
    // 内置测试图（程序生成，不依赖外部资源）
    // =========================================================

    private Bitmap makeDefaultBitmap() {
        int size = 1024;
        Bitmap bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888);
        Canvas c = new Canvas(bmp);

        // 背景渐变
        Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        p.setShader(new LinearGradient(0, 0, size, size,
                Color.rgb(26, 26, 36), Color.rgb(14, 14, 20), Shader.TileMode.CLAMP));
        c.drawRect(0, 0, size, size, p);

        // 四个"前景球"：中心亮 → 亮=凸起
        int[][] blobs = {
                {300, 380, 210, 0xFFE8D9B0},
                {700, 300, 150, 0xFFD95F4A},
                {380, 720, 175, 0xFF4A9FD9},
                {760, 720, 195, 0xFF8F7AD9},
        };
        for (int[] b : blobs) {
            int cx = b[0], cy = b[1], r = b[2], col = b[3];
            Paint bp = new Paint(Paint.ANTI_ALIAS_FLAG);
            bp.setShader(new RadialGradient(cx - r * 0.35f, cy - r * 0.35f, r * 1.1f,
                    new int[]{Color.WHITE, col, 0xFF1A1A22},
                    new float[]{0f, 0.28f, 1f}, Shader.TileMode.CLAMP));
            c.drawCircle(cx, cy, r, bp);
        }

        // 文字（用于验收「文字可读性」）
        Paint tp = new Paint(Paint.ANTI_ALIAS_FLAG);
        tp.setColor(Color.WHITE);
        tp.setTextAlign(Paint.Align.CENTER);
        tp.setTextSize(64f);
        tp.setFakeBoldText(true);
        c.drawText("浮雕视差", size / 2f, 520f, tp);

        tp.setTextSize(30f);
        tp.setFakeBoldText(false);
        tp.setColor(0xFFC9C9D4);
        c.drawText("RELIEF  PARALLAX  MVP", size / 2f, 574f, tp);

        return bmp;
    }
}
