#version 460 core
#extension GL_ARB_gpu_shader_int64 : enable

// 遮蔽カリング用のラスタパス (Vulkan 専用)。lod/gl46/cull/raster.vert に対応する。
//
// セクションの AABB を深度テスト付きで描き、**1 フラグメントでも通ったセクション**に
// visibilityData[sid] = frameId を書く。フラグメント側 (cull/raster.frag) は
// early_fragment_tests 付きで SSBO に書くだけなので GL 版をそのまま使える。
//
// == GL 版との唯一の違い: gl_InstanceID -> gl_InstanceIndex ==
// GL の gl_InstanceID は baseInstance を含まないが、Vulkan の gl_InstanceIndex は含む。
// 機械的な置換は baseInstance != 0 の描画で静かに壊れるため、互換プレリュードでは
// 意図的に置換していない (docs/phase2-glsl-compat.md 3.1)。
//
// **ここでは置換してよい。** prep が cullDrawIndirectCommand.baseInstance = 0 を書くので
// [確認済 — lod/gl46/prep.comp]、両者の値は一致する。
// この前提は VkCullPass が baseInstance = 0 を書くことで保たれており、
// VkCullTest.cullDrawUsesZeroBaseInstance が常設で見張っている。

#define VISIBILITY_ACCESS

#define SECTION_METADATA_BUFFER_BINDING 1
#define VISIBILITY_BUFFER_BINDING 2
#define INDIRECT_SECTION_LOOKUP_BINDING 3

#import <voxy:lod/section.glsl>
#import <voxy:lod/gl46/bindings.glsl>
#import <voxy:util/depthutils.glsl>

layout(location = 0) out flat uint id;
layout(location = 1) out flat uint value;

void main() {
    uint sid = indirectLookup[gl_InstanceIndex];

    SectionMeta section = sectionData[sid];

    uint detail = extractDetail(section);
    ivec3 ipos = extractPosition(section);
    ivec3 aabbOffset = extractAABBOffset(section);
    ivec3 size = extractAABBSize(section);

    //Transform ipos with respect to the vertex corner
    ivec3 pos = (((ipos<<detail)-baseSectionPos)<<5);

    const float EXPANSION = 1.0f;

    vec3 offset = aabbOffset-EXPANSION;
    offset += vec3(gl_VertexIndex&1, (gl_VertexIndex>>2)&1, (gl_VertexIndex>>1)&1)*(size+2*EXPANSION);

    gl_Position = MVP * vec4(vec3(pos)+offset*(1<<detail),1);

    //Bring closer to camera
    gl_Position.z += (CLOSER_SIGN*0.000001f) * gl_Position.w;

    id = sid;

    //Me when data race condition between visibilityData in the vert shader and frag shader
    uint previous = visibilityData[sid]&0x7fffffffu;
    bool wasVisibleLastFrame = previous==(frameId-1);
    value = (frameId&0x7fffffffu)|(uint(wasVisibleLastFrame)<<31);
}

//Undefine depth stuff
#import <voxy:util/depthutils.glsl>
