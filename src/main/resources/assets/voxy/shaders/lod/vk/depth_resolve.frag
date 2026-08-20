#version 460 core

// Vulkan の深度アタッチメント (D32_SFLOAT) を interop の R32F 画像へ写す。
//
// == なぜコピーではなくパスなのか ==
// vkCmdCopyImage は「片方が深度/ステンシル形式なら両方が同一形式でなければならない」
// (VUID-vkCmdCopyImage-srcImage-01557) ため、D32_SFLOAT -> R32_SFLOAT を直接コピーできない。
// IOSurface に depth aspect の面は作れない [確認済 — Phase 0 8.2] ので、
// 深度は色として運ぶしかなく、その変換にフルスクリーンパスが要る。
//
// これは GL 側 initDepthStencil が d32 -> d24s8 でやっていた
// 「フォーマット不一致のためのフルスクリーンコピー」の裏返しである
// (docs/phase5-proposal.md 3.1)。
//
// == texelFetch を使う理由 ==
// 深度値を 1 テクセルもぼかさずに運ぶため。線形補間が入ると
// GL 側で gl_FragDepth に書き戻した値が Vulkan 側と一致しなくなり、
// 「差分ゼロ」の検証が成立しなくなる。

layout(binding = 0) uniform sampler2D srcDepth;

layout(location = 0) out vec4 outDepth;

void main() {
    outDepth = vec4(texelFetch(srcDepth, ivec2(gl_FragCoord.xy), 0).r, 0.0, 0.0, 1.0);
}
