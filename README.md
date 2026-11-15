# 浮雕视差（Relief Parallax）

单眼可看的「假立体」视觉滤镜：对图片/视频做边缘浮雕 + 亮度分层假深度 + 微视差位移，
用陀螺仪或自动漂移驱动，产生浮雕凸起的运动感。

## 形态

- **App 内全屏**：主界面即预览，下方参数面板。
- **悬浮窗**：全屏覆盖层（首版），需要「显示在其他应用上层」权限。

## 参数

| 参数 | 作用 |
|---|---|
| 浮雕强度 | Sobel 假法线 + 方向光，0 为关闭 |
| 视差强度 | 假深度 UV 位移幅度 |
| 假深度 | 亮度到深度的混合与拉伸 |
| 光晕抑制 | 压暗强边缘外溢，减少白边 |
| 锐度 | Sobel 梯度增益 |
| 光源角度 | 方向光方位角 0–360° |
| 一键关闭 | 直通原图，用于 A/B 对比 |
| 长按对比 | 按住显示原图 |

## 实现要点

- 两趟 GLSL：`fs_normal.glsl` 半分辨率算 Sobel 假法线并打包进 RGB、A 存亮度；
  `fs_compose.glsl` 做方向光浮雕 + 深度视差 + 光晕抑制 + 文字保护。
- 法线 pass 走半分辨率 FBO，合成 pass 全分辨率，1080p 下每帧采样量约降一半。
- 视差驱动 `ParallaxDriver`：三频正弦漂移 + 陀螺仪/加速度计积分，输出 -1..1。
  信号只依赖时间与设备姿态，**不区分左右眼，所以单眼成立**。

## 已知限制

- 悬浮窗是「盖一层」的全屏覆盖，不是屏内合成；真正的全局滤镜需 MediaProjection（首版不做）。
- 陀螺仪有 60–120ms 级滞后；静止时视觉会轻微「呼吸」，这是漂移驱动本身。
- 首版视频仅支持 `file://` 路径；相册选到的 `content://` 会提示改用文件路径。
- 编译需要 JDK 17 + Android SDK，本地无环境请用 GitHub Actions。

## 构建

```bash
./gradlew assembleDebug
```

产物：`app/build/outputs/apk/debug/app-debug.apk`

或用 GitHub Actions：push 到 main 触发 `.github/workflows/build.yml`，在 Actions
页面下载 artifact。

## 配置

- `compileSdk 34` / `minSdk 24` / `targetSdk 34`
- AGP 8.1.4 + Gradle 8.2 + Java 17
- release 使用 debug 签名，便于直装测试