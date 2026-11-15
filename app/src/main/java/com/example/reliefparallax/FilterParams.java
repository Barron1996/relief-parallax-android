package com.example.reliefparallax;

/**
 * 所有滤镜参数的统一容器。
 * UI（MainActivity）写入，Renderer 读取，双方通过 volatile 保证可见性。
 */
public class FilterParams {

    // ---- 强度 ----
    public volatile float master   = 1.0f;   // 总强度 0..2
    public volatile float relief   = 1.0f;   // 浮雕强度 0..2
    public volatile float parallax = 1.0f;   // 视差幅度 0..2
    public volatile float sharp    = 1.4f;   // 法线锐化 0.2..4
    public volatile float lightDeg = 315f;   // 光源方向 0..360 度
    public volatile float depth    = 0.5f;   // 深度分层对比 0..1
    public volatile float halo     = 0.7f;   // 边缘光晕抑制 0..1

    // ---- 开关 ----
    public volatile boolean killed  = false; // 一键关闭（直通原图）
    public volatile boolean split   = false; // 对比原图
    public volatile boolean drift   = true;  // 自动漂移
    public volatile boolean gyro    = false; // 陀螺仪驱动

    /** 总强度是否作用于浮雕与视差 */
    public float reliefEffective()   { return relief   * master; }
    public float parallaxEffective() { return parallax * master; }
}