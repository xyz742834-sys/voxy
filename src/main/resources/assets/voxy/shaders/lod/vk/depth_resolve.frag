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

#import <voxy:util/depthutils.glsl>

layout(binding = 0) uniform sampler2D srcDepth;

layout(location = 0) out vec4 outDepth;

#ifdef REPROJECT_DEPTH
// == 深度の再投影 (Phase 5c-3a) ==
//
// Voxy は near=16 / far=48000 の**自前の投影**で描く。MC の far 平面より先が
// 描けなければ遠景 LoD にならないためである。しかし合成先は MC の深度バッファなので、
// **書き戻す前に MC の深度空間へ写し直す**必要がある。
//
// GL 側は post/blit_texture_depth_cutout.frag が同じことをしている
// [AbstractRenderPipeline.transformBlitDepth]。**式は同じで、実行する段だけが違う**
// (GL のブリットではなくここ) [docs/phase5c3-plan.md 2.4]。
layout(push_constant) uniform DepthResolvePushConstants {
    mat4 invSrcMvp;   // inverse(Voxy の MVP) — 画面 -> カメラ相対ワールド
    mat4 dstMvp;      // MC の MVP           — カメラ相対ワールド -> MC のクリップ
} push;
#endif

void main() {
    ivec2 texel = ivec2(gl_FragCoord.xy);
    float depth = texelFetch(srcDepth, texel, 0).r;

#ifdef REPROJECT_DEPTH
    // ⚠ FAR は**素通しする**。GL 版はここで discard しているが、こちらは
    // 色つき画像へ書いているので discard すると前フレームの中身が残る
    // (loadOp = DONT_CARE)。合成側は FAR を「何も描いていない」として捨てるので
    // [GlInteropCompositor の DepthMode.TEST]、そのまま渡すのが正しい。
    if (depth != FAR) {
        // NDC は gl_FragCoord から作る。地形パスと解決パスは同じビューポート
        // (y=0, height は正) なので、NDC -> フラグメント座標の写像は両者で同一である
        // [規約 2: 反転はどこにも無い]。
        //
        // ⚠⚠ **この写像の誤りは「深度を比べる検査」では原理的に捕まらない。**
        // (ndc_x, ndc_y, depth) から復元した点は、xy を変えても**視空間の z が同じ**
        // (同じ深度平面上を横に動くだけ) で、写し先が同じ視点の標準的な透視投影なら
        // 深度は z だけで決まるためである。実際に Y 反転の変異が
        // C16 / C16b / 往復の恒等を**全て素通り**した [失敗例 24]。
        // 見張っているのは **C16c** — 深度が横位置に依存する投影を写し先に使う。
        vec2 uv = gl_FragCoord.xy / vec2(textureSize(srcDepth, 0));

        vec4 srcClip = push.invSrcMvp * vec4(SCREEN2NDC(vec3(uv, depth)), 1.0);
        vec3 point = srcClip.xyz / srcClip.w;          // カメラ相対ワールド座標
        vec4 dstClip = push.dstMvp * vec4(point, 1.0);
        depth = dstClip.z / dstClip.w;

        // ⚠ **FAR ちょうどにしない。** FAR は「何も描いていない」の印なので、
        // 再投影の結果がそこに落ちると**描いたことが消える**。
        // MC の far 平面の外にあるものはここで「ぎりぎり手前」に留め置かれる。
        //
        // ⚠ CLOSER_SIGN は depthutils.glsl のマクロで、USE_REVERSE_Z の有無で
        // +1.0 / -1.0 が切り替わる。**定義を忘れると符号が逆になり、
        // 奥へ押しやってしまう** — VkDepth.defines() を必ず通すこと。
        // ε = 2/(2^24-1) = 1.19e-7 は 24bit 固定小数の刻みで、D32_SFLOAT から見ると
        // 大きめだが安全側である (MC の地形は z/w ~ 1e-4 なので 2 桁上)
        // [docs/phase5c3-plan.md 2.6]。
        depth = REDUCTION2(FAR + CLOSER_SIGN * (2.0 / float((1 << 24) - 1)), depth);
        depth = NDC2SCREEN_DEPTH(depth);
        // ⚠ GL 版にある gl_DepthRange の行は持ち込まない。Vulkan に gl_DepthRange は無く、
        // ビューポートの深度範囲は 0..1 (恒等) である。
        // 上流もあの行に「dont think this is right at all」と書いている
    }
#endif

    outDepth = vec4(depth, 0.0, 0.0, 1.0);
}
