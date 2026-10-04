package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkDependencyInfo;
import org.lwjgl.vulkan.VkMemoryBarrier2;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK13.*;

/**
 * {@code glMemoryBarrier} の翻訳先。
 *
 * <h2>なぜ専用ヘルパなのか</h2>
 * 全 33 箇所のうち <b>22 箇所が 2 パターンに集中している</b>
 * [確認済 — docs/phase3-barrier-survey.md]:
 * <ul>
 *   <li><b>P1</b> {@code GL_SHADER_STORAGE_BARRIER_BIT} のみ — 13 箇所</li>
 *   <li><b>P2</b> {@code GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT} — 9 箇所</li>
 * </ul>
 * 汎用のバリア API に全部流すより、<b>呼び出し側を読んだときに意図が分かる</b>ほうがよい。
 *
 * <h2>synchronization2 を使う理由</h2>
 * {@code vkCmdPipelineBarrier2} は stage/access が 64bit になり、
 * 「同期不要」を {@code NONE} で明示できる。
 * 旧 API では {@code TOP_OF_PIPE} / {@code BOTTOM_OF_PIPE} を
 * 「とりあえず置く」用途に流用しがちで意図が曖昧になる。
 * Vulkan 1.4 を要求しているのでコア機能として使える [確認済]。
 */
public final class VkBarriers {
    private VkBarriers() {}

    /**
     * <b>P1</b>: compute が書いた SSBO を次の compute が読む。
     *
     * <p>GL の {@code glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)} に対応。
     * 読み書き両方あり得るので dst には READ と WRITE の両方を立てる
     * (連続する dispatch が同じバッファを更新していく形が多いため)。
     */
    public static void computeToCompute(VkCommandBuffer cmd) {
        memoryBarrier(cmd,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT,
            VK_ACCESS_2_SHADER_STORAGE_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT);
    }

    /**
     * <b>P2</b>: compute が書いた間接コマンドを、直後の間接ディスパッチが読む。
     *
     * <p>GL の {@code glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT | GL_COMMAND_BARRIER_BIT)}
     * に対応。{@code COMMAND_BARRIER_BIT} が {@code INDIRECT_COMMAND_READ} に、
     * {@code SHADER_STORAGE_BARRIER_BIT} が {@code SHADER_STORAGE_READ} に写る。
     *
     * <p>間接バッファは {@code DRAW_INDIRECT} ステージで読まれる。これは
     * 描画だけでなく <b>{@code vkCmdDispatchIndirect} でも同じ</b>。
     */
    public static void computeToIndirect(VkCommandBuffer cmd) {
        computeToIndirect(cmd, VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT);
    }

    /**
     * <b>P2</b> の消費側ステージを指定する版。
     *
     * @param consumerStages 間接コマンドで起動されるステージのうち、
     *                       SSBO を読むもの。間接ディスパッチなら {@code COMPUTE_SHADER}、
     *                       間接描画なら {@code VERTEX_SHADER | FRAGMENT_SHADER}。
     */
    public static void computeToIndirect(VkCommandBuffer cmd, long consumerStages) {
        memoryBarrier(cmd,
            VK_PIPELINE_STAGE_2_COMPUTE_SHADER_BIT, VK_ACCESS_2_SHADER_STORAGE_WRITE_BIT,
            VK_PIPELINE_STAGE_2_DRAW_INDIRECT_BIT | consumerStages,
            VK_ACCESS_2_INDIRECT_COMMAND_READ_BIT | VK_ACCESS_2_SHADER_STORAGE_READ_BIT);
    }

    /** 間接描画向けの消費側ステージ。 */
    public static final long INDIRECT_DRAW_CONSUMERS =
        VK_PIPELINE_STAGE_2_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_2_FRAGMENT_SHADER_BIT;

    /**
     * <b>保守的な全書き</b>。翻訳できていない箇所に暫定で置く。
     *
     * <p><b>これは「正しい」バリアではなく「まず動かす」ためのもの。</b>
     * 使う箇所には必ず「なぜ保守的なのか」「削れるか未検証」をコメントで残すこと。
     * 同期バリデーションは<b>過剰なバリアを指摘しない</b>ため、
     * これを置いたまま放置すると恒久化する。
     *
     * @param reason なぜ保守的に張っているか (ログ/検索用)
     */
    public static void conservative(VkCommandBuffer cmd, String reason) {
        memoryBarrier(cmd,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT,
            VK_PIPELINE_STAGE_2_ALL_COMMANDS_BIT,
            VK_ACCESS_2_MEMORY_READ_BIT | VK_ACCESS_2_MEMORY_WRITE_BIT);
    }

    /** 個別に書く箇所向けの素の API。stage/access の根拠はコメントで残すこと。 */
    public static void memoryBarrier(VkCommandBuffer cmd,
                                     long srcStage, long srcAccess,
                                     long dstStage, long dstAccess) {
        try (MemoryStack stack = stackPush()) {
            var b = VkMemoryBarrier2.calloc(1, stack)
                .sType$Default()
                .srcStageMask(srcStage).srcAccessMask(srcAccess)
                .dstStageMask(dstStage).dstAccessMask(dstAccess);
            var dep = VkDependencyInfo.calloc(stack)
                .sType$Default()
                .pMemoryBarriers(b);
            VkCmd.pipelineBarrier2(cmd, dep);
        }
    }
}
