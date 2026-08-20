# Phase 2 — バインディング衝突監査

**目的**: 全シェーダの `binding` 番号を洗い出し、同一シェーダ (Vulkan では同一パイプライン) 内で
UBO / SSBO / サンプラ / イメージが同じ番号を使っていないか確認する。

**なぜ問題になるか**: GL では以下が**別々の名前空間**である。

| GL の名前空間 | 対応する GL 呼び出し |
|---|---|
| uniform buffer binding points | `glBindBufferBase(GL_UNIFORM_BUFFER, N, ...)` |
| shader storage binding points | `glBindBufferBase(GL_SHADER_STORAGE_BUFFER, N, ...)` |
| texture image units | `glBindTextureUnit(N, ...)` |
| image units | `glBindImageTexture(N, ...)` |

したがって GL では「UBO 0 と sampler 0 が同居」は完全に合法。
Vulkan では**同一 descriptor set 内で binding 番号が一意**でなければならず、
`set 0` に全リソースを置く規約の下ではこれらが衝突する。

**調査方法**: `ShaderLoader.parseRoot` と同じ規則で `#import` を再帰展開し、
シェーダ内 `#define` と Java 側 `Shader.Builder.define(...)` の両方から binding 値を解決した。
`#ifdef` ガードで無効化される宣言は除外している (未解決のマクロ名として現れる)。
スクリプトはスクラッチパッドの `bindscan.py` / `run.py`。

**対象**: Phase 1 のデッドコード削除後に Java から参照されている 27 シェーダ / 19 パイプライン。
`Shader.make()` / `Shader.makeAuto()` の全 19 呼び出し箇所を網羅した [確認済]。

---

## 1. 結論

**衝突は 1 パイプラインに 2 件のみ。いずれも地形描画パイプライン (MDIC terrain) に集中していた。**

| # | パイプライン | binding | 衝突 | 同一ステージ内か | 状態 |
|---|---|---:|---|---|---|
| **C-1** | terrain opaque / translucent | **0** | `UBO SceneUniform` vs `SAMPLER blockModelAtlas` | **はい (FRAGMENT 内)** | ✅ **解消済** (0 → 7) |
| **C-2** | terrain opaque / translucent | **1** | `SSBO QuadBuffer` vs `SAMPLER lightSampler` | **はい (VERTEX 内)** | ✅ **解消済** (1 → 6) |

**他の 18 パイプラインは衝突なし** [確認済]。

> **解消方針**: GL 版と Vulkan 版で binding 番号を食い違わせない。
> `#define` の値そのものを変更し、**両バックエンドが同じ番号を使う**。
> GL では別名前空間で衝突していなくても番号を揃えておくことで、移行中の照合が可能になる。
> 詳細は §8。

特筆すべきは `HierarchicalOcclusionTraverser` で、`BINDING_COUNTER = 1` から採番し
サンプラ (`HIZ_BINDING = 0`) を 0 に固定することで、
**UBO と SSBO を最初から単一の番号空間で管理している** [確認済]。
これは意図的かどうかは不明だが [推測]、結果として Vulkan 規約にそのまま適合する。

---

## 2. 衝突の詳細

### C-1: terrain pipeline, binding 0 — UBO vs SAMPLER

```
[UBO    ] lod/gl46/bindings.glsl:1    layout(binding = 0, std140) uniform SceneUniform { ... }
[SAMPLER] lod/gl46/quads.frag:12      layout(binding = 0) uniform sampler2D blockModelAtlas;
```

**発生経路** [確認済]:

- `bindings.glsl:1` の `SceneUniform` は **`#ifdef` ガードが無い**。
  `bindings.glsl` を `#import` する全シェーダが無条件に binding 0 の UBO を得る。
- `quads.frag:40` が `bindings.glsl` を import しているため、FRAGMENT ステージに UBO 0 が入る。
- 同じ `quads.frag:12` が自前で `sampler2D blockModelAtlas` を binding 0 に宣言している。

**→ FRAGMENT ステージ単体で衝突している。** VS/FS をまたぐ問題ではない。

補足: `bindings.glsl:19` にも `BLOCK_MODEL_TEXTURE_BINDING` を使う `blockModelAtlas` 宣言があるが、
こちらは `#ifdef BLOCK_MODEL_TEXTURE_BINDING` ガード付きで、地形パイプラインでは**未定義のため無効** [確認済]。
実際に効いているのは `quads.frag:12` のハードコード版。

### C-2: terrain pipeline, binding 1 — SSBO vs SAMPLER

```
[SSBO   ] lod/gl46/bindings.glsl:27   layout(binding = QUAD_BUFFER_BINDING, std430) buffer QuadBuffer { ... }
[SAMPLER] lod/lighting.glsl:12        layout(binding = LIGHTING_SAMPLER_BINDING) uniform sampler2D lightSampler;
```

**発生経路** [確認済]: `quads3.vert` が両方の define を持つ。

```glsl
// lod/gl46/quads3.vert
#define QUAD_BUFFER_BINDING     1
#define MODEL_BUFFER_BINDING    3
#define MODEL_COLOUR_BUFFER_BINDING 4
#define POSITION_SCRATCH_BINDING 5
#define LIGHTING_SAMPLER_BINDING 1   // ← QUAD_BUFFER_BINDING と同値
```

`lightSampler` は `quad_util.glsl:2` → `lighting.glsl` 経由で VERTEX に入り、
`quad_util.glsl:90` の `getLighting(lighting)` から使われる。

**→ VERTEX ステージ単体で衝突している。**

補足: `lighting.glsl` の宣言は `#ifdef LIGHTING_SAMPLER_BINDING` ガード付きで、
**`LIGHTING_SAMPLER_BINDING` を define しているのは `quads3.vert` だけ** [確認済]。
`quads.frag:41` も `lighting.glsl` を import しているが define を持たないため、
FRAGMENT 側には `lightSampler` は存在せず `getLightmapUv()` のみが入る。

---

## 3. terrain pipeline のステージ別バインディング表

Vulkan の descriptor set layout はパイプライン全体で 1 つ、binding ごとに `stageFlags` を持つ。
現状を写すと以下になる (衝突を解消しない限りこのレイアウトは作れない)。

| binding | VERTEX | FRAGMENT | 想定 descriptor type |
|---:|---|---|---|
| **0** | `UBO SceneUniform` | `UBO SceneUniform` **+** `SAMPLER blockModelAtlas` | **競合** |
| **1** | `SSBO QuadBuffer` **+** `SAMPLER lightSampler` | — | **競合** |
| 2 | — | `SAMPLER depthTex` | COMBINED_IMAGE_SAMPLER |
| 3 | `SSBO ModelBuffer` | `SSBO ModelBuffer` | STORAGE_BUFFER |
| 4 | `SSBO ModelColourBuffer` | — | STORAGE_BUFFER |
| 5 | `SSBO PositionScratchBuffer` | — | STORAGE_BUFFER |

`MODEL_BUFFER_BINDING` は `quads3.vert:14` と `quads.frag:35` の**両方**で 3 に define されており、
両ステージから見える [確認済]。

---

## 4. 衝突なしと確認したパイプライン (18)

| パイプライン | 使用 binding | 備考 |
|---|---|---|
| cull raster (MDIC) | UBO 0 / SSBO 1,2,3 | |
| cmdgen (MDIC) | UBO 0 / SSBO 1〜8 | 統計有効時に 8 |
| prep (MDIC) | UBO 0 / SSBO 1,2 | |
| buildtranslucents (MDIC) | UBO 0 / SSBO 1〜5 | |
| prefixsum simple / inital3 | SSBO 0 | |
| traversal (Traverser) | SAMPLER 0 / UBO 1 / SSBO 2,3,4,6,7,8,9,10 | 単一番号空間。5 は push constant 用 |
| sort_visibility (NodeCleaner) | SSBO 1,2,3 | |
| result_transformer (NodeCleaner) | SSBO 0,1,2,3 | |
| batch_visibility_set (NodeCleaner) | SSBO 0,1 | |
| scatter (AsyncNodeManager) | SSBO 0,1,2 | |
| memcpy (AsyncNodeManager) | SSBO 0,1,2 | |
| ssao (SSAO) | IMAGE 0 / SAMPLER 1,2,3 | image と sampler は別種だが番号は重複なし |
| setup_stencil_depth | SAMPLER 0 | |
| blit_texture_depth_cutout | SAMPLER 0,3 | |
| hiz blit (HiZBuffer) | SAMPLER 0 | |
| chunkoutline (BoundRenderer) | UBO 0 / SSBO 1 | |
| debug setup (DebugRenderer) | SSBO 0,1 | 未使用 (§6) |
| debug node_outline (DebugRenderer) | UBO 0 / SSBO 2 | 未使用 (§6) |

`traversal` の binding 5 (`NODE_QUEUE_INDEX_BINDING`) は
`queue.glsl:3` の `layout(location = ...) uniform uint queueIdx` すなわち**デフォルトブロック uniform** であり、
descriptor ではなく push constant に移る。番号空間が別なので衝突には数えない [確認済]。

---

## 5. 付随して確認した事項

### 5.1 PrintfInjector の binding

`PrintfDebugUtil` が `new PrintfInjector(50000, 20, ...)` を生成し、
`PrintfInjector:98` が `layout(binding = 20, std430) buffer PrintfOutputStream` を注入する [確認済]。

- **binding 20** は現行の最大使用値 (traversal の 10) と重ならない
- 既定では無効 (`-Dvoxy.enableShaderDebugPrintf=true` のときのみ)。
  無効時は `printf` を `//printf` に置換するだけの no-op プロセッサ

**→ 衝突なし。ただし Vulkan 版でも 20 を予約値として維持すること。**

### 5.2 デフォルトブロック uniform — Vulkan GLSL では**コンパイル不可**

衝突とは別問題だが、VkShader の実装に直接影響するため記録する。
現存 **7 ファイル・17 宣言** [確認済]:

| ファイル | 宣言数 |
|---|---:|
| `post/ssao.comp` | 6 |
| `post/blit_texture_depth_cutout.frag` | 5 |
| `lod/hierarchical/cleaner/batch_visibility_set.comp` | 2 |
| `post/setup_stencil_depth.frag` | 1 |
| `util/scatter.comp` | 1 |
| `lod/hierarchical/queue.glsl` | 1 |
| `lod/hierarchical/cleaner/result_transformer.comp` | 1 |
| **合計** | **17** |

移行の追跡は [`phase2-pushconstant-todo.md`](phase2-pushconstant-todo.md) で行う。

`queue.glsl` は `traversal_dev.comp` に import されるため、**トラバーサルも影響を受ける**。

Vulkan GLSL はデフォルト uniform ブロックを禁止しているため、
これらは shaderc に通した時点でエラーになる。push constant への移行が
**コンパイルを通すための前提条件**であり、後回しにできない。

`maxPushConstantsSize = 4096` [実測済 — Phase 0] なので、最大の `ssao.comp` (mat4 × 6 = 384 バイト) でも余裕がある。

### 5.3 `AutoBindingShader.rebuild` は死んだフィールド

`rebuild` は `insertOrReplaceBinding` と `texture` で `true` に設定されるが、
**`bind()` を含めどこからも読まれていない** [確認済 — 29 / 75 / 99 行の 3 箇所のみ]。
現行 GL 実装は毎回全バインディングを再適用している。

Vulkan 版では descriptor set の更新頻度を制御する必要があるため、
このフラグは**復活させる価値がある**。

### 5.4 `insertOrReplaceBinding` の重複判定キーが GL 前提

```java
if (entry.target == binding.target && entry.index == binding.index) {
```

`target` (`GL_UNIFORM_BUFFER` / `GL_SHADER_STORAGE_BUFFER`) を判定に含めているため、
**同一 index に UBO と SSBO を同時登録できてしまう**。
これは GL の名前空間分離を前提としたデータモデルであり、
Vulkan 版では `index` のみをキーとし、型不一致を**エラーにする**必要がある。

---

## 6. 付随して発見した既存バグ (Vulkan とは無関係)

**`DebugRenderer.debugShader` は現状コンパイルできない。**

- `node.glsl:2` の `layout(binding = NODE_DATA_BINDING, ...)` は **`#ifdef` ガードが無い** [確認済]
- `node_outline.vert:13` が `node.glsl` を import しているが、
  定義しているのは `NODE_DATA_INDEX` であって `NODE_DATA_BINDING` ではない [確認済]
- `DebugRenderer` 側も `NODE_DATA_BINDING` を define していない [確認済]

→ `NODE_DATA_BINDING` が未定義トークンのまま残り、整数定数として解釈できずコンパイルエラーになる。

**顕在化していない理由**: `DebugRenderer` は**どこからもインスタンス化されていない** [確認済 — 
`grep -rn 'DebugRenderer'` の結果が自ファイル以外 0 件]。

Vulkan 移植の対象外 (デバッグ機能・優先度低) だが、
移植時に「動くはずのものが動かない」と誤認しないよう記録しておく。

---

## 7. descriptor set の in-flight 更新 — 実際に引っかかる箇所

`VkAutoBindingShader` は「GPU が参照中の descriptor set を書き換えていないか」を
`VkFrameTracker` の世代照合で検出する。**静的解析の結果、引っかかる箇所が実在する。**

### 7.1 該当箇所: `rendering/bounding/` の 4 ストア

`BoundRenderer.render()` は毎フレーム以下を行う [確認済 —
`BoundRenderer:60-71`, 呼び出し元は `VoxyRenderSystem:301` の描画パス内]:

```java
public void render(Viewport<?> viewport, IBoundStore store) {
    store.preRender(viewport);                                    // ← ここでバッファが free + 再確保されうる
    int count = store.getCount();
    if (count == 0) { ...; return; }
    ((AutoBindingShader)this.rasterShader).ssbo(1, store.getBuffer());  // ← 新しいバッファに差し替え
    this.renderInner(viewport, renderDistance, count);
    store.postRender(viewport);
}
```

`preRender()` の中身 (`StreamedBoundStore:24-33`) [確認済]:

```java
if (this.count*4L > this.chunkPosBuffer.size()) {
    this.chunkPosBuffer.free();                                   // 解放
    this.chunkPosBuffer = new GlBuffer(((long) Math.ceil(this.count*1.25))*4);  // 再確保
}
```

同じ構造が 4 実装すべてにある [確認済]:

| ファイル | 再確保箇所 | 実行メソッド |
|---|---|---|
| `StreamedBoundStore.java` | :27 | `preRender()` |
| `ColumnStreamedBoundStore.java` | :39 | `preRender()` |
| `ExactBoundStore.java` | :37 | `preRender()` |
| `ChunkBoundStore.java` | :107 | `ensureSize1()` ← `:89` から呼ばれる |

**発火条件**: 可視チャンク数が現在の容量を超えたフレーム。
初期容量 `1<<12` から 1.25 倍ずつ増えるため、**ワールド読み込み中に数回発生する** [推測]。
定常状態では発生しない。

### 7.2 判断: **世代管理は不要。フレーム境界解放で足りる** (Phase 3 で確定)

> **`in-flight = 1` を採用したため、世代付き遅延解放キューは不要になった。**
>
> Phase 2 時点では「フレーム同期の設計が未定なので Phase 4/5 で再検討」としていたが、
> Phase 3 で **in-flight = 1** が確定したことで問題が単純化した:
>
> - **フレーム境界では GPU が必ずアイドル**である
>   (`VkFrameTracker.beginFrame()` が前フレームの fence を待つ)
> - よって「次の `beginFrame()` まで解放を遅らせる」という**単純な FIFO** で足りる。
>   どの世代がまだ動いているかを追う必要がない
>
> 実装: `VkFrameTracker.freeAtFrameEnd(Runnable)` [確認済 — `VkFrameTrackerTest`]。
>
> **なぜ in-flight = 1 で十分か**: Voxy は自前でプレゼンテーションを行わず、
> 合成は GL 側が行う。**Vulkan の完了を待たないと GL が正しい内容を読めない**ため、
> 待つことが構造的に必要であり、多重化しても待ち自体は消えない。
> 同期コストは実測 0.315ms (予算 16.6ms の 1.9%) [実測済 — Phase 0]。
>
> **in-flight 検出機構は残す。** 将来多重化した場合の防波堤として
> `VkFrameTracker.inFlight(long)` と `VkAutoBindingShader.assertSafeToMutate` を維持する。
>
> **多重化は測ってから。** フレーム時間が予算を超えることが実測で判明した場合にのみ検討する。
> 現時点で多重化の動機となる数字は存在しない。

### 7.3 ここは「別戦略が要る」箇所である

判断材料として 2 点挙げる。

**(a) descriptor 更新だけの問題ではない。** `chunkPosBuffer.free()` は
前フレームのコマンドバッファがまだ参照している可能性のあるバッファを破棄している。
GL ではドライバが遅延解放するため安全だが、**Vulkan では
`vkDestroyBuffer` の時点で GPU が使用中でないことをアプリが保証する必要がある**。
つまり in-flight 検出に引っかからなくても、この経路は**そのままでは移植できない**。

**(b) ただし影響範囲は限定的。** 該当するのは `BoundRenderer` 1 パイプラインのみで、
しかもこれは **FREX / Sodium 連携時のチャンク境界描画**という補助機能である
[確認済 — `VoxyRenderSystem:300` の条件は
`visbleSectionStream != null && !disableSodiumChunkRender() && !irisShadowActive()`]。
地形描画本体 (`MDICSectionRenderer`) やトラバーサルはこの経路を通らない。

### 7.4 対処 (Phase 3 で確定)

**`VkFrameTracker.freeAtFrameEnd(Runnable)` を使う。**

§7.3 で挙げた 2 つの問題が両方これで解ける:
- (a) 使用中バッファの `vkDestroyBuffer` → フレーム境界まで遅らせるので GPU はアイドル
- (b) descriptor set の in-flight 更新 → 同上

当初「世代付きキューが本命」としていたが、**in-flight = 1 では世代を持つ必要が無い**
(§7.2)。単純な FIFO で足りる。

却下した代替案:
- **容量固定 + 上限クランプ** — 再確保自体を無くす案。上限超過時の挙動を決める必要があり、
  遅延解放で足りる以上わざわざ挙動を変える理由が無い
- **このパイプラインだけ per-frame descriptor set** — (a) が残るため単独では不十分

### 7.5 その他のバッファは再確保しない

`GlBuffer` を再代入している箇所を全数調査した結果、
**上記 4 ストア以外はすべて `final` フィールドの一度きりの初期化** [確認済]。

特に確認した重要なもの:

| 対象 | 結果 |
|---|---|
| `BasicSectionGeometryData` のジオメトリバッファ | **再確保しない。** コンストラクタで 1 回確保するのみ。`ensureAccessable()` は sparse のページコミットだけを行い、macOS では `isSparse()==false` のため何もしない [確認済] |
| `BasicSectionGeometryData.sectionMetadataBuffer` | コンストラクタのみ (`:25`, `:37` は 2 つのコンストラクタ) |
| `RenderResourceReuse` | `VoxyRenderSystem` の**構築時と破棄時のみ** [確認済 — `:119`, `:123`]。フレーム中には走らない |
| `HierarchicalOcclusionTraverser` の各バッファ | すべて `final`、構築時のみ |
| `NodeCleaner.visibilityBuffer` | `final`、構築時のみ |
| `MDICViewport` の 5 バッファ | すべて `final`、ビューポート生成時のみ |

→ **懸念されていた `RenderResourceReuse` と `BasicSectionGeometryData` は、
実際には in-flight 更新を起こさない。** 問題は `rendering/bounding/` に局在している。

---

## 8. C-1 / C-2 の解消 (実施済み)

**方針**: GL 版と Vulkan 版で binding 番号を食い違わせない。
`#define` の値そのものを変更し、両バックエンドが同じ番号を使う。
GL では別名前空間なので番号を揃える必要は本来無いが、揃えておくことで
**移行中に GL 版と Vulkan 版の挙動を突き合わせられる**。

`bindings.glsl` は **import している全シェーダに波及するため動かさない**。
衝突している相手 (サンプラ) 側を空き番号へ移した。

### 8.1 変更内容

| # | 対象 | 変更 |
|---|---|---|
| C-2 | `lod/gl46/quads3.vert:17` | `#define LIGHTING_SAMPLER_BINDING` **1 → 6** |
| C-2 | `MDICSectionRenderer` | `LightMapHelper.bind(1)` → `bind(LIGHTMAP_TEXTURE_UNIT)` (=6) |
| C-1 | `lod/gl46/quads.frag:16` | `layout(binding = 0) uniform sampler2D blockModelAtlas` → **binding = 7** |
| C-1 | `MDICSectionRenderer` | `modelStore.bind(3, 4, 0)` → `bind(3, 4, BLOCK_ATLAS_TEXTURE_UNIT)` (=7) |
| 両方 | `MDICSectionRenderer` (2 箇所) | 後始末の `glBindSampler/glBindTextureUnit(0/1, 0)` を 7/6 へ |

Java 側は番号を直書きせず `MDICSectionRenderer` の定数
`LIGHTMAP_TEXTURE_UNIT = 6` / `BLOCK_ATLAS_TEXTURE_UNIT = 7` に集約し、
GLSL 側にも由来をコメントで残した。

### 8.2 変更後の地形パイプライン

| binding | VERTEX | FRAGMENT | descriptor type |
|---:|---|---|---|
| 0 | `UBO SceneUniform` | `UBO SceneUniform` | UNIFORM_BUFFER |
| 1 | `SSBO QuadBuffer` | — | STORAGE_BUFFER |
| 2 | — | `SAMPLER depthTex` | COMBINED_IMAGE_SAMPLER |
| 3 | `SSBO ModelBuffer` | `SSBO ModelBuffer` | STORAGE_BUFFER |
| 4 | `SSBO ModelColourBuffer` | — | STORAGE_BUFFER |
| 5 | `SSBO PositionScratchBuffer` | — | STORAGE_BUFFER |
| **6** | **`SAMPLER lightSampler`** | — | COMBINED_IMAGE_SAMPLER |
| **7** | — | **`SAMPLER blockModelAtlas`** | COMBINED_IMAGE_SAMPLER |

**全 19 パイプラインで衝突ゼロを再確認済** [確認済 — `run.py` の再実行]。

### 8.3 `bindings.glsl:19` は依然としてデッドコード

`#ifdef BLOCK_MODEL_TEXTURE_BINDING` 版の `blockModelAtlas` 宣言は、
`BLOCK_MODEL_TEXTURE_BINDING` が **Java / GLSL のどこからも define されていない**ため
全パイプラインで無効のまま [確認済]。

今回 `quads.frag` 側を define 経由に変えなかったのは、
そうすると `bindings.glsl:19` も有効化されて
**同名変数の二重宣言でコンパイルエラーになる**ため [確認済 — `quads.frag:40` が
`bindings.glsl` を import している]。整理は別途。

### 8.4 GL バックエンドでの目視確認は行っていない (恒久的な方針)

**本フォークは macOS 専用であり、GL 4.6 環境を持たないため、今後も目視確認は行わない。**

このマシンでは Voxy 自体が起動しない [確認済 — 実際に `./gradlew runClient` を実行]:

```
[Render thread/ERROR] (Voxy) [VoxyClient]: Voxy is unsupported on your system.
```

`VoxyClient.java:30` が `Capabilities.INSTANCE.compute && Capabilities.INSTANCE.indirectParameters`
で判定しており、実体は `glDispatchComputeIndirect != 0` と
`glMultiDrawElementsIndirectCountARB != 0`。Apple の GL 4.1 ではどちらも false になる。
**これは本移植そのものの動機であり、想定内の挙動である。**

代わりに以下を静的検証の根拠として残す [確認済]:

| 検証項目 | 結果 |
|---|---|
| GLSL の番号と Java 定数の一致 | `LIGHTING_SAMPLER_BINDING 6` ↔ `LIGHTMAP_TEXTURE_UNIT = 6` / `binding = 7` ↔ `BLOCK_ATLAS_TEXTURE_UNIT = 7` |
| unbind の追随 | 後始末 4 箇所すべてを 0/1 → 7/6 に変更済み |
| 他コードとの衝突 | unit 6/7 を使うコードは他に無い (他は最大 3) |
| 呼び出し元の網羅 | `LightMapHelper.bind` の呼び出し元は 1 箇所のみ |
| GL の下限 | `GL_MAX_TEXTURE_IMAGE_UNITS` の下限は 16。6/7 は範囲内 |
| クリーンアップ | `VoxyRenderSystem` の 2 ループはいずれも 0..11 を対象とし 6/7 を含む |

**GL バックエンドは Vulkan 移行の完了とともに削除される経路**であるため、
この確認レベルで十分と判断した。

### 8.5 回帰テスト

`./gradlew test` で実機 Vulkan を使って検証している (`src/test/java/me/cortex/voxy/vk/`)。
**Minecraft の起動は不要** — `VkContext` はサーフェスもウィンドウも持たないため素の JVM で動く。

| テスト | 内容 |
|---|---|
| `TerrainShaderTest.reflectionReportsExpectedBindings` | §8.2 の表と SPIR-V リフレクション結果が一致すること |
| `TerrainShaderTest.currentBindingsHaveNoCollision` | 現行番号で衝突しないこと |
| `TerrainShaderTest.oldLightingBindingIsDetectedAsCollision` | **C-2 を 6→1 に戻すと衝突として検出されること** |
| `TerrainShaderTest.oldAtlasBindingIsDetectedAsCollision` | **C-1 を 7→0 に戻すと衝突として検出されること** |

後者 2 つは**検出機構が生きていることの確認**である。
これが落ちる場合「衝突が無い」のではなく「検出できていない」を意味する。

### 8.6 残る注意点

**draw 統合 (Phase 4) で `cmdgen.comp` と `quads3.vert` を再設計する際、
バインディング番号を振り直す可能性がある** [推測]。
その場合も本節の方針 (GL と Vulkan で同一番号) を維持すること。
