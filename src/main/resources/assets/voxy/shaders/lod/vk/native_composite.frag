#version 460 core

// Native composite (Vulkan only, Minecraft on its Vulkan backend): Voxy's own render target —
// colour and D32 depth from VkHierarchicalScene — written into a pass that LOADs Minecraft's
// colour and depth. The pipeline tests and writes depth with Voxy's GREATER_OR_EQUAL, so a Voxy
// pixel lands exactly where its depth is at or nearer than Minecraft's (reverse-Z).
//
// texelFetch at the fragment's own pixel: both images are this frame's extent and were drawn
// with the same matrix, so pixel i is pixel i; no filtering, no reprojection (this slice renders
// Voxy with Minecraft's projection).

layout(binding = 0) uniform sampler2D srcColour;
layout(binding = 1) uniform sampler2D srcDepth;

layout(location = 0) out vec4 outColour;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float d = texelFetch(srcDepth, p, 0).r;
    // reverse-Z clear is 0.0: no Voxy geometry at this pixel, leave Minecraft's untouched
    if (!(d > 0.0)) discard;
    outColour = texelFetch(srcColour, p, 0);
    gl_FragDepth = d;
}
