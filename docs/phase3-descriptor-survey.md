# Phase 3 準備調査 — compute パスの descriptor バインド変化

**目的**: 「シェーダごとに永続 descriptor set 1 つ」という Phase 2 の決定が
どこで足りなくなるかを、traversal 単体ではなく**全 compute パスを見て**判定する。

**調査方法**: 全ディスパッチ箇所と、その前後の `glBindBufferBase` /
`glBindBufferRange` / `glUniform*` を読んだ [確認済]。

---

## 1. 結論

**4 つのカテゴリに分かれた。traversal のフリップフロップは最も軽い問題で、
より厄介なものが 2 つ見つかった。**

| カテゴリ | 内容 | 該当シェーダ | 事前生成できるか |
|---|---|---|---|
| **A** | ディスパッチ中に変化しない | 7 | ✅ 1 set / (shader, viewport) |
| **B** | 有限個の構成を巡回 | 1 (traversal) | ✅ **3 set** で足りる |
| **C** | **真に動的** (リングバッファのオフセット) | 3 | ❌ **不可** |
| **D** | 明示 range 指定 (offset + size) | 4 | 実装が未対応 → ✅ 対応済 (§5) |

**確定した戦略と却下した代替案は §6.5 を参照。**

**カテゴリ C が新しい発見。** `UploadStream` のリングバッファから
毎回異なるオフセットで bind しており、組み合わせが有限にならない。

---

## 2. カテゴリ A — 静的 (1 set で足りる)

ディスパッチの直前にまとめて bind され、以降のディスパッチまで変化しない。
バッファは viewport かフィールドに固定。

| シェーダ | binding 構成 | スコープ |
|---|---|---|
| `prep` (MDIC) | UBO 0, SSBO 1,2 | viewport |
| `cmdgen` (MDIC) | UBO 0, SSBO 1〜7 (+8 統計時) | viewport |
| `prefixsum` (MDIC) | SSBO 0 (`distanceCountBuffer`) | **完全固定** |
| `translucentGen` (MDIC) | UBO 0, SSBO 1〜5 | viewport |
| `cull raster` (MDIC, 描画) | UBO 0, SSBO 1,2,3 | viewport |
| `sorter` (NodeCleaner) | SSBO 1,2 (Auto) + SSBO 3 | 実質固定 (§2.1) |
| `traversal` の静的部分 | SAMPLER 0, UBO 1, SSBO 2,4,6,9,(10) | viewport / フィールド |

### 2.1 `sorter` の binding 3 について

`NodeCleaner.tick(GlBuffer nodeDataBuffer)` の引数を binding 3 に bind している。
呼び出し元は `AbstractRenderPipeline:217` の
`this.nodeCleaner.tick(this.traversal.getNodeBuffer())` のみで、
`getNodeBuffer()` は `final` フィールドを返す [確認済]。
**実質的に固定**だが、シグネチャ上は引数なので set は tick ごとに検証すべき。

### 2.2 viewport スコープの注意

viewport は `ViewportSelector` が `HashMap` で動的に増やす
(既定 1 つ + Vivecraft の VR パスごと) [確認済]。
Iris 経路は Phase 1 で削除済みなので、実質は**通常 1 つ**。
ただし set は **(shader, viewport) の組で持つ**必要がある。

---

## 3. カテゴリ B — traversal のフリップフロップ (**3 構成**)

`HierarchicalOcclusionTraverser.doTraversal` [確認済 — 317〜344 行]:

```java
glUniform1ui(NODE_QUEUE_INDEX_BINDING, 0);
glBindBufferBase(SSBO, NODE_QUEUE_SOURCE_BINDING, this.topNodeIds.id);
glBindBufferBase(SSBO, NODE_QUEUE_SINK_BINDING,   this.scratchQueueB.id);
glDispatchCompute(firstDispatchSize, 1, 1);

for (int iter = 1; iter < MAX_ITERATIONS; iter++) {
    glUniform1ui(NODE_QUEUE_INDEX_BINDING, iter);
    glBindBufferBase(SSBO, NODE_QUEUE_SOURCE_BINDING, ((iter&1)==0 ? A : B).id);
    glBindBufferBase(SSBO, NODE_QUEUE_SINK_BINDING,   ((iter&1)==0 ? B : A).id);
    glDispatchComputeIndirect(iter * 16);
}
```

`MAX_ITERATIONS = WorldEngine.MAX_LOD_LAYER + 1 = **5**` [確認済]。

反復 0〜4 で現れる (SOURCE, SINK) の組は **3 通りだけ** [確認済]:

| 構成 | SOURCE | SINK | 使う反復 |
|---|---|---|---|
| 0 | `topNodeIds` | `scratchQueueB` | 0 |
| 1 | `scratchQueueB` | `scratchQueueA` | 1, 3 |
| 2 | `scratchQueueA` | `scratchQueueB` | 2, 4 |

**→ set を 3 つ事前生成して反復ごとに `vkCmdBindDescriptorSets` で切り替えれば足りる。**
更新は一切起きないので in-flight 問題も回避できる。

`queueIdx` (push constant) は反復ごとに値が変わるが、
push constant は**コマンドバッファに記録される**ため、
各反復で `vkCmdPushConstants` → `vkCmdDispatchIndirect` を順に記録すればよい。
**シャドウバッファ方式は成立するが、「ディスパッチ直前に一度だけ流す」ではなく
「反復ごとに flush を呼べる」形にする必要がある** (§6)。

---

## 4. カテゴリ C — 真に動的 (**事前生成できない**)

`UploadStream` のリングバッファを、**呼び出しごとに異なるオフセット**で bind している。
オフセットは `rawUploadAddress(size)` が返すリング内の位置であり、有限集合にならない。

| シェーダ | binding | bind 内容 | 箇所 |
|---|---|---|---|
| `batchClear` (NodeCleaner) | 1 | `UploadStream.getRawBufferId()` @ `addr` | `NodeCleaner:163` |
| `multiMemcpy` (AsyncNodeManager) | 0 | 同 @ `ptr` | `AsyncNodeManager:557` |
| `multiMemcpy` | 1 | 同 @ `ptr + upCopies` | `AsyncNodeManager:558` |
| `scatterWrite` (AsyncNodeManager) | 0 | 同 @ `ptr` | `AsyncNodeManager:583` |

```java
long ptr = UploadStream.INSTANCE.rawUploadAddress(streamSize);   // ← 毎回変わる
...
glBindBufferRange(GL_SHADER_STORAGE_BUFFER, 0,
    UploadStream.INSTANCE.getRawBufferId(), ptr, UploadStream.alignUpAlloc(streamSize));
```

さらに `updateIds` / メッシュアップロードは**1 フレーム内に複数回**呼ばれうる [推測 —
`AsyncNodeManager.tick` が結果セットごとに実行するため]。

### 4.1 ここは dynamic offset が素直に嵌まる

**重要な区別**: traversal (カテゴリ B) で dynamic offset を却下した理由は
「`scratchQueueA`/`B` を 1 バッファに統合するレイアウト変更が要るから」だった。

**カテゴリ C では、その理由が当てはまらない。**
`UploadStream` は**すでに単一のリングバッファ**であり、
GL 側も既に「1 つのバッファ + オフセット」として使っている [確認済]。
`VK_DESCRIPTOR_TYPE_STORAGE_BUFFER_DYNAMIC` に写すのに
**バッファレイアウトの変更は一切不要**で、
`vkCmdBindDescriptorSets` の `pDynamicOffsets` に `ptr` を渡すだけになる。

ただし以下は要検討 [未検証]:
- `minStorageBufferOffsetAlignment` と `UploadStream.BASE_ALLOCATION_ALIGNEMENT` の整合
- リフレクションで `STORAGE_BUFFER` と `STORAGE_BUFFER_DYNAMIC` を区別できない
  (SPIR-V 上は同一)。どの binding を dynamic にするかは**呼び出し側の宣言が要る**

### 4.2 代替案

| 案 | 長所 | 短所 |
|---|---|---|
| **dynamic offset** | レイアウト変更不要。set は 1 つで済む | どの binding を dynamic にするか宣言が要る |
| 呼び出しごとに set を確保 (フレーム単位でプール reset) | 宣言不要。素直 | 確保コストとプール管理。フレーム境界の定義が要る |
| push descriptor | set 管理が消える | MoltenVK 対応が未確認 [未検証] |

---

## 5. カテゴリ D — 明示 range 指定 (**現実装の不足**)

`glBindBufferRange(target, index, buffer, offset, size)` で
**同一バッファの別範囲**を複数 binding に割り当てている箇所がある。

| シェーダ | binding | offset | size |
|---|---|---:|---|
| `resultTransformer` (NodeCleaner) | 0 | `0` | `4 * OUTPUT_COUNT` |
| `resultTransformer` | 2 | `4 * OUTPUT_COUNT` | `8 * OUTPUT_COUNT` |

どちらも `this.outputBuffer` の別範囲 [確認済 — `NodeCleaner:119,121`]。

### 5.1 現実装のバグ

`VkAutoBindingShader.buffer()` は range を**バッファ末尾まで**と決め打ちしている:

```java
long range = buffer.size() - offset;
```

これは誤り。SSBO の**可変長配列の要素数は range から決まる**ため、
`resultTransformer` の binding 0 は本来 `4*OUTPUT_COUNT` バイトなのに
バッファ末尾まで見えてしまい、シェーダ側の
`data.length()` 相当が変わる。カテゴリ C の 4 箇所も同様に size 指定が必要。

**→ `ssbo(int index, VkBuffer buffer, long offset, long range)` を追加した。** ✅
`offset + range > buffer.size()` と `range <= 0` も例外にしている。

---

## 6. シャドウバッファ方式への影響

Phase 3 の決定「setter はシャドウバッファに書き、ディスパッチ直前に一度だけ流す」は、
**カテゴリ B / C では「一度だけ」が成立しない**。

- traversal: 反復ごとに `queueIdx` が変わる → 反復ごとに `vkCmdPushConstants`
- `batchClear` / `scatterWrite`: 呼び出しごとに `count` が変わる

**方式自体は維持できる**が、API は
「値を書く setter」と「コマンドバッファへ流す `flush(cmdBuf)`」を分け、
**flush を任意の回数呼べる**形にする必要がある。
「ディスパッチ直前に一度だけ」ではなく「各ディスパッチ記録の直前に毎回」。

---

## 6.5 確定した戦略 (2026-08-14 決定)

| カテゴリ | 戦略 | 実装状況 |
|---|---|---|
| **A** (7 シェーダ) | 永続 1 set / (shader, viewport)。Phase 2 の決定どおり | 実装済 |
| **B** (traversal) | **3 構成を事前生成**し `vkCmdBindDescriptorSets` で切り替え | ✅ 実装済 (§8.1) |
| **C** (3 シェーダ) | **dynamic offset** (`*_BUFFER_DYNAMIC`) | 基盤実装済 (下記) |
| **D** (明示 range 4 箇所) | `ssbo(index, buf, offset, range)` を追加 | ✅ 実装済 |

### 6.5.1 カテゴリ C で dynamic offset を採った理由

traversal (カテゴリ B) で dynamic offset を却下した理由は
「`scratchQueueA`/`B` を 1 バッファに統合するレイアウト変更が必要」だった。
**カテゴリ C にはこの理由が当てはまらない** — `UploadStream` はすでに単一リングバッファで、
GL 側も「1 バッファ + オフセット」として使っているため、変更すべきレイアウトが存在しない。

**アラインメントは確認済み。調整不要** [実測済]:

| 項目 | 値 |
|---|---|
| `minStorageBufferOffsetAlignment` (M4 Pro / MoltenVK) | **16** |
| `UploadStream.BASE_ALLOCATION_ALIGNEMENT` | `max(限界値, 16)` = **16** |

`UploadStream.rawUploadAddress` は**確保サイズを切り上げ**(`alignUp(size, BASE_ALLOCATION_ALIGNEMENT)`)、
オフセットはその総和として算出される [確認済 — `UploadStream:71,100-104`]。
`AllocationArena` 自体はアラインメント非対応 (`//TODO: add alignment support`) だが、
**全確保サイズがアラインされていればオフセットも帰納的にアラインされる**ため invariant は成立する。

→ **アロケータ側の調整は不要。**

### 6.5.2 却下した代替案 (記録用)

| 案 | 却下理由 |
|---|---|
| 呼び出しごとに set 確保 (フレーム単位でプール reset) | フレームあたりの呼び出し回数が読めず、**descriptor pool の枯渇管理**という新しい問題を持ち込む |
| push descriptor (`VK_KHR_push_descriptor`) | MoltenVK での可否が未確認 [未検証]。**調査コストが dynamic offset の実装コストを上回る**見込み |
| dynamic offset (カテゴリ B / traversal に対して) | `scratchQueueA`/`B` の統合というバッファレイアウト変更を伴う。Phase 4 の draw 統合でまたレイアウトを触るため、**同じ場所を二度触るのを避ける** |

### 6.5.3 実装した基盤

- `VkShader.Builder.dynamicBinding(int)` — どの binding を `*_DYNAMIC` にするか宣言する。
  **SPIR-V 上は通常の SSBO と同一表現でリフレクションから判別できない**ため、
  呼び出し側の宣言が必要
- `VkAutoBindingShader.dynamicSsbo(index, buf, baseOffset, range)` — 窓を登録
- `VkAutoBindingShader.setDynamicOffset(index, offset)` — 記録時のオフセット。
  **descriptor set の更新が起きないのでフレーム途中でも安全** (これが採用の主な利点)。
  アラインメント違反はここで例外にする
- `VkAutoBindingShader.bind(cmd, bindPoint)` — `pDynamicOffsets` を組み立てて bind

**未実装**: `UploadStream` 自体の Vulkan 版。上記を使う側であり Phase 3 本体の作業。

---

## 7. Phase 3 着手前にやること

1. **カテゴリ C の set 戦略を決める** (§4.2)。ここが決まらないと
   `AsyncNodeManager` / `NodeCleaner` が移植できない
2. **`VkAutoBindingShader` に明示 range を追加** (§5.1) — これは不足なので確定作業
3. `queue.glsl` の push constant 移行 (本来の Phase 3 の入口)
4. push constant API を「setter + 複数回 flush」の形にする (§6)

**カテゴリ A / B だけなら「構成ごとに set を事前生成」で足りる。
カテゴリ C だけが別の仕組みを要求している。**

---

## 8. 実装結果 (Phase 3)

### 8.1 カテゴリ B — 実装済 ✅

`VkDescriptorSetGroup` で 3 構成を事前生成し、`vkCmdBindDescriptorSets` で切り替える
[確認済 — `TraversalDispatchTest`]。

- 静的な binding (UBO 1, SSBO 2,3,4,6,9) は `shared(...)` で全 variant に書く
- フリップフロップする SOURCE(7) / SINK(8) だけ `variant(n, ...)` で個別に書く
- `update()` 前に `bind()` すると例外 — **記録後に descriptor を更新する事故を防ぐ**
  (descriptor set は記録時ではなく実行時に読まれるため、
  1 つの set を反復ごとに書き換えると先に記録したディスパッチも最後の内容を見てしまう)

`queueIdx` (push constant) は反復ごとに `vkCmdPushConstants` → 記録、の順で流す。

**未検証**: シェーダの実行結果。binding 0 の `hizDepthSampler` に割り当てる
画像リソースが無い (VkTexture 抽象が未実装) ため、テストでは
`vkCmdDispatch` を記録していない。ディスパッチするとバリデーションが
「descriptor が未更新」を毎回報告し、**本物の指摘を埋もれさせる**ため。
検証できているのは variant 切り替え・push constant 記録・バリア記述・サブミットまで。

### 8.2 カテゴリ C / D — 実装済 ✅

`VkUploadStream` / `VkDownloadStream` を参照 (docs/phase3-uploadstream-proposal.md)。

### 8.3 デッドコード削除 (計 341 行)

移植対象から外すのではなく削除した。到達不能であることを確認済み [確認済 — 外部参照 0 件]。

| ファイル | 行数 | 理由 |
|---|---:|---|
| `rendering/util/RawDownloadStream.java` | 97 | どこからも未参照 |
| `rendering/util/BufferArena.java` | 78 | `BasicSectionGeometryManager` からのみ参照 |
| `rendering/section/geometry/BasicSectionGeometryManager.java` | 166 | 未参照。実際に使われるのは `BasicSectionGeometryData` |

### 8.4 次のギャップ: 画像リソース抽象

`VkTexture` / `VkImageView` / `VkSampler` が無いため以下が保留:

- traversal の hiZ サンプラ (binding 0) → 実行検証ができない
- `VkAutoBindingShader.texture()` → 明示的に `UnsupportedOperationException`
- `VkDescriptorSetGroup` も buffer 専用 (image write 未対応)

バリア翻訳より前か後かは要判断。
