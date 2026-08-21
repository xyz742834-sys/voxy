package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.shader.VkAutoBindingShader;
import me.cortex.voxy.client.core.vk.shader.VkDescriptorSetGroup;
import me.cortex.voxy.client.core.vk.shader.VkShader;
import me.cortex.voxy.client.core.vk.shader.VkShaderLoader;
import org.joml.Matrix4fc;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Phase 5c-4b — <b>階層トラバーサル</b>。どのノードを描くかを GPU が選ぶ。
 *
 * <h2>ディスパッチの回数は<b>構造で決まっている</b> [確認済 — GL 版と同じ]</h2>
 * CPU から {@link #MAX_ITERATIONS} 回積む。2 回目以降は個数を
 * 前の回が書いたバッファから取る ({@code vkCmdDispatchIndirect})。
 * <b>GPU 側にループは無い</b>ので、回数の上限は CPU のループが握っている。
 *
 * <p>⚠ <b>構造を変えるなら上限の根拠も変わる。</b> 移すときに変えない。
 *
 * <h2>⚠ キューの上限</h2>
 * {@code queue.glsl} の Vulkan 分岐が、シンク/ソースの実長で範囲外アクセスを塞ぎ、
 * <b>当たった回数を数える</b> [{@link #droppedNodePushes}]。
 * 黙って捨てると「描かれない」と「選ばれなかった」が区別できない [規約 18]。
 */
public final class VkTraversal {
    /** {@code WorldEngine.MAX_LOD_LAYER + 1} [確認済 — GL 版と同じ]。 */
    public static final int MAX_ITERATIONS = 5;
    public static final int LOCAL_SIZE_BITS = 5;
    public static final int LOCAL_SIZE = 1 << LOCAL_SIZE_BITS;

    // binding 番号は GL 版の採番と同じにしてある [HierarchicalOcclusionTraverser]
    private static final int HIZ = 0, SCENE_UNIFORM = 1, REQUEST_QUEUE = 2, RENDER_QUEUE = 3,
        NODE_DATA = 4, QUEUE_META = 6, QUEUE_SOURCE = 7, QUEUE_SINK = 8, RENDER_TRACKER = 9,
        TRAVERSAL_LIMITS = 10;

    /** ユニフォームのバイト数 [確認済 — traversal_dev.comp の SceneUniform]。 */
    public static final int UNIFORM_SIZE = 208;

    private final VkAutoBindingShader shader;
    private final long pipeline;
    private final VkDescriptorSetGroup sets;

    public final VkBuffer uniform, request, renderQueue, nodeData, queueMeta,
        topNodeIds, scratchA, scratchB, renderTracker, limits;
    /** 描画キューを外から借りているか。借り物は free しない。 */
    private final boolean borrowedRenderQueue;
    private final int maxRenderQueue, maxRequests;
    private int barriersEmitted;
    private boolean freed;

    /**
     * @param maxNodes      ノードバッファの容量
     * @param queueCapacity 作業キュー 1 本の容量 (ノード数)。
     *                      ⚠ <b>ここが溢れると捨てられる</b>。小さくすると上限の検査ができる
     * @param maxRenderQueue 描画キューの容量
     */
    public VkTraversal(VkTexture hiz, int maxNodes, int queueCapacity,
                       int maxRenderQueue, int maxRequests) {
        this(hiz, maxNodes, queueCapacity, maxRenderQueue, maxRequests, null);
    }

    /**
     * @param externalRenderQueue 描画キューを外から渡す。
     *                            ⚠ <b>{@code indirectLookup} と同じ形</b>
     *                            ({@code uint count; uint ids[]}) なので、
     *                            そのまま渡すと <b>cmdgen が直接読める</b>
     *                            [確認済 — {@code bindings.glsl} の
     *                            {@code IndirectSectionLookupBuffer}]。
     *                            {@code null} なら自前で作る
     */
    public VkTraversal(VkTexture hiz, int maxNodes, int queueCapacity,
                       int maxRenderQueue, int maxRequests, VkBuffer externalRenderQueue) {
        this.maxRenderQueue = maxRenderQueue;
        this.maxRequests = maxRequests;
        this.borrowedRenderQueue = externalRenderQueue != null;

        this.uniform      = new VkBuffer(UNIFORM_SIZE).name("traversalUniform");
        this.request      = new VkBuffer((long) maxRequests * 8 + 8).name("requestQueue");
        this.renderQueue  = externalRenderQueue != null ? externalRenderQueue
            : new VkBuffer((long) maxRenderQueue * 4 + 4).name("renderQueue");
        if (this.renderQueue.size() < (long) maxRenderQueue * 4 + 4) {
            throw new IllegalArgumentException("the render queue holds "
                + ((this.renderQueue.size() - 4) / 4) + " ids but " + maxRenderQueue
                + " were asked for");
        }
        this.nodeData     = new VkBuffer((long) maxNodes * VkNodeTree.NODE_SIZE).name("nodeData");
        this.queueMeta    = new VkBuffer(16L * MAX_ITERATIONS,
            VkBuffer.DEFAULT_USAGE | VK_BUFFER_USAGE_INDIRECT_BUFFER_BIT, true).name("queueMeta");
        this.topNodeIds   = new VkBuffer((long) queueCapacity * 4).name("topNodeIds");
        this.scratchA     = new VkBuffer((long) queueCapacity * 4).name("scratchA");
        this.scratchB     = new VkBuffer((long) queueCapacity * 4).name("scratchB");
        this.renderTracker = new VkBuffer((long) maxNodes * 4).name("renderTracker");
        this.limits       = new VkBuffer(16).name("traversalLimits");

        // ⚠ 深度規約は必須 — screenspace.glsl が REDUCTION / NEAR / DEPTH_SCALAR_COMPARE_EQUAL
        // を使う。忘れると **HiZ の遮蔽判定が裏返り、見えているノードを落とす**
        // (VkShader.Builder が弾くので、ここで忘れることはできない)
        this.shader = VkDepth.defines(
            VkShader.makeAuto(me.cortex.voxy.client.core.rendering.util.PrintfDebugUtil.PRINTF_processor)
                .name("vk-traversal"))
            .define("MAX_ITERATIONS", MAX_ITERATIONS)
            .define("LOCAL_SIZE_BITS", LOCAL_SIZE_BITS)
            .define("MAX_REQUEST_QUEUE_SIZE", maxRequests)
            .define("HIZ_BINDING", HIZ)
            .define("SCENE_UNIFORM_BINDING", SCENE_UNIFORM)
            .define("REQUEST_QUEUE_BINDING", REQUEST_QUEUE)
            .define("RENDER_QUEUE_BINDING", RENDER_QUEUE)
            .define("NODE_DATA_BINDING", NODE_DATA)
            .define("NODE_QUEUE_META_BINDING", QUEUE_META)
            .define("NODE_QUEUE_SOURCE_BINDING", QUEUE_SOURCE)
            .define("NODE_QUEUE_SINK_BINDING", QUEUE_SINK)
            .define("RENDER_TRACKER_BINDING", RENDER_TRACKER)
            .define("TRAVERSAL_LIMITS_BINDING", TRAVERSAL_LIMITS)
            .addSource(ShaderType.COMPUTE,
                VkShaderLoader.parse("voxy:lod/hierarchical/traversal_dev.comp"))
            .compile();

        // (source, sink) は 3 通りしかない。GL 版のフリップフロップと同じ
        //   variant 0: 入口   -> B
        //   variant 1: B      -> A     (奇数回)
        //   variant 2: A      -> B     (偶数回)
        this.sets = new VkDescriptorSetGroup(this.shader, 3);
        this.sets.shared(SCENE_UNIFORM, this.uniform)
            .shared(REQUEST_QUEUE, this.request)
            .shared(RENDER_QUEUE, this.renderQueue)
            .shared(NODE_DATA, this.nodeData)
            .shared(QUEUE_META, this.queueMeta)
            .shared(RENDER_TRACKER, this.renderTracker)
            .shared(TRAVERSAL_LIMITS, this.limits);
        this.sets.variant(0, QUEUE_SOURCE, this.topNodeIds).variant(0, QUEUE_SINK, this.scratchB);
        this.sets.variant(1, QUEUE_SOURCE, this.scratchB).variant(1, QUEUE_SINK, this.scratchA);
        this.sets.variant(2, QUEUE_SOURCE, this.scratchA).variant(2, QUEUE_SINK, this.scratchB);
        this.sets.sharedTexture(HIZ, hiz, VkSampler.nearestMipClamp());

        for (int v = 0; v < 3; v++) {
            var missing = this.sets.unbound(v);
            if (!missing.isEmpty()) {
                throw new IllegalStateException("traversal variant " + v
                    + " has unbound descriptors: " + missing);
            }
        }
        this.sets.update();

        try (MemoryStack stack = stackPush()) {
            var ci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                .stage(this.shader.stageInfos(stack).get(0))
                .layout(this.shader.pipelineLayout());
            long[] p = new long[1];
            VkContext.check(vkCreateComputePipelines(
                VkContext.get().device, VK_NULL_HANDLE, ci, null, p), "vkCreateComputePipelines");
            this.pipeline = p[0];
        }
    }

    /**
     * ユニフォームを書く [確認済 — {@code traversal_dev.comp} の {@code SceneUniform}]。
     *
     * <pre>
     *   0  mat4  MVP
     *  64  ivec3 camSecPos      76  uint  packedHizSize
     *  80  vec3  camSubSecPos   92  float minSSS
     *  96  Frustum (6 x vec4)
     * 192  uint  renderQueueMaxSize
     * 196  uint  frameId
     * 200  uint  requestQueueSize
     * 204  float renderDistance
     * </pre>
     *
     * @param minScreenSize 降下する閾値 (画面面積の比)。<b>小さいほど深く降りる</b>
     * @param renderDistance <b>2 乗した</b>距離。負なら無制限 [確認済 — シェーダが {@code <0} を見る]
     */
    public void writeUniform(Matrix4fc mvp, int[] camSection, float[] camSubPos,
                             int packedHizSize, float minScreenSize,
                             float[] frustumPlanes, int frameId, float renderDistance) {
        if (frustumPlanes.length != 24) {
            throw new IllegalArgumentException("need 6 planes of 4 floats, got "
                + frustumPlanes.length);
        }
        long p = this.uniform.addr();
        try (MemoryStack stack = stackPush()) {
            var fb = stack.mallocFloat(16);
            mvp.get(fb);
            for (int i = 0; i < 16; i++) MemoryUtil.memPutFloat(p + i * 4L, fb.get(i));
        }
        MemoryUtil.memPutInt(p + 64, camSection[0]);
        MemoryUtil.memPutInt(p + 68, camSection[1]);
        MemoryUtil.memPutInt(p + 72, camSection[2]);
        MemoryUtil.memPutInt(p + 76, packedHizSize);
        MemoryUtil.memPutFloat(p + 80, camSubPos[0]);
        MemoryUtil.memPutFloat(p + 84, camSubPos[1]);
        MemoryUtil.memPutFloat(p + 88, camSubPos[2]);
        MemoryUtil.memPutFloat(p + 92, minScreenSize);
        for (int i = 0; i < 24; i++) MemoryUtil.memPutFloat(p + 96 + i * 4L, frustumPlanes[i]);
        MemoryUtil.memPutInt(p + 192, this.maxRenderQueue);
        MemoryUtil.memPutInt(p + 196, frameId);
        MemoryUtil.memPutInt(p + 200, this.maxRequests);
        MemoryUtil.memPutFloat(p + 204, renderDistance);
    }

    /**
     * <b>フレームの開始状態にする。</b> 記録の前に呼ぶこと。
     *
     * <p>キューの個数、描画キューの先頭カウンタ、要求キュー、上限カウンタを 0 に戻す。
     * ⚠ <b>上限カウンタを毎フレーム 0 にする</b>ので、報告はそのフレームの値である。
     */
    public void reset(int topNodeCount) {
        long meta = this.queueMeta.addr();
        int firstDispatch = (topNodeCount + LOCAL_SIZE - 1) >> LOCAL_SIZE_BITS;
        MemoryUtil.memPutInt(meta,      firstDispatch);
        MemoryUtil.memPutInt(meta + 4,  1);
        MemoryUtil.memPutInt(meta + 8,  1);
        MemoryUtil.memPutInt(meta + 12, topNodeCount);
        for (int i = 1; i < MAX_ITERATIONS; i++) {
            MemoryUtil.memPutInt(meta + i * 16L,      0);
            MemoryUtil.memPutInt(meta + i * 16L + 4,  1);
            MemoryUtil.memPutInt(meta + i * 16L + 8,  1);
            MemoryUtil.memPutInt(meta + i * 16L + 12, 0);
        }
        MemoryUtil.memPutInt(this.renderQueue.addr(), 0);
        MemoryUtil.memPutInt(this.request.addr(), 0);
        MemoryUtil.memPutInt(this.request.addr() + 4, 0);
        for (int i = 0; i < 4; i++) MemoryUtil.memPutInt(this.limits.addr() + i * 4L, 0);
    }

    /**
     * トラバーサルを記録する。{@link #reset} を先に呼ぶこと。
     *
     * <p>⚠ 回数は {@link #MAX_ITERATIONS} 固定である。
     * <b>GPU 側の条件で回数が変わることはない</b> — 個数が 0 なら空回りするだけ。
     */
    public void record(VkCommandBuffer cmd, int topNodeCount) {
        if (this.freed) throw new IllegalStateException("VkTraversal was freed");
        this.barriersEmitted = 0;
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, this.pipeline);

        int firstDispatch = (topNodeCount + LOCAL_SIZE - 1) >> LOCAL_SIZE_BITS;
        if (firstDispatch > 0) {
            this.sets.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, 0);
            this.shader.pushUInt(0, 0);
            this.shader.flushPushConstants(cmd);
            vkCmdDispatch(cmd, firstDispatch, 1, 1);
        }

        for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
            this.barrier(cmd);
            this.sets.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, (iter & 1) == 1 ? 1 : 2);
            this.shader.pushUInt(0, iter);
            this.shader.flushPushConstants(cmd);
            vkCmdDispatchIndirect(cmd, this.queueMeta.handle, iter * 16L);
        }
        this.barrier(cmd);
    }

    /**
     * 直前の {@link #record} が積んだバリアの数。
     *
     * <h2>⚠ これは「バリアが正しい」ことの証拠ではない</h2>
     * この環境では<b>同期バリデーションが機能しない</b>ので、
     * バリアの範囲が足りているかは検証できない [未検証]。
     * 小さな仕事量では GPU が事実上直列に走るため、
     * <b>バリアを外しても結果が変わらない</b> (実際に変異で確かめた)。
     *
     * <p>ここで言えるのは<b>「積み忘れたら気づく」</b>ことだけである。
     * 消える種類の事故は捕まえられるが、<b>足りない種類の事故は捕まえられない</b>。
     */
    public int barriersEmitted() { return this.barriersEmitted; }

    /**
     * ⚠ 次の回は<b>前の回が書いた個数</b>を indirect で読む。
     * 書き込みが「間接ディスパッチの読み出し」から見えるようにしなければ、
     * <b>1 回前の個数で走る</b> — 落ちないし、描画数が少し違うだけに見える。
     */
    private void barrier(VkCommandBuffer cmd) {
        this.barriersEmitted++;
        // 書いたのはキューの中身と個数の両方。個数は間接ディスパッチが読み、
        // 中身は次の回のシェーダが読む。**両方を覆う**
        VkBarriers.computeToIndirect(cmd);
    }

    // ---------------- 結果の読み出し ----------------

    /** 描画キューに入ったメッシュ id。 */
    public int[] renderedMeshes() {
        int n = Math.min(MemoryUtil.memGetInt(this.renderQueue.addr()), this.maxRenderQueue);
        var out = new int[Math.max(0, n)];
        for (int i = 0; i < out.length; i++) {
            out[i] = MemoryUtil.memGetInt(this.renderQueue.addr() + 4 + (long) i * 4);
        }
        return out;
    }

    /** シンクキューが満杯で捨てたノード数。<b>0 でなければキューが小さい</b>。 */
    public int droppedNodePushes() { return MemoryUtil.memGetInt(this.limits.addr()); }

    /** ソースキューの外を指した読み出し。 */
    public int droppedNodeReads() { return MemoryUtil.memGetInt(this.limits.addr() + 4); }

    /** 要求キューに入った数 (メッシュを持たないノード)。 */
    public int requestCount() { return MemoryUtil.memGetInt(this.request.addr()); }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        vkDestroyPipeline(VkContext.get().device, this.pipeline, null);
        this.sets.free();
        this.shader.free();
        for (var b : new VkBuffer[]{this.uniform, this.request, this.nodeData,
                this.queueMeta, this.topNodeIds, this.scratchA, this.scratchB,
                this.renderTracker, this.limits}) {
            b.free();
        }
        // ⚠ 借り物は解放しない。二重解放になる
        if (!this.borrowedRenderQueue) this.renderQueue.free();
    }
}
