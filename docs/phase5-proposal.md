# Phase 5 提案 — interop 統合

**状態**: 調査と提案のみ。実装は未着手。

Phase 5 はこれまでと性質が違う。**Phase 4 までは「Vulkan の中で閉じた自己整合性」を
検証してきたが、Phase 5 は外界と繋がる。**

| | Phase 1〜4 | Phase 5 |
|---|---|---|
| メモリ | Vulkan が独占 | **GL と共有** |
| 入力 | 合成データ | **実データ** |
| 正しさの基準 | 自分の別経路との一致 | **GL 版と同じ絵か** |
| 実行環境 | 素の JVM (テスト) | **Minecraft のライフサイクル** |

---

## 1. Phase 0 実証コードの棚卸し

### 1.1 そのまま使えるもの / 書き直すもの [確認済 — 実物を読んだ]

| ファイル | 行数 | 判断 | 理由 |
|---|---:|---|---|
| `ObjC.java` | 55 | **ほぼそのまま** | Objective-C ランタイムの薄いラッパ。Voxy 固有の要素が無い |
| `IOSurf.java` | 108 | **ほぼそのまま** | `IOSurfaceCreate` + 問い合わせ。ライフサイクル管理だけ足す (§1.3) |
| `Cgl.java` | 57 | **ほぼそのまま** | `CGLTexImageIOSurface2D` の 1 関数が本体 |
| `Interop.java` | 73 | **書き直す** | `VkTexture` に統合する (§1.4) |
| `Metal.java` | 94 | **不要** | Phase 0 §8.5 で「`MTLTexture` の経由は不要」と判明済み。`VkImportMetalIOSurfaceInfoEXT` が IOSurface を直接 `VkImage` にする |
| `GlComposite.java` | 111 | **参考にして書き直す** | 合成の骨格は流用できるが、Voxy の `FullscreenBlit` / `initDepthStencil` と統合が要る (§3.1) |

**新規に書くコードは意外と少ない。** `ObjC` / `IOSurf` / `Cgl` の 220 行は
Voxy に依存していないので、`client/core/vk/interop/` にほぼ移すだけで済む [推測]。

### 1.2 Panama (FFM) は本番で問題ない — **確認済**

これは Phase 5 最大の環境リスクだったが、**解消した**。

**① Minecraft 26.2 の launcher manifest が既に必要な引数を渡している**
[確認済 — `~/.gradle/caches/fabric-loom/26.2/mojang_minecraft_info.json` を直接読んだ]:

```
--enable-native-access=ALL-UNNAMED
-XstartOnFirstThread          (macOS のみ。rules で os.name == osx に限定)
```

Minecraft 自身が LWJGL 3.4.1 を使っており、LWJGL 3.4 が FFM ベースなので、
**Mojang が既にこの引数を入れている**。mod 側で何もしなくてよい。
`-XstartOnFirstThread` も入っている (Phase 0 で GLFW に必須と分かっていたもの)。

**② 仮に引数が無くても JDK 25 では動く** [確認済 — 実測]:

```
$ java P.java          # --enable-native-access なし
WARNING: A restricted method in java.lang.foreign.SymbolLookup has been called
WARNING: Restricted methods will be blocked in a future release unless native access is enabled
OK objc_getClass(NSObject) = 0x1f03e5ed8
```

<b>警告は出るが動く。</b>ただし「将来のリリースでブロックされる」と明記されている。
①があるので当面は問題ないが、**JDK が上がったときに壊れうる箇所**として記録しておく。

> ⚠ 開発時の `./gradlew runClient` は loom が引数を組み立てる。
> こちらに `--enable-native-access` が入るかは [未検証]。
> 入っていなければ警告が出るだけなので実害は無いが、
> 「本番では出ないのに開発では出る警告」は混乱のもとなので確認したほうがよい。

### 1.3 IOSurface のライフサイクルを誰が持つか

Phase 0 の `Interop` は `close()` で `CFRelease` していた。Voxy に持ち込むと
**解放タイミングが 3 者に跨る**:

```
IOSurface  ←─ VkImage  (Vulkan が読み書き)
           ←─ GL_TEXTURE_RECTANGLE  (GL が読む)
```

`CFRelease` は参照カウントなので、GL テクスチャが生きている間に
IOSurface を解放しても即座には壊れない [推測] が、順序を決めておくべきである。

**提案**: `VkInteropImage` (新規) が単独の所有者になり、以下を 1 つのオブジェクトにまとめる:

| 持ち物 | 解放順 |
|---|---|
| `VkImageView` / `VkImage` | 1 |
| GL テクスチャ ID | 2 |
| `IOSurfaceRef` | 3 (最後) |

解放は `VkFrameTracker.freeAtFrameEnd` に載せる。
in-flight = 1 なのでフレーム境界では GPU がアイドルだが、
**GL 側がまだ読んでいる可能性がある** — ここは `glFinish` 相当が要るか [未検証]。

> ⚠ ウィンドウリサイズで毎回作り直す経路である
> (`NormalRenderPipeline.setup` が `viewport.width/height` の変化で再確保している [確認済])。
> リサイズ中に「GL が古いテクスチャを読んでいる最中に IOSurface を解放」が起きうる。

### 1.4 `VK_IMAGE_LAYOUT_GENERAL` と `VkTexture` のレイアウト追跡

Phase 0 は最後に `GENERAL` へ遷移して GL に渡していた [確認済 — `Step8.recordVulkanWork`]。

`VkTexture` は<b>レベルごとに現在のレイアウトを追跡</b>しており、
外部画像も同じ仕組みに載る設計になっている
[確認済 — `VkTexture.wrapExternal` の javadoc に明記済み]。

**提案**: `VkInteropImage` は `VkTexture` を内部に持ち (`wrapExternal` 経由ではなく
IOSurface から直接作った `VkImage` を包む)、フレームの最後に

```java
tex.barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
    COLOR_ATTACHMENT_OUTPUT, COLOR_ATTACHMENT_WRITE,
    BOTTOM_OF_PIPE, 0);
```

を発行して GL に渡す。<b>次フレームの `beginRendering` は `GENERAL` からの遷移</b>になるが、
`VkRenderTarget.beginRendering` は既に「現在のレイアウトから」遷移する作りなので変更不要 [推測]。

**⚠ ここに Phase 4 の経験が効かない新しいハザードがある**:
`GENERAL` に遷移させても、それは<b>Vulkan のキュー内での話</b>でしかない。
GL が読むタイミングとの順序は Vulkan のバリアでは表現できず、
CPU 側の同期 (§2) でしか担保できない。

---

## 2. GL ↔ Vulkan の同期設計

### 2.1 いまある部品

| 部品 | 現状 |
|---|---|
| `VkFrameTracker.waitForFrame()` | フェンス待ち。**既に「GL が結果を読む直前に呼ぶ」用途で書かれている** [確認済 — javadoc] |
| Phase 0 の実測 | `vkQueueWaitIdle` で 0.315 ms (1920x1080) |
| `VK_EXT_metal_objects` | rev 2 利用可 [確認済 — 実機で列挙] |

**`VkFrameTracker` は最初からこの用途を想定して書かれている。**
`waitForFrame()` の javadoc に「GL 側が結果を読む直前に呼ぶ。in-flight = 1 の要」とある。
新しい仕組みは要らず、**呼ぶ場所を決めるだけ** [推測]。

### 2.2 フレームループのどこに入るか [確認済 — `runPipeline` を読んだ]

```
VoxyRenderSystem.renderOpaque(viewport, srcDepthTex, srcColourTex)   ← MC の mixin から
  └─ pipeline.runPipeline(...)
       ├─ setup()                    ★ initDepthStencil (深度コピー + ステンシルマスク)
       ├─ renderOpaque               ┐
       ├─ innerPrimaryWork           │ ここが全部 Vulkan になる
       ├─ buildDrawCalls             │
       ├─ renderTemporal             │
       ├─ postOpaquePreTranslucent   │ SSAO (GL のまま？ §3.1)
       ├─ renderTranslucent          ┘
       └─ finish()                   ★ finalBlit で MC の FBO に合成
```

**提案する形**:

```
runPipeline:
  ① GL: initDepthStencil 相当 — MC の深度を interop 深度 IOSurface に書く
  ② VkFrameTracker.beginFrame()
  ③ Vulkan: 描画 5 段 + 3 パス (Phase 4 で完成済み)
  ④ VkFrameTracker.endFrame() + waitForFrame()      ← ここが 0.315ms
  ⑤ GL: 合成パス (色 + gl_FragDepth 書き戻し) → MC の FBO
```

**① が要るのが厄介である。** Vulkan 側の cull と `depthTex` は
「MC が既に描いた地形の深度」を必要とする [確認済 — `quads.frag` の `depthTex`、
`cull_raster` の深度テスト]。つまり<b>同期が双方向に要る</b>:

```
GL が深度を書く → Vulkan が読む   (GL → Vulkan の同期)
Vulkan が色/深度を書く → GL が読む (Vulkan → GL の同期)
```

Phase 0 が測ったのは後者だけ [確認済 — `Step8` は GL 側の書き込みを含まない]。
**前者の同期コストは [未検証]。** `glFinish` が要るなら GL パイプラインを
毎フレーム完全に空にすることになり、0.315ms では済まない可能性がある。

> **これは Phase 5 で最初に測るべき数字である。** §6 の 5a に入れた。

### 2.3 `MTLSharedEvent` を使う余地

`VkImportMetalSharedEventInfoEXT` で Vulkan 側は `MTLSharedEvent` を待てるが、
**GL 側にはイベントを待つ API が無い** (CGL/GL に Metal イベントの概念が無い)。

つまり使えるのは片方向だけで、しかも Vulkan → GL の方向は
既に 0.315ms で足りている。**現時点で導入する理由が無い** [推測]。

GL → Vulkan の方向 (§2.2 の①) が高コストだった場合に、
「GL の代わりに Metal コマンドバッファで深度を書く」形なら効く可能性はあるが、
それは MC の描画そのものに手を入れることになり範囲外。

---

## 3. Phase 1 で保留した 2 項目

### 3.1 `AbstractRenderPipeline` の分割 — **合成パスと同時に設計する**

Phase 1 の判断 [確認済 — `phase1-completion.md` §2.2] は正しかった。
`initDepthStencil` は 2 つのことをしている:

1. **d32 → d24s8 のフルスクリーンコピー** (フォーマット不一致で blit できない)
2. **ステンシルで「MC 地形が無い領域」をマスク**

interop 版で 1 は<b>そのまま interop の仕事になる</b> — 深度を R32F で渡すので、
フォーマット変換のフルスクリーンパスが「GL → IOSurface」のパスに置き換わる。

**⚠ しかし 2 (ステンシル) は interop で運べない。**
IOSurface に stencil aspect は無く、Phase 0 が確立したのは色 (`BGRA`) と
深度 (`r00f`) の 2 面だけである [確認済 — Phase 0 §8.2]。

考えられる形 (いずれも [未検証]):

| 案 | 内容 | 懸念 |
|---|---|---|
| A | ステンシルを GL 側だけで持つ。Vulkan は深度だけ見る | Vulkan 側で「MC 地形がある領域」を落とせない → 描画量が増える |
| B | ステンシル相当を深度値で表現する (MC 地形がある画素の深度を近くする) | 既に `depthTex` がその役割 [確認済 — `quads.frag` は深度境界と比べて discard する]。**A と実質同じ** |
| C | 合成パス (GL 側) でステンシルテストする | Vulkan は全部描き、GL 合成時に捨てる。無駄が最大 |

**現行の `depthTex` の仕組みが既に B である**ことに注目したい。
`quads.frag` は `texelFetch(depthTex, ...)` と比べて手前なら discard する。
つまり<b>ステンシルマスクの役割は深度境界バッファが既に担っている</b>可能性がある [推測]。
だとすると案 A で足り、ステンシルは GL 合成側の最終段だけで済む。

→ **`initDepthStencil` の 2 つの役割のうち、Vulkan 側に要るのは深度だけ**
という見立て。ここは実装前に確認したい (§7 の質問 1)。

### 3.2 `Capabilities` の分離 — **焦点が消えた**

Phase 1 は「`getFreeDedicatedGpuMemory` がユニファイドメモリで何を返すべきか未確定」
として保留した。調べた結果、**この問いは実質的に消えている**。

**① macOS では現行 GL 経路でも常に無効** [確認済 — コードを読んだ]:

```java
this.canQueryGpuMemory = cap.GL_NVX_gpu_memory_info;   // NVIDIA 専用拡張
```

`getFreeDedicatedGpuMemory()` の呼び出し 7 箇所は<b>すべて `canQueryGpuMemory` で
ガードされている</b> [確認済 — grep で全件確認]。
macOS では既にフォールバック経路しか通らない。

**② Vulkan 側は素直に答えられる** [確認済 — 実機で計測]:

```
VK_EXT_memory_budget rev 1  利用可
heaps = 1, types = 3
heap 0: size=24.00 GiB flags=0x1 budget=17.76 GiB usage=0.00 GiB
```

**ヒープが 1 つしかない**ので「専用 VRAM」という概念は無く、
`heapBudget` が「使ってよい量」を直接返す。しかもこれはシステム全体の状況を
反映した動的な値である。

**提案**: `canQueryGpuMemory` を「予算が問い合わせられるか」に読み替え、
Vulkan では **true** を返して `heapBudget - heapUsage` を渡す。
`totalDedicatedMemory` は `heapSize` を返す。
ベンダ判定 (`isNvidia` 等) は<b>抽象に上げない</b> — Vulkan 実装では全部 false でよく、
呼び出し側 4 箇所はいずれも「NVIDIA/Intel のワークアラウンド」だから
[確認済 — sparse buffer と repFragTest]。

これで Phase 1 が挙げた 3 つの保留理由がすべて解ける。

> 副産物: `RenderResourceReuse.getGeometryBufferSize` が
> 「1.5GB の余裕を残す」という NVIDIA 前提の式を持っている [確認済]。
> budget 17.76 GiB のマシンでこれをそのまま使うと妥当な値になるが、
> 統合メモリでは「GPU に取られるとシステム全体が苦しくなる」ので
> 別の指針が要るかもしれない [未検証]。

---

## 4. 実データの投入経路

### 4.1 5 つの入力の由来 [確認済 — 実物を読んだ]

| 入力 | 由来 | interop が要るか |
|---|---|---|
| **ブロックアトラス** | Voxy が `ModelFactory` で焼く。`nglTextureSubImage2D` で<b>タイル単位に差分アップロード</b> | **不要** (§4.2) |
| **ライトマップ** | Minecraft の GL テクスチャ (`gameRenderer.levelLightmap()`) 16x16 | **要る (GL → Vulkan)** (§4.3) |
| **ジオメトリ / メタデータ** | `NodeManager` / `AsyncNodeManager` が CPU から書く | 不要 (ホスト書き込み) |
| **`indirectLookup`** | `HierarchicalOcclusionTraverser` が GPU で書く | 不要 (Vulkan 内で完結) |
| **HiZ** | `HiZBuffer.buildMipChain(depthBuffer, ...)` が MC の深度から作る | **要る** (深度 interop に乗る) |

### 4.2 アトラスは interop しないほうがよい

実アトラスの寸法は **12288 x 8192** である
[確認済 — `MODEL_TEXTURE_SIZE=16`、`16*3*256 x 16*2*256`]。RGBA8 で 402 MB + mip。

> これは Stage 1 で導出した「256x256 モデルタイル × 3x2 面セル」の実物である。
> 合成アトラス (768x512 = 面セル 1 テクセル) はこの<b>最小構成</b>だったことが確認できた。

`ModelFactory` は<b>タイル単位で CPU からアップロードしている</b>だけで、
GL でレンダリングしているわけではない [確認済 — `nglTextureSubImage2D` 1 箇所]。

→ **Vulkan の画像を作り、同じタイルを `vkCmdCopyBufferToImage` で上げればよい。**
interop は不要で、`VkTerrainResources.recordAtlasUpload` が既にその形をしている。
`ModelFactory` のアップロード先を差し替えるのが Phase 5 の作業になる。

### 4.3 ライトマップは GL → Vulkan の方向が要る

Minecraft が所有する GL テクスチャなので、**IOSurface backed にはできない**
(既存のテクスチャに後から IOSurface を紐づける API は無い [推測])。

しかし **16x16 = 1 KB** なので、毎フレーム `glGetTexImage` → ホストメモリ →
`vkCmdCopyBufferToImage` で十分 [推測]。転送量が問題になる規模ではない。

> Phase 0 §5 が「双方向 (MC → Voxy のライトマップ・ブロックアトラス転送も必要)」と
> 書いていたが、**アトラスは Voxy 側の所有物なので双方向ではない。**
> 実際に GL → Vulkan が要るのはライトマップ (1 KB) と深度だけである。

### 4.4 段階的に繋ぐ順序

依存関係は `HiZ → traversal → indirectLookup → cmdgen` である。
Phase 1〜4 と同じ手 (**まだ移していない段は合成データで代用する**) が使える。

| 段 | 実物にするもの | 代用のまま残すもの | 検証できること |
|---|---|---|---|
| ① | アトラス + ライトマップ | ジオメトリ / lookup / HiZ | 実テクスチャで合成ジオメトリが正しく描かれる。**アトラスの UV 計算が実寸で合っているか** |
| ② | ジオメトリ + メタデータ (`NodeManager`) | lookup (全セクション) / HiZ | 実データでテーブルの不変条件が成立する。**stateId の範囲、面ごとの quad 数、T 超過** |
| ③ | HiZ + traversal → `indirectLookup` | — | 可視セクション数が妥当、フレーム間で安定 |

**① が最初なのは、アトラスが唯一「合成データと実データで挙動が変わることが
既に分かっている」入力だから。** Stage 1 で「アトラスの UV 計算と噛み合っていなかった」
不具合を踏んでおり、実寸でもう一度確かめる価値がある。

---

## 5. 検証設計 — **完全な検証はできない**

結論から書く。**「GL 版と同じ絵か」を厳密に検証する手段は、この環境には無い。**
できるのは以下の 4 層で<b>不一致が出うる範囲を狭めていく</b>ことだけである。

### 5.1 層 1: `Mode.PER_SECTION` との比較 — 最も強い代替

`Mode.PER_SECTION` は<b>無改造の `lod/gl46/quads3.vert` を使い、
セクション単位で draw する</b>経路である。Phase 4 で参照として残してある。

**これは「GL がやっていたこと」に最も近い Vulkan 経路である。**
実データを入れて `PER_SECTION` と `MERGED` を比較すれば、
<b>統合ロジックの正しさは実データ上で確かめられる</b>。

| 検証できる | できない |
|---|---|
| draw 統合 (二分探索・テーブル生成) | **共有コードのバグ** (`quads.frag` / `quad_util.glsl` / モデル復号) |
| 実データの規模での索引解決 | GL と Vulkan のラスタライザ差 |
| T 超過時の分割 | 深度の約束事の食い違い |

**共有コードのバグが見えない**のが最大の穴である。
GL 版と Vulkan 版が同じ `quad_util.glsl` を読む以上、
そこに移植由来の誤りがあれば両方が同じように間違う。

### 5.2 ⚠ 層 1 には Phase 4 で予告した 2 つの障害がある

**障害 A: 描画順の違い (共平面)**

`PER_SECTION` はセクション順、`MERGED` は面順に発行する。
共平面の quad があると後勝ちが変わる。

**提案する測り方**: 実データを読み戻して<b>共平面ペアの数を数える</b>。
`SyntheticTerrain.footprints()` と同じ計算を実ジオメトリに適用すればよい
(必要なのは quad 配列とセクションメタデータだけで、どちらも読み戻せる)。

- 0 件なら → 障害は存在しない。差分ゼロを要求してよい
- 0 でないなら → **その位置の画素を除外して比較する**。
  除外した画素数を記録し、「差分は全て共平面由来」であることを確かめる

これは「差分ゼロにならないから諦める」ではなく、
<b>差分の原因を共平面に限定できたことを示す</b>検証になる。

**障害 B: 半透明の非決定性**

実データでは半透明が重なるのが普通で、バケット内順序が非決定的なぶん
絵がフレームごとに揺れる。**GL 版も同じ挙動である** [確認済 — `atomicAdd`]。

**提案する分け方**:

| 対象 | 比較方法 |
|---|---|
| 不透明 + temporal | **画素単位で差分ゼロ**を要求 (決定的なので) |
| 半透明 | 画素比較はしない。**バケット割り当てと、バケットごとの quad 集合**を比較する (これは決定的) |
| 半透明の揺れ幅 | 同じ入力で N フレーム描いて<b>揺れる画素数を測る</b>。「揺れている」こと自体を記録する |

最後のものは検証ではなく<b>特性の記録</b>だが、
「Vulkan 版だけが不安定なのか、元々そうなのか」を将来切り分けるための基準線になる。

### 5.3 層 2: 実データ上の不変条件検査

**参照画像が無くても成立する検査**で、Phase 4 の資産がそのまま使える:

| 検査 | 実データで何を捕まえるか |
|---|---|
| prefix が単調・末尾が総数 | テーブル生成の破綻 |
| 二分探索が空エントリに着地しない | 索引の off-by-one |
| (quad, セクション) の集合に重複が無い | 二重描画 |
| `clampedBuckets == 0` | 半透明バケットの T 超過 |
| stateId < モデル数 | 範囲外読み (バリデーションが捕まえない) |
| 面ごとの quad 数 < T | 分割の必要性 |

**これらは実データを入れた初日から動く。** 絵の正しさとは独立に、
「壊れていない」ことの下限を保証する。

### 5.4 層 3: 統計値の突き合わせ

`cmdgen.comp` の `HAS_STATISTICS` 経路が
`visibleSectionCounts[5]` / `quadCounts[5]` を LoD レイヤ別に集計している [確認済]。

Vulkan 版 cmdgen に同じ集計を足せば:
- テーブルの総 quad 数と統計値が一致するか (内部整合)
- LoD レイヤの分布が妥当か (人間の判断)

**GL 版の数字と比べられれば強い検証になるが、それには GL 版を動かす必要がある** (§5.5)。

### 5.5 層 4: 別マシンで GL 版の参照データを取れないか — **提案**

この Mac で GL 版が動かないのは `glDispatchComputeIndirect` と
`glMultiDrawElementsIndirectCountARB` が Apple の GL 4.1 に無いためである
[確認済 — `phase2-binding-audit.md` §8.4]。**GL 4.6 環境なら動く。**

もし GL 4.6 が動く環境 (Linux/Windows 実機、あるいは Mesa llvmpipe) が使えるなら:

```
GL 4.6 環境: 同じワールド・同じ座標で Voxy を起動
             → スクリーンショット + RenderStatistics をダンプ
                    ↓ 参照データとしてリポジトリに置く
macOS:       同じワールド・同じ座標で Vulkan 版を起動 → 突き合わせ
```

**これが唯一の真の「GL 版と同じ絵か」の検証になる。**
llvmpipe が必要な拡張を持つかは [未検証] だが、
参照データを取るだけなら速度は問題にならない。

> ⚠ 実現しても<b>ピクセル完全一致は期待できない</b>。
> ラスタライザ規則・浮動小数の丸め・ドライバ差があるため、
> 「主要な構造が一致するか」「統計値が一致するか」のレベルになる [推測]。

**これは環境の有無に依存するので指示を仰ぎたい** (§7 の質問 3)。

### 5.6 何が検証できて何ができないか — まとめ

| | 手段 | 強さ |
|---|---|---|
| 統合ロジック (実データ) | `PER_SECTION` vs `MERGED` | ★★★ (共平面を除外すれば) |
| テーブルの健全性 | 不変条件検査 | ★★★ |
| 半透明のバケット割り当て | 集合比較 | ★★★ |
| 半透明の絵 | 揺れ幅の記録のみ | ★ (特性の記録) |
| **共有シェーダコードの移植の正しさ** | **手段が無い** | — |
| **GL 版との一致** | 別マシンの参照データがあれば | ★★ (構造・統計レベル) |
| 全体の妥当性 | 目視 | ★ |

**「共有シェーダコードの移植の正しさ」に手段が無いことが、Phase 5 の本質的な限界である。**
`quad_util.glsl` / `quads.frag` は GL 版と Vulkan 版で同じファイルを読んでいるので、
移植で意味論がずれていても両方が同じようにずれる。

唯一の緩和は<b>Stage 1 でやった「CPU で全経路を予測して画素を突き合わせる」手法</b>を
実データの一部に適用することである
[確認済 — `singleQuadLandsWhereAndHowPredicted` が `[192,0,55,65]` を的中させた]。
実データでも「この quad はこの画素にこの色で出るはず」を CPU で計算できれば、
共有コードの意味論を独立に検証できる。**実データでこれが現実的かは [未検証]。**

---

## 6. 実装の段階分け

各段で<b>動作確認できる区切り</b>を優先した。

### 5a — interop の土台 (Minecraft なし)

`ObjC` / `IOSurf` / `Cgl` を取り込み、`VkInteropImage` を作る。
**素の JVM のテストで完結する** (Phase 0 の `Step7h` と同じ形)。

| 検証 | できること |
|---|---|
| 往復で値が保存される | 色 (BGRA) / 深度 (R32F) をビット単位で [Phase 0 §8.3 の 7h を常設テスト化] |
| **GL → Vulkan 方向の同期コスト** | §2.2 で未測定と判明した数字を測る |
| ライフサイクル | 作成/解放を繰り返してリークしない |

> **5a で最初に測るべきは GL → Vulkan の同期コスト。**
> Phase 0 は Vulkan → GL しか測っておらず、
> ここが高いと設計 (§2.2) が変わる。

### 5b — 合成パス (Minecraft なし)

GL の FBO に Vulkan の結果を合成する。`initDepthStencil` の再設計もここ (§3.1)。

| 検証 | できること |
|---|---|
| 既知の絵が GL 側に正しく出る | オフスクリーン GL FBO を読み戻して比較 |
| `gl_FragDepth` 書き戻し | 深度が保存される |
| ステンシル相当の扱い | §3.1 の案 A で足りるか |

### 5c — Minecraft への接続

`VoxyRenderSystem` / `AbstractRenderPipeline` を Vulkan 経路に繋ぐ。
**ここで初めて `./gradlew runClient` が意味を持つ。**

| 検証 | できること |
|---|---|
| 起動して落ちない | ライフサイクル・スレッド |
| フレームが回る | 同期の実コスト |
| 何かが画面に出る | 合成の接続 |

> ⚠ **ここから先はテストが自動化しにくい。** Minecraft を起動する必要があり、
> これまでの「素の JVM で 155 テスト」の枠組みから外れる。
> 5a/5b をできるだけ厚くして、5c に持ち込む未知を減らすべきである。

### 5d — 実データの投入 (§4.4 の ①②③)

### 5e — 検証 (§5 の 4 層)

---

## 7. 設計判断が要る箇所 — 実装前に指示を仰ぎたいもの

### 質問 1: ステンシルマスクの扱い (§3.1)

`initDepthStencil` のステンシルは IOSurface で運べない。
現行の `depthTex` (深度境界) が既に同じ役割を果たしているという見立てだが、
確証が無い。

- **案 A**: Vulkan 側は深度境界だけ見る。ステンシルは GL 合成の最終段のみ ← 推す
- **案 C**: Vulkan は全部描き、GL 合成時にステンシルで捨てる

A を推すが、`depthTex` とステンシルが本当に等価かは実装して確かめるしかない。
**先に調べるべきか、A で進めて壊れたら戻るか**の判断を仰ぎたい。

### 質問 2: ブロックアトラスの所有者 (§4.2)

402 MB のアトラスを:

- **案 A**: Vulkan 画像として作り直し、`ModelFactory` のアップロード先を差し替える ← 推す
- **案 B**: GL テクスチャのまま interop で共有する

A を推す (interop で 402MB を共有するより素直で、既に
`recordAtlasUpload` が同じ形をしている) が、`ModelFactory` は GL API を直書きしており
[確認済 — `nglTextureSubImage2D`]、**触る範囲が Phase 5 の想定より広がる**。

### 質問 3: GL 版の参照データを取る環境があるか (§5.5)

GL 4.6 が動く環境 (実機 / llvmpipe / CI) が使えるか。
使えるなら「GL 版と同じ絵か」に初めて手が届く。
使えないなら §5.6 の限界をそのまま受け入れることになる。

### 質問 4: `Capabilities` の分離範囲 (§3.2)

`heapBudget` で答える方針でよいか。
また `RenderResourceReuse` の「1.5GB の余裕を残す」式を
統合メモリ向けに見直すか (24 GiB 中 17.76 GiB が budget、
GPU に取られるとシステム全体が苦しくなる)。

### 質問 5: 5c 以降のテスト戦略

Minecraft を起動する検証は自動化が難しい。

- 手動確認 + スクリーンショットの目視で進めるか
- ヘッドレスで MC を起動して画像を吐く仕組みを作るか (工数大)

---

## 8. 副次的に判明したこと

### 8.1 `VK_KHR_index_type_uint8` / `VK_EXT_index_type_uint8` が使えた [確認済 — 実機で列挙]

Stage 4b で「Vulkan コアに 8bit インデックスは無い」として
立方体インデックスを 32bit にしたが、**拡張としては両方とも利用可能だった**。
記述は正確 (コアには無い) だが、選択肢としては 8bit も採れた。
36 個のインデックスなので実害は無い。将来 8bit が有利になる場面があれば使える。

### 8.2 利用可能な interop 関連拡張 [確認済 — 実機で列挙]

```
VK_EXT_metal_objects        rev 2   IOSurface → VkImage
VK_EXT_external_memory_metal rev 1
VK_EXT_host_image_copy      rev 1   interop が高コストだった場合のフォールバック
VK_EXT_memory_budget        rev 1   §3.2
VK_KHR_external_semaphore / _fd     GL 側が待てないので使い道は薄い
```

### 8.3 メモリ構成 [確認済 — 実機で計測]

```
heaps = 1, types = 3
heap 0: size = 24.00 GiB, budget = 17.76 GiB, usage = 0.00 GiB
```

ヒープが 1 つしかないことが、`Capabilities` の設計判断 (§3.2) を単純にしている。
