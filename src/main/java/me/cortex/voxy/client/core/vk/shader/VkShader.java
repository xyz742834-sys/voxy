package me.cortex.voxy.client.core.vk.shader;

import me.cortex.voxy.client.core.gl.shader.IShaderProcessor;
import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.common.Logger;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * GL 側 {@code Shader} の Vulkan 版。
 *
 * <h2>バインディング規約</h2>
 * <b>全リソースを set 0 に置く。</b> GLSL は {@code set} 省略時に 0 になるため、
 * 既存の {@code layout(binding = N)} は無変更でそのまま通る。
 * draw 統合設計 (Phase 4) によりドロー単位の descriptor 更新が消えるので、
 * set を分ける動機 (頻繁な再バインドの回避) が該当しない。
 *
 * <h2>GL 版との違い</h2>
 * Vulkan には「プログラムを bind する」概念が無いため {@code bind()} は持たない。
 * 本クラスが持つのはシェーダモジュールと
 * {@link #descriptorSetLayout()} / {@link #pipelineLayout()} で、
 * 実際のバインドはパイプライン生成側が行う。
 */
public class VkShader {
    /**
     * デフォルトブロック uniform の検出用。
     * Vulkan GLSL では {@code layout(location=N) uniform vec2 x;} 形式が禁止されており、
     * shaderc に投げると分かりにくいエラーになるため、事前に弾いて明示的に報告する。
     *
     * <p>ブロック宣言 ({@code uniform Foo { ... }}) と不透明型
     * ({@code sampler*} / {@code image*} / {@code texture*} / {@code subpassInput}) は対象外。
     */
    /** 互換プレリュードで置換していない GL 固有 builtin。詳細は {@code checkNoUnshimmedInstanceId}。 */
    private static final Pattern GL_INSTANCE_ID = Pattern.compile("\\bgl_InstanceID\\b");

    private static final Pattern DEFAULT_BLOCK_UNIFORM = Pattern.compile(
        "(?m)^[^/\\n]*?\\buniform\\s+(?!.*\\b(?:sampler|image|texture|subpassInput)\\w*\\b)"
      + "[A-Za-z_]\\w*\\s+[A-Za-z_]\\w*\\s*(?:\\[[^\\]]*\\])?\\s*;");

    private final long[] modules;
    private final int[] stages;
    private final long descriptorSetLayout;
    private final long pipelineLayout;
    private final int pushConstantSize;
    private final List<SpirvReflect.Binding> bindings;
    private final Set<Integer> dynamicBindings;
    private final int pushConstantStages;
    private final Map<String, String> defines;
    private final String debugName;
    private boolean freed;

    VkShader(Builder<?> builder, CompiledStages compiled) {
        this.modules = compiled.modules;
        this.stages = compiled.stages;
        this.descriptorSetLayout = compiled.setLayout;
        this.pipelineLayout = compiled.pipelineLayout;
        this.pushConstantSize = compiled.pushConstantSize;
        this.bindings = compiled.bindings;
        this.dynamicBindings = compiled.dynamicBindings;
        this.pushConstantStages = compiled.pushConstantStages;
        this.defines = builder.defines;
        this.debugName = compiled.debugName;
    }

    /** dynamic offset を持つ binding (昇順)。{@code pDynamicOffsets} の順序はこれに従う。 */
    public List<Integer> dynamicBindingIndices() {
        return this.bindings.stream().map(SpirvReflect.Binding::binding)
            .filter(this.dynamicBindings::contains).sorted().toList();
    }

    public boolean isDynamic(int binding) { return this.dynamicBindings.contains(binding); }

    /** push constant を可視にするステージのビットマスク。 */
    public int pushConstantStages() { return this.pushConstantStages; }

    public long descriptorSetLayout() { return this.descriptorSetLayout; }
    public long pipelineLayout()      { return this.pipelineLayout; }
    public int pushConstantSize()     { return this.pushConstantSize; }
    public String debugName()         { return this.debugName; }

    /** set 0 の全バインディング。descriptor pool のサイズ決定などに使う。 */
    public List<SpirvReflect.Binding> bindings() { return Collections.unmodifiableList(this.bindings); }

    /** ビルダに渡された define。{@code AutoBindingShader} 相当の名前解決に使う。 */
    public Map<String, String> defines() { return Collections.unmodifiableMap(this.defines); }

    /** パイプライン生成用のステージ記述子を埋める。 */
    public VkPipelineShaderStageCreateInfo.Buffer stageInfos(MemoryStack stack) {
        var buf = VkPipelineShaderStageCreateInfo.calloc(this.modules.length, stack);
        for (int i = 0; i < this.modules.length; i++) {
            buf.get(i).sType$Default()
                .stage(this.stages[i])
                .module(this.modules[i])
                .pName(stack.UTF8("main"));
        }
        return buf;
    }

    public SpirvReflect.Binding bindingAt(int index) {
        for (var b : this.bindings) if (b.binding() == index) return b;
        return null;
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        var dev = VkContext.get().device;
        for (long m : this.modules) vkDestroyShaderModule(dev, m, null);
        vkDestroyPipelineLayout(dev, this.pipelineLayout, null);
        vkDestroyDescriptorSetLayout(dev, this.descriptorSetLayout, null);
    }

    public void assertNotFreed() {
        if (this.freed) throw new IllegalStateException("VkShader already freed: " + this.debugName);
    }

    // ------------------------------------------------------------------

    public static Builder<VkShader> make(IShaderProcessor... processors) {
        return new Builder<>(VkShader::new, chain(processors));
    }

    public static Builder<VkAutoBindingShader> makeAuto(IShaderProcessor... processors) {
        return new Builder<>(VkAutoBindingShader::new, chain(processors));
    }

    private static IShaderProcessor chain(IShaderProcessor[] processors) {
        IShaderProcessor applicator = (type, source) -> source;
        for (IShaderProcessor p : processors) {
            IShaderProcessor prev = applicator;
            applicator = (type, source) -> prev.process(type, p.process(type, source));
        }
        return applicator;
    }

    record CompiledStages(long[] modules, int[] stages, long setLayout, long pipelineLayout,
                          int pushConstantSize, List<SpirvReflect.Binding> bindings,
                          Set<Integer> dynamicBindings, int pushConstantStages, String debugName) {}

    interface IShaderObjectConstructor<J extends VkShader> {
        J make(Builder<J> builder, CompiledStages compiled);
    }

    /**
     * GL 側 {@code Shader.Builder} と同じ API 形状。
     * 呼び出し側の差分を抑えるため、メソッド名・引数・戻り値を揃えている。
     */
    public static class Builder<T extends VkShader> {
        final Map<String, String> defines = new HashMap<>();
        final Map<String, String> replacements = new LinkedHashMap<>();
        private final Map<ShaderType, String> sources = new LinkedHashMap<>();
        private final Set<Integer> dynamicBindings = new HashSet<>();
        private final IShaderProcessor processor;
        private final IShaderObjectConstructor<T> constructor;
        private String name = "unnamed";

        Builder(IShaderObjectConstructor<T> constructor, IShaderProcessor processor) {
            this.constructor = constructor;
            this.processor = processor;
        }

        @Override
        public Builder<T> clone() {
            var c = new Builder<>(this.constructor, this.processor);
            c.defines.putAll(this.defines);
            c.sources.putAll(this.sources);
            c.replacements.putAll(this.replacements);
            c.dynamicBindings.addAll(this.dynamicBindings);
            c.name = this.name;
            return c;
        }

        /**
         * この binding を dynamic offset 付き ({@code *_BUFFER_DYNAMIC}) として宣言する。
         *
         * <p><b>呼び出し側が宣言する必要がある理由</b>: SPIR-V 上では通常の SSBO/UBO と
         * まったく同じ表現になるため、リフレクションからは判別できない。
         *
         * <p>用途は {@code UploadStream} のようなリングバッファで、
         * 毎回異なるオフセットから bind する場合
         * (docs/phase3-descriptor-survey.md カテゴリ C)。
         */
        public Builder<T> dynamicBinding(int index) {
            this.dynamicBindings.add(index);
            return this;
        }

        public Builder<T> define(String name)                        { this.defines.put(name, ""); return this; }
        public Builder<T> define(String name, int value)             { this.defines.put(name, Integer.toString(value)); return this; }
        public Builder<T> define(String name, float value)           { this.defines.put(name, value + "f"); return this; }
        public Builder<T> define(String name, String value)          { this.defines.put(name, value); return this; }
        public Builder<T> defineIf(String n, boolean c)              { if (c) this.defines.put(n, ""); return this; }
        public Builder<T> defineIf(String n, boolean c, int v)       { if (c) this.defines.put(n, Integer.toString(v)); return this; }
        public Builder<T> replace(String value, String replacement)  { this.replacements.put(value, replacement); return this; }
        public Builder<T> apply(Consumer<Builder<T>> applyer)        { applyer.accept(this); return this; }
        public Builder<T> name(String name)                          { this.name = name; return this; }

        public Builder<T> add(ShaderType type, String id) {
            this.name = id;
            return this.addSource(type, VkShaderLoader.parse(id));
        }

        public Builder<T> addSource(ShaderType type, String source) {
            this.sources.put(type, this.processor.process(type, source));
            return this;
        }

        public T compile() {
            if (this.sources.isEmpty()) throw new IllegalStateException("no shader sources: " + this.name);

            String defs = this.defines.entrySet().stream()
                .map(e -> "#define " + e.getKey() + " " + e.getValue() + "\n")
                .collect(Collectors.joining());

            int n = this.sources.size();
            long[] modules = new long[n];
            int[] stages = new int[n];
            // binding -> (type, count, name) をステージ横断でマージする。
            // Vulkan の descriptor set layout はパイプライン全体で 1 つ、
            // binding ごとに stageFlags を OR で持つため。
            Map<Integer, SpirvReflect.Binding> merged = new TreeMap<>();
            Map<Integer, Integer> stageFlags = new HashMap<>();
            Map<Integer, ShaderType> originStage = new HashMap<>();
            int pushSize = 0;
            // push constant の可視ステージは「実際に宣言しているステージ」だけを立てる。
            // 全ステージに見せる案は採らない (バリデーションレイヤが正確な値を期待するため)。
            int pushStages = 0;

            var dev = VkContext.get().device;
            int i = 0;
            for (var entry : this.sources.entrySet()) {
                ShaderType glType = entry.getKey();
                VkShaderType type = VkShaderType.from(glType);
                String src = injectDefines(entry.getValue(), defs);
                for (var r : this.replacements.entrySet()) src = src.replace(r.getKey(), r.getValue());

                ByteBuffer spirv;
                try {
                    spirv = SpirvCompiler.compile(type, src, this.name + ":" + glType);
                } catch (RuntimeException e) {
                    // 既知の未移行パターンなら、shaderc の生エラーより先に
                    // 「何が未移行なのか」を説明する。
                    explainCompileFailure(type, src, this.name, glType, e);
                    throw e;
                }
                var refl = SpirvReflect.reflect(spirv);
                pushSize = Math.max(pushSize, refl.pushConstantSize());
                if (refl.pushConstantSize() > 0) pushStages |= type.stage;

                for (var b : refl.bindings()) {
                    if (b.set() != 0) {
                        throw new IllegalStateException(
                            "VkShader binding convention violated: " + this.name + " (" + glType + ") declares "
                          + b.name() + " in set " + b.set() + " at binding " + b.binding()
                          + ". All resources must live in set 0.");
                    }
                    var prev = merged.get(b.binding());
                    if (prev != null && prev.descriptorType() != b.descriptorType()) {
                        throw new IllegalStateException(
                            "Descriptor binding collision in shader '" + this.name + "' at set 0 binding "
                          + b.binding() + ":\n"
                          + "    " + prev.typeName() + "  '" + prev.name() + "'  (" + originStage.get(b.binding()) + ")\n"
                          + "    " + b.typeName()    + "  '" + b.name()    + "'  (" + glType + ")\n"
                          + "  GL treats UBO / SSBO / texture units as separate namespaces, Vulkan does not.\n"
                          + "  See docs/phase2-binding-audit.md");
                    }
                    if (prev == null) {
                        merged.put(b.binding(), b);
                        originStage.put(b.binding(), glType);
                    } else if (prev.count() != b.count()) {
                        throw new IllegalStateException("descriptor count mismatch at binding " + b.binding()
                            + " in " + this.name + ": " + prev.count() + " vs " + b.count());
                    }
                    stageFlags.merge(b.binding(), type.stage, (a, c) -> a | c);
                }

                modules[i] = createModule(dev, spirv);
                stages[i] = type.stage;
                i++;
            }

            if (pushSize > VkContext.get().maxPushConstantsSize) {
                throw new IllegalStateException("push constant block of " + pushSize
                    + " bytes exceeds device limit " + VkContext.get().maxPushConstantsSize
                    + " in shader " + this.name);
            }

            long setLayout;
            long pipeLayout;
            try (MemoryStack stack = stackPush()) {
                var lb = VkDescriptorSetLayoutBinding.calloc(merged.size(), stack);
                int k = 0;
                for (var b : merged.values()) {
                    lb.get(k++)
                        .binding(b.binding())
                        .descriptorType(toDynamic(b, this.dynamicBindings, this.name))
                        .descriptorCount(b.count())
                        .stageFlags(stageFlags.get(b.binding()));
                }
                var slci = VkDescriptorSetLayoutCreateInfo.calloc(stack).sType$Default().pBindings(lb);
                long[] p = new long[1];
                VkContext.check(vkCreateDescriptorSetLayout(dev, slci, null, p), "vkCreateDescriptorSetLayout");
                setLayout = p[0];

                var plci = VkPipelineLayoutCreateInfo.calloc(stack).sType$Default()
                    .pSetLayouts(stack.longs(setLayout));
                if (pushSize > 0) {
                    var pcr = VkPushConstantRange.calloc(1, stack)
                        .offset(0).size(pushSize)
                        .stageFlags(pushStages);
                    plci.pPushConstantRanges(pcr);
                }
                VkContext.check(vkCreatePipelineLayout(dev, plci, null, p), "vkCreatePipelineLayout");
                pipeLayout = p[0];
            }

            Logger.info("VkShader '" + this.name + "': " + merged.size() + " bindings, push="
                + pushSize + "B, stages=" + this.sources.keySet());

            return this.constructor.make(this,
                new CompiledStages(modules, stages, setLayout, pipeLayout, pushSize,
                    new ArrayList<>(merged.values()), Set.copyOf(this.dynamicBindings),
                    pushStages, this.name));
        }

        /** dynamic 宣言された binding の descriptor type を *_DYNAMIC に差し替える。 */
        private static int toDynamic(SpirvReflect.Binding b, Set<Integer> dynamic, String name) {
            if (!dynamic.contains(b.binding())) return b.descriptorType();
            return switch (b.descriptorType()) {
                case VK_DESCRIPTOR_TYPE_STORAGE_BUFFER -> VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC;
                case VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER -> VK_DESCRIPTOR_TYPE_UNIFORM_BUFFER_DYNAMIC;
                default -> throw new IllegalStateException("shader '" + name + "' declared binding "
                    + b.binding() + " ('" + b.name() + "') as dynamic, but it is a " + b.typeName()
                    + "; only uniform/storage buffers can have dynamic offsets");
            };
        }

        private static int orAll(int[] stages) {
            int r = 0;
            for (int s : stages) r |= s;
            return r;
        }

        private static String injectDefines(String src, String defs) {
            int nl = src.indexOf('\n');
            if (nl < 0) return defs + src;
            return src.substring(0, nl + 1) + defs + src.substring(nl + 1);
        }

        /**
         * shaderc のコンパイル失敗を、既知の未移行パターンに照らして説明し直す。
         *
         * <p><b>コンパイル前ではなく失敗後に走らせる理由</b>: ソースには
         * {@code #ifdef VULKAN} で分岐した <b>GL 用の分岐も文字列としては残っている</b>。
         * 前もって正規表現をかけると、実際にはプリプロセッサに落とされる
         * GL 側の {@code layout(location=N) uniform ...} を誤検出してしまう。
         * shaderc が通ったのなら、そのシェーダは定義上すでに正しい Vulkan GLSL である。
         *
         * <p>該当パターンが見つかれば置き換えの例外を投げ、
         * 見つからなければ何もしない (呼び出し側が元の例外を投げ直す)。
         */
        private static void explainCompileFailure(VkShaderType type, String src, String name,
                                                  ShaderType stage, RuntimeException cause) {
            // #ifdef 展開後のテキストを見る。生ソースには VULKAN 以外の分岐も残っており、
            // そのまま正規表現をかけると「実際にはコンパイルされない GL 分岐」を誤検出する。
            String expanded = SpirvCompiler.preprocess(type, src, name);
            if (expanded == null) return;   // プリプロセスすら通らない = 別の問題。元の例外に任せる
            Matcher m = DEFAULT_BLOCK_UNIFORM.matcher(expanded);
            List<String> hits = new ArrayList<>();
            while (m.find()) hits.add(m.group().trim());
            if (!hits.isEmpty()) {
                throw new IllegalStateException(
                    "Shader '" + name + "' (" + stage + ") still contains default-block uniforms, which Vulkan GLSL "
                  + "forbids. These must be migrated to push constants before this shader can be ported:\n"
                  + hits.stream().map(h -> "    " + h).collect(Collectors.joining("\n"))
                  + "\n  Migrate with the #ifdef VULKAN pattern, see docs/phase2-glsl-compat.md T-4."
                  + "\n  Tracking list: docs/phase2-pushconstant-todo.md", cause);
            }
            checkNoUnshimmedInstanceId(expanded, name, stage, cause);
        }

        /**
         * {@code gl_InstanceID} は互換プレリュードで置換していないため、残っていれば
         * shaderc が "undeclared identifier" で落ちる。原因が分かるように事前に弾く。
         *
         * <p>置換しない理由: GL の {@code gl_InstanceID} は baseInstance を含まないが、
         * Vulkan の {@code gl_InstanceIndex} は含む。機械的に置き換えると
         * baseInstance が 0 でない描画で静かに壊れる。
         * 特に {@code chunkoutline/outline.vsh} は {@code gl_InstanceID + gl_BaseInstance} と
         * 明示的に足しており、これは Vulkan の {@code gl_InstanceIndex} そのものなので、
         * 置換すると二重に加算されてしまう。
         */
        private static void checkNoUnshimmedInstanceId(String src, String name, ShaderType stage, RuntimeException cause) {
            if (!GL_INSTANCE_ID.matcher(src).find()) return;
            throw new IllegalStateException(
                "Shader '" + name + "' (" + stage + ") uses gl_InstanceID, which is deliberately NOT shimmed "
              + "for Vulkan.\n"
              + "  GL's gl_InstanceID excludes baseInstance; Vulkan's gl_InstanceIndex includes it, so a blind\n"
              + "  #define would silently corrupt any draw with a non-zero baseInstance.\n"
              + "  Port this shader by hand and confirm how baseInstance should be handled at each use site.\n"
              + "  Compat prelude contents: docs/phase2-glsl-compat.md", cause);
        }

        private static long createModule(VkDevice dev, ByteBuffer spirv) {
            try (MemoryStack stack = stackPush()) {
                var ci = VkShaderModuleCreateInfo.calloc(stack).sType$Default().pCode(spirv);
                long[] p = new long[1];
                VkContext.check(vkCreateShaderModule(dev, ci, null, p), "vkCreateShaderModule");
                return p[0];
            }
        }
    }
}
