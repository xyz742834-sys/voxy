package me.cortex.voxy.client.core.vk.shader;

import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.util.shaderc.Shaderc;

import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/** GLSL -> SPIR-V。shaderc 経由。 */
public class SpirvCompiler {
    private static final long COMPILER = Shaderc.shaderc_compiler_initialize();

    /** ソース文字列 -> SPIR-V のキャッシュ。同一シェーダの再コンパイルを避ける。 */
    private static final Map<String, ByteBuffer> CACHE = new HashMap<>();

    public static ByteBuffer compile(VkShaderType type, String source, String name) {
        String key = type.name() + "\0" + source;
        ByteBuffer cached = CACHE.get(key);
        if (cached != null) return cached;

        long opts = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(opts,
            Shaderc.shaderc_target_env_vulkan,
            Shaderc.shaderc_env_version_vulkan_1_2);
        Shaderc.shaderc_compile_options_set_optimization_level(opts,
            Shaderc.shaderc_optimization_level_performance);
        // OpName を残す。最適化を掛けると既定で剥がれてしまい、SpirvReflect が
        // 変数名を拾えなくなる。バインディング衝突や型不一致のエラーメッセージが
        // 'binding 1' ではなく "'quadData' vs 'lightSampler'" と出せるかどうかは
        // デバッグのしやすさに直結するため、サイズ増より可読性を取る。
        // (MoltenVK は debug info を無視するので実行時コストは無い)
        Shaderc.shaderc_compile_options_set_generate_debug_info(opts);

        long result = Shaderc.shaderc_compile_into_spv(
            COMPILER, source, type.shadercKind, name, "main", opts);

        int status = Shaderc.shaderc_result_get_compilation_status(result);
        if (status != Shaderc.shaderc_compilation_status_success) {
            String err = Shaderc.shaderc_result_get_error_message(result);
            Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(opts);
            Logger.error("SPIR-V compile failed (" + name + "):\n" + err);
            throw new IllegalStateException("shaderc: " + name + "\n" + err);
        }

        ByteBuffer spv = Shaderc.shaderc_result_get_bytes(result);
        ByteBuffer copy = MemoryUtil.memAlloc(spv.remaining());
        copy.put(spv).flip();

        Shaderc.shaderc_result_release(result);
        Shaderc.shaderc_compile_options_release(opts);

        CACHE.put(key, copy);
        return copy;
    }

    /**
     * プリプロセスのみ実行し、展開後のソースを返す。失敗したら null。
     *
     * <p>診断用。{@code #ifdef VULKAN} で分岐したソースは、生の文字列には
     * GL 側の分岐も残っているため、正規表現で「未移行の宣言」を探すと誤検出する。
     * 実際にコンパイル対象となるのは展開後のテキストなので、そちらを見る。
     */
    public static String preprocess(VkShaderType type, String source, String name) {
        long opts = Shaderc.shaderc_compile_options_initialize();
        Shaderc.shaderc_compile_options_set_target_env(opts,
            Shaderc.shaderc_target_env_vulkan,
            Shaderc.shaderc_env_version_vulkan_1_2);
        long result = Shaderc.shaderc_compile_into_preprocessed_text(
            COMPILER, source, type.shadercKind, name, "main", opts);
        try {
            if (Shaderc.shaderc_result_get_compilation_status(result)
                    != Shaderc.shaderc_compilation_status_success) {
                return null;
            }
            var bytes = Shaderc.shaderc_result_get_bytes(result);
            byte[] copy = new byte[bytes.remaining()];
            bytes.duplicate().get(copy);
            return new String(copy, java.nio.charset.StandardCharsets.UTF_8);
        } finally {
            Shaderc.shaderc_result_release(result);
            Shaderc.shaderc_compile_options_release(opts);
        }
    }

    public static void clearCache() {
        CACHE.values().forEach(MemoryUtil::memFree);
        CACHE.clear();
    }
}
