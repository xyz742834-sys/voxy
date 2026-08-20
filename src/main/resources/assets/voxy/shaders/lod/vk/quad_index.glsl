#ifndef _VOXY_QUAD_INDEX_DECL
#define _VOXY_QUAD_INDEX_DECL

// 統合描画 (Phase 4 Stage 2) の索引解決。
//
// == なぜ Vulkan 専用ファイルなのか ==
// GL 版 (lod/gl46/quads3.vert) はセクション単位の 88k draws のまま残す。
// ロジックが本質的に違うので #ifdef で 1 ファイルに同居させると両方読めなくなる。
// GL 版は「移植の参照仕様」として無傷で保つ (docs/phase4-proposal.md 3.3 案 B)。
//
// == テーブルの形 ==
// エントリ 1 件 = (セクション, 面) のラン 1 本。面優先で並んでおり、
// 面 f のエントリは連続した区間を占める。したがって面ごとの draw は
// baseVertex = 面の先頭 quad 通し番号 * 4 で撃ち分けられ、
// 頂点シェーダは gl_VertexIndex からグローバルな quad 通し番号を直接得る。
//
//   mergedPrefix[i]   エントリ i の手前までの累計 quad 数 (狭義単調増加)
//   mergedPrefix[n]   総 quad 数 (番兵)
//   mergedEntries[i]  { quadStart, drawId }
//
// 空ラン (quad 数 0) はテーブルに載せない。載せると prefix に平坦部ができ、
// 「どのエントリにも属さない通し番号」が生まれて解が一意でなくなる。

layout(binding = MERGED_ENTRY_BINDING, std430) readonly restrict buffer MergedEntryBuffer {
    uvec2 mergedEntries[];
};

layout(binding = MERGED_PREFIX_BINDING, std430) readonly restrict buffer MergedPrefixBuffer {
    uint mergedEntryCount;
    uint mergedPrefix[];
};

// 探索の反復上限。2^32 エントリでも 32 回で収束する。
//
// ⚠ GPU 上の探索ループには必ず上限を切ること。テーブルが壊れていると
// while ループが終わらず、OS レベルの GPU リセットに至る (Phase 0 で実際に起こした)。
// 上限に当たった場合は「間違った答えを返す」が、ハングはしない。
#define QUAD_INDEX_MAX_STEPS 32

struct MergedQuadRef {
    uint quadIndex;   // ジオメトリバッファ内の quad 番号
    uint drawId;      // positionBuffer の添字 (= セクション)
};

// prefix[lo] <= quadOrdinal < prefix[lo+1] となる lo を返す。
//
// 不変条件: prefix[lo] <= quadOrdinal < prefix[hi]
//   初期状態 prefix[0] == 0 <= quadOrdinal、prefix[entryCount] == 総数 > quadOrdinal
//   なので、有効な通し番号に対しては必ず成り立つ。
uint findMergedEntry(uint quadOrdinal) {
    uint lo = 0u;
    uint hi = mergedEntryCount;
    for (int step = 0; step < QUAD_INDEX_MAX_STEPS && (lo + 1u) < hi; step++) {
        uint mid = lo + ((hi - lo) >> 1);
        if (mergedPrefix[mid] <= quadOrdinal) {
            lo = mid;
        } else {
            hi = mid;
        }
    }
    return lo;
}

MergedQuadRef resolveQuad(uint quadOrdinal) {
    uint e = findMergedEntry(quadOrdinal);
    uvec2 entry = mergedEntries[e];

    MergedQuadRef result;
    result.quadIndex = entry.x + (quadOrdinal - mergedPrefix[e]);
    result.drawId = entry.y;
    return result;
}

#endif
