package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkNodeTree;
import me.cortex.voxy.client.core.vk.VkSceneUniform;
import me.cortex.voxy.client.core.vk.shader.SpirvCompiler;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import me.cortex.voxy.client.core.vk.shader.VkShaderType;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Independent decoding of actual shaderc SPIR-V decorations, not runtime reflection. */
class ShaderMemoryLayoutTest {
    private static final String DEPTH = "#define USE_REVERSE_Z\n#define USE_ZERO_ONE_DEPTH\n";
    private static final String TINT = "#define NO_SHADE_FACE_TINT 1.0\n#define UP_FACE_TINT 1.0\n"
        + "#define DOWN_FACE_TINT 0.5\n#define Z_AXIS_FACE_TINT 0.8\n#define X_AXIS_FACE_TINT 0.6\n";

    @Test void actualTerrainUniformOffsetsMatchTheJavaWriter() {
        var layout = compile(VkShaderType.VERTEX, VkShaderLoader.parse("voxy:lod/gl46/quads3.vert"), DEPTH + TINT);
        assertArrayEquals(new int[]{0, 64, 76, 80}, layout.offsets("SceneUniform"));
        assertEquals(16, layout.memberDecoration("SceneUniform", 0, 7)); // MatrixStride
        assertEquals(80 + 3 * Float.BYTES, VkSceneUniform.SIZE);
    }

    @Test void actualTraversalOffsetsAndNodeStrideMatchHostPacking() {
        String defines = DEPTH + """
            #define MAX_ITERATIONS 5
            #define LOCAL_SIZE_BITS 5
            #define MAX_REQUEST_QUEUE_SIZE 1024
            #define HIZ_BINDING 0
            #define SCENE_UNIFORM_BINDING 1
            #define REQUEST_QUEUE_BINDING 2
            #define RENDER_QUEUE_BINDING 3
            #define NODE_DATA_BINDING 4
            #define NODE_QUEUE_META_BINDING 6
            #define NODE_QUEUE_SOURCE_BINDING 7
            #define NODE_QUEUE_SINK_BINDING 8
            #define RENDER_TRACKER_BINDING 9
            #define TRAVERSAL_LIMITS_BINDING 10
            """;
        String source = VkShaderLoader.parse("voxy:lod/hierarchical/traversal_dev.comp").replace("printf", "//printf");
        var layout = compile(VkShaderType.COMPUTE, source, defines);
        assertArrayEquals(new int[]{0, 64, 76, 80, 92, 96, 192, 196, 200, 204}, layout.offsets("SceneUniform"));
        assertEquals(VkNodeTree.NODE_SIZE, layout.arrayStride("NodeData", 0));
        assertArrayEquals(new int[]{0, 8}, layout.offsets("requestQueueStruct"));
        assertEquals(8, layout.arrayStride("requestQueueStruct", 1));
        assertArrayEquals(new int[]{0, 4}, layout.offsets("renderQueueStruct"));
        assertEquals(4, layout.arrayStride("renderQueueStruct", 1));
    }

    @Test void sharedBufferStructsHaveTheDocumentedOffsetsAndStrides() {
        // Read the real shared declarations, exercising every field so optimization retains them.
        String source = VkShaderLoader.parse("voxy:lod/section.glsl")
            + VkShaderLoader.parse("voxy:lod/block_model.glsl").replace("#version 460", "")
            + VkShaderLoader.parse("voxy:lod/gl46/bindings.glsl").replace("#version 460", "")
            + """
            layout(local_size_x=1) in;
            void main() {
                uint i=gl_GlobalInvocationID.x;
                cmdBuffer[i]=DrawCommand(sectionData[i].a.x, sectionData[i].b.x,
                    modelData[i].faceData[i%6], int(modelData[i].flagsA),
                    modelData[i].colourTint+modelData[i].customId+modelData[i]._pad[i%7]);
            }
            """;
        var layout = compile(VkShaderType.COMPUTE, source,
            "#define DRAW_BUFFER_BINDING 1\n#define SECTION_METADATA_BUFFER_BINDING 2\n#define MODEL_BUFFER_BINDING 3\n");
        assertArrayEquals(new int[]{0, 4, 8, 12, 16}, layout.offsets("DrawCommand"));
        assertEquals(20, layout.arrayStride("DrawBuffer", 0));
        assertArrayEquals(new int[]{0, 16}, layout.offsets("SectionMeta"));
        assertEquals(32, layout.arrayStride("SectionBuffer", 0));
        assertArrayEquals(new int[]{0, 24, 28, 32, 36}, layout.offsets("BlockModel"));
        assertEquals(64, layout.arrayStride("ModelBuffer", 0));
    }

    @Test void insertingARealShaderMemberBreaksTheExpectedContract() {
        String source = VkShaderLoader.parse("voxy:lod/gl46/quads3.vert");
        assertTrue(source.contains("uint frameId;"));
        var layout = compile(VkShaderType.VERTEX, source.replace("uint frameId;", "uvec4 injectedMember;\nuint frameId;"), DEPTH + TINT);
        assertThrows(AssertionError.class, () -> assertArrayEquals(new int[]{0, 64, 76, 80}, layout.offsets("SceneUniform")));
    }

    private static Layout compile(VkShaderType type, String source, String defines) {
        return new Layout(SpirvCompiler.compile(type, source.replaceFirst("#version 460", "#version 460\n" + defines), "abi-" + type));
    }

    private static final class Layout {
        final Map<Integer, String> names = new HashMap<>();
        final Map<Integer, int[]> structs = new HashMap<>();
        final Map<Integer, Integer> strides = new HashMap<>();
        final Map<String, Integer> members = new HashMap<>();
        Layout(ByteBuffer binary) {
            var words = binary.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
            assertEquals(0x07230203, words.get(0));
            for (int p = 5; p < words.limit();) {
                int instruction = words.get(p), count = instruction >>> 16, opcode = instruction & 65535;
                assertTrue(count > 0 && p + count <= words.limit(), "malformed SPIR-V instruction");
                if (opcode == 5) {
                    var text = new StringBuilder();
                    outer: for (int j = p + 2; j < p + count; j++) for (int b = 0; b < 4; b++) {
                        int c = (words.get(j) >>> (8 * b)) & 255;
                        if (c == 0) break outer;
                        text.append((char)c);
                    }
                    names.put(words.get(p + 1), text.toString());
                } else if (opcode == 30) {
                    int[] types = new int[count - 2];
                    for (int j = 0; j < types.length; j++) types[j] = words.get(p + 2 + j);
                    structs.put(words.get(p + 1), types);
                } else if (opcode == 71 && count == 4 && words.get(p + 2) == 6) {
                    strides.put(words.get(p + 1), words.get(p + 3));
                } else if (opcode == 72 && count == 5) {
                    members.put(words.get(p + 1) + ":" + words.get(p + 2) + ":" + words.get(p + 3), words.get(p + 4));
                }
                p += count;
            }
        }
        int type(String name) {
            return names.entrySet().stream().filter(e -> e.getValue().equals(name) && structs.containsKey(e.getKey())
                && members.containsKey(e.getKey() + ":0:35")).map(Map.Entry::getKey).findFirst().orElseThrow();
        }
        int memberDecoration(String name, int member, int decoration) {
            return Objects.requireNonNull(members.get(type(name) + ":" + member + ":" + decoration), "missing decoration");
        }
        int[] offsets(String name) {
            int[] offsets = new int[structs.get(type(name)).length];
            for (int i = 0; i < offsets.length; i++) offsets[i] = memberDecoration(name, i, 35);
            return offsets;
        }
        int arrayStride(String name, int member) {
            return Objects.requireNonNull(strides.get(structs.get(type(name))[member]), "missing ArrayStride");
        }
    }
}
