// ============ Pass 2: 光照合成（无感浮雕 + 视差） ============
// 输入: uColor(原画面), uNormal(RG=法线, A=高度)
// uniform 名与 ReliefRenderer 的绑定保持一致，不要随意改名。
//
// ===== 无感四条铁律（"纯白不能变青"的根本保证）=====
// 1) 色相不变：只对 RGB 三通道加同一个标量 delta，绝不逐通道加不同偏置。
//    任何 vec3(r,g,b) 形式的偏移都会在灰/白区被放大成可见色偏。
// 2) 中值不变：平坦区 reliefTerm == 0 → delta == 0 → 输出严格等于输入。
// 3) 两端衰减：权重 lum*(1-lum)*4 在 lum=0/1 处为 0，峰值在 0.5。
//    纯白与纯黑区域完全不动，不会过曝截断、不会染色。
// 4) 加法而非乘法：避免 col*(1+k) 在 col=1.0 处越界截断产生边缘色阶。
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

varying vec2 vUV;

uniform sampler2D uColor;    // 原画面（0 号纹理）
uniform sampler2D uNormal;   // 法线+高度（1 号纹理）

uniform vec2  uRes;          // 视口尺寸
uniform float uRelief;       // 浮雕强度 0..1
uniform float uParallax;     // 视差强度 0..1
uniform float uDepth;        // 深度系数 0..1.5
uniform float uHalo;         // 高光强度 0..1
uniform float uLightAng;     // 光源角度（弧度）
uniform vec2  uShift;        // 视差偏移量
uniform float uSplit;        // 按住看原图
uniform float uKill;         // 总开关

const vec3 LUMA = vec3(0.2126, 0.7152, 0.0722);

void main() {
    // 视差：按高度场推开采样点，形成纵深位移
    vec2 uv = vUV;
    if (uParallax > 0.001 && uSplit < 0.5) {
        float h = texture2D(uNormal, vUV).a;
        float dep = (h - 0.5) * uDepth * uParallax;
        uv += uShift * dep;
        uv = clamp(uv, vec2(0.0), vec2(1.0));
    }

    vec4 col4 = texture2D(uColor, uv);

    // 关闭态 / 按住看原图：严格直通，像素级不变
    if (uKill > 0.5 || uSplit > 0.5 || uRelief <= 0.001) {
        gl_FragColor = col4;
        return;
    }

    vec3 col = col4.rgb;
    float lum = dot(col, LUMA);

    // ---- 光照 ----
    vec3 n = normalize(texture2D(uNormal, uv).rgb * 2.0 - 1.0);

    // uLightAng 是平面角：拆成 xy 后与法线 xy 做点积，得到纯标量
    vec2 L = vec2(cos(uLightAng), sin(uLightAng));

    float reliefTerm = dot(n.xy, L) * uRelief;

    // 两端软衰减权重
    float weight = lum * (1.0 - lum) * 4.0;

    // 单一标量增量：等量加到三通道 → 色相绝对不变
    float delta = reliefTerm * 0.55 * weight;

    // 高光：只在亮部朝光一侧轻微抬亮，同样等量
    float spec = pow(max(dot(n.xy, L), 0.0), 8.0) * uHalo * weight * 0.18;
    delta += spec;

    vec3 lit = clamp(col + delta, 0.0, 1.0);

    gl_FragColor = vec4(lit, col4.a);
}