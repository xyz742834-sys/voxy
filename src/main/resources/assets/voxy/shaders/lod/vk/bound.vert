#version 460 core

// Native port of voxy:chunkoutline/outline.vsh (GL's BoundRenderer): the boxes of Minecraft's
// built, visible chunk sections, depth only, into Voxy's depth-bound texture. Voxy's terrain then
// discards fragments nearer than the bound (lod/gl46/quads.frag: DEPTH_SCALAR_COMPARE(fragment,
// bound)), so Voxy draws only beyond vanilla's loaded terrain — the near cut.
//
// Differences from the GL shader, none of them in what is drawn: non-indexed (36 vertices per box
// from a corner table instead of a shared byte index buffer); one box per instance; positions are
// plain section coordinates (ivec4) instead of GL's packed ivec2; the matrix is Voxy's MVP relative
// to the scene's anchor (VkHostViewport.mvp), so origins are made anchor-relative. Faces are not
// culled: the pipeline keeps the farther depth (GL draws back faces with the same compare), and the
// farthest face of a box is a back face.

#import <voxy:util/depthutils.glsl>

layout(push_constant) uniform BoundPush {
    mat4 MVP;            // Voxy's projection * view, input relative to the anchor origin
    ivec4 anchorBlock;   // anchor section * 32, in blocks
    vec4 cameraRel;      // camera - anchorBlock (xyz), vanilla render distance in blocks (w)
} push;

layout(binding = 1, std430) restrict readonly buffer SectionBuffer {
    ivec4 sections[];    // chunk section coordinates (16-block units)
};

// the 12 triangles of a box, as corner indices (bit0 = x, bit1 = z, bit2 = y, as outline.vsh)
const int CORNERS[36] = int[36](
    0, 1, 3, 0, 3, 2,   4, 6, 7, 4, 7, 5,   0, 4, 5, 0, 5, 1,
    2, 3, 7, 2, 7, 6,   0, 2, 6, 0, 6, 4,   1, 5, 7, 1, 7, 3);

bool shouldRender(vec3 origin) {
    // outline.vsh's test: the box's nearest point to the camera within the render distance
    vec3 lo = origin - push.cameraRel.xyz;
    vec3 hi = lo + 16.0;
    vec3 nearest = clamp(vec3(0.0), lo, hi);
    return (nearest.x * nearest.x + nearest.z * nearest.z) < push.cameraRel.w * push.cameraRel.w
        && abs(nearest.y) < push.cameraRel.w;
}

void main() {
    ivec3 section = sections[gl_InstanceIndex].xyz;
    vec3 origin = vec3(section * 16 - push.anchorBlock.xyz);
    if (!shouldRender(origin)) {
        gl_Position = vec4(-100.0, -100.0, -100.0, 0.0);
        return;
    }
    int v = CORNERS[gl_VertexIndex];
    vec3 corner = vec3(ivec3(v & 1, (v >> 2) & 1, (v >> 1) & 1) * 16);
    gl_Position = push.MVP * vec4(origin + corner, 1.0);
    // outline.vsh: bring the bound a little closer to the camera
    gl_Position.z += CLOSER_SIGN * 0.0005;
}

#import <voxy:util/depthutils.glsl>
