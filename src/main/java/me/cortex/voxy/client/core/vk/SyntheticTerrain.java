package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryUtil;

import java.util.ArrayList;
import java.util.List;

/**
 * Stage 1 / Stage 2b 検証用の合成地形データ。
 *
 * <h2>なぜ合成データか</h2>
 * 実データ (ワールド読み込み + メッシュ生成 + ブロックアトラス) はこの環境で得られない。
 * Voxy 自体が GL 4.1 の Mac では起動しないため [確認済 — docs/phase2-binding-audit.md §8.4]。
 *
 * <p><b>検証できるのは自己整合性のみ。</b>「GL 版と同じ絵か」は検証していない。
 * draw 統合に問われているのは「統合前と統合後で同じ入力から同じ絵が出るか」なので、
 * これで足りる。実データとの一致は Phase 5 で実データを入れたときの別の問い。
 *
 * <h2>合成データの利点</h2>
 * 決定的で制御可能なため<b>境界条件を狙って作れる</b>。
 * 実データでは滅多に出ないケースを Stage 2b で狙い撃ちできる ({@link #boundaryCases()})。
 *
 * <h2>データ形式</h2>
 * シェーダ側の定義から導出した [確認済 — `quad_format.glsl` / `section.glsl` / `pos_util.glsl`]。
 *
 * <pre>
 * Quad (uint64):
 *   bit  0.. 2  face      (3)
 *   bit  3.. 6  sizeX-1   (4)
 *   bit  7..10  sizeY-1   (4)
 *   bit 11..15  posZ      (5)
 *   bit 16..20  posY      (5)
 *   bit 21..25  posX      (5)
 *   bit 26..41  stateId   (16)
 *   bit 46..54  biomeId   (9)
 *   bit 55..62  lightId   (8)
 *
 * SectionMeta (32 bytes = uvec4 a + uvec4 b):
 *   a.xy  packed LoD position
 *   a.z   AABB   offset(3x5b @0,5,10) | size-1(3x5b @15,20,25)
 *   a.w   quadStart (ジオメトリバッファ内の quad 単位オフセット)
 *   b.x   low16 = 半透明数        high16 = 両面数
 *   b.y   low16 = 下面(-Y)        high16 = 上面(+Y)
 *   b.z   low16 = -Z 面           high16 = +Z 面
 *   b.w   low16 = -X 面           high16 = +X 面
 * </pre>
 *
 * 面が 7 種類 (6 方向 + 両面) あることが、統合後の「opaque 7 draws」に対応する
 * [確認済 — `cmdgen.comp` の `msk` 構築]。
 */
public final class SyntheticTerrain {
    public static final int SECTION_METADATA_SIZE = 32;
    public static final int QUAD_SIZE = 8;

    /** 面の種類。{@code cmdgen.comp} の msk ビット順に対応する。 */
    public enum Face {
        DOWN(0), UP(1), NORTH(2), SOUTH(3), WEST(4), EAST(5), DOUBLE_SIDED(6);
        public final int bit;
        Face(int bit) { this.bit = bit; }
    }

    /** 1 セクションぶんの記述。 */
    public static final class Section {
        public final int x, y, z, level;
        /** 面ごとの quad 数。index は {@link Face#bit}。 */
        public final int[] faceCounts = new int[7];
        public int translucentCount;

        public Section(int x, int y, int z, int level) {
            this.x = x; this.y = y; this.z = z; this.level = level;
        }

        public Section face(Face f, int count) { this.faceCounts[f.bit] = count; return this; }
        public Section translucent(int count) { this.translucentCount = count; return this; }

        /**
         * セクション内の AABB (遮蔽カリングが描く箱)。既定はセクション全体 (offset 0, size 32)。
         *
         * <p><b>cull はこの箱を描いて深度テストする</b>ので、
         * 遮蔽される側の箱が遮蔽物からはみ出していると可視と判定される。
         * カリングのテストで「完全に隠れる」配置を作るには箱を絞る必要がある。
         *
         * @param offset 0..31
         * @param size   1..32
         */
        public Section aabb(int offset, int size) {
            if (offset < 0 || offset > 31) throw new IllegalArgumentException("aabb offset 0..31");
            if (size < 1 || size > 32) throw new IllegalArgumentException("aabb size 1..32");
            this.aabbOffset = offset;
            this.aabbSize = size;
            return this;
        }

        int aabbOffset = 0;
        int aabbSize = 32;

        /** 半透明 + 両面 + 6 方向の総 quad 数。 */
        public int totalQuads() {
            int n = this.translucentCount;
            for (int c : this.faceCounts) n += c;
            return n;
        }
    }

    private final List<Section> sections = new ArrayList<>();

    public SyntheticTerrain add(Section s) { this.sections.add(s); return this; }
    public int sectionCount() { return this.sections.size(); }
    public List<Section> sections() { return List.copyOf(this.sections); }

    public int totalQuads() {
        int n = 0;
        for (var s : this.sections) n += s.totalQuads();
        return n;
    }

    /**
     * このデータセットが使う最大の stateId。
     *
     * <p><b>モデルバッファの必要サイズを決めるのに要る。</b> 頂点シェーダは
     * {@code modelData[extractStateId(quad)]} を無検査で引くため、stateId が
     * バッファ長を超えると範囲外読みになる (Vulkan では未定義動作で、
     * バリデーションも捕まえない)。
     */
    public int maxStateId() {
        int max = 0;
        for (int si = 0; si < this.sections.size(); si++) {
            var s = this.sections.get(si);
            int[] runs = new int[8];
            runs[0] = s.translucentCount;
            runs[1] = s.faceCounts[Face.DOUBLE_SIDED.bit];
            for (int f = 0; f < 6; f++) runs[2 + f] = s.faceCounts[f];
            for (int r = 0; r < runs.length; r++) {
                for (int i = 0; i < runs[r]; i++) max = Math.max(max, stateIdOf(si, r, i));
            }
        }
        return max;
    }

    // ---------------- encoding ----------------

    /**
     * LoD 位置のパック。{@code pos_util.glsl} の {@code getLoDPosition} の逆変換。
     *
     * <pre>
     * packed.x : bit 28..31 = level      bit 20..27 = y (8bit 符号付き)
     *            bit  0..19 = z の上位 20 bit
     * packed.y : bit 28..31 = z の下位 4 bit
     *            bit  4..27 = x (24bit 符号付き)
     * </pre>
     *
     * <p><b>z の下位 4bit は {@code packed.y} の最上位に入る</b> —
     * 復号側が {@code z |= int(packedPos.y>>28)} と読むため。
     * ここを下位ビットに置くと z が失われる (実際に一度間違えた)。
     */
    public static long packPosition(int x, int y, int z, int level) {
        int px = ((level & 0xF) << 28) | ((y & 0xFF) << 20) | ((z >> 4) & 0xFFFFF);
        int py = ((x & 0xFFFFFF) << 4) | ((z & 0xF) << 28);
        return (Integer.toUnsignedLong(px)) | (Integer.toUnsignedLong(py) << 32);
    }

    /** {@code pos_util.glsl} と同じ復号。エンコードの往復検査に使う。 */
    public static int[] unpackPosition(long packed) {
        int px = (int) packed;
        int py = (int) (packed >>> 32);
        int level = px >>> 28;
        int y = (px << 4) >> 24;
        int x = (py << 4) >> 8;
        int z = ((px & ((1 << 20) - 1)) << 4) | (py >>> 28);
        z = (z << 8) >> 8;
        return new int[]{x, y, z, level};
    }

    /** Quad のパック。{@code quad_format.glsl} の 64bit 経路に対応。 */
    public static long packQuad(int face, int sizeX, int sizeY,
                                int posX, int posY, int posZ,
                                int stateId, int biomeId, int lightId) {
        if (sizeX < 1 || sizeY < 1) throw new IllegalArgumentException("size must be >= 1");
        long q = 0;
        q |= (face & 0x7L);
        q |= ((long) ((sizeX - 1) & 0xF)) << 3;
        q |= ((long) ((sizeY - 1) & 0xF)) << 7;
        q |= ((long) (posZ & 0x1F)) << 11;
        q |= ((long) (posY & 0x1F)) << 16;
        q |= ((long) (posX & 0x1F)) << 21;
        q |= ((long) (stateId & 0xFFFF)) << 26;
        q |= ((long) (biomeId & 0x1FF)) << 46;
        q |= ((long) (lightId & 0xFF)) << 55;
        return q;
    }

    // ---------------- buffer building ----------------

    /**
     * ジオメトリバッファ (quad 列) を書き込む。
     * セクションは {@link #sections()} の順に連続配置される。
     *
     * @return 各セクションの quadStart (quad 単位)
     */
    /**
     * stateId を<b>別の値へ写して</b>ジオメトリを書く (Phase 5c-2b)。
     *
     * <p>合成の stateId は索引から作った値なので、<b>実際に焼けたモデル id とは別物</b>である。
     * 実データのテクスチャで描くには、ここで写す必要がある。
     *
     * <p>⚠ <b>写像が単射でないと規約 1 が弱まる。</b>
     * 合成データは「索引がずれたら色が変わる」ように stateId ごとに違う色を割り当てていた。
     * 複数の quad が同じモデルを指すと、<b>その 2 つを取り違えても絵が変わらない</b>。
     * 位置はワールド空間で分かれたままなので<b>位置のずれは依然として絵に出る</b>が、
     * <b>モデルのずれは出なくなる</b> — この段で何が守られていないかを承知して使うこと。
     *
     * @param stateIdMap 元の stateId を受け取り、使うモデル id を返す
     */
    public int[] writeGeometry(VkBuffer target, java.util.function.IntUnaryOperator stateIdMap) {
        this.stateIdMap = stateIdMap;
        try {
            return this.writeGeometry(target);
        } finally {
            this.stateIdMap = null;
        }
    }

    /** {@link #writeGeometry(VkBuffer, java.util.function.IntUnaryOperator)} が設定する。 */
    private java.util.function.IntUnaryOperator stateIdMap;

    public int[] writeGeometry(VkBuffer target) {
        long need = (long) this.totalQuads() * QUAD_SIZE;
        if (target.size() < need) {
            throw new IllegalArgumentException("geometry buffer too small: need " + need
                + " got " + target.size());
        }
        int[] starts = new int[this.sections.size()];
        long addr = target.addr();
        int quadIndex = 0;

        for (int si = 0; si < this.sections.size(); si++) {
            var s = this.sections.get(si);
            starts[si] = quadIndex;

            // 順序は cmdgen.comp が読む順: 半透明 -> 両面 -> 6 方向
            quadIndex = writeRun(addr, quadIndex, starts[si], s.translucentCount, ROTATING_FACE, si, 0);
            quadIndex = writeRun(addr, quadIndex, starts[si], s.faceCounts[Face.DOUBLE_SIDED.bit],
                ROTATING_FACE, si, 1);
            for (int f = 0; f < 6; f++) {
                quadIndex = writeRun(addr, quadIndex, starts[si], s.faceCounts[f], f, si, 2 + f);
            }
        }
        return starts;
    }

    /**
     * {@link #writeRun} に「quad ごとに 0..5 を回す」ことを指示する印。
     *
     * <p><b>半透明ランと両面ランの quad にも実在の面 (0..5) を入れなければならない。</b>
     * quad 内の face フィールドは {@code model.faceData[face]} の添字になるが、
     * {@code BlockModel.faceData} は 6 要素しかない [確認済 — `block_model.glsl`]。
     * {@link Face#DOUBLE_SIDED} の値 6 をそのまま書くと <b>構造体の次のメンバ
     * ({@code flagsA}) を面データとして読む範囲外アクセス</b>になる。
     *
     * <p>「両面」「半透明」はセクションメタデータの本数 ({@code b.x}) で表現される
     * <b>区分</b>であって、quad の面の値ではない [確認済 — `cmdgen.comp` の msk 構築は
     * 本数だけを見ており、quad の face には触れていない]。
     */
    private static final int ROTATING_FACE = -1;

    /** セクション/ラン/連番から作る stateId。索引がずれれば絵の色が変わる。 */
    /** 写像があればそれを通す。無ければ合成の stateId をそのまま使う。 */
    private int mappedStateId(int sectionIdx, int runIdx, int i) {
        int raw = stateIdOf(sectionIdx, runIdx, i);
        return this.stateIdMap == null ? raw : this.stateIdMap.applyAsInt(raw);
    }

    private static int stateIdOf(int sectionIdx, int runIdx, int i) {
        return ((sectionIdx & 0x3F) << 10) | ((runIdx & 0xF) << 6) | (i & 0x3F);
    }

    /**
     * 1 ラン書き出す。stateId はセクション/ラン/連番から、位置は
     * <b>セクション内の通し番号</b>から作る。
     *
     * <h2>なぜ位置をラン内連番ではなく通し番号から作るのか</h2>
     * <b>そうしないと索引ずれが絵に出ない。</b> 当初はラン内の {@code i} から
     * 位置を作っていたため、同じセクションの別のラン (別の面) が
     * <b>まったく同じ格子点を共有</b>していた。この状態で全コマンドの
     * quadOffset を一律 +1 しても、描かれる quad の集合はラン境界で
     * 隠れている 1 枚が入れ替わるだけになり、<b>512x512 で 1 ピクセルも変わらなかった</b>
     * [確認済 — 実際に観測]。
     *
     * <p>Stage 2b が検出したいのはまさにこの種の索引ずれ (二分探索の off-by-one) なので、
     * 基準データは<b>各 quad が別々の可視位置を占める</b>必要がある。
     * セクション内通し番号なら 32768 枚まで一意に散る。
     *
     * @param sectionBase このセクション先頭の quad 番号。位置の通し番号を出すのに使う
     */
    private int writeRun(long addr, int quadIndex, int sectionBase,
                         int count, int face, int sectionIdx, int runIdx) {
        for (int i = 0; i < count; i++) {
            int k = quadIndex - sectionBase;                 // セクション内の通し番号
            int px = k & 0x1F, py = (k >> 5) & 0x1F, pz = (k >> 10) & 0x1F;
            int f = face == ROTATING_FACE ? (i % 6) : face;
            long q = packQuad(f, 1, 1, px, py, pz, this.mappedStateId(sectionIdx, runIdx, i),
                sectionIdx & 0x1FF, 0xF0);
            MemoryUtil.memPutLong(addr + (long) quadIndex * QUAD_SIZE, q);
            quadIndex++;
        }
        return quadIndex;
    }

    /** セクションメタデータを書き込む。 */
    public void writeMetadata(VkBuffer target, int[] quadStarts) {
        long need = (long) this.sections.size() * SECTION_METADATA_SIZE;
        if (target.size() < need) {
            throw new IllegalArgumentException("metadata buffer too small: need " + need
                + " got " + target.size());
        }
        long addr = target.addr();
        for (int i = 0; i < this.sections.size(); i++) {
            var s = this.sections.get(i);
            long base = addr + (long) i * SECTION_METADATA_SIZE;
            long pos = packPosition(s.x, s.y, s.z, s.level);

            MemoryUtil.memPutInt(base,      (int) pos);            // a.x
            MemoryUtil.memPutInt(base + 4,  (int) (pos >>> 32));   // a.y
            // AABB: offset を 0,5,10 bit に、size-1 を 15,20,25 bit に
            // [確認済 — section.glsl の extractAABBOffset / extractAABBSize]
            int ao = s.aabbOffset, as = s.aabbSize - 1;
            int aabb = (ao) | (ao << 5) | (ao << 10) | (as << 15) | (as << 20) | (as << 25);
            MemoryUtil.memPutInt(base + 8,  aabb);                 // a.z
            MemoryUtil.memPutInt(base + 12, quadStarts[i]);        // a.w

            MemoryUtil.memPutInt(base + 16,
                (s.translucentCount & 0xFFFF) | ((s.faceCounts[Face.DOUBLE_SIDED.bit] & 0xFFFF) << 16)); // b.x
            MemoryUtil.memPutInt(base + 20,
                (s.faceCounts[Face.DOWN.bit] & 0xFFFF) | ((s.faceCounts[Face.UP.bit] & 0xFFFF) << 16));  // b.y
            MemoryUtil.memPutInt(base + 24,
                (s.faceCounts[Face.NORTH.bit] & 0xFFFF) | ((s.faceCounts[Face.SOUTH.bit] & 0xFFFF) << 16)); // b.z
            MemoryUtil.memPutInt(base + 28,
                (s.faceCounts[Face.WEST.bit] & 0xFFFF) | ((s.faceCounts[Face.EAST.bit] & 0xFFFF) << 16));  // b.w
        }
    }

    // ---------------- draw command synthesis (CPU 版 cmdgen) ----------------

    /** {@code DrawCommand} のバイト数。5 uint [確認済 — `gl46/bindings.glsl`]。 */
    public static final int DRAW_COMMAND_SIZE = 20;

    /**
     * 1 面ぶんの描画コマンド。{@code cmdgen.comp} の {@code writeCmd} 1 回に対応する。
     *
     * @param drawId     {@code baseInstance}。頂点シェーダが {@code positionBuffer[gl_BaseInstance]}
     *                   を引くための添字で、セクションに 1 対 1 で対応する
     * @param quadOffset ジオメトリバッファ内の quad 単位オフセット ({@code baseVertex = offset<<2})
     * @param quadCount  この面の quad 数 ({@code indexCount = count*6})
     * @param faceBit    どの面区分か (デバッグ・検証用。コマンド自体には入らない)
     */
    public record OpaqueDraw(int drawId, int quadOffset, int quadCount, int faceBit) {}

    /**
     * <b>{@code cmdgen.comp} の不透明経路を CPU 側で再現する。</b>
     *
     * <h2>Stage 1 で GPU の 5 段を使わない理由</h2>
     * prep / cull / cmdgen / prefixsum / translucentGen を式から除くと、
     * 絵が壊れたときの切り分けが「頂点シェーダの索引解決」と「描画コマンドの形」に絞られる
     * (docs/phase4-proposal.md の Stage 1 縮小スコープ)。
     * 5 段は Stage 3 以降で扱う。
     *
     * <h2>本家との意図的な差異</h2>
     * <ol>
     *   <li><b>ラスタ由来の可視判定を行わない。</b> {@code cmdgen} は
     *       {@code visibilityData[sectionId] == frameId} でセクションを落とすが、
     *       これは cull ラスタパスの出力に依存する。ここでは全セクションを可視として扱う。
     *       <b>面方向マスク ({@code msk}) は再現する</b> — こちらはカメラのセクション座標だけで
     *       決まり、外部依存が無い ({@link #faceMask})。</li>
     *   <li><b>半透明ランは出さない。</b> これは cmdgen も同じで、半透明は
     *       {@code buildtranslucents.comp} の別経路に回る。ただし
     *       <b>ptr は半透明ぶん進める</b> — ここを忘れると全面が 1 ラン分ずれる。</li>
     * </ol>
     *
     * <p>面の並び順は {@code cmdgen.comp} の書き出し順そのまま
     * (両面 → DOWN → UP → NORTH → SOUTH → WEST → EAST)。
     * {@link #writeGeometry} のラン配置と一致していなければならない。
     *
     * @param baseSectionPos カメラのセクション座標。面方向マスクの基準になる
     */
    public List<OpaqueDraw> opaqueDrawCommands(int[] quadStarts, int[] baseSectionPos) {
        if (quadStarts.length != this.sections.size()) {
            throw new IllegalArgumentException("quadStarts has " + quadStarts.length
                + " entries but there are " + this.sections.size() + " sections");
        }
        var out = new ArrayList<OpaqueDraw>();
        for (int si = 0; si < this.sections.size(); si++) {
            var s = this.sections.get(si);
            int msk = faceMask(s, baseSectionPos);
            int ptr = quadStarts[si];

            ptr += s.translucentCount;      // 別経路。読み飛ばすが offset は進める

            int count = s.faceCounts[Face.DOUBLE_SIDED.bit];
            if ((msk & (1 << Face.DOUBLE_SIDED.bit)) != 0) {
                out.add(new OpaqueDraw(si, ptr, count, Face.DOUBLE_SIDED.bit));
            }
            ptr += count;

            for (int f = 0; f < 6; f++) {
                count = s.faceCounts[f];
                if ((msk & (1 << f)) != 0) out.add(new OpaqueDraw(si, ptr, count, f));
                ptr += count;
            }
        }
        return out;
    }

    /**
     * <b>面方向マスク。{@code cmdgen.comp} の {@code msk} 構築をそのまま写したもの</b> [確認済]。
     *
     * <p>カメラのいるセクションから見て<b>裏を向いている面を落とす</b>。
     * たとえばカメラより東にあるセクションの EAST 面は、そのセクションの向こう側なので
     * 描いても見えない。判定はセクション座標だけで決まり、
     * 深度バッファにもラスタパスにも依存しない。
     *
     * <pre>
     * relative = ipos - (baseSectionPos >> detail)
     *   bit 0 DOWN  quad があり かつ relative.y > -1
     *   bit 1 UP    quad があり かつ relative.y <  1
     *   bit 2 NORTH quad があり かつ relative.z > -1
     *   bit 3 SOUTH quad があり かつ relative.z <  1
     *   bit 4 WEST  quad があり かつ relative.x > -1
     *   bit 5 EAST  quad があり かつ relative.x <  1
     *   bit 6 両面  quad があれば常に
     * </pre>
     *
     * <p><b>これを CPU 側にも入れている理由</b>: Stage 3 で GPU 版 cmdgen と
     * 突き合わせるとき、両者が同じ規則で面を落とさないと差分が出る。
     * ラスタ由来の可視判定と違い外部入力が無いので、CPU で完全に再現できる。
     */
    public static int faceMask(Section s, int[] baseSectionPos) {
        int detail = s.level;
        int rx = s.x - (baseSectionPos[0] >> detail);
        int ry = s.y - (baseSectionPos[1] >> detail);
        int rz = s.z - (baseSectionPos[2] >> detail);
        int msk = 0;
        if (s.faceCounts[Face.DOWN.bit]  != 0 && ry > -1) msk |= 1 << Face.DOWN.bit;
        if (s.faceCounts[Face.UP.bit]    != 0 && ry <  1) msk |= 1 << Face.UP.bit;
        if (s.faceCounts[Face.NORTH.bit] != 0 && rz > -1) msk |= 1 << Face.NORTH.bit;
        if (s.faceCounts[Face.SOUTH.bit] != 0 && rz <  1) msk |= 1 << Face.SOUTH.bit;
        if (s.faceCounts[Face.WEST.bit]  != 0 && rx > -1) msk |= 1 << Face.WEST.bit;
        if (s.faceCounts[Face.EAST.bit]  != 0 && rx <  1) msk |= 1 << Face.EAST.bit;
        if (s.faceCounts[Face.DOUBLE_SIDED.bit] != 0)     msk |= 1 << Face.DOUBLE_SIDED.bit;
        return msk;
    }

    /** 面方向マスクをかけない基準。すべてのセクションが原点にいる扱いになる。 */
    public static final int[] ORIGIN = {0, 0, 0};

    /**
     * 描画コマンドを間接バッファへ書く。レイアウトは GL の
     * {@code DrawElementsIndirectCommand} と {@code VkDrawIndexedIndirectCommand} で同一
     * (5 uint = 20 バイト) [確認済]。
     *
     * @param indexQuadCapacity 共有インデックスバッファが持つ quad 数。
     *                          1 コマンドがこれを超えるとインデックスバッファの外を読む
     */
    public static void writeDrawCommands(VkBuffer target, List<OpaqueDraw> cmds, int indexQuadCapacity) {
        long need = (long) cmds.size() * DRAW_COMMAND_SIZE;
        if (target.size() < need) {
            throw new IllegalArgumentException("draw command buffer too small: need " + need
                + " got " + target.size());
        }
        long addr = target.addr();
        for (int i = 0; i < cmds.size(); i++) {
            var c = cmds.get(i);
            if (c.quadCount() > indexQuadCapacity) {
                throw new IllegalArgumentException("draw " + i + " needs " + c.quadCount()
                    + " quads of shared indices but only " + indexQuadCapacity + " are available");
            }
            long o = addr + (long) i * DRAW_COMMAND_SIZE;
            MemoryUtil.memPutInt(o,      c.quadCount() * 6);   // indexCount
            MemoryUtil.memPutInt(o + 4,  1);                   // instanceCount
            MemoryUtil.memPutInt(o + 8,  0);                   // firstIndex
            MemoryUtil.memPutInt(o + 12, c.quadOffset() << 2); // vertexOffset (= baseVertex)
            MemoryUtil.memPutInt(o + 16, c.drawId());          // firstInstance (= baseInstance)
        }
    }

    /**
     * {@code positionBuffer} を書く。{@code cmdgen} の
     * {@code positionBuffer[drawId] = extractRawPos(meta)} に対応する。
     * 頂点シェーダはこれを {@code gl_BaseInstance} で引く。
     */
    /**
     * {@code IndirectSectionLookupBuffer} を書く
     * ({@code uint sectionCount; uint indirectLookup[];})。
     *
     * <p>本番では traversal が可視セクション ID を書き出すが、
     * ここでは<b>恒等写像</b>にする ({@code indirectLookup[i] == i})。
     * GPU 版 cmdgen はこれを介してセクションを引くので、経路自体は本番と同じ。
     */
    public void writeIndirectLookup(VkBuffer target) {
        long need = 4L + (long) this.sections.size() * 4;
        if (target.size() < need) {
            throw new IllegalArgumentException("indirect lookup buffer too small: need " + need
                + " got " + target.size());
        }
        MemoryUtil.memPutInt(target.addr(), this.sections.size());
        for (int i = 0; i < this.sections.size(); i++) {
            MemoryUtil.memPutInt(target.addr() + 4L + (long) i * 4, i);
        }
    }

    /**
     * {@code visibilityData} を書く。GPU 版 cmdgen は
     * {@code (visibilityData[sid] & 0x7fffffff) == frameId} を可視条件にする。
     *
     * @param visible null なら全セクションを可視にする
     */
    public void writeVisibility(VkBuffer target, int frameId, boolean[] visible) {
        this.writeVisibility(target, frameId, visible, null);
    }

    /**
     * @param wasVisibleLastFrame bit 31 に入れる「前フレームも可視だった」印。
     *                            null なら全て 0 = <b>全セクションが temporal 対象</b>になる。
     *                            本番では cull が立てる [確認済 — `cull/raster.vert`]
     */
    public void writeVisibility(VkBuffer target, int frameId, boolean[] visible,
                                boolean[] wasVisibleLastFrame) {
        long need = (long) this.sections.size() * 4;
        if (target.size() < need) {
            throw new IllegalArgumentException("visibility buffer too small: need " + need
                + " got " + target.size());
        }
        for (int i = 0; i < this.sections.size(); i++) {
            boolean v = visible == null || visible[i];
            int bit31 = (wasVisibleLastFrame != null && wasVisibleLastFrame[i]) ? 0x80000000 : 0;
            // 不可視は frameId と一致しない値にする。0 は frameId 0 と衝突しうるので使わない
            MemoryUtil.memPutInt(target.addr() + (long) i * 4,
                (v ? (frameId & 0x7fffffff) : ((frameId + 1) & 0x7fffffff)) | bit31);
        }
    }

    public void writePositions(VkBuffer target) {
        this.writePositions(target, null);
    }

    /**
     * セクション位置を<b>アンカーぶんずらして</b>書く。
     * ホスト (Minecraft) の中で、合成地形を<b>プレイヤーの近くに置く</b>ために使う。
     *
     * <h2>なぜ単純な足し算ではないのか — LoD レベル</h2>
     * 頂点位置は {@code quad_util.glsl} で
     *
     * <pre>(getLoDPosition(sPos) &lt;&lt; lodLevel) - baseSectionPos</pre>
     *
     * と解かれる。つまりセクション座標は<b>そのセクションの LoD 空間</b>にあり、
     * {@code baseSectionPos} は LoD 0 空間にある。したがってアンカーを足すときは
     * <b>{@code anchor >> level} を足さなければならない</b> —
     * そうしないと level &gt; 0 のセクションだけが
     * {@code anchor} のぶん余計にずれる (5c-1d でここを間違えると
     * <b>1 セクションだけ遠くへ飛ぶ</b>)。
     *
     * <p>⚠ <b>{@code anchor} は {@code 1 << maxLevel()} の倍数であること。</b>
     * そうでないと {@code anchor >> level} で切り捨てが起き、
     * <b>レベルの違うセクションどうしが相対位置を保たない</b> (見た目には
     * 「一部だけがカクつく」形で出る)。{@link #alignAnchor} が整える。
     *
     * @param anchor LoD 0 のセクション座標で表したずらし量。{@code null} ならずらさない
     */
    public void writePositions(VkBuffer target, int[] anchor) {
        long need = (long) this.sections.size() * 8;
        if (target.size() < need) {
            throw new IllegalArgumentException("position scratch too small: need " + need
                + " got " + target.size());
        }
        if (anchor != null && anchor.length != 3) {
            throw new IllegalArgumentException("anchor must have 3 elements");
        }
        long addr = target.addr();
        for (int i = 0; i < this.sections.size(); i++) {
            var s = this.sections.get(i);
            int x = s.x, y = s.y, z = s.z;
            if (anchor != null) {
                x += anchor[0] >> s.level;
                y += anchor[1] >> s.level;
                z += anchor[2] >> s.level;
            }
            MemoryUtil.memPutLong(addr + (long) i * 8, packPosition(x, y, z, s.level));
        }
    }

    /**
     * 同じセクションを<b>2 組</b>持つデータセットを作る (Phase 5c-1e)。
     *
     * <p>前半が 1 組目、後半が 2 組目。{@link #writePositionsPerSection} に
     * 組ごとに違うアンカーを渡すことで、<b>同じ地形を手前と奥に置く</b>ことができる。
     *
     * <h2>なぜ 2 組要るのか</h2>
     * 遮蔽の対照は<b>片側だけでは成立しない</b>。奥のものが隠れることを見ても、
     * 「全部隠れている」のか「奥だけ隠れている」のかが区別できない。
     * <b>手前が出ていて奥が隠れている</b>ことを同時に見て初めて、
     * 深度テストが<b>効いている</b>と言える [7 例目からの設計]。
     */
    public SyntheticTerrain duplicated() {
        return this.repeated(2);
    }

    /**
     * 同じ地形を {@code copies} 組ぶんつなげる。
     * {@link #writePositionsPerSection} と組み合わせて<b>別々の距離に置く</b>のに使う。
     */
    public SyntheticTerrain repeated(int copies) {
        if (copies < 1) throw new IllegalArgumentException("copies must be >= 1, got " + copies);
        var out = new SyntheticTerrain();
        for (int pass = 0; pass < copies; pass++) {
            for (var s : this.sections) {
                var copy = new Section(s.x, s.y, s.z, s.level).translucent(s.translucentCount);
                System.arraycopy(s.faceCounts, 0, copy.faceCounts, 0, s.faceCounts.length);
                copy.aabbOffset = s.aabbOffset;
                copy.aabbSize = s.aabbSize;
                out.add(copy);
            }
        }
        return out;
    }

    /**
     * セクションごとに<b>別々のアンカー</b>で位置を書く (Phase 5c-1e)。
     *
     * <p>{@link #duplicated()} と組み合わせて<b>同じ地形を手前と奥に置く</b>のに使う。
     * アンカーの適用は {@link #writePositions(VkBuffer, int[])} と同じ規則
     * ({@code anchor >> level}) である。
     *
     * @param anchorPerSection セクション数と同じ長さ。各要素は LoD 0 のセクション座標
     */
    public void writePositionsPerSection(VkBuffer target, int[][] anchorPerSection) {
        long need = (long) this.sections.size() * 8;
        if (target.size() < need) {
            throw new IllegalArgumentException("position scratch too small: need " + need
                + " got " + target.size());
        }
        if (anchorPerSection.length != this.sections.size()) {
            throw new IllegalArgumentException("expected " + this.sections.size()
                + " anchors but got " + anchorPerSection.length);
        }
        long addr = target.addr();
        for (int i = 0; i < this.sections.size(); i++) {
            var s = this.sections.get(i);
            int[] a = anchorPerSection[i];
            MemoryUtil.memPutLong(addr + (long) i * 8, packPosition(
                s.x + (a[0] >> s.level),
                s.y + (a[1] >> s.level),
                s.z + (a[2] >> s.level),
                s.level));
        }
    }

    /** データセットに含まれる最大の LoD レベル。 */
    public int maxLevel() {
        int m = 0;
        for (var s : this.sections) m = Math.max(m, s.level);
        return m;
    }

    /**
     * アンカーを {@code 1 << maxLevel()} の倍数に切り下げる。
     *
     * <p>これをやらないと {@code anchor >> level} の切り捨てで
     * <b>レベルの違うセクションの相対位置が崩れる</b> ({@link #writePositions} の注意)。
     * 切り下げるので、地形はプレイヤーに対して
     * <b>{@code 32 << maxLevel()} ブロック単位で追従する</b>ことになる。
     */
    public int[] alignAnchor(int[] anchor) {
        int q = 1 << this.maxLevel();
        return new int[]{
            Math.floorDiv(anchor[0], q) * q,
            Math.floorDiv(anchor[1], q) * q,
            Math.floorDiv(anchor[2], q) * q,
        };
    }

    // ---------------- visible footprint (基準データの不変条件) ----------------

    /**
     * quad がワールド空間で覆う矩形の同一性キー。
     *
     * <p>面の向き (normal 軸)・その軸上の位置・面内の 2 座標・大きさで決まる。
     * <b>2 つの quad がこれを共有すると、まったく同じ平面の同じ矩形を奪い合う。</b>
     *
     * @param normalAxis 0=X, 1=Y, 2=Z
     */
    public record Footprint(int normalAxis, long plane, long inPlaneA, long inPlaneB, int extent) {
        /** ワールド空間の 4 隅。画面上の重なりを調べるのに使う。 */
        public double[][] corners() {
            int a = (this.normalAxis + 1) % 3;
            int b = (this.normalAxis + 2) % 3;
            var out = new double[4][3];
            int[][] d = {{0, 0}, {1, 0}, {0, 1}, {1, 1}};
            for (int i = 0; i < 4; i++) {
                out[i][this.normalAxis] = this.plane;
                out[i][a] = this.inPlaneA + (long) d[i][0] * this.extent;
                out[i][b] = this.inPlaneB + (long) d[i][1] * this.extent;
            }
            return out;
        }
    }

    /**
     * 全 quad のワールド空間フットプリント (ジオメトリ順)。
     *
     * <h2>なぜこれを検査するのか — 規約</h2>
     * <b>基準データの各 quad は、別々の可視位置を占めていなければならない。</b>
     * 共有すると 2 つの性質が同時に壊れる:
     * <ol>
     *   <li><b>索引ずれが絵に出なくなる。</b> 隠れている quad と入れ替わっても
     *       絵が変わらないため、対照実験が静かに無力化される
     *       [確認済 — Stage 1 で実際に踏んだ。docs/phase4-stage1-completion.md 2.2]</li>
     *   <li><b>描画順に結果が依存する。</b> 同一平面の矩形は深度比較で決着せず、
     *       後に描いたほうが勝つ。Stage 1 (セクション順) と Stage 2b (面順) は
     *       <b>発行順が違う</b>ので、共平面があると
     *       「統合のバグではない差分」が出て比較が成立しなくなる</li>
     * </ol>
     *
     * <h2>なぜ「投影後」ではなくワールド空間なのか</h2>
     * 投影後の画素位置で重複を見ると、<b>遠くの quad が同じ画素に落ちただけで失敗する</b>。
     * それはカメラの性質であってデータの欠陥ではない。
     * 一方ワールド空間の重複は視点に依らないデータの性質で、上の 2 つを直接保証する。
     * 「選んだカメラで実際に分離できているか」は
     * {@code shiftingTheQuadIndexChangesTheImage} の対照実験が受け持つ。
     *
     * <h2>モデルデータに依らない理由</h2>
     * 面の縁の {@code EPSILON} 拡張と面サイズは全 quad で共通なので、
     * 同一性の比較では相殺する。残るのは面の向き・位置・LoD 倍率だけ
     * [確認済 — `quad_util.glsl` の {@code setupQuad} / {@code getQuadCornerPos} から導出]。
     */
    public List<Footprint> footprints() {
        var out = new ArrayList<Footprint>();
        for (int si = 0; si < this.sections.size(); si++) {
            var s = this.sections.get(si);
            int scale = 1 << s.level;
            long ox = (long) (s.x << s.level) * 32;
            long oy = (long) (s.y << s.level) * 32;
            long oz = (long) (s.z << s.level) * 32;
            long[] origin = {ox, oy, oz};

            int quads = s.totalQuads();
            for (int k = 0; k < quads; k++) {
                out.add(footprintOf(faceOfLocalQuad(s, k), k, origin, scale));
            }
        }
        return out;
    }

    /**
     * {@link #footprints()} と同じ並びで「その quad が半透明ランに属するか」を返す。
     *
     * <p>半透明はブレンドが順序依存で、しかもバケット内の描画順が非決定的なので、
     * <b>画面上で重なっていないこと</b>を確かめる必要がある
     * (docs/phase4-stage1-completion.md 5.1 の 2 本目の規約)。
     */
    public boolean[] translucentMask() {
        var out = new boolean[this.totalQuads()];
        int at = 0;
        for (var s : this.sections) {
            int quads = s.totalQuads();
            for (int k = 0; k < quads; k++) out[at + k] = k < s.translucentCount;
            at += quads;
        }
        return out;
    }

    /** セクション内 k 番目の quad の面。{@link #writeGeometry} のラン配置と一致させること。 */
    private static int faceOfLocalQuad(Section s, int k) {
        int i = k;
        if (i < s.translucentCount) return i % 6;              // 半透明ラン: 面を回す
        i -= s.translucentCount;
        int dbl = s.faceCounts[Face.DOUBLE_SIDED.bit];
        if (i < dbl) return i % 6;                             // 両面ラン: 面を回す
        i -= dbl;
        for (int f = 0; f < 6; f++) {
            if (i < s.faceCounts[f]) return f;
            i -= s.faceCounts[f];
        }
        throw new IndexOutOfBoundsException("quad " + k + " is beyond this section");
    }

    /**
     * {@code quad_util.glsl} の面オフセットを再現する [確認済]。
     *
     * <pre>
     * axis = face>>1   0 -> 法線 Y、面内 (X,Z)
     *                  1 -> 法線 Z、面内 (X,Y)
     *                  2 -> 法線 X、面内 (Y,Z)
     * </pre>
     * {@code swizzelDataAxis} が深度成分を送る先がそのまま法線軸になる。
     * 面の内外 ({@code face&1}) は法線軸の座標に +1 されるかどうかで効く
     * (indentation 0 の場合)。
     */
    private static Footprint footprintOf(int face, int k, long[] origin, int scale) {
        int[] pos = {k & 0x1F, (k >> 5) & 0x1F, (k >> 10) & 0x1F};   // px, py, pz
        int axis = face >> 1;
        int normalAxis = switch (axis) {
            case 0 -> 1;    // Y
            case 1 -> 2;    // Z
            default -> 0;   // X
        };
        // 法線軸の局所座標: その軸の位置 + (face&1)
        int localNormal = pos[normalAxis] + (face & 1);
        int a = (normalAxis + 1) % 3;
        int b = (normalAxis + 2) % 3;
        return new Footprint(normalAxis,
            origin[normalAxis] + (long) localNormal * scale,
            origin[a] + (long) pos[a] * scale,
            origin[b] + (long) pos[b] * scale,
            scale);
    }

    // ---------------- merged draw table (Stage 2) ----------------

    /**
     * 統合描画用テーブルの 1 エントリ = <b>(セクション, 面) のラン 1 本</b>。
     * Stage 1 の {@link OpaqueDraw} と 1 対 1 に対応するが、並び順が違う (面優先)。
     */
    public record MergedEntry(int quadStart, int drawId, int faceBit, int quadCount) {}

    /**
     * 面方向別 7 draws のうちの 1 本。
     *
     * @param quadOrdinalStart この面が占める quad 通し番号の先頭 ({@code baseVertex = *4})
     */
    public record FaceDraw(int faceBit, int quadOrdinalStart, int quadCount) {}

    /**
     * 統合描画テーブル。
     *
     * <h2>設計 — なぜ単一のグローバル prefix sum なのか</h2>
     * エントリを<b>面優先で並べ</b>、面ごとの区間が連続するようにしてある。
     * こうすると 7 本の draw を {@code baseVertex = 面の先頭 quad 通し番号 * 4} で
     * 撃ち分けるだけでよく、頂点シェーダは
     * <b>{@code gl_VertexIndex} からグローバルな quad 通し番号を直接得られる</b>。
     *
     * <p>面ごとに別のテーブルを持って {@code gl_DrawID} で切り替える案もあるが、
     * 組み込み変数を 1 つ増やすことになるうえ、
     * {@code baseVertex} は Stage 1 で既に使っている仕組みなので流用が効く。
     *
     * @param prefix         長さ {@code entries.size()+1}。末尾は総 quad 数 (番兵)
     * @param faceEntryStart 長さ 8。面 f のエントリは {@code [faceEntryStart[f], faceEntryStart[f+1])}
     */
    public record MergedTable(List<MergedEntry> entries, int[] prefix,
                              int[] faceEntryStart, List<FaceDraw> faceDraws) {

        public int totalQuads() { return this.prefix[this.prefix.length - 1]; }
        public int entryCount() { return this.entries.size(); }

        /**
         * CPU 側の参照実装 — <b>線形探索</b>で quad 通し番号を解決する。
         * GLSL の二分探索と突き合わせるための「別の方法で出した答え」。
         *
         * <p>長さ 0 のエントリは {@code prefix[e] == prefix[e+1]} なので条件を満たさず、
         * 自然に読み飛ばされる。
         */
        public int[] resolveLinear(int quadOrdinal) {
            for (int e = 0; e < this.entries.size(); e++) {
                if (quadOrdinal >= this.prefix[e] && quadOrdinal < this.prefix[e + 1]) {
                    var en = this.entries.get(e);
                    return new int[]{en.quadStart() + (quadOrdinal - this.prefix[e]), en.drawId()};
                }
            }
            throw new IndexOutOfBoundsException("quad ordinal " + quadOrdinal + " is outside the table");
        }

        /** 長さが 1 以上のエントリの数 (密なレイアウトの中で実際に描かれるもの)。 */
        public int nonEmptyEntryCount() {
            int n = 0;
            for (int e = 0; e < this.entries.size(); e++) {
                if (this.prefix[e + 1] > this.prefix[e]) n++;
            }
            return n;
        }
    }

    /**
     * 面優先に並べ替えたテーブルを作る。<b>GPU 版 {@code lod/vk/cmdgen.comp} と同じ規則。</b>
     *
     * <h2>密なレイアウト — なぜ空ランも載せるのか</h2>
     * エントリのスロットは <b>{@code face * sectionCount + i}</b> で固定する
     * ({@code i} は {@code indirectLookup} 内の添字)。
     * quad 数 0 の面もスロットを占め、長さ 0 のエントリになる。
     *
     * <p><b>GPU 側を決定的にするため</b>である。詰めて並べようとすると
     * {@code atomicAdd} でスロットを取り合うことになり、
     * <b>同じ入力でも実行ごとにエントリの並びが変わる</b>。
     * そうなると「CPU が作ったテーブルと GPU が作ったテーブルが一致するか」を
     * バイト単位で比べられなくなり、Stage 3 の検証手段が 1 つ失われる。
     * 密なら atomic が要らず、CPU と GPU が同じ配列を作る。
     *
     * <p>代償は配列が {@code 7 * sectionCount} 固定になることだが、
     * 20,000 セクションでも 1.1MB でありユニファイドメモリでは無視できる。
     *
     * <p><b>quad 通し番号から quad への対応は詰めた版とまったく同じになる</b>
     * [確認済 — 空エントリは prefix に平坦部を作るだけで、
     * 非空エントリの並びは変わらないため]。二分探索も平坦部を正しく飛ばす
     * ({@code prefix[lo] <= q < prefix[lo+1]} を満たす lo は必ず非空)。
     *
     * <p>面の順序は {@link Face} のビット順 (DOWN, UP, NORTH, SOUTH, WEST, EAST, DOUBLE_SIDED)。
     * <b>ジオメトリ内のラン配置 (両面が先) とは別物</b>なので混同しないこと。
     *
     * @param baseSectionPos 面方向マスクの基準 ({@link #faceMask})
     */
    public MergedTable mergedTable(int[] quadStarts, int[] baseSectionPos) {
        return this.mergedTable(quadStarts, baseSectionPos, null);
    }

    /**
     * @param visible セクションごとの可視フラグ。null なら全可視。
     *                GPU 版は {@code visibilityData[sid] == frameId} で同じ判定を行う
     */
    public MergedTable mergedTable(int[] quadStarts, int[] baseSectionPos, boolean[] visible) {
        return this.mergedTable(quadStarts, baseSectionPos, visible, null);
    }

    /**
     * @param wasVisibleLastFrame null なら不透明テーブル。非 null なら <b>temporal テーブル</b>で、
     *                            {@code true} のセクション (前フレームも可視だった) を落とす。
     *                            GPU 版 {@code cmdgen.comp} の {@code renderTemporally} と同じ判定
     */
    public MergedTable mergedTable(int[] quadStarts, int[] baseSectionPos,
                                   boolean[] visible, boolean[] wasVisibleLastFrame) {
        if (quadStarts.length != this.sections.size()) {
            throw new IllegalArgumentException("quadStarts has " + quadStarts.length
                + " entries but there are " + this.sections.size() + " sections");
        }
        int n = this.sections.size();
        // セクションごとに、面 -> ラン先頭 quad 番号 を先に出す
        int[][] runStart = new int[n][7];
        for (int si = 0; si < n; si++) {
            var s = this.sections.get(si);
            int ptr = quadStarts[si] + s.translucentCount;
            runStart[si][Face.DOUBLE_SIDED.bit] = ptr;
            ptr += s.faceCounts[Face.DOUBLE_SIDED.bit];
            for (int f = 0; f < 6; f++) {
                runStart[si][f] = ptr;
                ptr += s.faceCounts[f];
            }
        }

        var entries = new ArrayList<MergedEntry>(7 * n);
        var faceEntryStart = new int[8];
        var faceDraws = new ArrayList<FaceDraw>();
        int[] prefix = new int[7 * n + 1];
        int running = 0;

        for (int f = 0; f < 7; f++) {
            faceEntryStart[f] = entries.size();
            int faceOrdinalStart = running;
            for (int si = 0; si < n; si++) {
                var s = this.sections.get(si);
                boolean live = (visible == null || visible[si])
                    && (wasVisibleLastFrame == null || !wasVisibleLastFrame[si]);
                int msk = live ? faceMask(s, baseSectionPos) : 0;
                int count = (msk & (1 << f)) != 0 ? s.faceCounts[f] : 0;
                prefix[entries.size()] = running;
                entries.add(new MergedEntry(runStart[si][f], si, f, count));
                running += count;
            }
            faceDraws.add(new FaceDraw(f, faceOrdinalStart, running - faceOrdinalStart));
        }
        faceEntryStart[7] = entries.size();
        prefix[entries.size()] = running;       // 番兵

        return new MergedTable(List.copyOf(entries), prefix, faceEntryStart, List.copyOf(faceDraws));
    }

    /** エントリ 1 件のバイト数 (uvec2 {quadStart, drawId})。 */
    public static final int MERGED_ENTRY_SIZE = 8;

    /** エントリ配列を SSBO へ書く。 */
    public static void writeMergedEntries(VkBuffer target, MergedTable table) {
        long need = (long) table.entryCount() * MERGED_ENTRY_SIZE;
        if (target.size() < need) {
            throw new IllegalArgumentException("merged entry buffer too small: need " + need
                + " got " + target.size());
        }
        long addr = target.addr();
        for (int i = 0; i < table.entryCount(); i++) {
            var e = table.entries().get(i);
            MemoryUtil.memPutInt(addr + (long) i * MERGED_ENTRY_SIZE, e.quadStart());
            MemoryUtil.memPutInt(addr + (long) i * MERGED_ENTRY_SIZE + 4, e.drawId());
        }
    }

    /**
     * prefix 配列を SSBO へ書く。<b>先頭 4 バイトはエントリ数</b>
     * (GL 側 {@code IndirectSectionLookupBuffer} と同じ「先頭に個数」形式)。
     */
    public static void writeMergedPrefix(VkBuffer target, MergedTable table) {
        long need = 4L + (long) table.prefix().length * 4;
        if (target.size() < need) {
            throw new IllegalArgumentException("merged prefix buffer too small: need " + need
                + " got " + target.size());
        }
        long addr = target.addr();
        MemoryUtil.memPutInt(addr, table.entryCount());
        for (int i = 0; i < table.prefix().length; i++) {
            MemoryUtil.memPutInt(addr + 4L + (long) i * 4, table.prefix()[i]);
        }
    }

    /**
     * 面ごとの draw を間接バッファへ書く。
     *
     * <h2>共有インデックスバッファを超える面は分割する</h2>
     * 1 draw が使えるインデックスは共有バッファのぶんだけなので、
     * 面の quad 数が {@code indexQuadCapacity} (T) を超えたら
     * <b>{@code baseVertex} を進めて複数 draw に分ける</b>。
     *
     * <p>分割しても頂点シェーダは変わらない。{@code gl_VertexIndex = baseVertex + インデックス値}
     * で、{@code baseVertex} は 4 の倍数なので
     * {@code >>2} はグローバル quad 通し番号、{@code &3} は corner のままである
     * (詳細は {@link VkQuadIndexBuffer})。
     *
     * <p><b>quad 数 0 の面も 1 本出す。</b> そうしないと面と draw の対応が
     * データ依存になり、GPU 側と CPU 側で並びを揃えるのが面倒になる。
     * {@code instanceCount = 0} の間接描画は仕様上の no-op。
     *
     * <p>{@code firstIndex} は常に 0、{@code baseInstance} は使わない
     * (セクションはテーブル側の {@code drawId} で解決する)。
     *
     * @return 書いた draw の本数
     */
    public static int writeFaceDraws(VkBuffer target, MergedTable table, int indexQuadCapacity) {
        if (indexQuadCapacity <= 0) throw new IllegalArgumentException("T must be > 0");
        int count = faceDrawCount(table, indexQuadCapacity);
        long need = (long) count * DRAW_COMMAND_SIZE;
        if (target.size() < need) {
            throw new IllegalArgumentException("face draw buffer too small: need " + need
                + " got " + target.size() + " (" + count + " draws at T=" + indexQuadCapacity + ")");
        }
        long addr = target.addr();
        int out = 0;
        for (var d : table.faceDraws()) {
            int emitted = 0;
            for (int o = 0; o < d.quadCount(); o += indexQuadCapacity) {
                int n = Math.min(d.quadCount() - o, indexQuadCapacity);
                writeDrawCommand(addr, out++, n, d.quadOrdinalStart() + o);
                emitted++;
            }
            if (emitted == 0) writeDrawCommand(addr, out++, 0, d.quadOrdinalStart());
        }
        return out;
    }

    private static void writeDrawCommand(long addr, int index, int quadCount, int quadOrdinalStart) {
        long o = addr + (long) index * DRAW_COMMAND_SIZE;
        MemoryUtil.memPutInt(o,      quadCount * 6);            // indexCount
        MemoryUtil.memPutInt(o + 4,  quadCount == 0 ? 0 : 1);   // instanceCount
        MemoryUtil.memPutInt(o + 8,  0);                        // firstIndex
        MemoryUtil.memPutInt(o + 12, quadOrdinalStart << 2);    // vertexOffset
        MemoryUtil.memPutInt(o + 16, 0);                        // firstInstance
    }

    /** このテーブルを T で分割したときの draw 本数。 */
    public static int faceDrawCount(MergedTable table, int indexQuadCapacity) {
        int n = 0;
        for (var d : table.faceDraws()) {
            n += Math.max(1, (d.quadCount() + indexQuadCapacity - 1) / indexQuadCapacity);
        }
        return n;
    }

    /**
     * CPU 側が知りうる draw 本数の<b>上限</b>。
     *
     * <p>GPU がテーブルを作る経路では実際の quad 数が CPU から見えないため、
     * 間接描画に渡す本数はこの上限になる。余ったスロットは
     * {@code instanceCount = 0} で埋まる (no-op)。
     *
     * <p>各面は {@code ceil(faceQuads/T)} 本、面は 7 つなので
     * {@code sum ceil(x_i/T) <= 7 + (sum x_i)/T} が上限になる。
     *
     * @param quadUpperBound 総 quad 数の上限。本番ではジオメトリバッファの使用量から出せる
     */
    public static int maxFaceDrawCount(int quadUpperBound, int indexQuadCapacity) {
        return 7 + quadUpperBound / indexQuadCapacity;
    }

    // ---------------- boundary cases ----------------

    /**
     * <b>境界条件を狙って作ったデータセット。</b>
     * 実データでは滅多に出ないが、Stage 2b の索引ずれを狙い撃ちで検出できる。
     *
     * <ol>
     *   <li>セクション数 1 / quad 数 1 — 最小</li>
     *   <li>面が 1 方向だけ</li>
     *   <li>quad 数の大きな偏り — prefix sum の境界</li>
     *   <li>空セクション (quad 数 0) が混ざる — prefix が進まない区間</li>
     *   <li>quad 数が 2 の冪ちょうど — 二分探索の境界</li>
     * </ol>
     */
    public static SyntheticTerrain boundaryCases() {
        var t = new SyntheticTerrain();

        // ⑦ 全 6 面 + 両面 + 半透明 — 7 draw 種すべてを使う。
        //   ⚠ 面方向マスクを全部通せるのは relative == 0 のセクションだけなので、
        //     このセクションだけは (0,0,0) に置く必要がある [確認済 — faceMask]
        var all = new Section(0, 0, 0, 0).translucent(3);
        for (Face f : Face.values()) all.face(f, 2);
        t.add(all);

        // ① 最小: quad 1 枚だけ。UP は relative.y < 1 が要るので y は 0 以下に置く
        t.add(new Section(0, -1, 0, 0).face(Face.UP, 1));

        // ② 1 方向のみ (他の面は 0)。NORTH は relative.z > -1
        t.add(new Section(1, 0, 0, 0).face(Face.NORTH, 4));

        // ③ 空セクション — prefix が進まない区間を作る
        t.add(new Section(2, 0, 0, 0));

        // ④ 大きな偏り: 直前が 0、ここで一気に増える
        t.add(new Section(3, 0, 0, 0).face(Face.UP, 100).face(Face.DOWN, 1));

        // ⑤ 空セクションを挟んでもう一度
        t.add(new Section(4, 0, 0, 0));

        // ⑥ 二分探索の境界: 2 の冪ちょうど。EAST は relative.x < 1 が要るので x は 0
        t.add(new Section(0, 0, 5, 0).face(Face.EAST, 64));

        // ⑧ 負座標 (位置エンコードの符号拡張を踏む)。WEST は relative.x > -1
        t.add(new Section(0, -2, -3, 1).face(Face.WEST, 5));

        // ⑨ 面方向マスクで丸ごと落ちるセクション。
        //    EAST しか持たないのに relative.x = 5 なので 1 面も残らない。
        //    Stage 3 で GPU 側の msk が効いていることの対照になる
        t.add(new Section(5, 0, 0, 0).face(Face.EAST, 3));

        return t;
    }

    /**
     * 半透明パス用のデータセット。
     *
     * <p><b>距離バケットを複数踏む</b>ようセクションを x 方向に並べてある
     * (原点からの距離 0,1,2,3 → バケット 1023,1022,1021,1020)。
     * 各セクションは半透明と不透明の両方を持ち、
     * 「半透明だけ / 不透明だけ」に偏らないようにしている。
     *
     * <p>半透明 quad どうしが<b>画面上で重ならない</b>ことは
     * {@code VkTranslucentTest.translucentQuadsDoNotOverlapOnScreen} が常設で検査する。
     */
    public static SyntheticTerrain translucentCases() {
        // 半透明は **セクションあたり 1 枚** にしてある。同じセクションに何枚も置くと
        // 隣り合う格子点の quad が画面上で重なり、規約 2 を破る [確認済 — 実際に破った]。
        // セクションは 32 ブロック離れているので、1 枚ずつなら画面上でも離れる。
        //
        // (2,0,0) と (0,0,2) は原点からの距離が同じ = **同じバケットに 2 セクション入る**。
        // バケット内の並べ替え (atomicAdd の経路) を踏むために要る。
        return new SyntheticTerrain()
            .add(new Section(1, 0, 0, 0).translucent(1).face(Face.UP, 2))   // 距離 1
            .add(new Section(2, 0, 0, 0).translucent(1).face(Face.UP, 2))   // 距離 2
            .add(new Section(1, 1, 0, 0).translucent(1).face(Face.UP, 2))   // 距離 2 (同バケット)
            .add(new Section(3, 0, 0, 0).translucent(1).face(Face.UP, 2));  // 距離 3
    }

    /** 単一セクション・単一 quad の最小データ。 */
    public static SyntheticTerrain minimal() {
        return new SyntheticTerrain().add(new Section(0, 0, 0, 0).face(Face.UP, 1));
    }
}
