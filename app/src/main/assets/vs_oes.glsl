// ============ OES 输入专用顶点着色器 ============
// SurfaceTexture 的纹理坐标矩阵需要左乘到 uv 上，
// 否则画面会镜像/旋转（不同设备差异很大，必须用 uTexMatrix 校正）。
attribute vec2 aPos;
uniform mat4 uTexMatrix;
varying vec2 vUV;
void main() {
    vUV = (uTexMatrix * vec4(aPos * 0.5 + 0.5, 0.0, 1.0)).xy;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
