package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.gl.Capabilities;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>GL コンテキストが無い環境で {@link Capabilities} を読んでも落ちないこと。</b>
 *
 * <p>MC 26.2 は Vulkan バックエンドを持ち、{@code options.txt} の
 * {@code preferredGraphicsBackend:"vulkan"} で選べる。そのとき GL コンテキストは
 * 存在しないが、{@code Capabilities} の静的初期化が {@code GL.getCapabilities()} を
 * 無条件に呼んでいたため <b>Minecraft ごと起動時にクラッシュしていた</b>
 * [確認済 — docs/phase5c-mc-vulkan-survey.md §5]。
 *
 * <pre>
 * java.lang.ExceptionInInitializerError
 *   at me.cortex.voxy.client.VoxyClient.initVoxyClient
 * Caused by: java.lang.IllegalStateException: No GLCapabilities instance set for the current thread.
 *   at me.cortex.voxy.client.core.gl.Capabilities.&lt;clinit&gt;
 * </pre>
 *
 * <h2>このテストが成立する理由</h2>
 * <b>Gradle の test worker には GL コンテキストが無い。</b>
 * つまりこのテストは<b>再現条件そのもの</b>で走っており、
 * 環境を作り込む必要がない。修正前のコードならクラスのロードで落ちる。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ul>
 *   <li><b>クラスがロードされずに通る</b> → {@code INSTANCE} を実際に読み、
 *       非 null を要求する (読めば clinit は必ず走る)</li>
 *   <li><b>「落ちない」だけで、機能が有効なまま残る</b> →
 *       Voxy が<b>自己無効化する側に倒れること</b>まで要求する。
 *       中途半端に有効だと、後で GL を呼んで落ちる</li>
 * </ul>
 */
public class CapabilitiesWithoutGlTest {

    @Test
    void loadingCapabilitiesWithoutAGlContextDoesNotThrow() {
        assertDoesNotThrow(() -> {
            assertNotNull(Capabilities.INSTANCE, "INSTANCE must exist even without GL");
        });
        assertFalse(Capabilities.INSTANCE.glAvailable,
            "there is no GL context in a Gradle test worker, so glAvailable must be false; "
                + "if this is true the test is not exercising the no-GL path at all");
    }

    /**
     * <b>GL が無いときは Voxy が自己無効化する側に倒れること。</b>
     *
     * <p>{@code VoxyClient.initVoxyClient} の {@code glSupported} 判定に相当する。
     * ここが true になってしまうと、この後 GL を呼ぶ経路に進んで別の場所で落ちる。
     */
    @Test
    void withoutGlTheModDisablesItself() {
        var c = Capabilities.INSTANCE;
        boolean glSupported = c.compute && c.indirectParameters && !c.hasBrokenDepthSampler;
        assertFalse(glSupported,
            "without a GL context Voxy must report itself unsupported, not proceed into GL calls");
    }

    /**
     * <b>⚠ GL コンテキストが無いときは Vulkan バックエンドも選んではならない。</b>
     *
     * <p>Vulkan 経路も<b>合成に GL を使う</b>。MC の色/深度テクスチャは
     * Sodium 経由で <b>GL のテクスチャ ID</b> として受け取っており
     * [確認済 — {@code MixinDefaultChunkRenderer} が {@code GlTextureView} にキャスト]、
     * MC が Vulkan バックエンドなら {@code VulkanGpuTextureView} が来て
     * <b>描画フレームで {@code ClassCastException} になる</b>。
     *
     * <p><b>実際に踏んだ。</b> 5c-1a でバックエンド選択を入れたとき、
     * 「GL が足りない → Vulkan を使う」だけを見ていたため、
     * <b>GL が存在しない場合まで Vulkan 経路を選んでしまった</b>
     * [docs/phase5c1b-completion.md §11]。
     *
     * <p>この選択条件は {@code VoxyClient.initVoxyClient} にあるが、
     * そこは Minecraft を起動しないと通らない。ここでは<b>判定に使う入力</b>
     * ({@code glAvailable}) が「GL 無し」を正しく表していることを固定する。
     */
    @Test
    void withoutGlTheVulkanBackendIsAlsoRefused() {
        assertFalse(Capabilities.INSTANCE.glAvailable,
            "no GL context here, so the Vulkan backend must be refused too: "
                + "the composite path needs GL");
    }

    /** ベンダ判定も含め、GL 由来の情報は全て「不明」側に倒れていること。 */
    @Test
    void everyGlDerivedFlagIsOff() {
        var c = Capabilities.INSTANCE;
        assertAll(
            () -> assertFalse(c.compute, "compute"),
            () -> assertFalse(c.indirectParameters, "indirectParameters"),
            () -> assertFalse(c.subgroup, "subgroup"),
            () -> assertFalse(c.INT64_t, "INT64_t"),
            () -> assertFalse(c.sparseBuffer, "sparseBuffer"),
            () -> assertFalse(c.canQueryGpuMemory, "canQueryGpuMemory"),
            () -> assertFalse(c.repFragTest, "repFragTest"),
            () -> assertFalse(c.meshShaders, "meshShaders"),
            () -> assertFalse(c.nvBarryCoords, "nvBarryCoords"),
            () -> assertFalse(c.isMesa, "isMesa"),
            () -> assertFalse(c.isIntel, "isIntel"),
            () -> assertFalse(c.isNvidia, "isNvidia"),
            () -> assertFalse(c.isAmd, "isAmd"),
            () -> assertFalse(c.hasBrokenDepthSampler, "hasBrokenDepthSampler")
        );
    }

    /**
     * {@code getFreeDedicatedGpuMemory()} は <b>投げる</b>のが正しい振る舞いであること。
     * 呼び出し側 7 箇所は全て {@code canQueryGpuMemory} でガードされている
     * [確認済 — docs/phase5-proposal.md §3.2]。
     * 黙って 0 を返すと「メモリが無い」と誤解される。
     */
    @Test
    void queryingGpuMemoryStillThrowsWhenUnavailable() {
        assertThrows(IllegalStateException.class,
            () -> Capabilities.INSTANCE.getFreeDedicatedGpuMemory());
    }
}
