#version 460 core

// 深度可視化パスの頂点シェーダ (Vulkan 専用)。
//
// 画面全体を覆う三角形 1 枚。頂点バッファは使わない (gl_VertexIndex から作る)。
// depth_resolve.vert と同じ形で、深度は書かないので gl_Position.z は何でもよい。

void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
