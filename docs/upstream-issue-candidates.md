# 上流 (Voxy 本家) への報告候補

このフォークで見つけた、**フォーク固有ではない**問題の一覧。
Vulkan 移植とは無関係に本家の利用者が踏むものだけをここに置く。

---

## 1. MC 26.2 で Vulkan バックエンドを選ぶと起動時クラッシュする — **修正済み (このフォーク)**

**影響**: MC 26.2 で `preferredGraphicsBackend:"vulkan"` を選んだ<b>全ユーザー</b>。
Voxy が入っているとゲームが起動しない。

**症状** [確認済 — 実機]:

```
java.lang.ExceptionInInitializerError
  at me.cortex.voxy.client.VoxyClient.initVoxyClient(VoxyClient.java:24)
  at com.mojang.blaze3d.systems.RenderSystem.handler$bbp000$voxy$injectInit
Caused by: java.lang.IllegalStateException: No GLCapabilities instance set for the current thread.
  at org.lwjgl.opengl.GL.getCapabilities(GL.java:273)
  at me.cortex.voxy.client.core.gl.Capabilities.<init>(Capabilities.java:58)
  at me.cortex.voxy.client.core.gl.Capabilities.<clinit>(Capabilities.java:36)
```

**原因**: `Capabilities` の `static final INSTANCE` 初期化が
`GL.getCapabilities()` を無条件に呼ぶ。MC が Vulkan バックエンドを選ぶと
GL コンテキストが存在しないので例外になり、`static` 初期化中の例外なので
`ExceptionInInitializerError` に化けて<b>ゲームごと落ちる</b>。

**なぜ自己無効化が効かないか**: Voxy は
`compute && indirectParameters` で `systemSupported` を判定して自己無効化する仕組みを
持っているが、<b>その判定より前に clinit が走る</b>。

**このフォークでの修正** (`Capabilities.java`):
GL コンテキストが取れなければ全機能 false の `Capabilities` を返し、
既存の `systemSupported` 判定に自己無効化を委ねる。
Phase 1 で `IrisUtil` をスタブ化したのと同じ形。

回帰テスト: `CapabilitiesWithoutGlTest` (素の JVM 4 件)。
**Gradle の test worker には GL コンテキストが無いので、再現条件そのもので走る。**

> 本家に出すときは Vulkan 移植の話を持ち込まず、
> 「MC 26.2 の Vulkan バックエンドで起動時クラッシュする」単体の修正として出せる。

---

## 2. `DebugRenderer.debugShader` はコンパイルできない — **未修正**

**影響**: 現状は顕在化しない (下記)。

**原因** [確認済 — `docs/phase2-binding-audit.md` §6]:

- `node.glsl:2` の `layout(binding = NODE_DATA_BINDING, ...)` に `#ifdef` ガードが無い
- `node_outline.vert:13` が `node.glsl` を import しているが、
  定義しているのは `NODE_DATA_INDEX` であって `NODE_DATA_BINDING` ではない
- `DebugRenderer` 側も `NODE_DATA_BINDING` を define していない

→ `NODE_DATA_BINDING` が未定義トークンのまま残り、整数定数として解釈できずコンパイルエラー。

**顕在化していない理由**: `DebugRenderer` は<b>どこからもインスタンス化されていない</b>
[確認済 — `grep -rn 'DebugRenderer'` が自ファイル以外 0 件]。

**このフォークでの扱い**: 修正していない。Vulkan 移植の対象外 (デバッグ機能)。
移植時に「動くはずのものが動かない」と誤認しないための記録として残している。

---

## 記録の方針

ここに載せるのは<b>本家の利用者が踏むもの</b>だけである。
macOS/Vulkan フォーク固有の判断・設計は各 phase の doc に置く。

---

## 3. トラバーサルのノードキューが<b>範囲外に読み書きする</b> — 未修正 (上流も TODO を残している)

**影響**: 階層トラバーサルを使う<b>全ユーザー</b>。
ノード数が確保したキュー容量を超えたときに発生する。

**場所**: `assets/voxy/shaders/lod/hierarchical/queue.glsl`

```glsl
//TODO: limit the size/writing out of bounds     <-- 上流のコメント
uint nodePushIndex = -1;
void pushNodesInit(uint nodeCount) {
    uint index = atomicAdd(nodeQueueMetadata[queueIdx+1].w, nodeCount);
    ...
    nodePushIndex = index;                        // 上限の検査が無い
}
void pushNode(uint nodeId) {
    nodeQueueSink[nodePushIndex++] = nodeId;      // <-- 範囲外書き込み
}
```

読み側も同じである:

```glsl
uint getCurrentNode() {
    if (nodeQueueMetadata[queueIdx].w <= gl_GlobalInvocationID.x) {
        return SENTINAL_OUT_OF_BOUNDS;
    }
    return nodeQueueSource[gl_GlobalInvocationID.x];   // <-- 個数は溢れても増え続ける
}
```

**なぜメタデータの個数が境界にならないか**: `nodeQueueMetadata[..].w` は
`atomicAdd` で<b>際限なく増える</b>。容量を超えても増え続けるので、
それを上限に使うと<b>確保していない領域を指す</b>。

**深刻度**: SSBO の範囲外アクセスは GL でも Vulkan でも未定義動作である。
⚠ **MoltenVK (macOS) では OS レベルの GPU リセットを起こした実績がある**
(このフォークの Phase 0、別の上限なしループで)。
`robustBufferAccess` が有効なら丸め込まれるが、<b>依存すべき挙動ではない</b>。

**このフォークでの対処**: `#ifdef VULKAN` の側にだけ上限を入れた。

- `pushNode` — `nodeQueueSink.length()` を超えたら捨てる
- `getCurrentNode` — `nodeQueueSource.length()` を超えたら番兵を返す
- 捨てた回数を数えて報告する (黙って切り捨てると「描かれない」と
  「選ばれなかった」が区別できなくなるため)

> **GL 側は上流のまま残してある。** この環境では GL 版を走らせられないので、
> 上限を入れて<b>壊していないことを確かめられない</b>。

**参考: 上限が要らなかった箇所**: 同じファイルの `enqueueChildren` のループは
`getChildCount` が `((flags >> 2)&7U)+1` = <b>3 ビット抽出なので 1..8 に収まる</b>。
データが壊れていても上限がある。<b>こちらには何も足していない。</b>
