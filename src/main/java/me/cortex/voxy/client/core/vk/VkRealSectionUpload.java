package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.common.world.WorldEngine;
import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5c-3b — <b>実データの {@code BuiltSection} を Vulkan のバッファへ置く</b>。
 *
 * <h2>⚠ 位置のエンコードが 2 種類ある</h2>
 * {@code BuiltSection.position} は <b>{@code WorldEngine} のキー形式</b>
 * ({@code lvl<<60 | y<<52 | z<<28 | x<<4}) で、
 * GPU が読むのは <b>{@code pos_util.glsl} の形式</b>
 * ({@link SyntheticTerrain#packPosition}) である。<b>別物である。</b>
 *
 * <p>⚠⚠ <b>ただし「別物」ではない</b> — 調べると<b>ワードを入れ替えただけ</b>だった
 * [確認済 — {@code VkRealPositionTest.theWorldKeyIsPackPositionWithItsWordsSwapped}]:
 *
 * <pre>
 *   (int)(key &gt;&gt;&gt; 32) == packPosition の下位ワード (px)
 *   (int) key         == packPosition の上位ワード (py)
 * </pre>
 *
 * <p>だから上流は<b>復号せずに上位ワードから書く</b>だけで正しい
 * [{@code SectionMeta.writeMetadataSplitParts}]。
 * ここは復号して詰め直しているので<b>遠回りだが結果は同じ</b>である。
 * 動いていて検査もあるので変えない — <b>等価な書き換えで壊す危険のほうが大きい</b>。
 *
 * <p>そのまま低位ワードから書くと位置が滅茶苦茶になる。落ちないし、
 * 「地形がどこかに出ている」ようには見えるので<b>気付きにくい</b>。
 *
 * <h2>AABB はそのまま使える [確認済]</h2>
 * {@code RenderDataFactory} は {@code minX | minY<<5 | minZ<<10 |
 * (maxX-minX-1)<<15 | ...} と詰めており、
 * {@code section.glsl} の {@code extractAABBOffset} / {@code extractAABBSize} と<b>同じ配置</b>である。
 *
 * <h2>⚠ 面方向マスクを掛けない</h2>
 * 合成側は {@code faceMask} で<b>裏を向いた面を落として</b>いる。ここでは掛けない。
 *
 * <p>あれは<b>最適化</b>であって正しさではない。掛けると 5c-3b が
 * 「実ジオメトリ」と「マスクの正しさ」の<b>2 変数</b>になる。
 * 全部描けば無駄はあるが<b>絵は正しい</b>。マスクは本番では {@code cmdgen} の仕事で、
 * 5c-4 で入る。
 */
public final class VkRealSectionUpload {
    private VkRealSectionUpload() {}

    /** バケツの数と並び。translucent, double-sided, 面 0..5 [確認済 — {@code RenderDataFactory}]。 */
    public static final int BUCKETS = VkGeometryInvariants.BUCKETS;

    /**
     * @param sectionCount  置いたセクション数
     * @param totalQuads    置いた quad 数 (<b>不透明も半透明も含む</b>)
     * @param drawCount     積んだ描画コマンド数
     * @param geometry      不変条件の検査に渡すための、置いたあとの姿
     */
    public record Uploaded(int sectionCount, int totalQuads, int drawCount,
                           List<VkGeometryInvariants.SectionGeometry> geometry) {}

    public static Uploaded upload(List<BuiltSection> sections, VkTerrainResources res) {
        // --- 入るかどうかを先に見る ---
        long totalQuads = 0;
        for (var s : sections) totalQuads += s.geometryBuffer.size / SyntheticTerrain.QUAD_SIZE;
        long geometryNeed = totalQuads * SyntheticTerrain.QUAD_SIZE;
        if (geometryNeed > res.geometry.size()) {
            throw new IllegalArgumentException("the meshed geometry is " + geometryNeed
                + "B but the buffer is " + res.geometry.size() + "B — mesh a smaller radius");
        }
        long metadataNeed = (long) sections.size() * SyntheticTerrain.SECTION_METADATA_SIZE;
        if (metadataNeed > res.sectionMetadata.size()) {
            throw new IllegalArgumentException("need " + metadataNeed + "B of section metadata"
                + " but the buffer is " + res.sectionMetadata.size() + "B");
        }
        if ((long) sections.size() * 8 > res.positionScratch.size()) {
            throw new IllegalArgumentException("more sections than the position buffer holds");
        }

        long geoAddr = res.geometry.addr();
        long metaAddr = res.sectionMetadata.addr();
        long posAddr = res.positionScratch.addr();

        var geometryView = new ArrayList<VkGeometryInvariants.SectionGeometry>(sections.size());
        var draws = new ArrayList<SyntheticTerrain.OpaqueDraw>();
        int quadCursor = 0;

        for (int si = 0; si < sections.size(); si++) {
            var s = sections.get(si);
            int count = (int) (s.geometryBuffer.size / SyntheticTerrain.QUAD_SIZE);

            MemoryUtil.memCopy(s.geometryBuffer.address,
                geoAddr + (long) quadCursor * SyntheticTerrain.QUAD_SIZE, s.geometryBuffer.size);

            // ⚠ ワールドのキー形式 -> GPU の形式へ**詰め直す**
            int lvl = WorldEngine.getLevel(s.position);
            int x = WorldEngine.getX(s.position);
            int y = WorldEngine.getY(s.position);
            int z = WorldEngine.getZ(s.position);
            long packed = SyntheticTerrain.packPosition(x, y, z, lvl);

            MemoryUtil.memPutLong(posAddr + (long) si * 8, packed);

            long base = metaAddr + (long) si * SyntheticTerrain.SECTION_METADATA_SIZE;
            MemoryUtil.memPutInt(base,      (int) packed);
            MemoryUtil.memPutInt(base + 4,  (int) (packed >>> 32));
            MemoryUtil.memPutInt(base + 8,  s.aabb);          // 配置はそのまま使える
            MemoryUtil.memPutInt(base + 12, quadCursor);

            int[] o = s.offsets;
            int[] counts = new int[BUCKETS];
            for (int b = 0; b < BUCKETS; b++) {
                counts[b] = (b + 1 < BUCKETS ? o[b + 1] : count) - o[b];
            }
            // b.x: translucent | double-sided<<16, b.y..b.w: 面 0..5
            MemoryUtil.memPutInt(base + 16, (counts[0] & 0xFFFF) | ((counts[1] & 0xFFFF) << 16));
            MemoryUtil.memPutInt(base + 20, (counts[2] & 0xFFFF) | ((counts[3] & 0xFFFF) << 16));
            MemoryUtil.memPutInt(base + 24, (counts[4] & 0xFFFF) | ((counts[5] & 0xFFFF) << 16));
            MemoryUtil.memPutInt(base + 28, (counts[6] & 0xFFFF) | ((counts[7] & 0xFFFF) << 16));

            // --- 描画コマンド。**半透明は飛ばし、残りは全部描く** ---
            for (int b = 1; b < BUCKETS; b++) {
                if (counts[b] == 0) continue;
                draws.add(new SyntheticTerrain.OpaqueDraw(
                    si, quadCursor + o[b], counts[b], b == 1 ? 6 : b - 2));
            }

            geometryView.add(new VkGeometryInvariants.SectionGeometry(
                s.position, o.clone(), geoAddr + (long) quadCursor * SyntheticTerrain.QUAD_SIZE, count));
            quadCursor += count;
        }

        SyntheticTerrain.writeDrawCommands(res.drawCall, draws, res.indexQuadCapacity);
        return new Uploaded(sections.size(), quadCursor, draws.size(), geometryView);
    }
}
