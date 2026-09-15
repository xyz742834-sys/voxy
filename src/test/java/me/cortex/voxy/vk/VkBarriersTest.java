package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.gl.shader.ShaderType;
import me.cortex.voxy.client.core.vk.*;
import me.cortex.voxy.client.core.vk.shader.*;
import org.junit.jupiter.api.*;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkComputePipelineCreateInfo;

import static org.junit.jupiter.api.Assertions.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * P1 / P2 ヘルパの検証。
 *
 * <p><b>本題は「バリアを外すと同期バリデーションが実際に検出するか」</b>。
 * これが確認できて初めて、バリアを入れたときの「指摘ゼロ」が意味を持つ。
 * 検出されないなら、それは「正しい」のではなく「見えていない」だけである。
 *
 * <h2>⚠⚠ Phase 6 — 「機能していない」の中身を分けた [規約 22]</h2>
 * Phase 3 は「この環境で同期バリデーションは一切機能しない」と結論していたが、
 * <b>それは 2 つの別の問題を 1 つに見ていた</b>ことが分かった。
 *
 * <h3>1. 見つけて直した: メッセージの分類バグ [確認済]</h3>
 * {@code VkContext} はメッセージ本文 ({@code pMessageString}) にリテラル
 * {@code "SYNC-HAZARD"} が含まれるかで判定していたが、<b>そのリテラルは本文ではなく
 * 構造化フィールド ({@code pMessageIdName}) にしか無い</b>
 * ({@code "SYNC-HAZARD-WRITE-AFTER-WRITE"} のように)。
 * 本文の言い回し ({@code "WRITE_AFTER_WRITE hazard detected"}) は VVL のバージョンで
 * 変わりうるので、本文への部分一致は最初から不安定な選択だった。
 * {@code createDebugMessenger} で ID 名を本文の前に埋め込むよう直した。
 *
 * <p>⚠ <b>これは MoltenVK 固有の制約ではなかった。</b> Mesa の Lavapipe
 * (ソフトウェアラスタライザの Vulkan ICD、{@code brew install mesa} で macOS にも入る)
 * 上で同じバリデーションレイヤを走らせても<b>同一の症状</b>だった —
 * ICD を変えても直らない = レイヤ側 (このプロジェクトの読み方) の問題だと確定できた。
 *
 * <h3>2. 直せなかった: descriptor 経由の SSBO 書き込みは追跡されない [確認済]</h3>
 * {@link #missingBarrierIsDetected()} (このシェーダのように<b>ふつうの
 * {@code VkDescriptorSet} で束縛した SSBO</b>への書き込み) は、
 * 分類バグを直した<b>後も</b>メッセージが 1 件も来ない ({@code validation messages = 0})。
 * GPU-Assisted Validation を追加で有効にしても変わらなかった [確認済]。
 * Khronos の公式ドキュメントもこれを既知の制約として認めている
 * (SyncVal は "does not support precise tracking of descriptors accessed by
 * the shader"; GPU-AV との統合が要るとしつつ、実際に統合しても本ケースは変わらなかった)。
 *
 * <p><b>この 2 つ目は Lavapipe でも直らない</b> — ICD ではなく
 * バリデーションレイヤ自体の設計上の穴だからである。
 *
 * <h3>実務上の帰結</h3>
 * <ul>
 *   <li>{@link #plainBufferHazardIsNowDetected()} が示す<b>非 descriptor のバッファ/画像
 *       ハザード</b> (コピー・fill・blit・レイアウト遷移) は<b>今は実際に検出される</b>。
 *       {@code VkTexture} のレイアウト遷移バリアはこの区分に入るので、信頼度が上がった</li>
 *   <li>{@link #computeToComputeSilencesTheHazard()} 等の「指摘ゼロ」は、
 *       <b>descriptor 経由の SSBO に関する限り今も弱い証拠のまま</b>である —
 *       直っていないのではなく、<b>この区分では検出自体ができない</b>ため</li>
 * </ul>
 */
public class VkBarriersTest {
    /** SSBO を 1 つ読み書きするだけの最小 compute シェーダ。 */
    private static final String SRC = """
        #version 460
        layout(local_size_x = 64) in;
        layout(binding = 0, std430) buffer Data { uint values[]; };
        void main() {
            uint i = gl_GlobalInvocationID.x;
            if (i < 64u) values[i] = values[i] + 1u;
        }
        """;

    private static VkAutoBindingShader shader;
    private static long pipeline;
    private static VkBuffer data;

    @BeforeAll
    static void setup() {
        VulkanTestSupport.requireVulkan();
        VkFrameTracker.init();
        shader = VkShader.makeAuto().name("barrier-test").addSource(ShaderType.COMPUTE, SRC).compile();
        data = new VkBuffer(64 * 4);
        shader.ssbo(0, data);

        try (MemoryStack stack = stackPush()) {
            var ci = VkComputePipelineCreateInfo.calloc(1, stack).sType$Default()
                .stage(shader.stageInfos(stack).get(0))
                .layout(shader.pipelineLayout());
            long[] p = new long[1];
            VkContext.check(vkCreateComputePipelines(VkContext.get().device, VK_NULL_HANDLE, ci, null, p),
                "vkCreateComputePipelines");
            pipeline = p[0];
        }
    }

    @AfterAll
    static void teardown() {
        if (VkFrameTracker.isRecordingFrame()) VkFrameTracker.get().endFrame();
        VkFrameTracker.get().waitIdle();
        if (pipeline != 0) vkDestroyPipeline(VkContext.get().device, pipeline, null);
        if (shader != null) shader.free();
        if (data != null) data.free();
        VkFrameTracker.shutdown();
    }

    @BeforeEach
    void clear() { VkContext.clearValidationMessages(); }

    private void twoDispatches(VkCommandBuffer cmd, boolean withBarrier) {
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        shader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        vkCmdDispatch(cmd, 1, 1, 1);
        if (withBarrier) VkBarriers.computeToCompute(cmd);
        vkCmdDispatch(cmd, 1, 1, 1);
    }

    /**
     * <b>ネガティブ対照</b>: バリア無しで同じ SSBO を read-modify-write する 2 つの
     * ディスパッチを並べると、同期バリデーションが WAW/RAW ハザードを報告すること。
     *
     * <p>⚠⚠ <b>ここは descriptor 経由の SSBO 書き込みである。</b> クラス javadoc の
     * 「2. 直せなかった」区分にあたり、<b>メッセージ分類バグを直した後も</b>
     * 何 1 つ報告が来ない。{@link #plainBufferHazardIsNowDetected()} との対比が要 —
     * あちらは同じレイヤ・同じ環境で<b>実際に検出される</b>ので、
     * 「レイヤが死んでいる」ではなく「この 1 区分だけ届かない」と言える [規約 22]。
     */
    @Test
    void missingBarrierIsDetected() {
        Assumptions.assumeTrue(VkContext.get().syncValidationEnabled,
            "sync validation is off; run with -PvkLibname=... -PvkValidation=true");

        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        this.twoDispatches(cmd, false);
        t.endFrame();
        t.waitForFrame();

        var hazards = VkContext.syncHazards();
        var all = VkContext.validationMessages();
        System.out.println("[vk] validation messages = " + all.size() + ", SYNC-HAZARD = " + hazards.size());
        hazards.stream().findFirst().ifPresent(h -> System.out.println("  " + h));

        // ここが空になるのは「バリアが正しい」からではない。メッセージ分類バグは直したので
        // (VkContext.createDebugMessenger が pMessageIdName を埋め込むようになった)、
        // これは「レイヤが descriptor 経由のバッファ書き込みを追跡できない」という
        // Khronos 自身が認めている制約である [確認済 — GPU-AV を足しても変わらず、
        // Lavapipe に ICD を替えても変わらなかった]。事実として記録し、環境の制約として中断する。
        Assumptions.abort(
            "synchronization validation produced no message at all (not just no SYNC-HAZARD) for a "
          + "deliberately unsynchronised read-modify-write pair through a descriptor-bound SSBO. "
          + "This is NOT the message-classification bug (fixed; see plainBufferHazardIsNowDetected) "
          + "and NOT ICD-specific (same result on MoltenVK and on Lavapipe via -PvkIcd=...). "
          + "GPU-Assisted Validation enabled alongside sync validation did not change it either. "
          + "Treat 'zero findings' for descriptor-bound buffer access as WEAK evidence for barrier "
          + "correctness on this stack. See docs/vulkan-validation-setup.md 7 and "
          + "docs/phase6-sync-validation.md.");
    }

    /**
     * <b>ポジティブ対照</b>: descriptor を介さない、素のバッファ間ハザードは
     * <b>実際に検出される</b>こと (Phase 6)。
     *
     * <p>{@code vkCmdFillBuffer} を同じ範囲へバリア無しで 2 回並べるだけの、
     * shader も descriptor も要らない最小のハザード。
     * これが {@link #missingBarrierIsDetected()} と<b>同じ環境・同じレイヤ</b>で
     * 検出されることが、「レイヤは生きている。区分が descriptor 経由に限られる」
     * という主張の根拠になる。
     *
     * <p>⚠ このケースは {@code VkTexture} のレイアウト遷移バリア (docs/phase4-buffer-hazards.md
     * が主に扱う対象) と同じ<b>非 descriptor</b>の区分に入るので、
     * <b>そちらの検証にはこのレイヤが実際に使える</b>ことも同時に示している。
     */
    @Test
    void plainBufferHazardIsNowDetected() {
        Assumptions.assumeTrue(VkContext.get().syncValidationEnabled, "sync validation is off");

        var probe = new VkBuffer(256);
        try {
            var t = VkFrameTracker.get();
            var cmd = t.beginFrame();
            vkCmdFillBuffer(cmd, probe.handle, 0, 256, 1);
            // ⚠ バリア無し。これが本題
            vkCmdFillBuffer(cmd, probe.handle, 0, 256, 2);
            t.endFrame();
            t.waitForFrame();

            var hazards = VkContext.syncHazards();
            System.out.println("[vk] plain buffer hazards = " + hazards.size());
            hazards.forEach(h -> System.out.println("  " + h));
            assertFalse(hazards.isEmpty(),
                "a deliberately unsynchronised pair of vkCmdFillBuffer calls on the same range "
              + "must be reported; if this is empty too, the message-classification fix regressed "
              + "or the layer itself stopped working (a much bigger problem than the descriptor gap)");
        } finally {
            probe.free();
        }
    }

    /** <b>P1</b>: {@code computeToCompute} を入れるとハザードが消えること。 */
    @Test
    void computeToComputeSilencesTheHazard() {
        Assumptions.assumeTrue(VkContext.get().syncValidationEnabled, "sync validation is off");

        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        this.twoDispatches(cmd, true);
        t.endFrame();
        t.waitForFrame();

        var hazards = VkContext.syncHazards();
        assertTrue(hazards.isEmpty(), () -> "computeToCompute should cover this dependency, got: " + hazards);
    }

    /** <b>P2</b>: 間接ディスパッチ向けのバリアが記録でき、指摘が出ないこと。 */
    @Test
    void computeToIndirectRecordsCleanly() {
        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        shader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        vkCmdDispatch(cmd, 1, 1, 1);
        VkBarriers.computeToIndirect(cmd);
        t.endFrame();
        t.waitForFrame();

        assertTrue(VkContext.syncHazards().isEmpty(),
            () -> "unexpected hazards: " + VkContext.syncHazards());
    }

    /** 保守的バリアでもハザードは消える (D 区分の暫定措置が機能すること)。 */
    @Test
    void conservativeBarrierAlsoCovers() {
        Assumptions.assumeTrue(VkContext.get().syncValidationEnabled, "sync validation is off");

        var t = VkFrameTracker.get();
        var cmd = t.beginFrame();
        vkCmdBindPipeline(cmd, VK_PIPELINE_BIND_POINT_COMPUTE, pipeline);
        shader.bind(cmd, VK_PIPELINE_BIND_POINT_COMPUTE);
        vkCmdDispatch(cmd, 1, 1, 1);
        VkBarriers.conservative(cmd, "test: stand-in for an untranslated site");
        vkCmdDispatch(cmd, 1, 1, 1);
        t.endFrame();
        t.waitForFrame();

        assertTrue(VkContext.syncHazards().isEmpty(),
            () -> "conservative barrier must cover everything, got: " + VkContext.syncHazards());
    }
}
