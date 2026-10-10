#version 460 core

// Native composite (Vulkan only, Minecraft on its Vulkan backend): Voxy's own render target —
// colour from VkHierarchicalScene, depth reprojected into Minecraft's depth space by the
// reprojecting VkDepthResolve — written into a pass that LOADs Minecraft's colour and depth.
//
// Voxy's GL composition: drawn at GL Voxy's point (Sodium's cutout pass, McNativeFrameHooks), with
// the vanilla depth bound (the near cut) already discarding Voxy's terrain inside Minecraft's loaded
// terrain, it writes Voxy's colour and its reprojected depth with Voxy's GREATER_OR_EQUAL, as GL's
// final blit does, so Minecraft's later cutout/translucent/entity draws test against Voxy. GL also
// confines Voxy to pixels where Minecraft's depth is still clear (a stencil from Minecraft's depth);
// with the near cut that holds wherever the bound covers Minecraft's terrain, and the judged samples
// still require it pixel by pixel (Voxy only on the ladder's CLEAR pixels).
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
    gl_FragDepth = d;
}
