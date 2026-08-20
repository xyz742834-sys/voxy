#version 460 core

// Phase 5c-1c — Minecraft の深度 (interop の R32F 経由) を **色に変換して返す**。
//
// == この段の目的 ==
// 深度が Vulkan 側に届いていることと、**深度経路の Y の向き**を確かめる。
// 色経路 (5c-1b) とは別の経路なので、**片方の反転が他方を打ち消せない**
// [docs/phase5c-plan.md 9]。
//
// == なぜ生値をそのまま出さないのか ==
// 逆Zでは深度は概ね near/distance である (下の値域の説明を参照)。
// MC の near は 0.05 なので、8 ブロック先で既に 0.006、
// 512 ブロック先では 0.0001 になる。**そのまま輝度にすると画面はほぼ真っ黒**で、
// 地形の形が読めない = この段の確認ができない。
//
// == 値域 ==
// 逆Z・0..1 の投影では、距離 d の点の深度は
//     z(d) = n*(f-d) / (d*(f-n))          n = near, f = far
// で、f >> n のとき z ~ n/d である。d=n で 1、d=f で 0 になる。
//
// pow(z, DEPTH_VIS_GAMMA) の指数は **見やすさのために選んだ任意の定数**である
// (根拠は docs/phase5c1c-completion.md に記録)。1/8 のとき、n=0.05 で
//     d=1 -> 0.69   d=8 -> 0.53   d=64 -> 0.41   d=512 -> 0.32
// となり、距離が 2 倍になるごとに輝度が約 8.3% 下がる
// (pow(0.5, 1/8) = 0.917)。**灰色の帯域全体を使い切る**ので段差が目で追える。
//
// == 空 ==
// MC は深度を clearDepth (逆Zでは FAR) でクリアするので、
// **何も描かれていない画素は z == FAR** になる。そこを専用色で塗る。
// 空は物理的に画面の上にあり、これは **Voxy の規約に一切依存しない外部の事実**である
// [規約 4 — docs/phase5c-y-orientation.md 4]。目視判定の基準はこれを使う。
//
// FAR は util/depthutils.glsl が USE_REVERSE_Z から決める。
// **ここで 0.0 と直に書いてはならない** — 規約を変えたときに
// この 1 行だけが取り残される [規約 6]。

#import <voxy:util/depthutils.glsl>

layout(binding = 0) uniform sampler2D srcDepth;

layout(location = 0) out vec4 outColour;

void main() {
    // texelFetch なので補間は起きない。行の対応がそのまま保たれる。
    // GL 規約のまま通しているので **Y の読み替えは入れない**
    // [docs/phase5c-y-orientation.md]。反転が 1 つも無ければ打ち消し合いが起きない
    float z = texelFetch(srcDepth, ivec2(gl_FragCoord.xy), 0).r;

    if (z == FAR) {
        outColour = vec4(DEPTH_VIS_SKY, 1.0);
        return;
    }

    float g = pow(clamp(z, 0.0, 1.0), DEPTH_VIS_GAMMA);
    outColour = vec4(g, g, g, 1.0);
}
