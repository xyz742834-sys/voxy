#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

#ifdef GL_ARB_gpu_shader_int64
#define QUAD_DATA_USE_64_BIT
#endif

// 統合描画版の地形頂点シェーダ (Vulkan 専用)。
//
// lod/gl46/quads3.vert との違いは **索引の解決方法だけ** である:
//
//   GL 版        quadData[gl_VertexID>>2]          positionBuffer[gl_BaseInstance]
//   ここ         テーブルを二分探索して解決         positionBuffer[解決した drawId]
//
// 位置・UV・属性の計算は quad_util.glsl の共通コードをそのまま呼ぶので、
// 同じ quad を指しさえすれば **出力はビット単位で一致するはず** [推測]。
// これを実際に確かめるのが Stage 2b の差分ゼロ比較である。
//
// GL 版から落としたもの (いずれも現状 define されておらず、出力に影響しない [確認済]):
//   USE_NV_JANK / GL_NV_gpu_shader5 の f16vec4 gl_Position
//   USE_NV_BARRY / USE_SINGLE_TRI のバリセントリック経路
//   DEBUG_RENDER のハッシュ色出力
// 落とした理由は、分岐が残っていると「無効なはずの経路」を疑う手間が毎回かかるため。

#define QUAD_BUFFER_BINDING 1
#define MODEL_BUFFER_BINDING 3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#define LIGHTING_SAMPLER_BINDING 6

#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/block_model.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:lod/quad_util.glsl>
#import <voxy:lod/vk/quad_index.glsl>

layout(location = 0) out flat uvec4 interData;
layout(location = 1) out vec2 uv;

vec2 taaShift();

void main() {
    taaOffset = taaShift();

    // gl_VertexIndex は baseVertex を含む。面ごとの draw が
    // baseVertex = 面の先頭 quad 通し番号 * 4 を渡すので、
    // ここで得られるのは **全 7 draw をまたいだグローバルな通し番号** になる。
    uint quadOrdinal = uint(gl_VertexIndex) >> 2;
    uint cornerId = uint(gl_VertexIndex) & 3u;

    MergedQuadRef ref = resolveQuad(quadOrdinal);

    QuadData quad;
    uvec2 pos = positionBuffer[ref.drawId];
    setupQuad(quad, quadData[ref.quadIndex], pos, cornerId == 1u);

    gl_Position = getQuadCornerPos(quad, cornerId);
    uv = getCornerUV(quad, cornerId);

    //Note: other data is automatically discarded as it is undefiend and has not been generated
    interData = quad.attributeData;
}

#ifndef TAA_PATCH
vec2 taaShift() {return vec2(0.0);}
#endif
