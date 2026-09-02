package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK13.*;

/**
 * Stage 4b: <b>遮蔽カリング。</b> GL 側 {@code buildDrawCalls} の cull ラスタパスに対応する。
 *
 * <p>セクションごとに AABB の箱を深度テスト付きで描き、
 * <b>1 フラグメントでも通ったセクション</b>に {@code visibilityData[sid] = frameId} を書く。
 * 深度は<b>直前の不透明パスが書いたもの</b>で、これが「すでに描かれた手前のものに
 * 隠れているか」の判定になる。
 *
 * <h2>⚠ cull の正しさは「同じ絵が出ること」ではない</h2>
 * cull を入れると可視セクションの集合が変わるので、
 * Stage 2b との差分ゼロを成功条件にはできない。判定基準は 2 方向:
 * <ol>
 *   <li><b>見えているものを落としていない</b> — cull あり/なしで<b>絵が変わらない</b></li>
 *   <li><b>見えないものは落ちている</b> — 遮蔽されたセクションが実際に不可視になる</li>
 * </ol>
 * 1 だけだと「何もカリングしていない」が通り、2 だけだと過剰カリングが通る。
 * 両方要る (docs/phase4-stage4-completion.md)。
 *
 * <h2>深度書き込みをしない</h2>
 * 箱は判定のためだけに描くので、深度にもカラーにも書かない。
 * カラーアタッチメント自体を付けない ({@code VkGraphicsPipeline.Builder.depthOnly})。
 */
public class VkCullPass {
    /** 立方体の頂点数 (8) / インデックス数 (6 面 x 2 三角形 x 3)。 */
    public static final int CUBE_VERTEX_COUNT = 8;
    public static final int CUBE_INDEX_COUNT = 6 * 2 * 3;

    /**
     * GL 側 {@code SharedIndexBuffer.generateCubeIndexBuffer} と同じ並び [確認済]。
     * GL は {@code GL_UNSIGNED_BYTE} で引くが、<b>Vulkan コアに 8bit インデックスは無い</b>
     * ({@code VK_KHR_index_type_uint8} が要る) ので 32bit にしてある。
     * 値は 0..7 なので幅を広げても意味は変わらない。
     */
    private static final int[] CUBE_INDICES = {
        0, 1, 2, 3, 2, 1,      // bottom
        6, 5, 4, 5, 6, 7,      // top
        0, 4, 1, 5, 1, 4,      // north
        3, 6, 2, 6, 3, 7,      // south
        2, 4, 0, 4, 2, 6,      // west
        1, 5, 3, 7, 3, 5,      // east
    };

    private static final Map<Integer, VkBuffer> CUBE_INDEX_CACHE = new HashMap<>();

    private static VkBuffer cubeIndexBuffer() {
        return CUBE_INDEX_CACHE.computeIfAbsent(0, k -> {
            var b = new VkBuffer(CUBE_INDICES.length * 4L, false).name("cubeIndices");
            for (int i = 0; i < CUBE_INDICES.length; i++) {
                MemoryUtil.memPutInt(b.addr() + i * 4L, CUBE_INDICES[i]);
            }
            return b;
        });
    }

    /** デバイス破棄前に呼ぶこと。 */
    public static void shutdown() {
        CUBE_INDEX_CACHE.values().forEach(VkBuffer::free);
        CUBE_INDEX_CACHE.clear();
    }

    private final VkTerrainResources res;
    private final VkTerrainRenderer.Barriers barriers;
    private final VkAutoBindingShader shader;
    private final VkGraphicsPipeline pipeline;
    private boolean freed;

    public VkCullPass(VkTerrainResources res, VkTerrainRenderer.Barriers barriers) {
        this.res = res;
        this.barriers = barriers;

        // 深度の約束事は地形パスと揃えること。ずれると CLOSER_SIGN の符号が逆になり
        // 「手前に寄せる」つもりの補正が奥へ押しやる
        this.shader = VkDepth.defines(VkShader.makeAuto().name("vk-cull-raster"))
            .addSource(ShaderType.VERTEX, VkShaderLoader.parse("voxy:lod/vk/cull_raster.vert"))
            .addSource(ShaderType.FRAGMENT, VkShaderLoader.parse("voxy:lod/vk/cull_raster.frag"))
            .compile();

        this.pipeline = VkGraphicsPipeline.builder(this.shader)
            .depthOnly()
            .cullMode(VK_CULL_MODE_NONE)
            // 深度テストはする。**書かない** — 判定のためだけに描く箱なので、
            // 書くと後続の描画やカリングが誤った深度を見ることになる
            .depthTest(true).depthWrite(false)
            .depthCompare(VkDepth.COMPARE_OP)
            .build();

        this.shader
            .ubo(0, this.res.uniform, 0, VkSceneUniform.SIZE)
            .ssbo(1, this.res.sectionMetadata)
            .ssbo(2, this.res.visibility)
            .ssbo(3, this.res.indirectLookup);
        var missing = this.shader.unboundBindings();
        if (!missing.isEmpty()) {
            throw new IllegalStateException("cull shader has unbound descriptors: " + missing);
        }
    }

    /**
     * <b>prep が書いた間接描画コマンド</b>でカリングを記録する (Phase 5c-5b2)。
     *
     * <h2>⚠ なぜホストの数を使わないのか</h2>
     * 階層トラバーサルではセクション数を<b>GPU が決める</b>ので、ホストは
     * <b>前フレームの数しか知らない</b>。それを渡すと:
     * <ul>
     *   <li>今フレームのほうが多い → <b>末尾のセクションが可視の印を貰えない</b>
     *       → cmdgen が 0 quad 扱いにする → <b>点滅する</b></li>
     *   <li>今フレームのほうが少ない → 範囲外の古い id に印を書く
     *       (cmdgen は先頭 {@code sectionCount} 件しか見ないので<b>害は無い</b>)</li>
     * </ul>
     * 片方が「消える」側なので、<b>正確な数が要る</b>。
     * GL 版も同じ理由で prep が {@code cullDrawIndirectCommand} を書いている。
     *
     * <p>⚠ 呼ぶ場所は <b>prep の後、cmdgen の前</b>である。
     *
     * @param indirect {@code VkTerrainResources.cullDraw} (5 uint)
     */
    public void recordIndirect(VkCommandBuffer cmd, VkRenderTarget target, VkBuffer indirect) {
        this.assertNotFreed();
        this.beforeCull(cmd);
        target.beginRenderingDepthOnly(cmd);
        this.pipeline.bind(cmd);
        this.shader.bind(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS);
        vkCmdBindIndexBuffer(cmd, cubeIndexBuffer().handle, 0, VK_INDEX_TYPE_UINT32);
        // ⚠ instanceCount が 0 なら仕様上の no-op。ホスト側のガードは要らない
        vkCmdDrawIndexedIndirect(cmd, indirect.handle, 0, 1,
            SyntheticTerrain.DRAW_COMMAND_SIZE);
        target.endRendering(cmd);
        this.afterCull(cmd);
    }

    /**
     * カリングを記録する。<b>不透明パスの直後、テーブル生成の直前</b>に呼ぶこと。
     *
     * <p>GL 側と同じく間接描画で発行する。{@code baseInstance = 0} である必要がある —
     * 頂点シェーダが {@code gl_InstanceIndex} をそのまま {@code indirectLookup} の
     * 添字に使うため (docs/phase2-glsl-compat.md 3.1 の条件)。
     *
     * @param sectionCount {@code indirectLookup} に入っているセクション数
     */
    public void record(VkCommandBuffer cmd, VkRenderTarget target, int sectionCount) {
        this.assertNotFreed();
        if (sectionCount <= 0) return;

        this.beforeCull(cmd);

        target.beginRenderingDepthOnly(cmd);
        this.pipeline.bind(cmd);
        this.shader.bind(cmd, VK_PIPELINE_BIND_POINT_GRAPHICS);
        vkCmdBindIndexBuffer(cmd, cubeIndexBuffer().handle, 0, VK_INDEX_TYPE_UINT32);
        // instanceCount = セクション数。firstInstance は 0 でなければならない
        vkCmdDrawIndexed(cmd, CUBE_INDEX_COUNT, sectionCount, 0, 0, 0);
        target.endRendering(cmd);

        this.afterCull(cmd);
    }

    /**
     * 不透明パスの深度書き込み → カリングの深度テスト、および
     * 前フレームの {@code visibilityData} 読み (cmdgen) → 今フレームの書き込みの WAR。
     */
    private void beforeCull(VkCommandBuffer cmd) {
        if (this.barriers == VkTerrainRenderer.Barriers.CONSERVATIVE) {
            VkBarriers.conservative(cmd, "stage4b: opaque depth -> cull");
            return;
        }
        // 深度のレイアウト遷移は beginRenderingDepthOnly が持つ。
        // ここで断つのは visibilityData への WAR (前フレームの cmdgen が読んだ)
        VkBarriers.memoryBarrier(cmd,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT | VK_PIPELINE_STAGE_2_HOST_BIT,
            VK_ACCESS_2_HOST_WRITE_BIT,
            VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    /**
     * カリングが書いた {@code visibilityData} を cmdgen が読む (RAW)。
     *
     * <p><b>書き手はフラグメントシェーダである。</b>
     * {@code phase4-buffer-hazards.md} §4 が「P1 では src が誤り」と指摘している箇所で、
     * {@code computeToCompute} を使うと src が compute になってしまい合わない。
     */
    private void afterCull(VkCommandBuffer cmd) {
        if (this.barriers == VkTerrainRenderer.Barriers.CONSERVATIVE) {
            VkBarriers.conservative(cmd, "stage4b: cull -> cmdgen");
            return;
        }
        VkBarriers.memoryBarrier(cmd,
            VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }

    private void assertNotFreed() {
        if (this.freed) throw new IllegalStateException("VkCullPass already freed");
    }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        this.pipeline.free();
        this.shader.free();
    }
}
