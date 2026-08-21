package me.cortex.voxy.client.core.rendering.hierachical;

/**
 * <b>ノードの置き場所</b> — GL と Vulkan の分岐点 (Phase 5c-4b)。
 *
 * <h2>境界がこの形で出るのは 3 度目である</h2>
 * <table>
 *   <tr><th>段</th><th>境界</th><th>渡すもの</th></tr>
 *   <tr><td>5c-2b</td><td>{@code ModelUploadTarget}</td><td>ベイク済みのモデル</td></tr>
 *   <tr><td>5c-3b</td><td>{@code IGeometryManager}</td><td>メッシュ化済みのセクション</td></tr>
 *   <tr><td>5c-4b</td><td>ここ</td><td>更新のあったノード</td></tr>
 * </table>
 *
 * <p>いずれも<b>CPU で並べ終えたバッファを上げる 1 手</b>である。
 * Voxy の設計が元々そうなっているので、移植が素直に進んでいる。
 *
 * <h2>⚠ 既定実装を置かない</h2>
 * {@link #commitNodes} を空の既定にすると、新しい置き場所が<b>黙って忘れる</b> —
 * GL 側で忘れれば<b>何も GPU に届かない</b>。5c-2b で同じ判断をした。
 */
public interface NodeUploadTarget {
    /**
     * ノード {@code nodeIndex} の <b>16 バイト</b>を書く先のアドレス。
     *
     * <p>⚠ 返ったアドレスは<b>この呼び出しの直後に書ききること</b>。
     * GL 実装は転送ストリームの一時領域を返すので、次の呼び出しで無効になりうる。
     */
    long nodeWriteAddress(int nodeIndex);

    /**
     * 書き終わったあとに呼ぶ。
     *
     * <p>GL 実装はここで {@code UploadStream} を流す —
     * <b>呼ばないと何も GPU に届かない</b>。
     * Vulkan 実装はホスト可視のメモリに直に書くので何もしない。
     */
    void commitNodes();
}
