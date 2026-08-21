#version 460 core

// HiZ の 1 レベルを書く頂点シェーダ (Vulkan 専用)。
//
// GL 側の post/../hiz/blit.vsh は gl_VertexID の 4 頂点クアッド (TRIANGLE_FAN) だが、
// Vulkan には gl_VertexID が無い。**uv の張り方は同じ**にする —
// 画面全体で uv が [0,1] を張れば、textureGather の 4 テクセルの取り方が一致する。
//
// ⚠ 深度は書かない。GL 版は深度アタッチメントへ gl_FragDepth を書いていたが、
// こちらは R32_SFLOAT の**色**として持つ (traversal 側は sampler2D の .r を読むので等価)。

layout(location = 0) out vec2 uv;

void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    uv = p;
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
