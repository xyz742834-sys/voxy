package me.cortex.voxy.client.core.model;

/**
 * <b>モデルアトラスの配置。GL 実装と Vulkan 実装が共有する唯一の計算。</b>
 *
 * <h2>なぜ切り出すのか</h2>
 * アトラスの配置は<b>3 箇所で一致していなければならない</b>:
 *
 * <ol>
 *   <li>GL のアップロード ({@code ModelFactory} の {@code nglTextureSubImage2D})</li>
 *   <li>Vulkan のアップロード ({@code VkTerrainResources})</li>
 *   <li><b>シェーダの UV 計算</b> ({@code quads.frag} の {@code 1.0/256.0})</li>
 * </ol>
 *
 * <p>同じ式を 3 回書くと、<b>1 つだけ直し忘れたときに絵が静かにずれる</b>。
 * Stage 1 でまさにその型の不具合を踏んでいる。
 * ここに集めておけば「両方の実装が同じものを作る」ことが<b>比較する以前に構造で保証される</b>。
 *
 * <h2>並び</h2>
 * <pre>
 * モデル 1 つ = 3 x 2 の面セル。面セルの一辺は {@link #FACE_TEXELS} (実物は 16)。
 * タイル格子は 256 x 256 で、シェーダがこの数を直書きしている。
 *
 *   タイル原点 X = (modelId & 0xFF)      * 3 * faceTexels
 *   タイル原点 Y = ((modelId >> 8) & 0xFF) * 2 * faceTexels
 * </pre>
 *
 * <p>面セルを 1 テクセルに縮めた<b>合成アトラス</b>も同じ格子を使う
 * ([docs/phase5c2a-completion.md] §2)。だから {@code faceTexels} を引数に取る。
 */
public final class ModelAtlasLayout {
    private ModelAtlasLayout() {}

    /** タイル格子の一辺。<b>シェーダが直書きしている数</b>なので動かせない。 */
    public static final int TILES = 256;
    /** モデル 1 つが占める面セルの列数 (面 6 種を 3x2 に並べる)。 */
    public static final int FACE_COLS = 3;
    /** 同・行数。 */
    public static final int FACE_ROWS = 2;

    /**
     * 実物の面セル一辺。{@code ModelFactory.MODEL_TEXTURE_SIZE} と同じ値である。
     * <b>あちらを変えたらここも変わる</b> — 一致は {@code ModelAtlasLayoutTest} が見張る。
     */
    public static final int FACE_TEXELS = 16;

    public static int atlasWidth(int faceTexels)  { return TILES * FACE_COLS * faceTexels; }
    public static int atlasHeight(int faceTexels) { return TILES * FACE_ROWS * faceTexels; }

    /**
     * ミップ段数。面セルが 1 テクセルになるまで。
     * 実物は {@code numberOfTrailingZeros(16) = 4} 段である
     * [確認済 — {@code RenderResourceReuse.getOrCreateModelStoreTextureAtlas}]。
     */
    public static int mipLevels(int faceTexels) {
        return Math.max(1, Integer.numberOfTrailingZeros(faceTexels));
    }

    /** タイル原点 X (ミップ 0)。 */
    public static int tileX(int modelId, int faceTexels) {
        return (modelId & 0xFF) * FACE_COLS * faceTexels;
    }

    /** タイル原点 Y (ミップ 0)。 */
    public static int tileY(int modelId, int faceTexels) {
        return ((modelId >> 8) & 0xFF) * FACE_ROWS * faceTexels;
    }

    /** ミップ {@code lvl} での面セル一辺。 */
    public static int cellTexels(int faceTexels, int lvl) {
        return Math.max(1, faceTexels >> lvl);
    }

    /** ミップ {@code lvl} でのタイル幅。 */
    public static int tileWidth(int faceTexels, int lvl) {
        return FACE_COLS * cellTexels(faceTexels, lvl);
    }

    /** ミップ {@code lvl} でのタイル高さ。 */
    public static int tileHeight(int faceTexels, int lvl) {
        return FACE_ROWS * cellTexels(faceTexels, lvl);
    }

    /**
     * CPU 側バッファの中で、ミップ {@code lvl} が始まるバイト位置。
     *
     * <p>ベイク結果は<b>ミップを連続して並べた 1 本のバッファ</b>である
     * [確認済 — {@code ModelFactory.ModelBakeResultUpload.texture}]。
     */
    public static long mipByteOffset(int faceTexels, int lvl) {
        long o = 0;
        for (int i = 0; i < lvl; i++) o += (long) tileWidth(faceTexels, i) * tileHeight(faceTexels, i) * 4L;
        return o;
    }

    /** 1 モデルが全ミップで占めるバイト数。 */
    public static long bytesPerModel(int faceTexels) {
        return mipByteOffset(faceTexels, mipLevels(faceTexels));
    }
}
