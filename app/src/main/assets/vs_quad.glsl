// ============ 全屏 Quad 顶点着色器 ============
// GLES 2.0: attribute 必须用 attribute 关键字
// 注意与 WebGL 版的差异：
//   - WebGL 里 vUV.y = 1.0 - vUV.y 是因为纹理上传方向
//   - Android Bitmap 上传到 GL 时如需翻转，在 Renderer 里用 UNPACK_FLIP_Y，不在 shader 里
attribute vec2 aPos;
varying vec2 vUV;

void main() {
    vUV = aPos * 0.5 + 0.5;
    gl_Position = vec4(aPos, 0.0, 1.0);
}
