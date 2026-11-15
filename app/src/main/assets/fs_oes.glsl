// ============ blit: GL_TEXTURE_EXTERNAL_OES → 普通 2D 纹理 ============
// 目的：让后面的 Sobel / compose 两个 pass 完全不用改 sampler 类型，
// 只需把这里输出的 2D 纹理当作原来的 uColor 即可。
#extension GL_OES_EGL_image_external : require
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif
varying vec2 vUV;
uniform samplerExternalOES uOES;
void main() {
    gl_FragColor = texture2D(uOES, vUV);
}
