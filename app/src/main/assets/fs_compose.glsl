// ============ Pass 2: 浮雕光照 + 视差采样 + 合成 ============
// 输入: uColor(原图) / uNormal(Pass1 输出)
// 输出: 最终画面
#ifdef GL_FRAGMENT_PRECISION_HIGH
precision highp float;
#else
precision mediump float;
#endif

varying vec2 vUV;

uniform sampler2D uColor;
uniform sampler2D uNormal;
uniform vec2  uRes;        // 屏幕像素尺寸
uniform float uRelief;     // 浮雕强度
uniform float uParallax;   // 视差幅度
uniform float uDepth;      // 深度分层对比
uniform float uHalo;       // 边缘光晕抑制
uniform float uLightAng;   // 光源方向(弧度)
uniform vec2  uShift;      // 视差偏移 (-1..1)
uniform float uSplit;      // 0=滤镜 1=原图
uniform float uKill;       // 1=完全关闭滤镜

float luma(vec3 c) { return dot(c, vec3(0.2126, 0.7152, 0.0722)); }

void main() {
    vec2 uv = vUV;

    // ---- 一键关闭：直通原图 ----
    if (uKill > 0.5) {
        gl_FragColor = vec4(texture2D(uColor, uv).rgb, 1.0);
        return;
    }

    // ---- 1) 取回法线与高度 ----
    vec4 nPack = texture2D(uNormal, uv);
    float h0 = nPack.a;

    // ---- 2) 假深度：低频亮度(9 点稀疏模糊) ----
    vec2 t = (1.0 / uRes) * 4.0;
    float hl = 0.0;
    hl += texture2D(uNormal, uv + vec2(-t.x, -t.y)).a;
    hl += texture2D(uNormal, uv + vec2( 0.0, -t.y)).a;
    hl += texture2D(uNormal, uv + vec2( t.x, -t.y)).a;
    hl += texture2D(uNormal, uv + vec2(-t.x,  0.0)).a;
    hl += texture2D(uNormal, uv + vec2( t.x,  0.0)).a;
    hl += texture2D(uNormal, uv + vec2(-t.x,  t.y)).a;
    hl += texture2D(uNormal, uv + vec2( 0.0,  t.y)).a;
    hl += texture2D(uNormal, uv + vec2( t.x,  t.y)).a;
    hl += h0;
    hl /= 9.0;

    // 深度 = 混合高频/低频亮度，做对比拉伸
    float depth = mix(h0, hl, 0.65);
    depth = (depth - 0.5) * (1.0 + uDepth * 2.0) + 0.5;
    depth = clamp(depth, 0.0, 1.0);

    // ---- 3) 视差：按深度偏移 UV ----
    // 近处(亮)偏移大、远处(暗)偏移小，形成相对位移
    vec2 pOff = uShift * uParallax * 0.028;
    vec2 uvP = uv + pOff * (depth - 0.5) * 2.0;
    uvP = clamp(uvP, vec2(0.0005), vec2(0.9995));

    vec3 col = texture2D(uColor, uvP).rgb;
    vec3 nrm = texture2D(uNormal, uvP).rgb * 2.0 - 1.0;
    float nlen = length(nrm.xy);
    nrm = normalize(nrm);

    // ---- 4) 方向光浮雕光照 ----
    vec3 L = normalize(vec3(cos(uLightAng), sin(uLightAng), 0.9));
    float ndl = dot(nrm, L);
    float shade = clamp(ndl * 0.5 + 0.5, 0.0, 1.0);
    float reliefTerm = (shade - 0.5) * 2.0;   // -1..1

    // ---- 5) 边缘光晕抑制 ----
    // 法线横向分量大的地方是锐利边缘，减弱明暗避免白边/黑边
    float edgeW = smoothstep(0.35, 1.25, nlen);
    reliefTerm *= mix(1.0, 1.0 - edgeW, uHalo * 0.85);

    // ---- 6) 明暗合成 ----
    float gain = uRelief * 0.85;
    vec3 lit = col * (1.0 + reliefTerm * gain);
    lit += vec3(0.045, 0.045, 0.05) * max(reliefTerm, 0.0) * uRelief;
    lit -= vec3(0.03) * max(-reliefTerm, 0.0) * uRelief;

    // ---- 7) 文字可读性保护 ----
    // 局部对比高的区域（文字/细线）降低明暗扰动
    float localContrast = smoothstep(0.10, 0.42, abs(h0 - hl) + nlen * 0.18);
    lit = mix(lit, col, localContrast * 0.55);

    // ---- 8) 对比原图切换 ----
    vec3 outCol = mix(clamp(lit, 0.0, 1.0), texture2D(uColor, uv).rgb, uSplit);

    gl_FragColor = vec4(outCol, 1.0);
}