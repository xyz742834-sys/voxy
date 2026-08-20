package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryUtil;

import java.util.HashMap;
import java.util.Map;

/**
 * 共有 quad インデックスバッファ。GL 側 {@code SharedIndexBuffer.INSTANCE} に対応する。
 *
 * <h2>なぜ 32bit にしたか</h2>
 * draw 統合 (Stage 2) で <b>1 つの draw に 1 つの面の全 quad が入る</b>ようになった。
 * 16bit ではインデックス値が {@code 4*quadCount-1 < 65536} に収まる必要があり、
 * <b>1 draw あたり 16,384 quad が上限</b>になる。実データでは確実に超える。
 *
 * <h2>なぜ「面全体を覆うサイズ」にしないか</h2>
 * インデックスバッファは<b>共有</b>資源なので、シーン全体を覆う必要はない。
 * 面全体を覆おうとすると 1 quad あたり 24 バイト (6 インデックス x 4 バイト) 要り、
 * ジオメトリ (8 バイト/quad) の <b>3 倍</b>になる。
 * ジオメトリ容量の上限 4GiB では <b>12GiB</b> に達する
 * [確認済 — 見積もりは docs/phase4-stage3-completion.md 7.1]。
 *
 * <p>代わりに {@link #DEFAULT_QUAD_CAPACITY} で固定し、
 * 1 面がこれを超えたら {@code baseVertex} を進めて複数 draw に分ける。
 *
 * <h2>なぜ分割しても頂点シェーダに触らずに済むか</h2>
 * {@code gl_VertexIndex = baseVertex + インデックス値} であり、
 * {@code baseVertex} は 4 の倍数なので:
 * <ul>
 *   <li>{@code gl_VertexIndex >> 2} は<b>グローバルな quad 通し番号のまま</b></li>
 *   <li>{@code gl_VertexIndex & 3} は<b>corner のまま</b></li>
 * </ul>
 * したがって Stage 2b で差分ゼロを確認したシェーダロジックは一切変わらない。
 * (非インデックス描画案を却下した理由がそのまま生きる。)
 *
 * <h2>T = 1M の根拠</h2>
 * <table>
 *   <tr><th>T</th><th>バッファ</th><th>draws (537M quad)</th></tr>
 *   <tr><td>16,380 (旧 16bit)</td><td>0.4 MiB</td><td>32,790</td></tr>
 *   <tr><td><b>1,048,576</b></td><td><b>24 MiB</b></td><td><b>519</b></td></tr>
 *   <tr><td>4,194,304</td><td>96 MiB</td><td>135</td></tr>
 * </table>
 * Phase 0 の実測で <b>1,000 draws は +0.10ms</b> なので 519 draws は余裕の内側。
 * これより小さいと draw 数が閾値に近づき、大きいとメモリの無駄になる。
 */
public final class VkQuadIndexBuffer {
    /** 1 draw が覆える quad 数 (T)。24 MiB。 */
    public static final int DEFAULT_QUAD_CAPACITY = 1 << 20;

    /**
     * 容量ごとに 1 本だけ持つ。<b>内容は不変</b>なので使い回して問題ない。
     * テストが小さい T を使うため容量をキーにする (24MiB を毎回作らずに済む)。
     */
    private static final Map<Integer, VkQuadIndexBuffer> CACHE = new HashMap<>();

    public final VkBuffer buffer;
    public final int quadCapacity;

    private VkQuadIndexBuffer(int quadCapacity) {
        this.quadCapacity = quadCapacity;
        this.buffer = new VkBuffer((long) quadCapacity * 6 * 4, false).name("quadIndices");
        fill(this.buffer, quadCapacity);
    }

    public static VkQuadIndexBuffer get() { return of(DEFAULT_QUAD_CAPACITY); }

    public static VkQuadIndexBuffer of(int quadCapacity) {
        if (quadCapacity <= 0) throw new IllegalArgumentException("quad capacity must be > 0");
        return CACHE.computeIfAbsent(quadCapacity, VkQuadIndexBuffer::new);
    }

    /**
     * quad ごとに 6 個の 32bit インデックスを書く。
     * <b>並びは GL 側と同一でなければならない</b>
     * [確認済 — `SharedIndexBuffer.generateQuadIndicesShort`]:
     *
     * <pre>(i+1, i+2, i+0,  i+1, i+3, i+2)</pre>
     *
     * <p><b>両三角形とも先頭が {@code i+1} なのが要点。</b> 頂点シェーダは
     * {@code (gl_VertexIndex&3) == 1} の頂点でのみ flat 属性を生成するので、
     * provoking vertex が corner 1 でなければ属性が未定義になる。
     * GL 側は {@code glProvokingVertex(GL_FIRST_VERTEX_CONVENTION)} を明示しており、
     * <b>Vulkan の既定も first vertex</b> なので追加設定は要らない [確認済]。
     */
    private static void fill(VkBuffer target, int quadCount) {
        long ptr = target.addr();
        for (int q = 0; q < quadCount; q++) {
            int i = q * 4;
            MemoryUtil.memPutInt(ptr,      i + 1);
            MemoryUtil.memPutInt(ptr + 4,  i + 2);
            MemoryUtil.memPutInt(ptr + 8,  i);
            MemoryUtil.memPutInt(ptr + 12, i + 1);
            MemoryUtil.memPutInt(ptr + 16, i + 3);
            MemoryUtil.memPutInt(ptr + 20, i + 2);
            ptr += 24;
        }
    }

    /** デバイス破棄前に呼ぶこと。 */
    public static void shutdown() {
        CACHE.values().forEach(b -> b.buffer.free());
        CACHE.clear();
    }

    public static int cachedCount() { return CACHE.size(); }
}
