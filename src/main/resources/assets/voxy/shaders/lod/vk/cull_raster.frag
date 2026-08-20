#version 460 core

// 遮蔽カリングのフラグメント側 (Vulkan 専用)。lod/gl46/cull/raster.frag と中身は同じで、
// 入力に location を付けただけ。Vulkan GLSL は user in/out に location を要求する。

#define VISIBILITY_BUFFER_BINDING 2
layout(binding = VISIBILITY_BUFFER_BINDING, std430) restrict buffer VisibilityBuffer {
    uint visibilityData[];
};

// 深度テストを **フラグメントシェーダより前** に走らせる。
// これが無いと SSBO 書き込みのあるシェーダでは深度テストが後ろに回り、
// 遮蔽されたセクションまで可視と記録されてしまう。
layout(early_fragment_tests) in;

layout(location = 0) in flat uint id;
layout(location = 1) in flat uint value;

void main() {
    visibilityData[id] = value;
}
