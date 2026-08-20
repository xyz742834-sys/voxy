# `glMemoryBarrier` 全数調査

> **⚠ 本ドキュメントの対応表 (§11) は従、`phase4-buffer-hazards.md` の
> アクセス系列表が主である。**
>
> §10.3 で「ヘルパが正しく分類が正しければ 22 箇所は正しい」と書いたが、
> **この前提は Stage 1 の洗い出しで崩れた**。GL のビットだけでは
> 正しい stage/access が決まらない箇所が 4 件見つかっている
> (`phase4-buffer-hazards.md` §10.2)。対応表は出発点として使い、
> **最終的な根拠はアクセス系列表に置くこと**。

**状態**: 調査完了。翻訳規則を確定 (§10)、対応表を作成 (§11)。
**適用は未実施** — 対象箇所がまだ Vulkan 側に存在しないため (§14)。

---

## 0. まず件数の訂正

**実際の呼び出しは 42 箇所ではなく 33 箇所** [確認済]。

`vulkan-port-feasibility.md` の「42 箇所」は `grep -c 'glMemoryBarrier'` の結果で、
**`import static ... glMemoryBarrier` の 7 行**と本ドキュメント自身の言及を含んでいた。
実際の呼び出しだけを数えると 33。

```bash
grep -rn 'glMemoryBarrier(' src/main/java | grep -v 'import ' | wc -l   # -> 33
```

| ファイル | 件数 |
|---|---:|
| `MDICSectionRenderer` | 11 |
| `NodeCleaner` | 5 |
| `HierarchicalOcclusionTraverser` | 5 |
| `AsyncNodeManager` | 4 |
| `UploadStream` | 2 |
| `DownloadStream` | 2 |
| `GPUTiming` | 1 |
| `HiZBuffer` | 1 |
| `DebugRenderer` | 1 |
| `AbstractRenderPipeline` | 1 |

加えて `glTextureBarrier()` が 1 箇所 (`HiZBuffer:102`)。これも翻訳対象。

---

## 1. 結論 — パターンは 10 種類、上位 2 種で 3 分の 2

**軸 2 (同一パターンの重複) の答えが最も重要**: 33 箇所は 33 通りではない。

| # | バリアビット | 件数 | 割合 |
|---|---|---:|---:|
| **P1** | `SHADER_STORAGE` のみ | **13** | 39% |
| **P2** | `SHADER_STORAGE｜COMMAND` | **9** | 27% |
| P3 | `BUFFER_UPDATE` のみ | 3 | 9% |
| P4 | `UNIFORM｜SHADER_STORAGE` | 2 | 6% |
| P5 | `COMMAND｜SHADER_STORAGE｜UNIFORM` | 1 | 3% |
| P6 | `SHADER_STORAGE｜COMMAND｜BUFFER_UPDATE` | 1 | 3% |
| P7 | `CLIENT_MAPPED_BUFFER｜BUFFER_UPDATE` | 1 | 3% |
| P8 | `FRAMEBUFFER｜TEXTURE_FETCH` | 1 | 3% |
| P9 | `FRAMEBUFFER｜PIXEL_BUFFER` | 1 | 3% |
| P10 | `-1` (全ビット) | 1 | 3% |

**P1 + P2 で 22 箇所 (67%)。** さらに P3/P7 (計 4 箇所) は
ストリーム再設計で消える見込み (§5)。

> **P2 の内訳**: ソース上は 2 通りの書き順で現れる
> (`SHADER_STORAGE｜COMMAND` が 5、`COMMAND｜SHADER_STORAGE` が 4) が、
> **ビット集合としては同一**なので 1 パターンとして数えている [確認済]。

実測での分布 (再現可能):

```bash
grep -rhn 'glMemoryBarrier(' src/main/java | grep -v 'import ' \
 | sed 's/.*glMemoryBarrier(//' | sed 's/).*//' | sed 's/ //g' | sort | uniq -c | sort -rn
```

→ **実質的に意図を読み解く必要があるのは 10 箇所前後** [推測]。

---

## 2. P1: `SHADER_STORAGE_BARRIER_BIT` のみ (13 箇所)

すべて **compute dispatch → 次の compute dispatch** の SSBO 依存。

| 場所 | 直前 | 直後 | 文脈 |
|---|---|---|---|
| `MDIC:281` | (バインド) | prep dispatch | 毎フレーム |
| `MDIC:283` | prep dispatch | cull draw | 毎フレーム |
| `MDIC:334` | (バインド) | cmdgen dispatchIndirect | 毎フレーム |
| `MDIC:356` | (バインド) | prefixsum dispatch | 毎フレーム **(意図不明 §6.1)** |
| `MDIC:358` | prefixsum dispatch | translucentGen | 毎フレーム |
| `NodeCleaner:114` | (バインド) | sorter dispatch | 条件付き (`shouldCleanGeometry`) |
| `NodeCleaner:125` | sorter dispatch | resultTransformer dispatch | 同上 |
| `NodeCleaner:127` | resultTransformer | DownloadStream.download | 同上 |
| `NodeCleaner:162` | (アップロード) | batchClear dispatch | 条件付き (`updateIds`) |
| `NodeCleaner:164` | batchClear dispatch | (後続) | 同上 |
| `AsyncNodeManager:565` | (アップロード) | multiMemcpy dispatch | 条件付き |
| `AsyncNodeManager:567` | multiMemcpy dispatch | (後続) | 同上 |
| `Traverser:351` | traversal dispatch | DownloadStream.download | 毎フレーム |

**翻訳の自明さ: 高い** [推測]。ほぼすべて

```
srcStage  = COMPUTE_SHADER,  srcAccess = SHADER_WRITE
dstStage  = COMPUTE_SHADER,  dstAccess = SHADER_READ | SHADER_WRITE
```

例外は `NodeCleaner:127` と `Traverser:351` で、**直後がダウンロード**のため
`dstStage = TRANSFER`, `dstAccess = TRANSFER_READ` になる
(`VkDownloadStream.download` が既に発行しているものと重複する可能性あり — 要確認 [未検証])。

---

## 3. P2: `SHADER_STORAGE｜COMMAND` (9 箇所)

すべて **compute が書いた indirect コマンドを、直後の draw / dispatch が読む**。

| 場所 | 直後の間接コマンド |
|---|---|
| `MDIC:209` | `glMultiDrawElementsIndirectCountARB` (不透明/temporal) |
| `MDIC:254` | `glMultiDrawElementsIndirectCountARB` (半透明) |
| `MDIC:304` | `glDrawElementsIndirect` (オクルージョンカリング) |
| `MDIC:336` | `glDispatchComputeIndirect` (cmdgen) |
| `MDIC:371` | `glDispatchComputeIndirect` (translucentGen) |
| `Traverser:330` | `glDispatchCompute` (第 1 反復) |
| `Traverser:340` | `glDispatchComputeIndirect` (反復ループ内) |
| `Traverser:346` | (ループ終了後) |
| `DebugRenderer:66` | `glDrawElementsIndirect` (未使用パス) |

**翻訳の自明さ: 高い** [推測]。

```
srcStage  = COMPUTE_SHADER,  srcAccess = SHADER_WRITE
dstStage  = DRAW_INDIRECT | COMPUTE_SHADER (または VERTEX_SHADER/FRAGMENT_SHADER)
dstAccess = INDIRECT_COMMAND_READ | SHADER_READ
```

`COMMAND_BARRIER_BIT` が `INDIRECT_COMMAND_READ` に、
`SHADER_STORAGE_BARRIER_BIT` が `SHADER_READ` に素直に対応する。

**注意**: `MDIC:209` / `MDIC:254` のコメントは
`//Barrier everything is needed` で、**どのバッファが対象かを特定していない**。
直後の描画は SSBO (ジオメトリ/メタデータ/位置) と間接バッファの両方を読むため、
両方を含める必要がある [推測]。

`Traverser:346` は**ループを抜けた直後**で、直後の読み手がこの関数内に無い。
呼び出し元 (`doTraversal` → `AbstractRenderPipeline`) を跨ぐため、
**単独では意図が読み取れない** (§6.2)。

---

## 4. 画像バリアになる箇所 — **2 箇所だけ**

軸 4 の答え。レイアウト遷移を伴うのは以下のみ。

### 4.1 `HiZBuffer:102-103` — `glTextureBarrier()` + `FRAMEBUFFER｜TEXTURE_FETCH`

```java
glDrawArrays(GL_TRIANGLE_FAN, 0, 4);          // level i に書く
glTextureBarrier();
glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT|GL_TEXTURE_FETCH_BARRIER_BIT);
glTextureParameteri(this.texture.id, GL_TEXTURE_BASE_LEVEL, i);   // 次は level i を読む
```

**`VkTexture.barrier()` で扱える** [確認済 — `VkTextureTest.differentLevelsHoldDifferentLayouts`
が同じ形を再現済み]。

```
level i:   DEPTH_STENCIL_ATTACHMENT_OPTIMAL -> SHADER_READ_ONLY_OPTIMAL
srcStage = LATE_FRAGMENT_TESTS, srcAccess = DEPTH_STENCIL_ATTACHMENT_WRITE
dstStage = FRAGMENT_SHADER,     dstAccess = SHADER_READ
```

`glTextureBarrier()` は「同一テクスチャへの描画と読み出しの間の順序保証」で、
Vulkan では上記のレイアウト遷移バリアに吸収される [推測]。

### 4.2 `AbstractRenderPipeline:222` — `FRAMEBUFFER｜PIXEL_BUFFER`

```java
this.nodeManager.tick(...);
this.nodeCleaner.tick(...);
glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT);
this.traversal.doTraversal(viewport);
```

**Phase 5 (interop) との関係**: この関数 `innerPrimaryWork` は
Phase 1 で「`initDepthStencil` は interop の合成パスと不可分」と判断した
`AbstractRenderPipeline` の中にある。

ただし**このバリア自体は合成パスではない** [確認済 — `initDepthStencil` は
`setup()` から呼ばれる別経路]。位置的には
「ノード更新 (アップロード/ダウンロードを含む) → トラバーサル」の境界にある。

`GL_FRAMEBUFFER_BARRIER_BIT` が何を指しているかは**この文脈からは読み取れない** —
直前の `nodeManager.tick` / `nodeCleaner.tick` はフレームバッファに書かないため。
`GL_PIXEL_BUFFER_BARRIER_BIT` も同様に、直前にピクセルバッファ操作が見当たらない。
→ §6.3 に分類。

---

## 5. ストリーム再設計で消える箇所 (4 箇所)

| 場所 | ビット | 消える理由 |
|---|---|---|
| `UploadStream:124` | `BUFFER_UPDATE` | `commit()` の `glCopyNamedBufferSubData` 前。**転送自体が消える** (モード 1 は対象へ直接書く) |
| `UploadStream:131` | `BUFFER_UPDATE` | 同上。コピー後の可視化 |
| `DownloadStream:110` | `BUFFER_UPDATE` | コピー前。`VkDownloadStream` が `vkCmdPipelineBarrier` を明示発行する形に置換済み |
| `DownloadStream:115` | `CLIENT_MAPPED_BUFFER｜BUFFER_UPDATE` | ホスト可視化。`HOST_READ` バリアとして `VkDownloadStream` に実装済み |

**これらは翻訳ではなく削除**になる [確認済 — `VkUploadStream` / `VkDownloadStream` は既に
この構造で実装済み]。

`UploadStream:131` のコメントが示唆的:

```java
glMemoryBarrier(GL_BUFFER_UPDATE_BARRIER_BIT);
//|GL_SHADER_STORAGE_BARRIER_BIT|GL_UNIFORM_BARRIER_BIT
//expected + other barriers which may cause issues if not
```

**GL 側でも必要なビットが確定していない**ことを示している。

---

## 6. 意図が読み取れない / 曖昧な箇所

**軸 3 (`GL_ALL_BARRIER_BITS` 相当) と、それに準じるもの。**

### 6.1 `MDIC:356` — コメントが「不明」と明言

```java
this.prefixSumShader.bind();
glBindBufferBase(GL_SHADER_STORAGE_BUFFER, 0, this.distanceCountBuffer.id);
glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);//Am unsure if is needed
glDispatchCompute(1,1,1);
```

**原作者が必要性を判断できていない。** 直前の `cmdgen` が
`distanceCountBuffer` (binding 7) に `atomicAdd` で書いているので
**必要である可能性が高い** [推測] が、確証はない。

### 6.2 `Traverser:346` — 読み手が関数外

ループを抜けた直後のバリア。直後の読み手がこの関数内に存在せず、
呼び出し元 (`AbstractRenderPipeline.innerPrimaryWork` のループ、
あるいはその後の `buildDrawCalls`) を跨ぐ。
**単独では対象が特定できない。**

### 6.3 `AbstractRenderPipeline:222` — ビットと文脈が噛み合わない

§4.2 の通り。`FRAMEBUFFER` / `PIXEL_BUFFER` のどちらも、
直前の操作 (ノード更新) と対応が取れない。
**歴史的な残骸である可能性がある** [推測 — 検証していない]。

### 6.4 `GPUTiming:191` — `glMemoryBarrier(-1)` = 全ビット

```java
glQueryCounter(this.query, GL_TIMESTAMP);
glFinish();                                              // ← CPU 待ち
glGetQueryBufferObjectui64v(this.query, this.store.id, GL_QUERY_RESULT_NO_WAIT, slot*8L);
glMemoryBarrier(-1);                                     // ← 全ビット
```

**軸 3 に該当する唯一の箇所。** `-1` は `GL_ALL_BARRIER_BITS`。

直後にこの結果を読むのは `download()` の `DownloadStream` 経由なので、
実際に必要なのは `BUFFER_UPDATE` 相当だけと思われる [推測]。
ただし `glFinish()` との併用 (§7) から、
**そもそも同期の意図が整理されていない**可能性がある。

---

## 7. `glFinish` との併用 — 1 箇所 (`GPUTiming:189-191`)

**`glMemoryBarrier` と `glFinish` を隣接して使っているのはここだけ** [確認済]。

- `glMemoryBarrier` = 同一コンテキスト内の**順序保証** (GPU 側)
- `glFinish` = **CPU 待ち** (全コマンド完了までブロック)

両方を並べているのは「クエリ結果が確実に書かれてから読みたい」意図と読める [推測] が、
`glFinish` が既に全完了を保証しているため **`glMemoryBarrier(-1)` は冗長**に見える。

**Vulkan では意味が全く異なる**:
- `glFinish` → `vkQueueWaitIdle` / fence 待ち → **in-flight = 1 では `beginFrame()` が既に行う**
- `glMemoryBarrier` → `vkCmdPipelineBarrier` → コマンドバッファ内に記録

したがって GPUTiming の翻訳では
**`glFinish` は消え、クエリプール結果の取得タイミングがフレーム境界に移る** [推測]。
`VkQueryPool` + `vkGetQueryPoolResults` への置換と一体で設計する必要がある。

なお `glFinish` は他に 16 箇所あるが [確認済 — §7 の対象外]、
いずれも `glMemoryBarrier` と隣接しておらず、
リソース解放前の安全策かストリームの枯渇処理である。

---

## 8. 翻訳の自明さによる分類 (軸 1)

| 区分 | 件数 | 内容 |
|---|---:|---|
| **A. ビットから一意に決まる** | 22 | P1 (13) + P2 (9)。前後の dispatch/draw から stage/access が決まる |
| **B. 翻訳ではなく削除** | 4 | §5 のストリーム 4 箇所 |
| **C. 文脈を読めば決まる** | 3 | `MDIC:369` (P5), `Traverser:325` (P6), `AsyncNodeManager:587/589` (P4) のうち 3 |
| **D. 意図が読み取れない** | 4 | §6.1〜6.4 |

※ C の内訳は P4 が 2 件・P5 が 1 件・P6 が 1 件で計 4 件だが、
`AsyncNodeManager:587/589` は同一パターンの対なので実質 3 パターン。

---

## 9. 次の判断に必要な材料 (今回は決めない)

- **翻訳方針**: A (22 箇所) は定型化できるので、ヘルパ API を作れば
  ほぼ機械的に書ける [推測]。D (4 箇所) だけ個別判断が要る
- **順序**: B は既に済んでいる。A のうち compute → compute (P1) が最も安全に着手できる
- **ヘルパの形**: P1 / P2 が 22 箇所を占めるので、
  この 2 つに専用の短い API を用意する価値がある [推測]
- **D の扱い**: 保守的に倒す (全ビット相当) か、実機で削って様子を見るか。
  同期バリデーションが検出してくれるので**削って試す**ことも可能 [推測]

---

## 10. 翻訳規則の検証 (主軸の担保)

同期バリデーションが機能しない以上 (§11)、**正しさは実行時検証ではなく
翻訳規則そのものの正しさで担保する**。ここでは 2 つのヘルパを仕様と照合する。

### 10.1 P1 `computeToCompute`

**GL の意味論** — `GL_SHADER_STORAGE_BARRIER_BIT`:

> Accesses to shader storage blocks after the barrier will reflect writes
> prior to the barrier.

つまり「バリア以前の書き込みが、以後の**アクセス (読みも書きも)** から見える」。
Voxy の該当 13 箇所では生産者・消費者ともに compute dispatch [確認済 — §2]。

**翻訳**:

| | 値 | 根拠 |
|---|---|---|
| `srcStage` | `COMPUTE_SHADER` | 書き手は compute dispatch |
| `srcAccess` | `SHADER_STORAGE_WRITE` | SSBO への書き込み。`SHADER_WRITE` ではなく**より狭い**こちらを使う (GL 側もストレージブロック限定のため) |
| `dstStage` | `COMPUTE_SHADER` | 読み手も compute dispatch |
| `dstAccess` | `SHADER_STORAGE_READ｜SHADER_STORAGE_WRITE` | GL は「以後のアクセス」であり読み書き両方。WRITE を含めることで WAW も覆う |

**WRITE を dst に含める必要性**: 連続する dispatch が同じバッファを
更新していく形 (`values[i] += 1` を 2 回) では RAW だけでなく WAW も起きる。
GL の文言が「accesses」であって「reads」でないことと一致する。

### 10.2 P2 `computeToIndirect`

**GL の意味論** — `GL_COMMAND_BARRIER_BIT`:

> Command data sourced from buffer objects by Draw*Indirect and
> DispatchComputeIndirect commands after the barrier will reflect data
> written by shaders prior to the barrier.

`GL_SHADER_STORAGE_BARRIER_BIT` との OR なので、
**間接コマンドと SSBO の両方**が対象。

**翻訳**:

| | 値 | 根拠 |
|---|---|---|
| `srcStage` | `COMPUTE_SHADER` | 間接コマンドを書くのは compute (`cmdgen` 等) |
| `srcAccess` | `SHADER_STORAGE_WRITE` | 間接バッファも SSBO として書かれている [確認済 — `cmdgen.comp` の `cmdBuffer[idx] = cmd`] |
| `dstStage` | `DRAW_INDIRECT ｜ consumerStages` | 間接データは `DRAW_INDIRECT` ステージで読まれる。**描画・間接ディスパッチとも同じ** |
| `dstAccess` | `INDIRECT_COMMAND_READ ｜ SHADER_STORAGE_READ` | 前者が `COMMAND_BARRIER_BIT`、後者が `SHADER_STORAGE_BARRIER_BIT` に対応 |

**⚠ stage と access の整合について**: Vulkan は
「各アクセスフラグが stage マスクのいずれかのステージで**サポートされている**こと」を要求する
(VUID-VkMemoryBarrier2-dstAccessMask-*)。

- `INDIRECT_COMMAND_READ` は `DRAW_INDIRECT` がサポート
- `SHADER_STORAGE_READ` は `DRAW_INDIRECT` では**サポートされない**。
  シェーダステージが必要

したがって **`dstStage` には必ず `DRAW_INDIRECT` とシェーダステージの両方が要る**。
ヘルパはこれを構造的に保証している (`consumerStages` を必須引数にし、
`DRAW_INDIRECT` を常に OR する)。

`consumerStages` の指定:

| 消費側 | 値 |
|---|---|
| 間接ディスパッチ (`vkCmdDispatchIndirect`) | `COMPUTE_SHADER` (既定) |
| 間接描画 (`vkCmdDrawIndexedIndirect`) | `INDIRECT_DRAW_CONSUMERS` = `VERTEX_SHADER｜FRAGMENT_SHADER` |

### 10.3 この構造で何が担保されるか

> **ヘルパが正しく、各箇所の分類が正しければ、A 区分 22 箇所は正しい。**

分類は GL のビットから機械的に決まる (§11 の表)。
したがって残る人的判断は「分類を間違えないこと」だけになる。
各適用箇所には**元の GL ビットをコメントで残す**こと。

```java
// P1: GL では glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT) 
//     (MDICSectionRenderer:281) -> compute write -> compute read
VkBarriers.computeToCompute(cmd);
```

---

## 11. 全 33 箇所の翻訳対応表

適用時にこの表を引けば機械的に決まる。**区分の根拠は GL のビットのみ**。

| # | 場所 | GL ビット | 区分 | 翻訳 |
|---|---|---|---|---|
| 1 | `MDIC:209` | `SS｜CMD` | A/P2 | `computeToIndirect(cmd, INDIRECT_DRAW_CONSUMERS)` |
| 2 | `MDIC:254` | `SS｜CMD` | A/P2 | 同上 |
| 3 | `MDIC:281` | `SS` | A/P1 | `computeToCompute` |
| 4 | `MDIC:283` | `SS` | A/P1 | `computeToCompute` |
| 5 | `MDIC:304` | `SS｜CMD` | A/P2 | `computeToIndirect(cmd, INDIRECT_DRAW_CONSUMERS)` |
| 6 | `MDIC:334` | `SS` | A/P1 | `computeToCompute` |
| 7 | `MDIC:336` | `SS｜CMD` | A/P2 | `computeToIndirect(cmd)` |
| 8 | `MDIC:356` | `SS` | **D** | `conservative` (原作者が必要性不明と明記) |
| 9 | `MDIC:358` | `SS` | A/P1 | `computeToCompute` |
| 10 | `MDIC:369` | `CMD｜SS｜UNI` | C | 個別 (UBO 読みを含む) |
| 11 | `MDIC:371` | `SS｜CMD` | A/P2 | `computeToIndirect(cmd)` |
| 12 | `NodeCleaner:114` | `SS` | A/P1 | `computeToCompute` |
| 13 | `NodeCleaner:125` | `SS` | A/P1 | `computeToCompute` |
| 14 | `NodeCleaner:127` | `SS` | C | 個別 (直後がダウンロード = TRANSFER) |
| 15 | `NodeCleaner:162` | `SS` | A/P1 | `computeToCompute` |
| 16 | `NodeCleaner:164` | `SS` | A/P1 | `computeToCompute` |
| 17 | `Traverser:325` | `SS｜CMD｜BUF` | C | 個別 (アップロード後の可視化を含む) |
| 18 | `Traverser:330` | `SS｜CMD` | A/P2 | `computeToIndirect(cmd)` |
| 19 | `Traverser:340` | `SS｜CMD` | A/P2 | `computeToIndirect(cmd)` |
| 20 | `Traverser:346` | `SS｜CMD` | **D** | `conservative` (読み手が関数外) |
| 21 | `Traverser:351` | `SS` | C | 個別 (直後がダウンロード) |
| 22 | `AsyncNodeManager:565` | `SS` | A/P1 | `computeToCompute` |
| 23 | `AsyncNodeManager:567` | `SS` | A/P1 | `computeToCompute` |
| 24 | `AsyncNodeManager:587` | `UNI｜SS` | A/P1 相当 | 個別 (UBO を含むが構造は P1) |
| 25 | `AsyncNodeManager:589` | `UNI｜SS` | A/P1 相当 | 同上 |
| 26 | `DebugRenderer:66` | `SS｜CMD` | A/P2 | `computeToIndirect(cmd, INDIRECT_DRAW_CONSUMERS)` — **未使用パス** |
| 27 | `HiZBuffer:102` | `glTextureBarrier` | 画像 | `VkTexture.barrier` |
| 28 | `HiZBuffer:103` | `FB｜TEXFETCH` | 画像 | `VkTexture.barrier` (27 と統合) |
| 29 | `AbstractRenderPipeline:222` | `FB｜PIXEL` | **D** | `conservative` (ビットと文脈が噛み合わない) |
| 30 | `GPUTiming:191` | `-1` | **除外** | Phase 6 で `VkQueryPool` と一体設計 |
| 31 | `UploadStream:124` | `BUF` | B | 削除済 (転送自体が消えた) |
| 32 | `UploadStream:131` | `BUF` | B | 削除済 |
| 33 | `DownloadStream:110` | `BUF` | B | 削除済 (`VkDownloadStream` が明示発行) |
| 34 | `DownloadStream:115` | `CLIENT｜BUF` | B | 削除済 (`HOST_READ` として実装済み) |

※ 33 呼び出し + `glTextureBarrier` 1 = 34 行。

**D 区分は 4 箇所**: #8, #20, #29 と、`GPUTiming` を除外扱いにしたぶん
実質 3 箇所 + 除外 1。

---

## 12. 同期バリデーションは機能しない (未解決)

**`SYNC-HAZARD-*` はこの環境で一切報告されない** [確認済 —
`VkBarriersTest.missingBarrierIsDetected` のネガティブ対照]。

切り分け済みの事項は `docs/vulkan-validation-setup.md` §7 を参照。
要点:

- 一般バリデーションは機能している (同じメッセンジャで指摘が出る)
- レイヤは読み込まれ、`VK_EXT_validation_features` も有効
- 環境変数経由でも同じ

**MoltenVK / portability 環境の制約と推測されるが未確認** [未検証]。
調査コストが読めないため今は行わない。

### 12.1 これによる方針の変更

| 当初 | 変更後 |
|---|---|
| D 区分は保守的に張ってから削る実験 | **保守的なまま残す。** 削っても壊れたことを検出できない |
| 「指摘ゼロ」を安全の証拠にする | **証拠にしない。** 翻訳規則の正しさ (§10) で担保する |

D 区分の各箇所には
「削れるか未検証。同期バリデーションが機能する環境が得られたら再検討」
とコメントを残すこと。

性能影響 (`ALL_COMMANDS` × 3〜4 箇所) は Phase 4 以降に実測して判断する [未検証]。

---

## 13. 宿題: 統合後の実出力比較 (Phase 5 以降)

同期バリデーションが使えないため、**最終防衛線は実出力の比較**になる。

Phase 4 / 5 が完成したら:

1. GL 版と Vulkan 版で**同じワールド・同じ視点**を描く
2. 出力を比較する
3. 一度の一致では不十分。**視点やワールドを変えて繰り返し**、差分が出ないことを積み上げる

バリア漏れは非決定的に現れるため一度の比較では捕まらないが、
**繰り返して差分が出ないことは証拠になる**。

なお GL 版はこの Mac では動かない (`docs/phase2-binding-audit.md` §8.4) ため、
比較には GL 4.6 環境が要る [未検証 — 実施方法は未定]。

---

## 14. ⚠ 適用がまだできない理由

**残り 30 箇所にヘルパを「適用」することは、現時点では不可能である** [確認済]。

理由: **翻訳対象の呼び出し箇所は、すべて GL クラスの中にある。**
それらのクラスに Vulkan 版が存在しない。

| 呼び出し箇所を持つクラス | Vulkan 版 |
|---|---|
| `MDICSectionRenderer` (11) | **無し** |
| `NodeCleaner` (5) | **無し** |
| `HierarchicalOcclusionTraverser` (5) | **無し** |
| `AsyncNodeManager` (4) | **無し** |
| `HiZBuffer` (1+1) | **無し** |
| `AbstractRenderPipeline` (1) | **無し** |
| `DebugRenderer` (1) | **無し** (かつ未使用パス) |

現在 `client/core/vk/` にあるのは**基盤のみ**:
`VkContext` / `VkBuffer` / `VkTexture` / `VkSampler` /
`VkUploadStream` / `VkDownloadStream` / `VkFrameTracker` / `VkBarriers` /
シェーダ系 4 クラス。

`VkBarriers` を実際に使っているのは**テストコードだけ** [確認済 —
`grep -rn 'VkBarriers\.'`]。前回「traversal に適用した」と述べたのも
`TraversalDispatchTest` の中であり、ディスパッチループを**テストで再現したもの**で、
本番のレンダラではない。

### 14.1 したがって順序はこうなる

```
[済] バリア調査 + 翻訳規則の確定 + 対応表
      ↓
[次] レンダラ本体の Vulkan 移植 (Phase 4)
      ↓
      移植しながら、対応表 (§11) を引いてバリアを書く
```

**バリア翻訳は独立した作業ではなく、レンダラ移植の一部**である。
対応表があるので、移植時には引くだけで済む。

### 14.2 今回までに済んでいること

- 翻訳規則を仕様と照合して確定 (§10)
- 全 34 行の対応表を作成 (§11)。区分は GL のビットから機械的に決まる
- ヘルパ 3 種を実装し、記録・サブミットまで動作確認
- B 区分 4 箇所は実際に削除済み (`VkUploadStream` / `VkDownloadStream`)
- 画像バリアの機構を実装・検証済み (`VkTexture.barrier`)

---

## 15. ⚠ 対応表の前提に例外が見つかった (Phase 4 着手時)

**「GL のビットから機械的に決まる」(§10.3) には例外がある。**

### 15.1 GL が暗黙に処理し、Vulkan では明示が要るハザード

`renderOpaque` は `buildDrawCalls` **より前**に呼ばれる [確認済 —
`AbstractRenderPipeline.runPipeline` 113 行 vs 123 行]。したがって
同一フレームの同一コマンドバッファ内で:

```
renderOpaque    : drawCallBuffer を GL_DRAW_INDIRECT_BUFFER として読む  (MDIC:192)
      ↓
cmdgen dispatch : drawCallBuffer を SSBO として書く                      (MDIC:320)
```

**これは write-after-read (WAR) ハザードである。**

GL 側の該当バリア `MDIC:334` は `glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT)` で、
**間接読み出しについて何も言っていない**。GL の `glMemoryBarrier` は主に
「以前の書き込みを可視にする」ためのもので、WAR の順序は実装の順序保証に委ねられる。

Vulkan では WAR にも明示的な実行依存が要る:

```
srcStage  = DRAW_INDIRECT,   srcAccess = INDIRECT_COMMAND_READ
dstStage  = COMPUTE_SHADER,  dstAccess = SHADER_STORAGE_WRITE
```

### 15.2 対応表への影響

| 影響 | 内容 |
|---|---|
| #6 (`MDIC:334`) の分類 | A/P1 (`computeToCompute`) は**不十分**。WAR 成分が抜ける |
| より一般に | **GL に対応するバリアが存在しない箇所**がありうる。GL が暗黙に処理していたため 33 箇所に現れない |

**したがって「33 箇所を翻訳すれば済む」ではない。**
Vulkan 側で新たに必要になるバリアが別に存在する。

### 15.3 どう扱うか

- 対応表 (§11) は**出発点として有効**。GL にあるものは漏らさず翻訳できる
- ただし **Vulkan 固有のハザードを別途洗い出す必要がある** [未検証 — 未実施]
- 洗い出しの軸: 同一バッファが「間接読み / 頂点読み / SSBO 書き」など
  **異なるアクセスで複数回現れる箇所**

Stage 1 (統合せずそのまま移植) の際に、
バッファごとのアクセス系列を追って洗い出すのが現実的 [推測]。
