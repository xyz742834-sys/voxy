#version 460 core

// Native composite (Vulkan only, Minecraft on its Vulkan backend): Voxy's own render target —
// colour from VkHierarchicalScene, depth reprojected into Minecraft's depth space by the
// reprojecting VkDepthResolve — written into a pass that LOADs Minecraft's colour and depth.
//
// Voxy's GL composition rule: Voxy's terrain appears only where Minecraft drew nothing. The GL
// path builds a stencil from Minecraft's depth (setup_stencil_depth.frag: pixels whose depth is not
// the clear value are excluded) and draws Voxy only inside it. Here the depth test does the same:
// the fragment's depth is the clear value 0 and the pipeline compares with Voxy's GREATER_OR_EQUAL,
// so it passes exactly where Minecraft's stored depth is still 0 (reverse-Z, nothing nearer can be
// stored below 0). Depth writes are off: Minecraft's depth is left as it was.
//
// texelFetch at the fragment's own pixel: both images are this frame's extent, so pixel i is
// pixel i; no filtering.

layout(binding = 0) uniform sampler2D srcColour;
layout(binding = 1) uniform sampler2D srcDepth;

layout(location = 0) out vec4 outColour;

void main() {
    ivec2 p = ivec2(gl_FragCoord.xy);
    float d = texelFetch(srcDepth, p, 0).r;
    // reverse-Z clear is 0.0: no Voxy geometry at this pixel, leave Minecraft's untouched
    if (!(d > 0.0)) discard;
    outColour = texelFetch(srcColour, p, 0);
    gl_FragDepth = 0.0;
}
