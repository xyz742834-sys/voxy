package me.cortex.voxy.client.core.vk.shader;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;

/**
 * SPIR-V バイナリから descriptor バインディングと push constant のサイズを取り出す最小リフレクション。
 *
 * <p>GLSL ソースを正規表現で読むのではなくコンパイル結果を読む理由:
 * {@code #ifdef} とマクロ展開が済んだ後の「実際に存在する宣言」だけが SPIR-V に残るため。
 * 例えば {@code bindings.glsl} の大半の宣言は {@code #ifdef} ガード付きで、
 * どれが有効かはシェーダごとに異なる。
 *
 * <p>読むのは以下だけで、命令列の他の部分は読み飛ばす:
 * <ul>
 *   <li>{@code OpDecorate}     — DescriptorSet / Binding / Block / BufferBlock / ArrayStride</li>
 *   <li>{@code OpMemberDecorate} — Offset / MatrixStride (push constant のサイズ算出用)</li>
 *   <li>{@code OpVariable}     — storage class から descriptor type を決める</li>
 *   <li>{@code OpType*}        — ポインタ先の型を辿るため</li>
 * </ul>
 */
public final class SpirvReflect {
    private static final int MAGIC = 0x07230203;

    // --- opcodes ---
    private static final int OP_NAME              = 5;
    private static final int OP_TYPE_INT          = 21;
    private static final int OP_TYPE_FLOAT        = 22;
    private static final int OP_TYPE_VECTOR       = 23;
    private static final int OP_TYPE_MATRIX       = 24;
    private static final int OP_TYPE_IMAGE        = 25;
    private static final int OP_TYPE_SAMPLER      = 26;
    private static final int OP_TYPE_SAMPLED_IMAGE= 27;
    private static final int OP_TYPE_ARRAY        = 28;
    private static final int OP_TYPE_RUNTIME_ARRAY= 29;
    private static final int OP_TYPE_STRUCT       = 30;
    private static final int OP_TYPE_POINTER      = 32;
    private static final int OP_CONSTANT          = 43;
    private static final int OP_VARIABLE          = 59;
    private static final int OP_DECORATE          = 71;
    private static final int OP_MEMBER_DECORATE   = 72;

    // --- decorations ---
    private static final int DEC_BLOCK         = 2;
    private static final int DEC_BUFFER_BLOCK  = 3;
    private static final int DEC_ARRAY_STRIDE  = 6;
    private static final int DEC_MATRIX_STRIDE = 7;
    private static final int DEC_BINDING       = 33;
    private static final int DEC_DESCRIPTOR_SET= 34;
    private static final int DEC_OFFSET        = 35;

    // --- storage classes ---
    private static final int SC_UNIFORM_CONSTANT = 0;
    private static final int SC_UNIFORM          = 2;
    private static final int SC_PUSH_CONSTANT    = 9;
    private static final int SC_STORAGE_BUFFER   = 12;

    /** 反射で得た 1 つの descriptor バインディング。 */
    public record Binding(int set, int binding, int descriptorType, int count, String name) {
        public String typeName() {
            return switch (this.descriptorType) {
                case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER         -> "UNIFORM_BUFFER";
                case VK_DESCRIPTOR_TYPE_STORAGE_BUFFER         -> "STORAGE_BUFFER";
                case VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER -> "COMBINED_IMAGE_SAMPLER";
                case VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE          -> "SAMPLED_IMAGE";
                case VK_DESCRIPTOR_TYPE_STORAGE_IMAGE          -> "STORAGE_IMAGE";
                case VK_DESCRIPTOR_TYPE_SAMPLER                -> "SAMPLER";
                default -> "type" + this.descriptorType;
            };
        }
    }

    public record Result(List<Binding> bindings, int pushConstantSize) {}

    // 命令走査中の状態
    private final Map<Integer, int[]> typeOps = new HashMap<>();      // resultId -> full instruction words
    private final Map<Integer, Integer> decSet = new HashMap<>();
    private final Map<Integer, Integer> decBinding = new HashMap<>();
    private final Map<Integer, Integer> decArrayStride = new HashMap<>();
    private final Map<Integer, Boolean> isBufferBlock = new HashMap<>();
    private final Map<Integer, Boolean> isBlock = new HashMap<>();
    private final Map<Integer, Long> constants = new HashMap<>();
    private final Map<Integer, String> names = new HashMap<>();
    /** structId -> (memberIndex -> offset) */
    private final Map<Integer, Map<Integer, Integer>> memberOffset = new HashMap<>();
    /** structId -> (memberIndex -> matrixStride) */
    private final Map<Integer, Map<Integer, Integer>> memberMatrixStride = new HashMap<>();
    private final List<int[]> variables = new ArrayList<>();

    private SpirvReflect() {}

    public static Result reflect(ByteBuffer spirv) {
        return new SpirvReflect().run(spirv);
    }

    private Result run(ByteBuffer spirv) {
        IntBuffer w = spirv.duplicate().order(ByteOrder.LITTLE_ENDIAN).asIntBuffer();
        if (w.remaining() < 5 || w.get(0) != MAGIC) {
            throw new IllegalArgumentException("not a SPIR-V module (magic=0x"
                + Integer.toHexString(w.remaining() > 0 ? w.get(0) : 0) + ")");
        }

        int i = 5; // skip header
        while (i < w.limit()) {
            int word0 = w.get(i);
            int count = word0 >>> 16;
            int op = word0 & 0xFFFF;
            if (count == 0) break; // malformed; stop rather than loop forever
            int[] ins = new int[count];
            for (int k = 0; k < count; k++) ins[k] = w.get(i + k);
            this.consume(op, ins);
            i += count;
        }

        return new Result(this.buildBindings(), this.computePushConstantSize());
    }

    private void consume(int op, int[] ins) {
        switch (op) {
            case OP_NAME -> {
                if (ins.length > 2) this.names.put(ins[1], decodeString(ins, 2));
            }
            case OP_DECORATE -> {
                int target = ins[1];
                int dec = ins[2];
                switch (dec) {
                    case DEC_DESCRIPTOR_SET -> this.decSet.put(target, ins[3]);
                    case DEC_BINDING        -> this.decBinding.put(target, ins[3]);
                    case DEC_ARRAY_STRIDE   -> this.decArrayStride.put(target, ins[3]);
                    case DEC_BUFFER_BLOCK   -> this.isBufferBlock.put(target, true);
                    case DEC_BLOCK          -> this.isBlock.put(target, true);
                    default -> {}
                }
            }
            case OP_MEMBER_DECORATE -> {
                int structId = ins[1];
                int member = ins[2];
                int dec = ins[3];
                if (dec == DEC_OFFSET) {
                    this.memberOffset.computeIfAbsent(structId, k -> new HashMap<>()).put(member, ins[4]);
                } else if (dec == DEC_MATRIX_STRIDE) {
                    this.memberMatrixStride.computeIfAbsent(structId, k -> new HashMap<>()).put(member, ins[4]);
                }
            }
            case OP_CONSTANT -> {
                // ins = [word0, resultType, resultId, value...]
                if (ins.length >= 4) this.constants.put(ins[2], Integer.toUnsignedLong(ins[3]));
            }
            case OP_VARIABLE -> this.variables.add(ins);
            case OP_TYPE_INT, OP_TYPE_FLOAT, OP_TYPE_VECTOR, OP_TYPE_MATRIX, OP_TYPE_IMAGE,
                 OP_TYPE_SAMPLER, OP_TYPE_SAMPLED_IMAGE, OP_TYPE_ARRAY, OP_TYPE_RUNTIME_ARRAY,
                 OP_TYPE_STRUCT, OP_TYPE_POINTER -> this.typeOps.put(ins[1], ins);
            default -> {}
        }
    }

    private static String decodeString(int[] ins, int start) {
        StringBuilder sb = new StringBuilder();
        outer:
        for (int k = start; k < ins.length; k++) {
            int v = ins[k];
            for (int b = 0; b < 4; b++) {
                int c = (v >>> (b * 8)) & 0xFF;
                if (c == 0) break outer;
                sb.append((char) c);
            }
        }
        return sb.toString();
    }

    private static String firstNonEmpty(String... candidates) {
        for (String c : candidates) if (c != null && !c.isEmpty()) return c;
        return "?";
    }

    private int opcodeOf(int id) {
        int[] ins = this.typeOps.get(id);
        return ins == null ? -1 : (ins[0] & 0xFFFF);
    }

    private List<Binding> buildBindings() {
        List<Binding> out = new ArrayList<>();
        for (int[] v : this.variables) {
            // OpVariable: [word0, resultType(ptr), resultId, storageClass, (initializer)]
            int ptrType = v[1];
            int id = v[2];
            int storage = v[3];

            Integer binding = this.decBinding.get(id);
            Integer set = this.decSet.get(id);
            if (binding == null) continue;          // not a descriptor
            int setIdx = set == null ? 0 : set;     // GLSL の set 省略時は 0

            int[] ptr = this.typeOps.get(ptrType);
            if (ptr == null) continue;
            int pointee = ptr[3];                    // OpTypePointer: [w0, id, storageClass, type]

            // 配列なら descriptorCount を取り、要素型まで降りる
            int count = 1;
            while (this.opcodeOf(pointee) == OP_TYPE_ARRAY) {
                int[] arr = this.typeOps.get(pointee);
                Long n = this.constants.get(arr[3]);
                count *= n == null ? 1 : (int) (long) n;
                pointee = arr[2];
            }

            int type = this.descriptorTypeOf(storage, pointee, id);
            if (type < 0) continue;
            // インターフェースブロック (UBO/SSBO) は GLSL 上で変数が無名なため、
            // OpName は空文字列で載っている。その場合はブロック型の名前
            // ("SceneUniform" など) に落とす。getOrDefault だと空文字列を
            // 「見つかった」と扱ってしまうので明示的に弾く。
            String name = firstNonEmpty(this.names.get(id), this.names.get(pointee));
            out.add(new Binding(setIdx, binding, type, count, name));
        }
        out.sort((a, b) -> a.set() != b.set() ? Integer.compare(a.set(), b.set())
                                             : Integer.compare(a.binding(), b.binding()));
        return out;
    }

    private int descriptorTypeOf(int storage, int pointee, int varId) {
        switch (storage) {
            case SC_UNIFORM_CONSTANT -> {
                int oc = this.opcodeOf(pointee);
                if (oc == OP_TYPE_SAMPLED_IMAGE) return VK_DESCRIPTOR_TYPE_COMBINED_IMAGE_SAMPLER;
                if (oc == OP_TYPE_SAMPLER)       return VK_DESCRIPTOR_TYPE_SAMPLER;
                if (oc == OP_TYPE_IMAGE) {
                    // OpTypeImage: [w0, id, sampledType, dim, depth, arrayed, ms, sampled, format...]
                    int[] img = this.typeOps.get(pointee);
                    int sampled = img.length > 7 ? img[7] : 0;
                    return sampled == 2 ? VK_DESCRIPTOR_TYPE_STORAGE_IMAGE
                                        : VK_DESCRIPTOR_TYPE_SAMPLED_IMAGE;
                }
                return -1;
            }
            case SC_UNIFORM -> {
                // SPIR-V 1.0-1.2: SSBO は Uniform + BufferBlock、UBO は Uniform + Block
                if (Boolean.TRUE.equals(this.isBufferBlock.get(pointee))) return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
                return VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER;
            }
            case SC_STORAGE_BUFFER -> {
                // SPIR-V 1.3+: SSBO は StorageBuffer + Block
                return VK_DESCRIPTOR_TYPE_STORAGE_BUFFER;
            }
            default -> {
                return -1;
            }
        }
    }

    /** push constant ブロックの必要バイト数。無ければ 0。 */
    private int computePushConstantSize() {
        int max = 0;
        for (int[] v : this.variables) {
            if (v[3] != SC_PUSH_CONSTANT) continue;
            int[] ptr = this.typeOps.get(v[1]);
            if (ptr == null) continue;
            max = Math.max(max, this.sizeOf(ptr[3]));
        }
        return max;
    }

    /**
     * 型のバイトサイズ。push constant ブロックのサイズ算出にのみ使う。
     * shaderc は push_constant ブロックに Offset/ArrayStride/MatrixStride を必ず付けるため、
     * それらを優先して使い、無い場合のみ素の計算に落とす。
     */
    private int sizeOf(int typeId) {
        int[] t = this.typeOps.get(typeId);
        if (t == null) return 0;
        int oc = t[0] & 0xFFFF;
        switch (oc) {
            case OP_TYPE_INT, OP_TYPE_FLOAT -> {
                return t[2] / 8;                       // width in bits
            }
            case OP_TYPE_VECTOR -> {
                return this.sizeOf(t[2]) * t[3];
            }
            case OP_TYPE_MATRIX -> {
                return this.sizeOf(t[2]) * t[3];       // columnType * columnCount
            }
            case OP_TYPE_ARRAY -> {
                Long n = this.constants.get(t[3]);
                int stride = this.decArrayStride.getOrDefault(typeId, this.sizeOf(t[2]));
                return n == null ? 0 : (int) (long) n * stride;
            }
            case OP_TYPE_STRUCT -> {
                Map<Integer, Integer> offs = this.memberOffset.get(typeId);
                Map<Integer, Integer> mstride = this.memberMatrixStride.getOrDefault(typeId, Map.of());
                int size = 0;
                for (int m = 0; m < t.length - 2; m++) {
                    int memberType = t[2 + m];
                    int memberSize;
                    Integer ms = mstride.get(m);
                    if (ms != null && this.opcodeOf(memberType) == OP_TYPE_MATRIX) {
                        memberSize = ms * this.typeOps.get(memberType)[3];
                    } else {
                        memberSize = this.sizeOf(memberType);
                    }
                    int off = offs == null ? 0 : offs.getOrDefault(m, 0);
                    size = Math.max(size, off + memberSize);
                }
                return size;
            }
            default -> {
                return 0;
            }
        }
    }
}
