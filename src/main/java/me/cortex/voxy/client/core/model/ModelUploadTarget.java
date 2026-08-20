package me.cortex.voxy.client.core.model;

import me.cortex.voxy.common.util.MemoryBuffer;

/**
 * <b>ベイクしたモデルの置き場所</b> — GL と Vulkan の分岐点 (Phase 5c-2b)。
 *
 * <h2>境界をここに置いた理由</h2>
 * ベイクは<b>最後まで CPU 側で完結している</b>
 * [確認済 — {@code SoftwareModelTextureBakery} → {@code ColourDepthTextureData} →
 * {@code ModelBakeResultUpload} の {@code MemoryBuffer}]。
 * GPU に触れるのは<b>並べ終わったバッファを上げる 1 手だけ</b>である。
 *
 * <p>そこに境界を置くと:
 * <ul>
 *   <li>GL 実装と Vulkan 実装が<b>同じ入力</b>を受け取る — 両者が同じものを作っているか比べられる</li>
 *   <li>ベイクの側 ({@code ModelFactory}) は<b>一切変えなくてよい</b>。
 *       あちらは Minecraft のワールドに依存するので、触らないほうがよい</li>
 *   <li>アトラス内の配置は {@link ModelAtlasLayout} が<b>唯一の式</b>として持つので、
 *       実装が 2 つになっても<b>ずれようがない</b></li>
 * </ul>
 *
 * <h2>⚠ 参照仕様は壊さない</h2>
 * GL 実装 ({@code ModelStore}) は<b>そのまま残る</b>。ここでやっているのは
 * 「参照仕様を書き換える」ことではなく<b>分岐点を足す</b>ことである
 * (Phase 1 で {@code RenderPipelineFactory} に分岐を入れたときと同じ構造)。
 */
public interface ModelUploadTarget {
    /**
     * このフレームで上げるものがある、と分かった時点で呼ばれる
     * (<b>上げるものが 1 つも無いときは呼ばれない</b>)。
     *
     * <p>⚠ <b>既定実装を置かない</b>のは意図的である。GL 実装は
     * ここで生の GL のアンパック指定が要り、忘れると<b>アトラスが静かにずれる</b>。
     * 既定を空にすると、新しい置き場所が<b>黙って忘れられる</b>。
     */
    void beginUploads();

    /**
     * 上げ終わったあとに呼ばれる。
     *
     * <p>GL 実装はここで {@code UploadStream} を流す —
     * <b>呼ばないと何も GPU に届かない</b>。
     * Vulkan 実装はバッファを直に書くので、ここでは何もしない
     * (アトラスの転送は {@code recordUploads} が<b>フレームの中で</b>行う)。
     */
    void commitUploads();

    /**
     * モデルの属性 ({@code MODEL_SIZE} バイト) を {@code modelId} 番目に置く。
     *
     * @param model 呼び出し側が所有する。<b>この呼び出しの中で読み切ること</b>
     */
    void uploadModel(int modelId, MemoryBuffer model);

    /**
     * バイオーム色を {@code index} 番目 (4 バイト単位) から置く。
     * バイオーム色を持たないモデルでは呼ばれない。
     */
    void uploadBiomeColours(int index, MemoryBuffer colours);

    /**
     * バイオーム色の表を<b>先頭から丸ごと</b>置く。
     * バイオームが増えたときにまとめて張り替える経路である。
     */
    void uploadBiomeColourTable(MemoryBuffer colours);

    /**
     * モデルの属性のうち<b>バイオーム索引の 1 フィールドだけ</b>を書き換える。
     *
     * <p>⚠ 位置は {@code MODEL_SIZE * modelId + 4*6 + 4} である
     * [確認済 — {@code ModelFactory.BiomeUploadResult}]。
     * <b>属性全体を書き直すのではない</b> — 他のフィールドは既に入っている。
     */
    void patchModelBiomeIndex(int modelId, int biomeIndex);

    /**
     * モデルのテクスチャをアトラスへ置く。
     *
     * <p>{@code texture} は<b>ミップを連続して並べた 1 本のバッファ</b>で、
     * 各段の位置と大きさは {@link ModelAtlasLayout} が決める。
     *
     * @param mipLevels このモデルが持つミップ段数 (ミップ無しなら 1)
     */
    void uploadModelTexture(int modelId, MemoryBuffer texture, int mipLevels);
}
