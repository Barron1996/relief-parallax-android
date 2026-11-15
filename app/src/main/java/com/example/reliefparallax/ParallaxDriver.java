package com.example.reliefparallax;

import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;

/**
 * 视差驱动：合并「自动低频漂移」与「陀螺仪倾斜」两路信号。
 *
 * 设计要点：
 * - 漂移用三个不同频率正弦叠加，避免机械的往复感。
 * - 陀螺仪以首次采样为基准，映射到 -1..1（±22° 满量程）。
 * - 两路信号直接相加，最终在 Renderer 里被 clamp。
 * - 这是单眼视错觉：信号只依赖时间与设备姿态，与「哪只眼睛看」无关。
 */
public class ParallaxDriver implements SensorEventListener {

    private final FilterParams params;
    private final SensorManager sensorManager;
    private Sensor gyroSensor;

    // 输出：归一化 -1..1
    private volatile float outX = 0f;
    private volatile float outY = 0f;

    // 陀螺仪基准值（首次采样时确定）
    private Float baseGamma = null;
    private Float baseBeta = null;
    private volatile float gyroX = 0f;
    private volatile float gyroY = 0f;
    private volatile boolean gyroReady = false;

    private long startNanos = 0L;

    /** 单参构造：内部自建一份参数（悬浮窗/独立实例用） */
    public ParallaxDriver(Context ctx) {
        this(ctx, FilterParams.I);
    }

    public ParallaxDriver(Context ctx, FilterParams params) {
        this.params = params;
        this.sensorManager = (SensorManager) ctx.getSystemService(Context.SENSOR_SERVICE);
        if (sensorManager != null) {
            // 优先陀螺仪；没有则退回加速度计（用重力方向近似倾斜）
            gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE);
            if (gyroSensor == null) {
                gyroSensor = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER);
            }
        }
    }

    public boolean hasSensor() {
        return gyroSensor != null;
    }

    /** 便捷入口：开启传感器监听，返回是否有可用传感器 */
    public boolean start() {
        if (gyroSensor == null) return false;
        setSensorEnabled(true);
        return true;
    }

    /** 便捷入口：关闭传感器监听 */
    public void stop() {
        setSensorEnabled(false);
        gyroX = 0f;
        gyroY = 0f;
        gyroReady = false;
        outX = 0f;
        outY = 0f;
    }

    /** 开启/关闭传感器监听 */
    public void setSensorEnabled(boolean enabled) {
        if (sensorManager == null || gyroSensor == null) return;
        if (enabled) {
            baseGamma = null;
            baseBeta = null;
            gyroReady = false;
            sensorManager.registerListener(this, gyroSensor, SensorManager.SENSOR_DELAY_GAME);
        } else {
            sensorManager.unregisterListener(this);
            gyroReady = false;
        }
    }

    /** 每帧调用：更新输出偏移 */
    public void update(float dtSeconds) {
        if (startNanos == 0L) startNanos = System.nanoTime();
        float t = (System.nanoTime() - startNanos) / 1_000_000_000f;

        float sx = 0f, sy = 0f;

        if (params.drift) {
            // 三频叠加，频率比 0.31 : 0.73 : 0.17
            sx += Math.sin(t * 0.31f) * 0.55f
                + Math.sin(t * 0.73f + 1.7f) * 0.30f
                + Math.sin(t * 0.17f + 4.1f) * 0.15f;
            sy += Math.cos(t * 0.23f + 0.9f) * 0.55f
                + Math.cos(t * 0.61f + 3.3f) * 0.30f
                + Math.cos(t * 0.13f + 2.2f) * 0.15f;
        }

        if (params.gyro && gyroReady) {
            // 陀螺仪信号乘以系数增强可见性
            sx += gyroX * 1.2f;
            sy += gyroY * 1.2f;
        }

        // 无任何驱动时保持中心（静帧 → 无位移，用于对比验收）
        outX = clamp(sx, -1f, 1f);
        outY = clamp(sy, -1f, 1f);
    }

    public float getX() { return outX; }
    public float getY() { return outY; }

    // ---------------- SensorEventListener ----------------

    @Override
    public void onSensorChanged(SensorEvent event) {
        if (event.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
            // 陀螺仪给角速度，积分得到角度偏移（简化：线性累积并衰减）
            // dt 约 1/60，此处用固定步长近似
            float dt = 0.016f;
            // event.values: [0]=绕 x(俯仰), [1]=绕 y(偏航), [2]=绕 z(滚转) rad/s
            gyroX = clamp(gyroX + event.values[1] * dt * 6f, -1f, 1f);
            gyroY = clamp(gyroY + event.values[0] * dt * 6f, -1f, 1f);
            // 缓慢回中，避免积分漂移
            gyroX *= 0.995f;
            gyroY *= 0.995f;
            gyroReady = true;

        } else if (event.sensor.getType() == Sensor.TYPE_ACCELEROMETER) {
            // 用重力在 x/y 上的投影估算倾斜角，映射到 -1..1
            float ax = event.values[0];
            float ay = event.values[1];
            // 约 ±9.8 m/s² 对应 ±90°，实用取 ±5 满量程
            gyroX = clamp(-ax / 5f, -1f, 1f);
            gyroY = clamp( ay / 5f, -1f, 1f);
            gyroReady = true;
        }
    }

    @Override
    public void onAccuracyChanged(Sensor sensor, int accuracy) { }

    private static float clamp(float v, float lo, float hi) {
        return v < lo ? lo : (v > hi ? hi : v);
    }
}
