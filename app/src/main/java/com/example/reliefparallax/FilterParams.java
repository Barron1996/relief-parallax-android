package com.example.reliefparallax;

/**
 * 所有滤镜参数的统一容器（进程级单例）。
 * MainActivity 的滑块写入 {@link #I}，OverlayService 的 Renderer 读取同一个实例，
 * 因此拖动滑块时全屏覆盖层会实时跟随，无需跨进程/跨组件传参。
 */
public final class FilterParams {

    /** 全局唯一实例 */
    public static final FilterParams I = new FilterParams();

    private FilterParams() { }

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

    /** 总强度作用于浮雕 */
    public float reliefEffective()   { return relief   * master; }
    /** 总强度作用于视差 */
    public float parallaxEffective() { return parallax * master; }
}
