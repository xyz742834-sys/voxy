package me.cortex.voxy.client.core.vk.shader;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Vulkan 向けのシェーダソース組み立て。
 *
 * <p>GL 側 {@code ShaderLoader} と同じ {@code #import} 展開規則を実装しているが、
 * <b>{@code net.minecraft.resources.Identifier} に依存しない</b>。
 * 依存を切った理由は 2 つ:
 * <ul>
 *   <li>Minecraft を起動せずに {@code ./gradlew test} からシェーダをコンパイルできるようにするため。
 *       {@code VkContext} はサーフェスもウィンドウも持たないので、この 1 点さえ外れれば
 *       Vulkan 経路全体が素の JVM で動く。</li>
 *   <li>Vulkan バックエンドが Minecraft のリソース系に触る必要が実際に無いため。
 *       シェーダは mod の jar に入っており、クラスパスから直接読める。</li>
 * </ul>
 *
 * <p>展開規則は {@code ShaderLoader.ShaderLoadingParser.parseRoot} と同一:
 * {@code #version} 行を落とし、{@code #import <ns:path>} を再帰的に本文へ置換する。
 */
public class VkShaderLoader {
    private static final Pattern IMPORT_PATTERN =
        Pattern.compile("#import <(?<namespace>.*):(?<path>.*)>");

    /**
     * GL / Vulkan の組み込み変数差を吸収する互換プレリュード。
     * <b>Vulkan 経路にのみ注入される。GL 側のソースは一切変更しない。</b>
     *
     * <p>ここに入れてよいのは「GL と Vulkan で意味論が完全に一致するもの」だけ。
     * 内容の一覧と根拠は {@code docs/phase2-glsl-compat.md} を参照。
     *
     * <p><b>{@code gl_InstanceID} は意図的に含めていない。</b>
     * GL の {@code gl_InstanceID} は baseInstance を含まないが Vulkan の
     * {@code gl_InstanceIndex} は含むため、機械的な置換は誤りになる。
     * 使用しているシェーダは {@link VkShader} 側で明示的なエラーにしている。
     */
    private static final String COMPAT_PRELUDE =
        // GL: index + basevertex / VK: index + vertexOffset -> 同一。安全に置換できる
        "#define gl_VertexID gl_VertexIndex\n";
    // NOTE: バックエンド判別に使う VULKAN マクロはここでは定義しない。
    // shaderc/glslang が Vulkan ターゲット時に VULKAN=100 を自動で定義するため、
    // 自前で定義すると "Macro redefined; different substitutions" になる。
    // GL 側の glslang は定義しないので、#ifdef VULKAN がそのまま backend 判別として働く。

    /** {@code "voxy:lod/gl46/quads3.vert"} 形式の id を受け取り、展開済みソースを返す。 */
    public static String parse(String id) {
        int colon = id.indexOf(':');
        if (colon < 0) throw new IllegalArgumentException("shader id must be 'namespace:path': " + id);
        return parse(id.substring(0, colon), id.substring(colon + 1));
    }

    public static String parse(String namespace, String path) {
        // Vulkan GLSL では core プロファイル指定が無効なので "core" は付けない。
        return "#version 460\n" + COMPAT_PRELUDE + String.join("\n", expand(namespace, path));
    }

    /** 注入している互換 define の一覧 (ドキュメント / テスト用)。 */
    public static String compatPrelude() { return COMPAT_PRELUDE; }

    private static List<String> expand(String namespace, String path) {
        List<String> out = new ArrayList<>();
        for (String line : load(namespace, path).split("\n", -1)) {
            if (line.startsWith("#version")) continue;
            if (line.startsWith("#import")) {
                Matcher m = IMPORT_PATTERN.matcher(line);
                if (!m.matches()) throw new IllegalArgumentException("Unknown import: " + line);
                out.addAll(expand(m.group("namespace"), m.group("path")));
            } else {
                out.add(line);
            }
        }
        return out;
    }

    private static String load(String namespace, String path) {
        String res = "/assets/" + namespace + "/shaders/" + path;
        try (InputStream in = VkShaderLoader.class.getResourceAsStream(res)) {
            if (in == null) throw new IllegalArgumentException("Shader not found: " + res);
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new RuntimeException("Failed to read shader source for " + res, e);
        }
    }
}
