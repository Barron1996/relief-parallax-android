package com.example.reliefparallax;

import android.content.Context;
import android.opengl.GLES20;
import android.util.Log;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/**
 * GL 工具类：shader 编译、program 链接、FBO 创建。
 * 目标 GLES 2.0，兼容性最好。
 */
public final class GlUtil {

    private static final String TAG = "GlUtil";

    private GlUtil() {}

    /** 从 assets 读取文本文件（glsl 等） */
    public static String readAsset(Context ctx, String name) throws IOException {
        StringBuilder sb = new StringBuilder();
        try (BufferedReader br = new BufferedReader(
                new InputStreamReader(ctx.getAssets().open(name), "UTF-8"))) {
            String line;
            while ((line = br.readLine()) != null) {
                sb.append(line).append('\n');
            }
        }
        return sb.toString();
    }

    public static int compileShader(int type, String src) {
        int shader = GLES20.glCreateShader(type);
        if (shader == 0) {
            throw new RuntimeException("glCreateShader failed");
        }
        GLES20.glShaderSource(shader, src);
        GLES20.glCompileShader(shader);

        int[] ok = new int[1];
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetShaderInfoLog(shader);
            GLES20.glDeleteShader(shader);
            Log.e(TAG, "Shader compile failed:\n" + log);
            throw new RuntimeException("Shader compile failed: " + log);
        }
        return shader;
    }

    public static int linkProgram(String vsSrc, String fsSrc, String[] attribs) {
        int vs = compileShader(GLES20.GL_VERTEX_SHADER, vsSrc);
        int fs = compileShader(GLES20.GL_FRAGMENT_SHADER, fsSrc);

        int prog = GLES20.glCreateProgram();
        if (prog == 0) {
            throw new RuntimeException("glCreateProgram failed");
        }
        GLES20.glAttachShader(prog, vs);
        GLES20.glAttachShader(prog, fs);

        // 绑定 attribute 位置，避免每帧查询
        for (int i = 0; i < attribs.length; i++) {
            GLES20.glBindAttribLocation(prog, i, attribs[i]);
        }

        GLES20.glLinkProgram(prog);

        int[] ok = new int[1];
        GLES20.glGetProgramiv(prog, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) {
            String log = GLES20.glGetProgramInfoLog(prog);
            GLES20.glDeleteProgram(prog);
            Log.e(TAG, "Program link failed:\n" + log);
            throw new RuntimeException("Program link failed: " + log);
        }

        // shader 已链接进 program，可以释放
        GLES20.glDeleteShader(vs);
        GLES20.glDeleteShader(fs);
        return prog;
    }

    /** 全屏 quad：两个三角形，NDC 坐标 */
    public static FloatBuffer createQuadBuffer() {
        float[] verts = {
                -1f, -1f,
                 1f, -1f,
                -1f,  1f,
                 1f,  1f
        };
        FloatBuffer fb = ByteBuffer.allocateDirect(verts.length * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer();
        fb.put(verts).position(0);
        return fb;
    }

    /** 创建一张空纹理，用于 RTT */
    public static int createTexture(int w, int h) {
        int[] tex = new int[1];
        GLES20.glGenTextures(1, tex, 0);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, tex[0]);
        GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D, 0, GLES20.GL_RGBA,
                w, h, 0, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, null);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
        return tex[0];
    }

    /** 创建绑定到给定纹理的 FBO */
    public static int createFbo(int textureId) {
        int[] fbo = new int[1];
        GLES20.glGenFramebuffers(1, fbo, 0);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, fbo[0]);
        GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER, GLES20.GL_COLOR_ATTACHMENT0,
                GLES20.GL_TEXTURE_2D, textureId, 0);

        int status = GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
        GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER, 0);
        if (status != GLES20.GL_FRAMEBUFFER_COMPLETE) {
            throw new RuntimeException("FBO incomplete: 0x" + Integer.toHexString(status));
        }
        return fbo[0];
    }
}