#version 460 core

// Native port of voxy:post/blit_texture_depth_cutout.frag with EMIT_COLOUR (GL's final blit in
// NormalRenderPipeline.finish): Voxy's colour with Minecraft's environmental fog over distance
// (HAS_FOG) and a fade at Voxy's render-distance edge into alpha (HAS_FADE). It runs in Voxy's own
// submission into an image the composite then blends (SRC_ALPHA, ONE_MINUS_SRC_ALPHA, as GL) — and
// which the judged sample reads back as its reference. The depth reprojection GL does in the same
// blit is VkDepthResolve's (already native); this pass only needs Voxy's raw depth to find the
// point's distance.

#import <voxy:util/depthutils.glsl>

layout(binding = 0) uniform sampler2D colourTex;
layout(binding = 1) uniform sampler2D depthTex;   // Voxy's own depth, Voxy's projection

layout(push_constant) uniform PostPush {
    mat4 invMvp;        // inverse(Voxy's MVP), relative to the scene's anchor
    vec4 cameraRel;     // camera - anchor origin (xyz)
    vec4 endParams;     // GL's uniform 4
    vec4 fogColour;     // GL's uniform 5
    vec4 fadeParams;    // GL's uniform 6
} push;

layout(location = 0) out vec4 colour;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    vec2 uv = gl_FragCoord.xy / vec2(textureSize(depthTex, 0));
    float depth = texelFetch(depthTex, p, 0).r;
    if (depth == 0.0f || depth == 1.0f) {
        discard;
    }
    vec4 view = push.invMvp * vec4(SCREEN2NDC(vec3(uv, depth)), 1.0);
    vec3 point = view.xyz / view.w - push.cameraRel.xyz;   // camera-relative, as GL's rev3d

    colour = texelFetch(colourTex, p, 0);
    if (push.fogColour.a > 0.0) {
        float fogLerp = clamp(fma(length(point.xyz), push.endParams.x, push.endParams.y), 0, push.endParams.z);
        colour.rgb = mix(colour.rgb, push.fogColour.rgb, fogLerp * push.fogColour.a);
    }
    if (push.fadeParams.x > 0) {
        float len = push.fadeParams.x > 1.5 ? length(point.xyz) : length(point.xz);
        colour.a *= 1 - clamp(fma(len, push.fadeParams.z, push.fadeParams.y), 0, 1);
    } else {
        colour.a = 1.0;
    }
}

#import <voxy:util/depthutils.glsl>
