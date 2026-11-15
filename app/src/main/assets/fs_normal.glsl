// ============ Pass 1: Sobel 梯度 → 打包假法线 + 高度 ============
// 输出: RGB = 法线映射到 [0,1]，A = 亮度(高度场)
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

varying vec2 vUV;

uniform sampler2D uTex;
uniform vec2  uTexel;      // 1.0 / 纹理尺寸
uniform float uSharp;      // 梯度放大系数
uniform float uHasTex;     // 0 = 无素材，输出平法线

float luma(vec2 uv) {
    vec3 c = texture2D(uTex, uv).rgb;
    // 快速近似 sRGB → 线性
    c = c * c;
    return dot(c, vec3(0.2126, 0.7152, 0.0722));
}

void main() {
    if (uHasTex < 0.5) {
        gl_FragColor = vec4(0.5, 0.5, 1.0, 0.5);
        return;
    }

    vec2 t = uTexel;

    // 3x3 Sobel
    float l00 = luma(vUV + t * vec2(-1.0, -1.0));
    float l10 = luma(vUV + t * vec2( 0.0, -1.0));
    float l20 = luma(vUV + t * vec2( 1.0, -1.0));
    float l01 = luma(vUV + t * vec2(-1.0,  0.0));
    float l21 = luma(vUV + t * vec2( 1.0,  0.0));
    float l02 = luma(vUV + t * vec2(-1.0,  1.0));
    float l12 = luma(vUV + t * vec2( 0.0,  1.0));
    float l22 = luma(vUV + t * vec2( 1.0,  1.0));

    float gx = (l20 + 2.0 * l21 + l22) - (l00 + 2.0 * l01 + l02);
    float gy = (l02 + 2.0 * l12 + l22) - (l00 + 2.0 * l10 + l20);

    // 约定：亮 = 凸起。梯度反向，使亮部边缘产生凸起法线
    vec2 g = -vec2(gx, gy) * uSharp * 2.2;

    // 高度场 = 亮度
    float h = luma(vUV);

    // 假法线 n = normalize(gx, gy, 1)
    vec3 n = normalize(vec3(g, 1.0));

    gl_FragColor = vec4(n * 0.5 + 0.5, h);
}