package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.client.core.model.ModelAtlasLayout;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * 地形描画に必要なバッファとテクスチャ一式。
 * GL 側の {@code MDICViewport} (GlBuffer × 5) + {@code ModelStore} に相当する。
 *
 * <h2>GL 版との対応</h2>
 * <table>
 *   <tr><th>GL</th><th>ここ</th><th>binding</th></tr>
 *   <tr><td>{@code MDICViewport.drawCallBuffer}</td><td>{@link #drawCall}</td><td>間接 / SSBO 1</td></tr>
 *   <tr><td>{@code MDICViewport.drawCountCallBuffer}</td><td>{@link #drawCount}</td><td>間接 / SSBO 2</td></tr>
 *   <tr><td>{@code MDICViewport.positionScratchBuffer}</td><td>{@link #positionScratch}</td><td>SSBO 5</td></tr>
 *   <tr><td>{@code MDICViewport.indirectLookupBuffer}</td><td>{@link #indirectLookup}</td><td>SSBO</td></tr>
 *   <tr><td>{@code MDICViewport.visibilityBuffer}</td><td>{@link #visibility}</td><td>SSBO</td></tr>
 *   <tr><td>ジオメトリバッファ</td><td>{@link #geometry}</td><td>SSBO 1 (QuadBuffer)</td></tr>
 *   <tr><td>{@code sectionMetadataBuffer}</td><td>{@link #sectionMetadata}</td><td>SSBO</td></tr>
 *   <tr><td>{@code ModelStore.modelBuffer}</td><td>{@link #model}</td><td>SSBO 3</td></tr>
 *   <tr><td>{@code ModelStore.modelColourBuffer}</td><td>{@link #modelColour}</td><td>SSBO 4</td></tr>
 *   <tr><td>{@code ModelStore.textures}</td><td>{@link #atlas}</td><td>sampler 7</td></tr>
 * </table>
 *
 * <p>サイズは GL 版の実値ではなく<b>合成データに合わせて小さく取る</b>。
 * Stage 1 の目的は正しさの確認であり、容量ではない。
 */
public class VkTerrainResources {
    /** ブロックモデル 1 件のバイト数 [確認済 — `block_model.glsl` の 16 uint]。 */
    public static final int MODEL_SIZE = 64;

    /**
     * アトラスのモデルタイル格子。<b>256x256 で固定である</b> [確認済 — `quads.frag` の
     * {@code getBaseUV}]。フラグメントシェーダは
     * {@code vec2(modelId&0xFF, (modelId>>8)&0xFF) * (1/256)} をタイル原点とするため、
     * タイル格子の大きさはアトラスの画素数ではなく <b>modelId の分解の仕方</b>で決まる。
     */
    /** {@link ModelAtlasLayout#TILES} と同一。<b>式は 1 箇所</b>に置いてある。 */
    public static final int MODEL_TILES = ModelAtlasLayout.TILES;
    /**
     * 1 モデルタイル内の面セル格子。{@code getBaseUV} の
     * {@code vec2(face>>1, face&1) * (1/(vec2(3,2)*256))} に対応する。
     * セル (cx, cy) が面 {@code cx*2 + cy} に当たる。
     */
    public static final int FACE_COLS = ModelAtlasLayout.FACE_COLS;
    public static final int FACE_ROWS = ModelAtlasLayout.FACE_ROWS;

    /**
     * 合成アトラスの寸法。<b>面セルがちょうど 1 テクセルになるように選んである。</b>
     *
     * <p>当初は 256x256 の「16x16 タイル x 16px」で作っていたが、
     * <b>シェーダの UV 計算とまったく噛み合っていなかった</b>:
     * 上記のとおりタイル格子は 256x256 なので、256px のアトラスでは
     * 1 モデル = 1 テクセル、1 面 = 1/3 テクセルとなり、
     * <b>面どころかモデルすら分離できない</b>。
     * 768x512 にすると (modelId, face) の組に 1 テクセルが一意に対応し、
     * 描かれた色から索引を逆算できる ({@link #atlasTexel})。
     */
    public static final int ATLAS_W = MODEL_TILES * FACE_COLS;   // 768
    public static final int ATLAS_H = MODEL_TILES * FACE_ROWS;   // 512

    /**
     * アトラスの倍率 (Phase 5c-2a)。<b>タイル格子は変わらない</b> — 変わるのは
     * 1 面セルが何テクセルかだけである。
     *
     * <h2>なぜこれが「1 変数だけ変える」対照になるのか</h2>
     * シェーダの UV 計算は<b>タイル格子 256x256 を直書き</b>している
     * [{@code quads.frag} の {@code 1.0/256.0}]。合成アトラスは面セルを 1 テクセルに
     * 縮めただけで<b>格子は実物と同じ</b>なので、実寸に変えても
     * <b>同じ quad が同じ色で出なければならない</b>。
     *
     * <p>アトラスは<b>合成データと実データで挙動が変わることが既に分かっている
     * 唯一の入力</b>である [確認済 — Stage 1 で UV 計算と噛み合わない不具合を踏んだ]。
     * だから実データ投入の最初の一歩をここに置く。
     */
    public enum AtlasScale {
        /** 面セル 1 テクセル。768x512、ミップ無し。Phase 4 以来の既定。 */
        SMALL(1),
        /**
         * 面セル 16x16。<b>12288x8192、ミップ 4 段</b> — 実物と同じ寸法である
         * [確認済 — {@code RenderResourceReuse.getOrCreateModelStoreTextureAtlas}]。
         *
         * <p>⚠ 画像だけで約 534MB (ミップ込み) を占める。
         * <b>staging は全面ではなくモデル単位</b>にしてある (§recordAtlasUpload)。
         */
        REAL(ModelAtlasLayout.FACE_TEXELS);

        /** 1 面セルの一辺 (テクセル)。 */
        public final int facePx;
        /** ミップ段数。面セルが 1 テクセルになるまで。 */
        public final int mipLevels;

        AtlasScale(int facePx) {
            this.facePx = facePx;
            this.mipLevels = ModelAtlasLayout.mipLevels(facePx);
        }

        /** 1 モデル (3x2 面セル) が全ミップで占めるバイト数。 */
        public long bytesPerModel() { return ModelAtlasLayout.bytesPerModel(this.facePx); }

        public int width()  { return ModelAtlasLayout.atlasWidth(this.facePx); }
        public int height() { return ModelAtlasLayout.atlasHeight(this.facePx); }
    }




    public final VkBuffer uniform;
    public final VkBuffer geometry;
    public final VkBuffer sectionMetadata;
    public final VkBuffer model;
    public final VkBuffer modelColour;
    public final VkBuffer positionScratch;
    public final VkBuffer drawCall;
    public final VkBuffer drawCount;
    public final VkBuffer indirectLookup;
    public final VkBuffer visibility;
    /**
     * 共有 quad インデックスバッファ (32bit)。<b>所有しない</b> —
     * {@link VkQuadIndexBuffer} が容量ごとに 1 本だけ持ち回す。
     */
    public final VkQuadIndexBuffer index;
    /** 1 draw が覆える quad 数 (T)。これを超える面は複数 draw に分かれる。 */
    public final int indexQuadCapacity;

    // ---- Stage 2: 統合描画テーブル ----
    /** {@code (セクション, 面)} ラン 1 本ごとの {@code uvec2 {quadStart, drawId}}。binding 10。 */
    public final VkBuffer mergedEntry;
    /** 先頭 4 バイトがエントリ数、以降が prefix sum (末尾に総 quad 数の番兵)。binding 11。 */
    public final VkBuffer mergedPrefix;
    /** 面方向別 7 draws の間接コマンド。Stage 1 の {@link #drawCall} とは別に持つ。 */
    public final VkBuffer mergedDraw;
    /** {@code cmdgen} の間接ディスパッチサイズ。{@code prep} が書き、GPU が読む。 */
    public final VkBuffer mergedDispatch;

    // ---- Stage 4d: temporal ----
    /**
     * temporal テーブルの prefix。<b>エントリ配列は {@link #mergedEntry} と共有する</b> —
     * temporal は不透明とまったく同じランを、前フレーム不可視だったセクションに
     * 絞り込んだものなので、{@code (quadStart, drawId)} は 1 バイトも変わらない。
     * 違うのは quad 数 (= prefix) だけ [確認済 — `cmdgen.comp` の renderTemporally 経路]。
     */
    public final VkBuffer temporalPrefix;
    /** temporal の面ごとの DrawCommand。 */
    public final VkBuffer temporalDraw;

    // ---- Stage 4a: 半透明 ----
    /**
     * 距離バケットの計数 / 走査後はバケットごとのスロット開始位置。
     * <b>ちょうど 1024 uint</b> — {@code util/prefixsum/inital3.comp} がその形を前提にしている。
     */
    public final VkBuffer translucentBucket;
    /** {@code uint count; uint drawIds[];} — cmdgen が登録した半透明セクションの一覧。 */
    public final VkBuffer translucentList;
    /** バケット順に並べ替えた {@code uvec2 {quadStart, drawId}}。 */
    public final VkBuffer translucentEntry;
    /** {@code uint slotCount; uint prefix[];} — 走査前は quad 数、走査後は prefix。 */
    public final VkBuffer translucentPrefix;
    /** バケットごとの DrawCommand (1024 本)。 */
    public final VkBuffer translucentDraw;
    /** 診断用: 1 バケットの最大 quad 数。T を超えたかを CPU が確かめる。 */
    public final VkBuffer translucentStats;

    /** 距離バケット数。{@code TRANSLUCENT_WRITE_BASE} と同じ [確認済 — MDICSectionRenderer]。 */
    public static final int TRANSLUCENT_BUCKETS = 1024;

    public final VkTexture atlas;
    /** アトラスの倍率。{@link AtlasScale}。 */
    public final AtlasScale atlasScale;
    /** ライトマップ。GL 側は Minecraft のものを借りるので、ここでは合成する。 */
    public final VkTexture lightmap;

    /** モデルバッファに入るモデル数。stateId がこれを超えると範囲外読みになる。 */
    public final int maxModels;

    private final VkBuffer atlasStaging;
    private final VkBuffer lightmapStaging;

    public VkTerrainResources(int maxSections, int maxQuads, int maxDrawCommands) {
        this(maxSections, maxQuads, maxDrawCommands, 4096, VkQuadIndexBuffer.DEFAULT_QUAD_CAPACITY);
    }

    public VkTerrainResources(int maxSections, int maxQuads, int maxDrawCommands, int maxModels) {
        this(maxSections, maxQuads, maxDrawCommands, maxModels, VkQuadIndexBuffer.DEFAULT_QUAD_CAPACITY);
    }

    /**
     * @param indexQuadCapacity 共有インデックスバッファが覆う quad 数 (T)。
     *                          小さくすると面の分割が起きるので、テストが分割経路を踏める
     */
    public VkTerrainResources(int maxSections, int maxQuads, int maxDrawCommands,
                              int maxModels, int indexQuadCapacity) {
        this(maxSections, maxQuads, maxDrawCommands, maxModels, indexQuadCapacity, AtlasScale.SMALL);
    }

    /**
     * @param atlasScale アトラスの倍率。{@link AtlasScale#REAL} は<b>実物と同じ寸法</b>で、
     *                   絵が変わってはならない (Phase 5c-2a の対照)
     */
    public VkTerrainResources(int maxSections, int maxQuads, int maxDrawCommands,
                              int maxModels, int indexQuadCapacity, AtlasScale atlasScale) {
        this.atlasScale      = atlasScale;
        this.maxModels       = maxModels;
        this.indexQuadCapacity = indexQuadCapacity;
        this.uniform         = new VkBuffer(1024).zero().name("uniform");
        this.geometry        = new VkBuffer(Math.max(4096L, (long) maxQuads * 8)).zero().name("geometry");
        this.sectionMetadata = new VkBuffer(Math.max(4096L, (long) maxSections * 32)).zero().name("sectionMeta");
        this.model           = new VkBuffer((long) MODEL_SIZE * maxModels).zero().name("model");
        this.modelColour     = new VkBuffer(4L * 4096).zero().name("modelColour");
        this.positionScratch = new VkBuffer(Math.max(4096L, (long) maxDrawCommands * 8)).zero().name("positionScratch");
        // DrawCommand は 5 uint = 20 バイト [確認済 — bindings.glsl の struct]
        this.drawCall        = new VkBuffer(Math.max(4096L, (long) maxDrawCommands * 20)).zero().name("drawCall");
        this.drawCount       = new VkBuffer(1024).zero().name("drawCount");
        this.indirectLookup  = new VkBuffer(Math.max(4096L, (long) maxSections * 4 + 4)).zero().name("indirectLookup");
        this.visibility      = new VkBuffer(Math.max(4096L, (long) maxSections * 4)).zero().name("visibility");
        this.index           = VkQuadIndexBuffer.of(indexQuadCapacity);

        // エントリ数の上限はランの本数 = セクション数 x 7 面
        long maxEntries = Math.max(1L, (long) maxSections * 7);
        this.mergedEntry  = new VkBuffer(Math.max(4096L, maxEntries * 8)).zero().name("mergedEntry");
        this.mergedPrefix = new VkBuffer(Math.max(4096L, 4L + (maxEntries + 1) * 4)).zero().name("mergedPrefix");
        // 面 7 つ + 分割ぶん。上限は 7 + 総quad/T [SyntheticTerrain.maxFaceDrawCount と同じ式]
        long maxFaceDraws = 7L + (long) maxQuads / indexQuadCapacity;
        this.mergedDraw   = new VkBuffer(Math.max(4096L, maxFaceDraws * 20)).zero().name("mergedDraw");
        this.mergedDispatch = new VkBuffer(4096).zero().name("mergedDispatch");

        // temporal: 不透明と同じ形 (エントリ配列だけ共有する)
        this.temporalPrefix = new VkBuffer(Math.max(4096L, 4L + (maxEntries + 1) * 4)).zero().name("temporalPrefix");
        this.temporalDraw   = new VkBuffer(Math.max(4096L, maxFaceDraws * 20)).zero().name("temporalDraw");

        // 半透明: セクションごとに最大 1 エントリ
        long maxT = Math.max(1L, maxSections);
        this.translucentBucket = new VkBuffer(TRANSLUCENT_BUCKETS * 4L).zero().name("tBucket");
        this.translucentList   = new VkBuffer(Math.max(4096L, 4L + maxT * 4)).zero().name("tList");
        this.translucentEntry  = new VkBuffer(Math.max(4096L, maxT * 8)).zero().name("tEntry");
        this.translucentPrefix = new VkBuffer(Math.max(4096L, 4L + (maxT + 1) * 4)).zero().name("tPrefix");
        this.translucentDraw   = new VkBuffer(TRANSLUCENT_BUCKETS * 20L).zero().name("tDraw");
        this.translucentStats  = new VkBuffer(256).zero().name("tStats");

        // TRANSFER_SRC は読み戻し検証のために要る。これが無いと
        // vkCmdCopyImageToBuffer と TRANSFER_SRC_OPTIMAL への遷移が仕様違反になる
        // (MoltenVK は動いてしまうが、バリデーションが指摘する)。
        int texUsage = VK_IMAGE_USAGE_SAMPLED_BIT
                     | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                     | VK_IMAGE_USAGE_TRANSFER_SRC_BIT;
        this.atlas = new VkTexture(VK_FORMAT_R8G8B8A8_UNORM, atlasScale.mipLevels,
            atlasScale.width(), atlasScale.height(), texUsage).name("blockAtlas");
        this.lightmap = new VkTexture(VK_FORMAT_R8G8B8A8_UNORM, 1, 16, 16, texUsage)
            .name("lightmap");

        // staging はテクスチャごとに分ける。1 本を使い回すと、CPU の書き換えと
        // GPU のコピーに順序が付かず「後に書いた内容を両方が読む」ことになる。
        // ⚠ **全面ではなくモデル単位**。実寸のアトラスは 402MB あり、
        // 全面の staging を持つと host 側にもう 1 枚抱えることになる。
        // 本番も **モデル単位でアップロードしている** [確認済 — ModelFactory の
        // nglTextureSubImage2D] ので、こちらのほうが経路としても近い
        this.atlasStaging = new VkBuffer(Math.max(4096L, atlasScale.bytesPerModel() * maxModels))
            .name("atlasStaging");
        this.lightmapStaging = new VkBuffer(16L * 16 * 4).name("lightmapStaging");
        this.fillSyntheticAtlas();
        this.fillSyntheticLightmap();
    }

    // ---------------- synthetic atlas ----------------

    /**
     * <b>(modelId, face) の組から色が一意に決まるパターン</b>を書く。
     * 合成データの stateId と同じ思想で、<b>索引がずれたら絵に出る</b>ようにする。
     *
     * <p>テクセル 1 個が面セル 1 個に対応するので:
     * <ul>
     *   <li>R = modelId の下位 8bit</li>
     *   <li>G = modelId の上位 8bit</li>
     *   <li>B = 面 (0..5) を 40 刻みで符号化</li>
     * </ul>
     *
     * <p><b>描かれたピクセルから索引を逆算できる</b>のが狙い。
     * 面ごとの明度差 ({@code *_FACE_TINT}) が RGB 全体に掛かるため厳密な復号ではないが、
     * 「別のセクション/別の面が描かれたら別の色になる」ことは保証される。
     */
    private void fillSyntheticAtlas() {
        long addr = this.atlasStaging.addr();
        int facePx = this.atlasScale.facePx;
        long o = addr;
        // モデルごと、ミップごとに 3x2 の面セルを書く。
        // ⚠ **面セルの中は一様色**にする。そうするとミップを縮小で作っても
        // <b>色が変わらない</b>ので、「実寸にしても同じ絵」の主張がミップにも及ぶ
        for (int modelId = 0; modelId < this.maxModels; modelId++) {
            int tx = modelId & 0xFF, ty = (modelId >> 8) & 0xFF;
            for (int lvl = 0; lvl < this.atlasScale.mipLevels; lvl++) {
                int cell = Math.max(1, facePx >> lvl);
                int w = FACE_COLS * cell, h = FACE_ROWS * cell;
                for (int y = 0; y < h; y++) {
                    for (int x = 0; x < w; x++) {
                        int face = (x / cell) * FACE_ROWS + (y / cell);
                        MemoryUtil.memPutByte(o,     (byte) tx);
                        MemoryUtil.memPutByte(o + 1, (byte) ty);
                        MemoryUtil.memPutByte(o + 2, (byte) (face * 40 + 15));
                        MemoryUtil.memPutByte(o + 3, (byte) 255);
                        o += 4;
                    }
                }
            }
        }
    }

    /**
     * ある {@code (modelId, face)} が引くはずのテクセル色 (RGBA)。
     * テストが「シェーダが引いた色」と突き合わせるために使う。
     */
    public static int[] atlasTexel(int modelId, int face) {
        return new int[]{ modelId & 0xFF, (modelId >> 8) & 0xFF, face * 40 + 15, 255 };
    }

    /** アトラス内でその組が占めるテクセル座標。 */
    public static int[] atlasTexelPos(int modelId, int face) {
        return new int[]{
            (modelId & 0xFF) * FACE_COLS + (face >> 1),
            ((modelId >> 8) & 0xFF) * FACE_ROWS + (face & 1),
        };
    }

    /** ライトマップは一様な明るさにする (照明差で絵が変わらないように)。 */
    private void fillSyntheticLightmap() {
        long addr = this.lightmapStaging.addr();
        for (int i = 0; i < 16 * 16; i++) {
            long o = addr + (long) i * 4L;
            MemoryUtil.memPutByte(o,     (byte) 255);
            MemoryUtil.memPutByte(o + 1, (byte) 255);
            MemoryUtil.memPutByte(o + 2, (byte) 255);
            MemoryUtil.memPutByte(o + 3, (byte) 255);
        }
    }

    /**
     * アトラスとライトマップを GPU へ転送する記録を積む。
     * <b>ユニファイドメモリでもテクスチャは TILING_OPTIMAL なので転送が要る</b> —
     * バッファと違って直接書けない。
     */
    public void recordAtlasUpload(VkCommandBuffer cmd) {
        this.uploadImage(cmd, this.lightmapStaging, this.lightmap, 16, 16);
        // ⚠ 実データを焼いたときは**合成の中身で上書きしない**。
        // 順序は「焼いた分を転送 → 地形描画の recordUploads」なので、
        // ここで合成を流すと**焼いたタイルが消える**
        if (!this.externalAtlasContent) this.uploadAtlas(cmd);
    }

    /**
     * アトラスの中身を<b>外から入れる</b> (Phase 5c-2b の実データ経路)。
     * 以降 {@link #recordAtlasUpload} は合成の中身を流さない。
     */
    public void useExternalAtlasContent() { this.externalAtlasContent = true; }

    private boolean externalAtlasContent;

    /**
     * アトラスを<b>モデル単位・ミップ単位</b>で転送する。
     *
     * <p>本番と同じ粒度である [確認済 — {@code ModelFactory} が
     * {@code nglTextureSubImage2D} をモデルごと・レベルごとに呼ぶ]。
     * 実寸 (12288x8192) では全面の staging を持てないので、粒度を合わせる必要もある。
     *
     * <p>⚠ <b>{@code maxModels} より先のタイルは未定義のまま</b>である。
     * 本番も「ベイクしたモデルだけ」を上げるので同じだが、
     * <b>範囲外の stateId を引くと未定義の色が出る</b>
     * (バリデーションは捕まえない — 5c-3 の不変条件検査で見る)。
     */
    private void uploadAtlas(VkCommandBuffer cmd) {
        this.atlas.barrierAll(cmd, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);

        int facePx = this.atlasScale.facePx;
        int mips = this.atlasScale.mipLevels;
        // ⚠ **スタックに積んではならない。** 領域は maxModels * mips 個あり、
        // 既定のモデル数 (4096) だけで LWJGL の MemoryStack を超える
        // (実際に "Out of stack space" で test JVM を落とした)
        var regions = VkBufferImageCopy.calloc(this.maxModels * mips);
        try {
            long offset = 0;
            int i = 0;
            for (int modelId = 0; modelId < this.maxModels; modelId++) {
                int baseX = ModelAtlasLayout.tileX(modelId, facePx);
                int baseY = ModelAtlasLayout.tileY(modelId, facePx);
                for (int lvl = 0; lvl < mips; lvl++) {
                    int w = ModelAtlasLayout.tileWidth(facePx, lvl);
                    int h = ModelAtlasLayout.tileHeight(facePx, lvl);
                    var r = regions.get(i++);
                    r.bufferOffset(offset).bufferRowLength(0).bufferImageHeight(0);
                    r.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                        .mipLevel(lvl).baseArrayLayer(0).layerCount(1);
                    r.imageOffset().set(baseX >> lvl, baseY >> lvl, 0);
                    r.imageExtent().set(w, h, 1);
                    offset += (long) w * h * 4L;
                }
            }
            vkCmdCopyBufferToImage(cmd, this.atlasStaging.handle, this.atlas.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, regions);
        } finally {
            regions.free();
        }

        this.atlas.barrierAll(cmd, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT);
    }

    private void uploadImage(VkCommandBuffer cmd, VkBuffer staging, VkTexture tex, int w, int h) {
        tex.barrier(cmd, 0, VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL,
            VK_PIPELINE_STAGE_HOST_BIT, VK_ACCESS_HOST_WRITE_BIT,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT);

        try (MemoryStack stack = stackPush()) {
            var region = VkBufferImageCopy.calloc(1, stack)
                .bufferOffset(0).bufferRowLength(0).bufferImageHeight(0);
            region.imageSubresource()
                .aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
            region.imageOffset().set(0, 0, 0);
            region.imageExtent().set(w, h, 1);
            vkCmdCopyBufferToImage(cmd, staging.handle, tex.image,
                VK_IMAGE_LAYOUT_TRANSFER_DST_OPTIMAL, region);
        }

        tex.barrier(cmd, 0, VK_IMAGE_LAYOUT_SHADER_READ_ONLY_OPTIMAL,
            VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_WRITE_BIT,
            VK_PIPELINE_STAGE_VERTEX_SHADER_BIT | VK_PIPELINE_STAGE_FRAGMENT_SHADER_BIT,
            VK_ACCESS_SHADER_READ_BIT);
    }

    // ---------------- model data ----------------

    /**
     * 面全体を覆う面データ。サイズ 0..15 を 0,4,8,12 bit に置く。
     *
     * <p><b>indentation (16..21 bit) は 0 にする。</b>
     * {@code quad_util.glsl} は面ごとに {@code mix(depthOffset, 1-depthOffset, face&1)}
     * を使うので、0 のときに <b>-Y 面が y=0、+Y 面が y=1</b> という
     * 素直な立方体になる [確認済]。63 (= 1.0) を入れると各面が反対側に貼り付き、
     * 内外が裏返った箱になる。
     */
    private static final int FULL_FACE_DATA = (0) | (15 << 4) | (0 << 8) | (15 << 12);

    /** {@code flagsA} の bit 3。立てると面ごとの方向性ティント (`*_FACE_TINT`) が効く。 */
    public static final int MODEL_FLAG_SHADED = 8;

    /**
     * 最小限のブロックモデルを書く。全 6 面が面全体を覆う単純な立方体。
     *
     * @param modelId モデル番号
     * @param tileX   アトラス内タイル X ({@code customId} に入れるだけ。UV は modelId から決まる)
     * @param tileY   アトラス内タイル Y
     */
    public void writeSimpleModel(int modelId, int tileX, int tileY) {
        this.writeModel(modelId, 0);
        MemoryUtil.memPutInt(this.model.addr() + (long) modelId * MODEL_SIZE + 32,
            (tileY << 8) | tileX);                                // customId
    }

    /**
     * モデルを 1 件書く。
     *
     * @param flagsA {@code modelIsShaded} / {@code modelIsTranslucent} / {@code modelHasBiomeLUT}
     *               のビット。{@code colourTint} は {@code 0xFFFFFFFF} 固定なので
     *               biome LUT を立ててはいけない (colourData を引きにいく)
     */
    public void writeModel(int modelId, int flagsA) {
        if (modelId < 0 || modelId >= this.maxModels) {
            throw new IndexOutOfBoundsException("model " + modelId + " of " + this.maxModels);
        }
        long base = this.model.addr() + (long) modelId * MODEL_SIZE;
        for (int f = 0; f < 6; f++) {
            MemoryUtil.memPutInt(base + (long) f * 4, FULL_FACE_DATA);
        }
        MemoryUtil.memPutInt(base + 24, flagsA);
        // -1 は「ティントしない」の印 [確認済 — quad_util.glsl の `tintColour != uint(-1)`]
        MemoryUtil.memPutInt(base + 28, 0xFFFFFFFF);              // colourTint
        MemoryUtil.memPutInt(base + 32, 0);                       // customId
    }

    /**
     * 全モデルスロットを同一の立方体モデルで埋める。
     *
     * <p>合成データの stateId は疎に散らばるので、使われる番号だけを書くより
     * <b>全部埋めるほうが「未初期化のモデルを引いた」事故が起きない</b>。
     * モデル 1 件 64 バイトなので 8192 件でも 512KB で済む。
     */
    public void fillModels(int flagsA) {
        for (int i = 0; i < this.maxModels; i++) this.writeModel(i, flagsA);
    }

    public void free() {
        this.uniform.free();
        this.geometry.free();
        this.sectionMetadata.free();
        this.model.free();
        this.modelColour.free();
        this.positionScratch.free();
        this.drawCall.free();
        this.drawCount.free();
        this.indirectLookup.free();
        this.visibility.free();
        this.mergedEntry.free();
        this.mergedPrefix.free();
        this.mergedDraw.free();
        this.mergedDispatch.free();
        this.temporalPrefix.free();
        this.temporalDraw.free();
        this.translucentBucket.free();
        this.translucentList.free();
        this.translucentEntry.free();
        this.translucentPrefix.free();
        this.translucentDraw.free();
        this.translucentStats.free();
        this.atlas.free();
        this.lightmap.free();
        this.atlasStaging.free();
        this.lightmapStaging.free();
    }
}
