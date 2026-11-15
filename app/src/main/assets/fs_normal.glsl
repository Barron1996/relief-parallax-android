// ============ Pass 1: Sobel 梯度 → 假法线 + 高度 ============
// 输出: RGB = 法线映射到 [0,1]，A = 亮度(高度场)
//
// 无感设计约束：
// 1) 梯度只在 Rec.709 亮度域计算，是单通道标量，不存在色相参与。
// 2) 不做 sRGB→线性 的非线性变换（原实现 c*c 会放大亮部梯度，
//    导致白底区域在边缘产生染色），保持与屏幕值一致的能量分布。
// 3) 无素材时输出平法线 + 中灰高度，合成阶段退化为恒等。
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

varying vec2 vUV;

uniform sampler2D uTex;
uniform vec2  uTexel;   // 1.0 / 纹理尺寸
uniform float uSharp;   // 梯度放大系数
uniform float uHasTex;  // 0 = 无素材，输出平法线

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

float lum(vec2 uv) {
    return dot(texture2D(uTex, uv).rgb, LUMA);
}

void main() {
    if (uHasTex < 0.5) {
        gl_FragColor = vec4(0.5, 0.5, 1.0, 0.5);
        return;
    }

    vec2 t = uTexel;

    float l00 = lum(vUV + t * vec2(-1.0, -1.0));
    float l10 = lum(vUV + t * vec2( 0.0, -1.0));
    float l20 = lum(vUV + t * vec2( 1.0, -1.0));
    float l01 = lum(vUV + t * vec2(-1.0,  0.0));
    float l21 = lum(vUV + t * vec2( 1.0,  0.0));
    float l02 = lum(vUV + t * vec2(-1.0,  1.0));
    float l12 = lum(vUV + t * vec2( 0.0,  1.0));
    float l22 = lum(vUV + t * vec2( 1.0,  1.0));

    float gx = (l20 + 2.0 * l21 + l22) - (l00 + 2.0 * l01 + l02);
    float gy = (l02 + 2.0 * l12 + l22) - (l00 + 2.0 * l10 + l20);

    // 约定：亮边缘凸起，梯度取反
    vec2 g = -vec2(gx, gy) * uSharp * 2.2;

    vec3 n = normalize(vec3(g, 1.0));

    gl_FragColor = vec4(n * 0.5 + 0.5, lum(vUV));
}