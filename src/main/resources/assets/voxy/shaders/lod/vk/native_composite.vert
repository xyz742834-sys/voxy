#version 460 core

// Full-screen triangle (Vulkan only), shared shape with depth_resolve.vert.
//
// 画面全体を覆う三角形 1 枚。頂点バッファは使わない (gl_VertexIndex から作る)。
// Voxy の GL 側 post/fullscreen2.vert は 4 頂点のクアッドだが、
// ここは深度を書かないので gl_Position.z は何でもよく、3 頂点で足りる。

void main() {
    vec2 p = vec2((gl_VertexIndex << 1) & 2, gl_VertexIndex & 2);
    gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
}
