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
    // 默认值按「无感」调：浮雕与视差都取克制值，
    // 只在倾斜设备时能感受到立体感，静止时尽量与原始画面一致。
    public volatile float master   = 1.0f;   // 总强度 0..2
    public volatile float relief   = 0.45f;  // 浮雕强度 0..2
    public volatile float parallax = 0.35f;  // 视差幅度 0..2
    public volatile float sharp    = 1.4f;   // 法线锐化 0.2..4
    public volatile float lightDeg = 315f;   // 光源方向 0..360 度
    public volatile float depth    = 0.5f;   // 深度分层对比 0..1
    public volatile float halo     = 0.7f;   // 边缘光晕抑制 0..1

    // ---- 开关 ----
    public volatile boolean killed  = false; // 一键关闭（直通原图）
    public volatile boolean split   = false; // 对比原图
    public volatile boolean drift   = false; // 自动漂移（默认关：静止时不晃）
    public volatile boolean gyro    = true;  // 陀螺仪驱动（默认开：倾斜才有效果）

    /** 总强度作用于浮雕 */
    public float reliefEffective()   { return relief   * master; }
    /** 总强度作用于视差 */
    public float parallaxEffective() { return parallax * master; }
}