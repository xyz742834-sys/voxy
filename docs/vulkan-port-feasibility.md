# Voxy → Vulkan (MoltenVK / Apple Silicon) 移植実現可能性調査

**調査対象**: MCRcortex/voxy, ブランチ `dev`, commit `337b919d` (2026-08-14)
**調査日**: 2026-08-14
**前提環境**: Apple M4 Pro + MoltenVK 1.4.2 (Vulkan 1.4.357, INTEGRATED_GPU)
**本調査ではコードを一切変更していない。**

> **改訂 (2026-08-14)**: Phase 0 / B-1 の実機実測 ([`phase0-b1-mdi-measurement.md`](phase0-b1-mdi-measurement.md)) を受けて §1 / §3.6 / §6.7(a) / §8.2 / §9 / §10 / §11 を更新した。主な変更は (a) degenerate draw 単体案の撤回と draw 統合の必須化、(b) B-1 の go/no-go からの格下げと B-3 への go/no-go 移動、(c) push constant 上限確定によるデフォルト uniform 対処の難易度引き下げ。実測値を反映した箇所は **[実測済]** と表記する。

---

## 0. 表記規約

本レポートでは以下を厳密に区別する。

- **[確認済]** — 実際にファイルを読み、または grep で件数を数えて確認した事実。
- **[実測済]** — Phase 0 で実機ベンチマークにより測定した事実。出典は `phase0-b1-mdi-measurement.md`。
- **[推測]** — 確認した事実からの推論。検証はしていない。
- **[未検証]** — 本リポジトリからは判断できず、実測が必要な事項。

> **測定環境に関する注記** [実測済]: Phase 0 のベンチは LWJGL 同梱の **MoltenVK / Vulkan 1.2.296** で実施した。本レポートが前提とする brew 版 1.4.2 とはビルドが異なる。per-draw 課金の構造はドライバのコマンド展開方式に由来するため 1.4 でも同様と見込む [推測] が、移植本体は 1.4 を基準とするため、Phase 4 で 1.4 上での再確認が望ましい。

---

## 1. エグゼクティブサマリ

結論から言うと、**この移植は当初想定より条件が良い**。特に以下 3 点が事前想定を覆した。

| 事前想定 | 実際 [確認済] |
|---|---|
| sparse texture を使っていれば自前ページアロケータが必要 | **sparse texture は不使用。** 使っているのは `ARB_sparse_buffer` のみで、しかも NVIDIA + Windows 限定のフォールバック経路。macOS では機能検出で自動的に無効化される。**対処不要。** |
| bindless texture の代替評価が必要 | **bindless は一切不使用。** `ARB_bindless_texture`, `glGetTextureHandle*`, uint64 サンプラハンドルすべて 0 件。**対処不要。** |
| `drawIndirectCount = false` への対処が要設計 | 該当呼び出しは**わずか 2 箇所** [確認済]。ただし当初想定した degenerate draw 単体案は**実測により棄却された** [実測済]。詳細は §3.6。 |

そして最大の朗報:

> **`common/` + `commonImpl/` (計 8,992 行, 76 ファイル) には `org.lwjgl.opengl` の import が 1 件も存在しない。** [確認済]

storage / world / LoD ingest / voxelization 層は GL から完全に分離されており、**そのまま再利用できる**。「storage 層を再利用し rendering 層を新規実装する」という方針は**成立する**。

### 実測を受けた最大の設計変更 [実測済]

初版で「唯一の go/no-go 級の未知数」としていた **B-1 (MoltenVK 上の MDI 実効性能) は測定され、結論が出た**。

> **MoltenVK は indirect draw を per-draw 課金で処理する。バッチ効果はゼロで、Metal の Indirect Command Buffer による GPU 側展開は行われていない。**
> 実描画 **175 ns/draw**、degenerate でも **19 ns/draw**、サブミット時処理が内容非依存で **30 ns/draw**。

これにより **Voxy 現行のセクション単位 draw (88,000 件規模) は維持できない** — 18.0ms かかり、1 パス 1.5ms の予算を 12 倍超過する。degenerate 埋めによる `drawIndirectCount` 代替も 4.2ms で 2.8 倍超過し、**単体では成立しない**。

ただし同時に、**統合の目標値が当初懸念より遥かに緩い**ことも判明した:

> 200,000 三角形を `drawCount = 1` で描くと 18.444ms、`drawCount = 1,000` に分割しても 18.545ms。**差は 0.5%。**

つまり必要なのは「1 ドローまで畳む」ことではなく「**1,000 ドロー以下に収める**」ことである。面方向別 6 ドロー構成は増分 0.016ms で事実上コストゼロ。統合の追加工数は 300〜600 行と見積もられ、改訂後の総見積り 6,750〜11,350 行 (§8.2) に対して誤差の範囲に収まる。

**したがって B-1 は go/no-go ではなく「設計分岐の判定材料」に格下げされ、唯一の go/no-go は B-3 (GL↔Vulkan interop コスト) に移った。** → §9 参照。

---

## 2. 層の境界

### 2.1 パッケージ別 GL 依存

`org.lwjgl.opengl` を import しているファイル数 [確認済]:

| パッケージ | ファイル数 | 総行数 | GL import を持つファイル |
|---|---:|---:|---:|
| `me.cortex.voxy.common` | 65 | 7,175 | **0** |
| `me.cortex.voxy.commonImpl` | 11 | 1,817 | **0** |
| `me.cortex.voxy.client` | 151 | 22,020 | 43 |
| **合計** | **227** | **31,012** | **43** |

43 ファイルの内訳: `client/core` 40, `client/iris` 2, `client/mixin` 1。

### 2.2 GL 非依存層 (再利用可能)

以下は **GL に一切触れていない** [確認済]:

- `common/world/` — `WorldEngine`, `WorldSection`, `ActiveSectionTracker`, `Mapper` 等
- `common/config/storage/` — RocksDB / LMDB / Redis / in-memory バックエンド
- `common/voxelization/` — `WorldConversionFactory`
- `common/util/`, `common/thread/` — `AllocationArena`, `HierarchicalBitSet`, `ServiceManager` 等
- `commonImpl/importers/` — `WorldImporter` (532行), `DHImporter` (471行)

さらに **`client` 配下にも GL 非依存の大物が存在する** [確認済] — これが重要:

| ファイル | 行数 | GL import |
|---|---:|---:|
| `client/core/rendering/building/RenderDataFactory.java` | 1,806 | **0** |
| `client/core/rendering/hierachical/NodeManager.java` | 1,718 | **0** |
| `client/core/rendering/building/RenderGenerationService.java` | 364 | **0** |
| `client/core/model/bakery/SoftwareRasterizer.java` | 338 | **0** |
| `client/core/util/ScanMesher2D.java` | 335 | **0** |

つまり**メッシュ生成 (`RenderDataFactory`) と LoD ノード階層ロジック (`NodeManager`) という 2 大コアが、合計 3,524 行まるごと GL 非依存**である。これらは Vulkan バックエンドでも無改変で使える。

### 2.3 バックエンド抽象の有無

**存在する。ただし部分的。** [確認済]

`client/core/rendering/section/backend/AbstractSectionRenderer.java` (106行) は本物のバックエンド抽象である:

```java
public abstract class AbstractSectionRenderer<T extends Viewport<T>, J extends IGeometryData> {
    public abstract void renderOpaque(T viewport);
    public abstract void buildDrawCalls(T viewport);
    public abstract void renderTemporal(T viewport);
    public abstract void renderTranslucent(T viewport);
    public abstract T createViewport();
    public abstract void free();
}
```

リフレクションベースの `Factory` 登録機構まで用意されている (コンストラクタシグネチャを検証する `Factory.create(Class)`)。抽象メソッドのシグネチャに **GL 型は一切現れない** — `Viewport` と `IGeometryData` のみ。

しかし実際の選択ロジックは以下の通り [確認済] (`VoxyRenderSystem.java:79-82`):

```java
private static AbstractSectionRenderer.Factory<?,? extends IGeometryData> getRenderBackendFactory() {
    //TODO: need todo a thing where selects optimal section render based on if supports the pipeline and geometry data type
    return MDICSectionRenderer.FACTORY;
}
```

**実装は `MDICSectionRenderer` 1 つのみで、ハードコードされている。** つまり「複数バックエンドを想定した骨組みはあるが、実際に 2 つ目が刺さったことはない」状態。[推測] この抽象が本当に十分かは、2 つ目を実装して初めて分かる。

**NVIDIA 専用パスについて** [確認済]: `client/mixin/nvidium/MixinRenderPipeline.java` は Voxy 内のバックエンドではなく、**外部 MOD である Nvidium の `RenderPipeline#renderFrame` に注入するための mixin** である。Voxy 自身のバックエンド分岐ではない。Voxy 内の NVIDIA 依存は `Capabilities` 内の以下に限られる:
- `GL_NV_representative_fragment_test` (カリング高速化, 任意)
- `GL_NV_mesh_shader` (`ShaderType.MESH/TASK` に enum はあるが**使用箇所なし**)
- `GL_NVX_gpu_memory_info` (VRAM 量クエリ)
- `GL_NV_fragment_shader_barycentric`, `GL_NV_gpu_shader5` (シェーダ内 `#ifdef` ガード付き)

すべて任意経路であり、Vulkan バックエンドでは単純に無効化できる。

### 2.4 抽象化されて **いない** 層

**`AbstractRenderPipeline` (283行) は抽象クラスだが中身は GL 直書きである。** [確認済]

基底クラスの具象メソッド内に以下が直接書かれている:
- `glEnable(GL_STENCIL_TEST)`, `glStencilOp`, `glStencilFunc`, `glStencilMask` (`initDepthStencil`)
- `glClearNamedFramebufferfi`, `glBindFramebuffer`, `glBindTextureUnit`, `glBindSampler`
- `glMemoryBarrier(GL_FRAMEBUFFER_BARRIER_BIT | GL_PIXEL_BUFFER_BARRIER_BIT)` (`innerPrimaryWork`)
- `GlFramebuffer scratchFramebuffer`, `DepthFramebuffer fb` をフィールドとして保持
- `static final int DEPTH_SAMPLER = glGenSamplers()` を static 初期化子で生成

GL import は 29 件で全ファイル中最多。**ここが「バックエンド抽象が届いていない」最大の箇所**であり、Vulkan 化では `AbstractRenderPipeline` を実質的に書き直す必要がある。

### 2.5 判定

> **「storage 層を再利用し rendering 層を新規実装する」は成立する。** [確認済 — GL import の分布で裏付け]

障害となるのは以下の 2 点のみ:

1. `AbstractRenderPipeline` が抽象でなく GL 具象である (283行の書き直し)
2. `Capabilities` (223行) が GL コンテキストから直接 capability を引く設計 (`GL.getCapabilities()`, シェーダ試験コンパイル) になっている。Vulkan では `VkPhysicalDeviceFeatures` から引く同等物に差し替えが必要。

どちらも**局所的**であり、層構造そのものの破綻ではない。

---

## 3. GL 呼び出しの棚卸し

### 3.1 全体規模

| 指標 | 件数 [確認済] |
|---|---:|
| 使用されている GL 関数の種類 | **144** |
| 使用されている GL 定数の種類 | **173** |
| GL 関数/定数に言及するソース行 (Iris 除く) | **871** |
| GL import を持つファイル (Iris 除く) の総行数 | 7,917 |
| `client/core/gl/` パッケージ (GL 薄ラッパ層) | **1,487** |

重要なのは最後から 2 番目と 3 番目の対比である。**GL に触れるファイルは 7,917 行あるが、そのうち実際に GL API に言及している行は 871 行 (11%)** にすぎない。残りは CPU 側ロジックであり、大部分が保存できる。

ファイル別の GL 呼び出し密度 [確認済]:

| ファイル | 総行数 | GL 言及行 | 密度 |
|---|---:|---:|---:|
| `rendering/hierachical/AsyncNodeManager.java` | 1,033 | 17 | 1% |
| `model/ModelFactory.java` | 1,000 | 7 | 0% |
| `VoxyRenderSystem.java` | 588 | 37 | 6% |
| `rendering/hierachical/HierarchicalOcclusionTraverser.java` | 407 | 41 | 10% |
| `rendering/section/backend/mdic/MDICSectionRenderer.java` | 396 | 103 | **26%** |
| `AbstractRenderPipeline.java` | 283 | 62 | **21%** |
| `gl/Capabilities.java` | 223 | 79 | **35%** |
| `SSAO.java` | 175 | 37 | 21% |
| `rendering/util/HiZBuffer.java` | 137 | 48 | **35%** |

GL 密度が高いのは薄い低レベルファイルに集中しており、**大物ファイルほど GL 密度が低い**。これは移植にとって理想的な分布である。

### 3.2 compute shader (`glDispatchCompute` 系)

**18 箇所** [確認済]。うち 2 箇所は `glDispatchComputeIndirect`。

| ファイル | 行 | 備考 |
|---|---|---|
| `rendering/hierachical/HierarchicalOcclusionTraverser.java` | 328, **343 (Indirect)** | 階層トラバーサル本体。343 は反復ディスパッチ |
| `rendering/hierachical/NodeCleaner.java` | 116, 126, 163 | ノード GC |
| `rendering/hierachical/AsyncNodeManager.java` | 566, 588 | ノードコピー / 更新 |
| `rendering/section/backend/mdic/MDICSectionRenderer.java` | 271, **324 (Indirect)**, 346, **359 (Indirect)** | draw call 生成 |
| `rendering/util/HiZBuffer2.java` | 119 | **未使用経路** (§5.4 参照) |
| `SSAO.java` | 157 | 任意機能 |
| `rendering/hierachical/DebugRenderer.java` | 65 | デバッグのみ |
| `gl/Capabilities.java` | 179 | AMD ドライバ不具合検出テスト |

**対処難易度: 容易。** compute / dispatch indirect は Vulkan 1.0 コア。`vkCmdDispatch` / `vkCmdDispatchIndirect` に 1:1 対応する。

### 3.3 SSBO

`GL_SHADER_STORAGE_*` / `glBindBufferBase` / `glBindBufferRange` の言及行数 [確認済]:

| ファイル | 件数 |
|---|---:|
| `rendering/section/backend/mdic/MDICSectionRenderer.java` | 39 |
| `rendering/hierachical/NodeCleaner.java` | 12 |
| `rendering/hierachical/HierarchicalOcclusionTraverser.java` | 11 |
| `rendering/hierachical/AsyncNodeManager.java` | 11 |
| `rendering/hierachical/DebugRenderer.java` | 6 |
| `gl/shader/AutoBindingShader.java` | 6 |
| `VoxyRenderSystem.java` | 6 |
| `model/ModelStore.java` | 4 |
| `iris/IrisVoxyRenderPipelineData.java` | 3 |
| `gl/shader/PrintfInjector.java` | 3 |
| `gl/Capabilities.java` | 3 |
| `rendering/util/UploadStream.java` | 1 |
| `IrisVoxyRenderPipeline.java` | 1 |
| **合計** | **106** |

**重要な設計上の利点** [確認済]: バインディング番号は**すべて数値リテラルまたは `#define` で明示されている**。`AutoBindingShader` は GL のシェーダリフレクション (`glGetProgramResourceIndex` 等) を**使っておらず**、Java 側の `#define` マップから直接インデックスを引く実装になっている:

```java
public AutoBindingShader ssbo(String define, GlBuffer binding) {
    return this.ssbo(Integer.parseInt(this.defines.get(define)), binding, 0);
}
```

これは Vulkan の descriptor set レイアウトへ機械的に写像できることを意味する。**リフレクション依存がないのは移植上きわめて有利。**

**対処難易度: 容易〜要設計。** SSBO 自体は Vulkan コア。要設計なのは「GL の flat な binding point 空間 (0..N)」を「Vulkan の (set, binding) 2 次元空間」へ写像する規約を決める部分。descriptorIndexing = true なので自由度は高い。

### 3.4 `glBufferStorage` / 永続マップ

**9 箇所** [確認済]:

| ファイル:行 | 内容 |
|---|---|
| `gl/GlBuffer.java:45` | `nglNamedBufferStorage` (全バッファの基底) |
| `gl/GlPersistentMappedBuffer.java:16-17` | `GL_MAP_PERSISTENT_BIT` + `nglMapNamedBufferRange` |
| `rendering/util/UploadStream.java:38` | `GL_CLIENT_STORAGE_BIT｜GL_MAP_WRITE_BIT｜GL_MAP_UNSYNCHRONIZED_BIT` + coherent または `FLUSH_EXPLICIT` |
| `rendering/util/DownloadStream.java:38` | `GL_MAP_READ_BIT` |
| `rendering/util/RawDownloadStream.java:30` | `GL_MAP_READ_BIT｜GL_MAP_COHERENT_BIT` |
| `gl/Capabilities.java:155` | 機能テスト用 |

**対処難易度: 容易 (むしろ有利)。** ターゲットが `INTEGRATED_GPU` (ユニファイドメモリ) であるため、`HOST_VISIBLE | HOST_COHERENT | DEVICE_LOCAL` なメモリタイプが全域で利用できる。GL の永続マップは Vulkan の `vkMapMemory` を永続保持する形にそのまま置換でき、しかも**ディスクリート GPU 向けに書かれた staging 経路が不要になるぶん単純化される**。`UploadStream` (187行) / `DownloadStream` (189行) はロジックを保ったままバッファ生成部だけ差し替えられる [推測]。

> **Phase 0 で裏付け済み** [実測済]: `DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT | **HOST_CACHED**` を全て備えたメモリタイプの存在と、**ヒープが 1 つのみ**であることを実機確認した。`HOST_CACHED` まで揃っているため読み戻しも速く、`HOST_COHERENT` により `glFlushMappedNamedBufferRange` 相当 (`UploadStream.java:81,117`) の明示フラッシュも不要になる。詳細と工数への反映は §8.2 を参照。

### 3.5 DSA (`glCreate*` / `glNamed*`)

**33 箇所** [確認済]。内訳:

| 関数 | 件数 |
|---|---:|
| `glNamedFramebufferTexture` | 5 |
| `glCreateSamplers` | 4 |
| `glNamedFramebufferDrawBuffers` | 3 |
| `glCreateVertexArrays` | 3 |
| `glCreateTextures` | 3 |
| `glCreateShader` | 3 |
| `glCreateBuffers` | 3 |
| `glNamedFramebufferDrawBuffer` | 2 |
| `glNamedBufferStorage` | 2 |
| `glCreateProgram` | 2 |
| `glCreateFramebuffers` | 2 |
| その他 (`glNamedRenderbufferStorage` 等) | 4 |

**対処難易度: 容易。** DSA は「バインドせずにオブジェクトを操作する」API であり、Vulkan のオブジェクトモデルとそもそも思想が一致している。むしろ**非 DSA の GL コードより移植しやすい**。33 箇所という数も小さい。

`glCreateVertexArrays` が 3 件あるが、§5.6 の通り実際の描画は VAO を使わない (vertex pulling) ため、実質的な移植対象は空の VAO 生成のみ。

### 3.6 Indirect draw 全般

**`glMultiDrawElementsIndirectCountARB`: 2 箇所のみ** [確認済]

| 場所 | 用途 |
|---|---|
| `MDICSectionRenderer.java:204` | `renderTerrain()` — 不透明 + temporal 描画 (共通ヘルパのため実質 2 用途) |
| `MDICSectionRenderer.java:245` | 半透明描画 |

`renderTerrain` は `renderOpaque` (225行) と `renderTemporal` (369行) の両方から呼ばれるため、**呼び出しサイトは 2 だが論理的な描画パスは 3 本**。

**`glDrawElementsIndirect`: 2 箇所** [確認済]
- `MDICSectionRenderer.java:294` — オクルージョンカリングのラスタライズパス
- `hierachical/DebugRenderer.java:76` — デバッグのみ

**`glDispatchComputeIndirect`: 3 箇所** (§3.2 参照) — これは `drawIndirectCount` とは無関係、Vulkan コア。

#### `drawIndirectCount = false` への対処

> **⚠ 改訂 (Phase 0 実測後)**: 初版はこの項を「実装は容易」と評価していた。**この評価は撤回する。** degenerate draw 方式は単体では予算を 2.8 倍超過し成立しない [実測済]。以下、初版の分析を残したうえで実測結果と改訂後の方針を示す。

##### (初版の分析 — バッファレイアウト面では依然として正しい)

[確認済]:

`lod/gl46/cmdgen.comp` の描画コマンド生成は以下の形になっている:

```glsl
uint cmdCnt = bitCount(msk);
uint cmdPtr = atomicAdd(opaqueDrawCount, cmdCnt);
...
void writeCmd(uint idx, uint instance, uint offset, uint quadCount) {
    DrawCommand cmd;
    cmd.count = quadCount * 6;
    cmd.instanceCount = 1;
    cmd.firstIndex = 0;
    cmd.baseVertex = int(offset)<<2;
    cmd.baseInstance = instance;
    cmdBuffer[idx] = cmd;
}
```

つまり **`atomicAdd` によってコマンドは index 0 から隙間なく詰めて書かれる**。`drawCount` は「有効な先頭 N 件」を意味する。したがってバッファレイアウト上は、末尾を `indexCount = 0` で埋めれば `drawIndirectCount` なしでも正しい絵が出る。**この点は実測後も変わらない。**

`maxDrawCount` は固定上限ではなく実セクション数から算出されている [確認済] (`MDICSectionRenderer.java:225`):

```java
this.renderTerrain(viewport, 0, 4*3,
    Math.min((int)(this.geometryManager.getSectionCount()*4.4+128), OPAQUE_DRAW_COUNT));
```

`OPAQUE_DRAW_COUNT = 400_000` は上限クランプにすぎず、通常時は `sectionCount * 4.4 + 128` になる。

##### 実測結果 — degenerate draw 単体案は成立しない [実測済]

問題は「正しい絵が出るか」ではなく「発行する draw 件数のコスト」だった。MoltenVK の per-draw 課金は degenerate コマンドにも及ぶ:

| 成分 | 実測値 [実測済] |
|---|---:|
| 実描画 (`gpu_ms`) | 175 ns/draw |
| **degenerate (`gpu_ms`)** | **19 ns/draw** |
| サブミット時処理 (内容非依存) | 30 ns/draw |

degenerate であっても `19 + 30 = 49 ns/draw` が課金される。`sectionCount = 20,000` → 88,128 draws での試算:

| 条件 | コスト [実測済] | 1 パス予算 1.5ms との比 |
|---|---:|---:|
| 実描画 88,000 件 | 18.0 ms | **12.0 倍超過** |
| **全件 degenerate 88,000 件** | **4.2 ms** | **2.8 倍超過** |
| 予算 1.5ms に収まる draw 数 | 約 7,300 件 | — |

さらに `HALF` 条件 (実描画と degenerate が半々) が両者の算術平均と誤差 0.05% で一致した [実測済] ことから、**バッチ効果は存在しない**ことが確認されている。「degenerate なら安い」という前提そのものが不成立。

##### 改訂後の方針 — draw 統合が前提

**`drawIndirectCount = false` は単独で解ける問題ではなく、draw 統合の一部として解消される。** 統合後の draw 数が数百〜1,000 件規模になれば:

- degenerate 埋めのコストは無視できる (1,000 件で 0.10ms) [実測済]
- CPU 側で `maxDrawCount` を固定発行しても問題にならない
- **`drawIndirectCount` の不在という制約自体が消滅する**

統合の目標値は **1,000 draws 以下** [実測済]。「1 ドローまで畳む」必要はなく、面方向別 6 ドロー構成は増分 0.016ms でコストゼロ。

統合に伴う主要な設計変更 [実測済 — `phase0-b1-mdi-measurement.md` §4.1]:

- `cmdgen.comp` (220行): 「88,000 件の DrawCommand 生成」→「数百件の DrawCommand + セクションオフセットテーブル生成」
- **`firstInstance` によるセクション ID 伝達は廃止** → prefix sum で `gl_VertexIndex` → (section, quad) を解決
- `quads3.vert:54` の `positionBuffer[gl_BaseInstance]` は索引方式の変更が必要

なお `firstInstance` の有無は性能に**完全に無影響**であることが確認されている [実測済] ため、これを捨てることによる損失はない。

**対処難易度: 要設計 (draw 統合の一部として)。** degenerate 埋め自体は数十行だが、それが効くのは統合後の話であり、単体では意味を持たない。統合の追加工数は 300〜600 行 [実測済 — 同 §4.3]。

##### 却下された代替案

初版で挙げた「`DownloadStream` で `drawCount` を 1〜2 フレーム遅延で CPU に読み戻す」案は、**そもそも不要になった**。統合後は draw 数が数百なので上限発行のコストが無視でき、遅延読み戻しによる 1 フレームずれのリスクを負う理由がない。

### 3.7 `glMemoryBarrier` / image load-store / atomic counter

**`glMemoryBarrier`: 42 箇所** [確認済]

| ファイル | 件数 |
|---|---:|
| `rendering/section/backend/mdic/MDICSectionRenderer.java` | 12 |
| `rendering/hierachical/NodeCleaner.java` | 6 |
| `rendering/hierachical/HierarchicalOcclusionTraverser.java` | 6 |
| `rendering/hierachical/AsyncNodeManager.java` | 5 |
| `rendering/util/UploadStream.java` | 3 |
| `rendering/util/DownloadStream.java` | 3 |
| `rendering/util/HiZBuffer.java` | 2 |
| `rendering/hierachical/DebugRenderer.java` | 2 |
| `util/GPUTiming.java` | 1 |
| `rendering/util/HiZBuffer2.java` | 1 |
| `AbstractRenderPipeline.java` | 1 |

**これが移植で最も注意を要する項目である。** GL の `glMemoryBarrier` は「グローバルなバリア、ビットマスクでどのアクセス種別か指定」という粗い意味論だが、Vulkan の `vkCmdPipelineBarrier2` は **(srcStage, srcAccess, dstStage, dstAccess) + リソース単位**という細かい意味論を要求する。1:1 変換はできず、**42 箇所すべてについて「何を待っているのか」を読み解いて記述し直す必要がある**。

さらに GL では暗黙に保証されているが Vulkan では明示が必要な同期 (同一バッファへの連続 compute 書き込み等) が**バリアが書かれていない箇所にも潜在的に必要**である [推測]。つまり 42 箇所は下限であり、上限ではない。

**image load/store** [確認済]: `imageStore` の使用は 2 ファイルのみ:
- `post/ssao.comp` — `rgba8 writeonly image2D` 1 個 (任意機能)
- `hiz/hiz.comp` — `r32f writeonly image2D` 6 個 (**未使用経路**, §5.4)

`imageLoad` は 0 件。**atomic counter (`atomic_uint` / `GL_ATOMIC_COUNTER_BUFFER`) は 0 件** — atomic はすべて SSBO 上の `atomicAdd` / `atomicCompSwap` であり、これは Vulkan でそのまま動く。

**対処難易度: バリア = 困難 (本移植の最大の工数集中点)。image/atomic = 容易。**

### 3.8 fence / sync

**`glFenceSync` / `glClientWaitSync` / `glDeleteSync`: `gl/GlFence.java` (44行) に完全にカプセル化されている** [確認済]。使用箇所は 13, 21, 42 行の 3 箇所のみ。

**対処難易度: 容易。** 44 行のクラスを `VkFence` または timeline semaphore で置き換えるだけ。Vulkan 1.4 なので timeline semaphore はコア。

ただし **`glFinish` が 17 箇所ある** [確認済] のが気になる:

| ファイル | 件数 |
|---|---:|
| `rendering/util/DownloadStream.java` | 6 |
| `rendering/section/geometry/BasicSectionGeometryData.java` | 4 |
| `VoxyRenderSystem.java` | 3 |
| `rendering/util/RawDownloadStream.java` | 2 |
| `rendering/util/UploadStream.java` | 1 |
| `model/bakery/SoftwareModelTextureBakery.java` | 1 |

`glFinish` は「GPU 完了まで CPU をブロック」であり、Vulkan では `vkQueueWaitIdle` / `vkDeviceWaitIdle` に相当する。多くはリソース解放やバッファ再確保時の安全策 [確認済 — `BasicSectionGeometryData` の 4 件はいずれも sparse commitment 変更またはバッファ解放の前後] なので、Vulkan でも同等の同期で置き換え可能。ただし `DownloadStream` の 6 件はホットパスに近い可能性があり、fence ベースへの書き換えが望ましい [推測]。

### 3.9 LWJGL の GL42/43/44/45/46 および ARB* import

上記 §3.1 の通り GL import を持つファイルは 43 (Iris 含む)。バージョン別の主な依存 [確認済]:

| 依存元 | 主な用途 | Vulkan での扱い |
|---|---|---|
| `GL42` (`glMemoryBarrier`) | §3.7 の 42 箇所 | 要書き換え |
| `GL43C` (`glDispatchCompute`, `GL_SHADER_STORAGE_BUFFER`) | compute + SSBO | コア相当 |
| `GL44` (`GL_MAP_PERSISTENT_BIT`, `GL_MAP_COHERENT_BIT`, `GL_CLIENT_STORAGE_BIT`) | 永続マップ | ユニファイドメモリで有利化 |
| `GL45/GL45C` (DSA 全般) | §3.5 の 33 箇所 | 思想が一致、容易 |
| `ARBComputeShader` | `SSAO.java:13` | コア相当 |
| `ARBIndirectParameters` | `MDICSectionRenderer.java:29-30` — **`GL_PARAMETER_BUFFER_ARB`, `glMultiDrawElementsIndirectCountARB`** | **要対処 (§3.6)** |
| `ARBSparseBuffer` | `RenderResourceReuse`, `GlBuffer`, `BasicSectionGeometryData` | **macOS では不使用 (§4)** |
| `ARBTimerQuery` | `util/GPUTiming.java` (`GL_TIMESTAMP`, `glQueryCounter`) | `VkQueryPool` へ、容易 |
| `ARBDirectStateAccess` | `AutoBindingShader.java:11` | 容易 |
| `NVXGPUMemoryInfo` | `Capabilities.java` VRAM クエリ | `VK_EXT_memory_budget` または削除 |
| `NVMeshShader` | `ShaderType.java` の enum 定義のみ — **使用箇所 0** | 削除可 |

---

## 4. sparse texture の使用有無

### 結論: **sparse texture は使用していない。対処不要。**

`glTexPageCommitment` / `ARB_sparse_texture` / `VirtualTexture` の検索結果は **0 件** [確認済]。

代わりに使われているのは **`ARB_sparse_buffer`** であり、これは全く別の機能 (テクスチャではなくバッファのページコミット) である。使用箇所 [確認済]:

| ファイル:行 | 内容 |
|---|---|
| `gl/Capabilities.java:59` | `this.sparseBuffer = cap.GL_ARB_sparse_buffer;` |
| `gl/GlBuffer.java:8,46,63-64` | `GL_SPARSE_STORAGE_BIT_ARB` フラグ保持 + `isSparse()` |
| `core/RenderResourceReuse.java:62-91` | ジオメトリバッファ確保のフォールバック |
| `rendering/section/geometry/BasicSectionGeometryData.java:51-145` | `glBufferPageCommitmentARB` によるコミット拡縮 |

### 用途

`BasicSectionGeometryData` の巨大なジオメトリバッファ (`1<<20` セクション分) を確保する際、**通常確保が `GL_OUT_OF_MEMORY` で失敗した場合の救済措置**として sparse buffer を使い、実際に使う範囲だけページコミットする。`sparseCommitment` フィールドで現在のコミット範囲を追跡し、必要に応じて `glBufferPageCommitmentARB(..., true)` で拡張、解放時に `false` でデコミットする。

### macOS での扱い

該当コードは以下でガードされている [確認済] (`BasicSectionGeometryData.java:51`, `RenderResourceReuse.java:62`):

```java
if (!(Capabilities.INSTANCE.isNvidia && ThreadUtils.isWindows && Capabilities.INSTANCE.sparseBuffer)) {
```

および `if ((buffer == null || error == GL_OUT_OF_MEMORY) && Capabilities.INSTANCE.sparseBuffer)`。

つまり **`Capabilities.INSTANCE.sparseBuffer == false` なら経路全体が死ぬ**。Vulkan バックエンドでは `sparseBinding = false` に対応して `sparseBuffer = false` 相当を返せばよく、**自前ページアロケータの実装は不要**。

### 代替可能性の評価

固定サイズプール + 空きリスト管理での代替は**そもそも必要ない**が、仮に必要になった場合でも:
- コミット範囲は `sparseCommitment` という**単一の線形範囲**でしか管理されていない [確認済] (`glBufferPageCommitmentARB(GL_ARRAY_BUFFER, this.sparseCommitment, size - this.sparseCommitment, true)`)
- つまり「先頭から連続 N バイト」だけのモデル。断片化管理は存在しない
- ユニファイドメモリ環境では物理 VRAM 上限そのものが存在しないため、この救済措置の動機自体が弱い [推測]

**対処難易度: 容易 (実質的に作業不要)。**

---

## 5. bindless texture の使用有無

### 結論: **一切使用していない。対処不要。**

以下すべて **0 件** [確認済]:
- `ARB_bindless_texture`
- `glGetTextureHandleARB` / `glGetTextureSamplerHandleARB`
- `glMakeTextureHandleResidentARB`
- GLSL 側の `uvec2` → `sampler2D` キャスト、`sampler2D(...)` コンストラクタ呼び出し

テクスチャバインドはすべて従来型の `glBindTextureUnit(unit, tex)` (DSA) である [確認済 — `AutoBindingShader.java:11`, `MDICSectionRenderer.java:178,212` 等]。

`descriptorIndexing = true` は使えるが、**使う必要がない**。ただし [推測] Vulkan バックエンドを書く際、テクスチャユニットごとの descriptor set 更新を減らすために descriptorIndexing を「あえて使う」設計を選ぶことはできる。これは移植の必須要件ではなく最適化の選択肢。

**対処難易度: 容易 (作業不要)。**

---

## 6. GLSL シェーダの棚卸し

### 6.1 ファイル一覧と規模

**47 ファイル / 3,076 行** [確認済]。

| 拡張子 | ファイル数 | 意味 |
|---|---:|---|
| `.comp` | 15 | compute |
| `.glsl` | 12 | インクルード (`#import` 対象) |
| `.frag` | 10 | fragment |
| `.vert` | 6 | vertex |
| `.vsh` / `.fsh` | 2 / 2 | vertex / fragment (旧命名) |

ディレクトリ別: `post/` 9, `lod/` 7, `lod/gl46/` 6, `util/` 4, `lod/hierarchical/` 4, `lod/hierarchical/debug/` 3, `lod/hierarchical/cleaner/` 3, `hiz/` 3, `util/prefixsum/` 2, `lod/gl46/test/` 2, `lod/gl46/cull/` 2, `chunkoutline/` 2。

**Java から参照されている実稼働シェーダは 29 本** [確認済]。残りは以下 6 本が**未参照 (デッドコード)** [確認済]:

```
post/depth_copy.frag
post/blit_texture_cutout.frag
post/depth0.frag
util/set.comp
lod/gl46/test/raw.frag
lod/gl46/test/raw.vert
```

移植対象から外せる。

主要な実稼働シェーダの行数 [確認済]:

| シェーダ | 行数 | 役割 |
|---|---:|---|
| `lod/gl46/quads.frag` | 252 | メイン地形 FS |
| `lod/gl46/cmdgen.comp` | 220 | **draw call 生成 (移植の中核)** |
| `lod/hierarchical/traversal_dev.comp` | 191 | 階層オクルージョントラバーサル |
| `lod/gl46/quads3.vert` | 83 | メイン地形 VS |
| `lod/gl46/cull/raster.vert` | 61 | オクルージョンカリング |
| `lod/gl46/buildtranslucents.comp` | 50 | 半透明ソート |
| `lod/gl46/prep.comp` | 23 | ディスパッチ準備 |
| `lod/gl46/cull/raster.frag` | 15 | |

インクルード (`.glsl`) [確認済]: `lod/hierarchical/screenspace.glsl` 198, `lod/quad_util.glsl` 188, `lod/hierarchical/node.glsl` 102, `lod/gl46/bindings.glsl` 94, `lod/quad_format.glsl` 78, `util/depthutils.glsl` 66, `lod/hierarchical/queue.glsl` 56, ほか 5 本 (計 132)。

### 6.2 `#version` の分布

[確認済]:

| バージョン | 件数 |
|---|---:|
| `#version 460 core` | 16 |
| `#version 450` | 8 |
| `#version 330 core` | 7 |
| `#version 460` | 3 |
| `#version 450 core` | 1 |

**評価**: 大半が 450/460 であり、Vulkan GLSL (glslang の `--target-env vulkan1.x`) が要求する最低ラインを満たす。`330` の 7 本はブリット/デバッグ系の単純なシェーダ [推測 — `post/`, `chunkoutline/`, `hiz/blit.*` が該当] であり、450 への引き上げは機械的。

### 6.3 `layout(std430/std140)`

**18 ファイルで使用、計 55 箇所** [確認済]。上位:

| ファイル | 件数 |
|---|---:|
| `lod/gl46/bindings.glsl` | 10 |
| `lod/hierarchical/traversal_dev.comp` | 5 |
| `lod/hierarchical/queue.glsl` | 4 |
| `lod/hierarchical/cleaner/result_transformer.comp` | 4 |
| `util/scatter.comp` | 3 |
| `util/memcpy.comp` | 3 |
| (以下 12 ファイル、各 1〜2) | |

**対処難易度: 容易。** `std430` / `std140` は Vulkan GLSL でもそのまま有効。レイアウト規則も同一。

### 6.4 64bit 整数

**`lod/quad_format.glsl` に集中している** [確認済]。9 箇所すべてが同一ファイル:

```glsl
#define Quad uint64_t
vec3 extractPos(uint64_t quad) { ... }
ivec2 extractSize(uint64_t quad) { ... }
uint extractFace(uint64_t quad) { ... }
uint extractStateId(uint64_t quad) { ... }
uint extractBiomeId(uint64_t quad) { ... }
uint extractLightId(uint64_t quad) { ... }
bool isQuadEmpty(uint64_t quad) { return quad == uint64_t(0); }
```

`#extension GL_ARB_gpu_shader_int64 : enable` が 5 ファイルに存在 [確認済]。`quads3.vert` では以下のフォールバック分岐がある [確認済]:

```glsl
#extension GL_ARB_gpu_shader_int64 : enable
#ifdef GL_ARB_gpu_shader_int64
#define QUAD_DATA_USE_64_BIT
#endif
```

**`shaderInt64 = true` [実測済 — vulkaninfo および Phase 0 の双方で確認] なので、この分岐は 64bit 側が採られる。** SPIR-V では `GL_ARB_gpu_shader_int64` を `#extension` として書く代わりに `Int64` capability が有効になればよく、glslang が自動で処理する。事前想定通り **uvec2 手書き分解は不要** [確認済 — 分解パスは `#else` として存在するが使う必要がない]。

**対処難易度: 容易。**

### 6.5 atomic 操作

**20 箇所** [確認済]。すべて SSBO 上の操作であり、atomic counter buffer は不使用:

| シェーダ | 内容 |
|---|---|
| `lod/gl46/cmdgen.comp` | `atomicAdd` × 7 (draw count, visible count, translucent) |
| `lod/hierarchical/traversal_dev.comp` | `atomicAdd` × 6 (request queue, render queue, counters) |
| `lod/hierarchical/cleaner/sort_visibility.comp` | `atomicCompSwap` × 2, ヘルパ関数 × 4 |
| `lod/hierarchical/queue.glsl` | `atomicAdd` × 2 |
| `lod/gl46/buildtranslucents.comp` | `atomicAdd` × 1 |

`atomicCompSwap` を使った lock-free な max-exchange (`atomicDerefMaxExchangeGlobal` / `...Local`) が `sort_visibility.comp` にある [確認済]。これは Vulkan でもそのまま動く。

**対処難易度: 容易。**

### 6.6 `gl_DrawID` / `gl_BaseInstance`

**`gl_DrawID` / `gl_DrawIDARB` の使用は 0 件** [確認済]。これは重要な発見で、`gl_DrawID` 相当の移植問題は**そもそも発生しない**。

代わりに **`gl_BaseInstance` が使われている** [確認済]:

| 場所 | 内容 |
|---|---|
| `lod/gl46/quads3.vert:54` | **`uvec2 pos = positionBuffer[gl_BaseInstance];`** — メイン地形描画パス |
| `chunkoutline/outline.vsh:31` | `uint id = (gl_InstanceID<<5)+gl_BaseInstance+(gl_VertexID>>3);` |
| `lod/gl46/test/raw.vert:18,21,24` | (未参照シェーダ) |

そして `cmdgen.comp:47` で [確認済]:

```glsl
cmd.baseInstance = instance;   // instance == drawId == gl_GlobalInvocationID.x
```

**つまり事前想定は完全に正しい。** `baseInstance` を section ID の運び先として使う設計がすでに実装されており、**`drawIndirectFirstInstance = true` [実測済] がこれを支える**。この capability が false であれば移植は成立しなかった。

> **改訂 (Phase 0 実測後)**: `firstInstance` → `gl_InstanceIndex` の伝達は実機で確認された (200 を設定 → 200 が届く) [実測済]。`shaderDrawParameters = true` なので `gl_BaseInstance` 経路も使え、**両方が利用可能**。
>
> **ただし §3.6 の draw 統合方針により、この経路は採用しない見込みである。** 統合後は 1 draw が複数セクションを束ねるため、draw 単位の `baseInstance` ではセクションを識別できない。代わりに **prefix sum による `gl_VertexIndex` → (section, quad) 解決**に置き換わる [実測済 — `phase0-b1-mdi-measurement.md` §4.1]。
>
> なお `firstInstance` の有無は性能に完全に無影響であることも確認されており [実測済]、この経路を捨てることによる損失はない。ここで重要なのは「capability が存在したこと」ではなく「存在しても使わない設計に移ること」である。

`gl_InstanceID` は 2 箇所 (`node_outline.vert:24`, `cull/raster.vert:22`) [確認済]。Vulkan では `gl_InstanceIndex` に読み替えが必要 (GL の `gl_InstanceID` は baseInstance を含まないが Vulkan の `gl_InstanceIndex` は含む) — **これは意味論が異なるため注意が必要**。ただし該当 2 箇所はいずれも `baseInstance = 0` で描画されている [推測 — `raster.vert` は `glDrawElementsIndirect` で 1 draw、`node_outline.vert` はデバッグ] ため実害は小さい。

`GL_ARB_shader_draw_parameters : require` は **`lod/hierarchical/debug/node_outline.vert` の 1 本のみ** [確認済] — デバッグシェーダなので切り捨て可能。

**対処難易度: 容易 (ただし `gl_InstanceID` → `gl_InstanceIndex` の意味論差は要確認)。**

### 6.7 SPIR-V 化 (glslang) の際に問題になる GL 固有記法

#### (a) デフォルトブロック uniform — **20 箇所** [確認済]

Vulkan GLSL では `layout(location=N) uniform vec2 x;` のような**デフォルト uniform ブロックが禁止**されている。push constant か UBO に移す必要がある。

| ファイル | 該当 uniform |
|---|---|
| `post/ssao.comp` | `mat4 Proj`, `invProj`, `MV`, `sourceInvProj`, `MVP`, `invMVP` (6) |
| `post/blit_texture_depth_cutout.frag` | `mat4 invProjMat`, `projMat`, `vec4 endParams`, `fogColour`, `fadeParams` (5) |
| `post/setup_stencil_depth.frag` | `vec2 scaleFactor` |
| `post/depth_copy.frag` | `vec2 scaleFactor` (**未参照シェーダ**) |
| `hiz/hiz.comp` | `vec2 invImSize` (**未使用経路**) |
| `util/scatter.comp` | `uint count` |
| `util/set.comp` | `uint count` (**未参照シェーダ**) |
| `lod/hierarchical/queue.glsl` | `uint queueIdx` |
| `lod/hierarchical/cleaner/result_transformer.comp` | `uint visibilityCounter` |
| `lod/hierarchical/cleaner/batch_visibility_set.comp` | `uint count`, `uint setTo` |

実稼働シェーダに限れば **15 箇所**。

**Phase 0 の実測により、全 15 箇所が push constant で処理できることが確定した** [実測済]:

> **`maxPushConstantsSize = 4096`**

初版では「`mat4` × 6 の `ssao.comp` は 384 バイトで push constant 上限を超える可能性があるため UBO 化が必要」と推測していたが、**384 バイトは 4096 に対して 9.4% にすぎず、余裕で収まる**。15 箇所のうち最大のものでも上限の 1/10 未満であり、**UBO 化は一切不要**。

これは移植を単純化する。UBO 経路を作れば descriptor set の割り当て・更新・ライフタイム管理が必要になるが、push constant はコマンドバッファに直接埋まるためそれらが全て不要になる。

Java 側の対応する `glUniform*` 呼び出しも書き換えが必要 [確認済 — `glUniform2f`, `nglUniformMatrix4fv`, `glUniform1i`, `glUniform1f` が使われている] だが、`vkCmdPushConstants` への置換は機械的である。

**対処難易度: 容易** (初版の「要設計」から格下げ)。全て push constant に収まり、descriptor 管理が不要になったため。

#### (b) `#import` 独自ディレクティブ — **23 ファイルで使用、12 種類** [確認済]

```
#import <voxy:lod/quad_format.glsl>
#import <voxy:lod/gl46/bindings.glsl>
...
```

これは `ShaderLoader.java` (65行) が GL に渡す前に**文字列展開する独自機構**である [確認済 — `Shader.Builder` の `IShaderProcessor` チェーン]。glslang に渡す前に同じ展開を行えばよいので**問題にならない**。

**対処難易度: 容易。**

#### (c) NV 拡張 — すべて `#ifdef` ガード済み [確認済]

```glsl
#ifdef USE_NV_JANK
#extension GL_NV_gpu_shader5 : enable
#endif
```

`USE_NV_JANK` / `USE_NV_BARRY` を define しなければ経路ごと消える。`GL_NV_fragment_shader_barycentric : require` も同様。**対処難易度: 容易。**

#### (d) subgroup 操作 — 影響は限定的 [確認済]

subgroup を使うのは 2 ファイルのみ:

| ファイル | 使用 | 稼働状況 |
|---|---|---|
| `hiz/hiz.comp` | `subgroupClusteredMax`, `subgroupMax`, `subgroupBarrier` | **未使用経路** — 後述 |
| `util/prefixsum/inital3.comp` | `subgroupExclusiveAdd`, `gl_SubgroupID`, `gl_SubgroupInvocationID` | **フォールバックあり** |

`inital3.comp` は `MDICSectionRenderer.java:77` で以下のように選択される [確認済]:

```java
.add(ShaderType.COMPUTE, Capabilities.INSTANCE.subgroup ? "voxy:util/prefixsum/inital3.comp"
                                                        : "voxy:util/prefixsum/simple.comp")
```

**subgroup 非対応時の `simple.comp` 経路が用意されている。** したがって subgroup サポートが不確実でも移植は止まらない。

`hiz.comp` については重要な発見がある [確認済]: `Viewport.java:14,57` を見ると

```java
//public final HiZBuffer2 hiZBuffer = new HiZBuffer2();
this.hiZBuffer = new HiZBuffer(properties);
```

**`HiZBuffer2` (compute 版、`hiz.comp` を使う) はコメントアウトされており、稼働しているのは `HiZBuffer` (ラスタ版、`hiz/blit.vsh` + `hiz/blit.fsh`)。** つまり `hiz.comp` の subgroup 依存と 6 個の image store は**現状の稼働経路には存在しない**。

**対処難易度: 容易。**

#### (e) `gl_FragDepth` / depth 規約

`RenderProperties` が `isZero2One()` / `isReverseZ()` / `closerEqualDepthCompare()` を持つ [確認済]。GL の `[-1,1]` 深度と Vulkan の `[0,1]` 深度の差を吸収する仕組みが**すでに存在する**。Minecraft 側の設定に追従する形になっている。

**対処難易度: 容易 (既存の抽象が使える)。**

---

## 7. Iris / シェーダパック連携

### 7.1 規模

[確認済]:

| 対象 | 行数 |
|---|---:|
| `client/iris/` (7 ファイル) | 1,155 |
| `client/mixin/iris/` (10 ファイル) | 317 |
| `client/core/IrisVoxyRenderPipeline.java` | 311 |
| `client/core/util/IrisUtil.java` | 80 |
| **合計** | **1,863** |

主要ファイル: `IrisVoxyRenderPipelineData.java` 578, `IrisShaderPatch.java` 397, `IrisVoxyRenderPipeline.java` 311, `VoxyUniforms.java` 104, `IrisUtil.java` 80, `MixinIrisRenderingPipeline.java` 64, `VoxySamplers.java` 55, `MixinProgramSet.java` 51, `MixinLevelRenderer.java` 45。

### 7.2 結合度

**結合は疎である。** [確認済]

Iris 経路は `AbstractRenderPipeline` のサブクラスとして**きれいに分離されている**。`RenderPipelineFactory.java` (46行) の全体:

```java
public static AbstractRenderPipeline createPipeline(...) {
    AbstractRenderPipeline pipeline = null;
    if (IrisUtil.IRIS_INSTALLED && IrisUtil.SHADER_SUPPORT) {
        pipeline = createIrisPipeline(...);
    }
    if (pipeline == null) {
        pipeline = new NormalRenderPipeline(...);
    }
    return pipeline;
}
```

つまり **Iris が無い/失敗した場合は `NormalRenderPipeline` (168行) にフォールバックする構造がすでにある**。Iris 経路を丸ごと削除しても `NormalRenderPipeline` が残る。

`iris` を参照している `iris/` 外のファイルは **11 個** [確認済]:

```
client/config/VoxyConfigMenu.java
client/core/RenderPipelineFactory.java
client/core/RenderProperties.java
client/core/VoxyRenderSystem.java
client/core/rendering/ViewportSelector.java
client/mixin/minecraft/MixinLevelRenderer.java
client/mixin/minecraft/MixinRenderSystem.java
client/mixin/sodium/MixinDefaultChunkRenderer.java
client/mixin/sodium/MixinFallbackVisibleChunkCollector.java
client/mixin/sodium/MixinRenderSectionManager.java
client/mixin/sodium/MixinVisibleChunkCollector.java
```

`RenderProperties.java` の参照は限定的 [確認済 — 46-73行の `irisUseBlockAtlasUv()` と `closerEqualDepthCompare()` 内の 1 条件のみ]。

### 7.3 切り離し作業量の見積り

[推測 — 実際に削除を試していないため]:

- **削除**: `client/iris/` + `client/mixin/iris/` + `IrisVoxyRenderPipeline.java` + `IrisUtil.java` = **1,863 行**
- **編集**: 上記 11 ファイルの Iris 参照箇所の除去。各ファイルの参照は数行〜数十行規模 → **概算 150〜250 行の編集**
- **`build.gradle`**: Iris 依存の除去
- **`fabric.mod.json`** 相当: mixin 設定から `mixin/iris` パッケージの除去

**対処難易度: 容易。** 1 日〜2 日程度の作業と見込む [推測]。フォールバック構造が既にあるため、削除後も破綻しない。

なお **Iris 連携を切り捨てる判断は妥当** [確認済 — 理由]: `IrisShaderPatch.java` (397行) は **Iris のシェーダパック GLSL ソースを文字列レベルでパッチする**実装である。Vulkan バックエンドではシェーダパックが GL GLSL を前提とするため原理的に成立しない。

---

## 8. 再利用可能コード量 vs 新規実装が必要なコード量

### 8.1 Java (総 31,012 行)

[確認済 — GL import の有無で機械的に分類]

| 区分 | 行数 | 割合 | 扱い |
|---|---:|---:|---|
| `common` + `commonImpl` (GL 非依存) | 8,992 | 29% | **無改変で再利用** |
| `client` GL 非依存 (Iris 分 532 を除く) | 12,240 | 39% | **ほぼ無改変で再利用** |
| `client` GL 依存 (Iris 分 1,331 を除く) | **7,917** | **26%** | **移植対象** |
| Iris 関連 (GL 依存 1,331 + 非依存 532) | 1,863 | 6% | **削除** |

**再利用可能: 21,232 行 (68%) / 移植対象: 7,917 行 (26%) / 削除: 1,863 行 (6%)**

### 8.2 移植対象 7,917 行の内訳を精査すると

**7,917 行のうち、実際に GL API に言及している行は 871 行 (11%) にすぎない** [確認済]。残り約 7,000 行は同一ファイル内に同居している CPU 側ロジックである。

具体例 [確認済]:

| ファイル | 総行数 | GL 言及行 | 実質的な移植作業 |
|---|---:|---:|---|
| `AsyncNodeManager.java` | 1,033 | 17 (1%) | バッファ操作の差し替えのみ。ロジック 1,000 行超は保存 |
| `ModelFactory.java` | 1,000 | 7 (0.7%) | ほぼテクスチャ確保のみ |
| `SoftwareModelTextureBakery.java` | 322 | 17 (5%) | テクスチャ読み戻しのみ |

したがって **「新規に書く必要がある行数」の現実的な見積りは 7,917 行ではなく、1,500〜2,500 行程度** [推測] である。内訳:

| 対象 | 現行行数 | 新規実装見積り | 根拠 |
|---|---:|---:|---|
| `client/core/gl/` 全体 (Vulkan ラッパへ) | 1,487 | 2,500〜4,000 [推測] | Vulkan は GL より冗長。device/queue/allocator/descriptor 管理が増える |
| `AbstractRenderPipeline` | 283 | 400〜600 [推測] | render pass / barrier 明示化で膨らむ |
| `MDICSectionRenderer` → draw 統合型 renderer | 396 | 500〜700 [推測] | パイプライン生成。degenerate 埋めは数十行 |
| **draw 統合の追加分** (`cmdgen.comp` 再設計 + prefix sum + `quads3.vert` 索引変更) | — | **300〜600** [実測済] | §3.6。`phase0-b1-mdi-measurement.md` §4.3 の見積り |
| `HierarchicalOcclusionTraverser` | 407 | 450〜550 [推測] | バリア記述の増加 |
| `NodeCleaner` + `AsyncNodeManager` の GL 部 | (39行相当) | 150〜250 [推測] | |
| `UploadStream` / `DownloadStream` / `RawDownloadStream` | 473 | **300〜450** [推測、実測に基づく] | **下方修正。**ユニファイドメモリ確定によりステージング経路が不要 (下記) |
| `HiZBuffer` | 137 | 200〜300 [推測] | |
| `Capabilities` | 223 | 150〜250 [推測] | Vulkan の方が capability クエリは素直 |
| `GPUTiming` | 207 | 200〜250 [推測] | `VkQueryPool`。`timestampValidBits = 64` / `timestampPeriod = 1.0` 確認済 [実測済] |
| `Shader` / `ShaderLoader` / `AutoBindingShader` / `ShaderType` | 464 | 600〜900 [推測] | shaderc 呼び出し + SPIR-V キャッシュ + パイプライン生成。shaderc 経由の GLSL → SPIR-V は疎通確認済 [実測済] |
| **小計 (Java)** | — | **5,750〜8,850** | |
| **GL–Vulkan interop 層 (新規、既存対応物なし)** | 0 | **1,000〜2,500** [推測] | §9 B-3 参照。IOSurface / 深度合成 |
| **合計** | — | **6,750〜11,350** | |

初版の合計 6,550〜10,900 行に対し、draw 統合分 (+300〜600) と Upload/Download の下方修正 (−100〜150) が相殺し、**実質的に変わらない**。

#### ユニファイドメモリ確定による単純化 [実測済]

Phase 0 の Step 1 で以下が実機確認された:

> **`DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT | HOST_CACHED` を全て備えたメモリタイプが存在し、ヒープは 1 つのみ。**

これは §3.4 で「有利化する」と推測していた点の裏付けであり、影響は推測時より大きい:

- **ステージングバッファが一切不要** — `vkCmdCopyBuffer` によるホスト→デバイス転送経路そのものを実装しなくてよい
- **`HOST_CACHED` があるため読み戻しも速い** — `DownloadStream` (189行) の PBO 相当ロジックが素直に移る
- GL の永続マップ (`GL_MAP_PERSISTENT_BIT`) は `vkMapMemory` の永続保持にそのまま対応
- `HOST_COHERENT` があるため `glFlushMappedNamedBufferRange` 相当 (`UploadStream.java:81,117`) の明示フラッシュが不要になる

ディスクリート GPU 向けに書かれた Vulkan の教科書的な転送経路を**丸ごと省略できる**ため、`UploadStream` / `DownloadStream` / `RawDownloadStream` の 473 行は**現行より短くなる可能性がある** [推測]。見積りを 400〜600 から 300〜450 に下方修正した。

### 8.3 シェーダ (総 3,076 行)

[確認済 + 推測]:

| 区分 | 行数 | 扱い |
|---|---:|---|
| 未参照シェーダ 6 本 | 概算 100〜150 | 削除 |
| `#ifdef` で無効化される NV 経路 | 概算 30 | 削除 |
| デフォルト uniform の移動 (15 箇所) | 影響 40〜80 行 | 書き換え |
| `gl_InstanceID` → `gl_InstanceIndex` (2 箇所) | 2 行 | 書き換え |
| `#version 330` → `450` (7 本) | 7 行 | 書き換え |
| **その他すべて** | **約 2,800 行 (91%)** | **無改変で SPIR-V 化可能** [推測] |

**シェーダは移植コストの主要因ではない。** これは Voxy が最初から 460 core + compute 前提で書かれていること、GL 固有記法の使用が少ないことによる [確認済]。

---

## 9. ブロッカー候補

初版では B-1 を「唯一の go/no-go 級の未知数」としていたが、**Phase 0 の実測により B-1 は解消され、go/no-go は B-3 に移った** [実測済]。

### 🔴 B-3: GL–Vulkan interop コスト (**現時点で唯一の go/no-go**)

**[未検証 — 実測が必要。Phase 0 の次のステップ]**

Minecraft 本体は GL のままであるため、Vulkan で描いた LoD を GL のフレームバッファに**深度付きで合成**する必要がある。macOS に GL–Vulkan/Metal の公式 interop 拡張は存在しないため、IOSurface 経由 + 深度を R32F で渡して `gl_FragDepth` に書き戻す方式が想定される。

**Voxy 側の接続点は小さい** [確認済]。Minecraft 側から受け取るのは **GL テクスチャ ID (int) のみ**:

```java
// MixinDefaultChunkRenderer.java:61
renderer.renderOpaque(viewport,
    ((GlTextureView)target.getDepthTextureView()).glId(),
    ((GlTextureView)target.getColorTextureView()).glId());
```

接続点は **5 箇所、すべて「GL テクスチャ ID を渡す」という単一の形**に統一されている [確認済 — §9 B-3 詳細を参照]。

**しかし双方向である** [確認済]。Voxy → MC (描画結果の合成) だけでなく、**MC → Voxy (ライトマップ、ブロックテクスチャアトラス) の方向も存在する**。後者はテクスチャ更新のたびに転送が必要になる [推測]。

**なぜこれが go/no-go なのか**: interop コストは移植の設計努力で減らせる部分が少ない。ドライバと OS の境界を毎フレーム跨ぐコストであり、Voxy 側のアルゴリズムを変えても改善しない。B-1 は「設計を変えれば解決できる」問題だったが、B-3 は**そうではない**。

**撤退ライン: 合計 3〜4ms** [実測済 — `phase0-b1-mdi-measurement.md` §5 で設定]。60fps 予算 16.6ms の約 1/4 を毎フレーム interop に払うことになり、「軽量な LoD レンダラ」という Voxy の存在意義が失われるため。

**測定内容**:
1. 合成 + 深度書き戻しパスのコスト (ms)
2. GL↔Vulkan 同期で失うコスト (ms)

**必要なもの**: JNI または Panama (JDK 25 なので FFM API が正式機能として使える)。

---

### 🟢 B-1: MoltenVK 上での MDI 実効性能 (**解消済 — 設計分岐の判定材料**)

**[実測済 — `phase0-b1-mdi-measurement.md`]**

初版で go/no-go としていた項目。**測定の結果、go/no-go ではなかった。**

#### 測定結果

| 判定項目 | 結果 [実測済] |
|---|---|
| MoltenVK の MDI 実装 | **per-draw 課金。バッチ効果ゼロ。ICB 経路は使われていない** |
| セクション単位 88,000 draws の維持 | **不可** (18.0ms、予算の 12 倍) |
| degenerate draw による `drawIndirectCount` 代替 | **単体で不可** (88,000 件で 4.2ms、予算の 2.8 倍) |
| **draw 統合の目標値** | **1,000 draws 以下** |

初版の懸念「MoltenVK が CPU 側で N 回ループしてエンコードする実装なら破滅的」は**形としては外れた** [実測済]。`cpu_ms` は drawCount に依存せず一定 (0.004〜0.019ms) であり、MoltenVK は `vkCmdDrawIndexedIndirect` の時点では処理を遅延し、コストは `gpu_ms` とサブミット時処理に現れる。**ただし per-draw 課金であるという結論は同じ**であり、対処方針への影響はない。

`HALF` 条件が実描画と degenerate の算術平均に誤差 0.05% で一致した [実測済] ことが、バッチ効果ゼロの決定的な証拠である。

#### なぜ go/no-go でなくなったのか

初版は「これが遅い場合、対処は移植ではなくアーキテクチャ変更になり、§8 の見積りが完全に外れる」と評価していた。**この評価は誤りだった** [実測済]。理由:

> 200,000 三角形を `drawCount = 1` で描くと 18.444ms、`drawCount = 1,000` に分割しても 18.545ms。**差は 0.5%。**

統合の目標が「1 ドロー」ではなく「1,000 ドロー以下」で足りるため、実装の自由度が大きい。折れ点は約 7,300 件で、そこまでは draw 起因のコストが 0.35ms 以下に収まる。面方向別 6 ドロー構成は増分 0.016ms で**事実上コストゼロ**。

結果として追加工数は **300〜600 行** [実測済] にとどまり、総見積り 6,750〜11,350 行に対して誤差の範囲。アーキテクチャ変更ではなく**設計上の選択**に収まった。

#### 統合案が実行可能である根拠 [実測済 + 確認済]

| 前提 | 状況 |
|---|---|
| 頂点入力 | VAO 不使用、`gl_VertexID` による SSBO からの vertex pulling が既に実装済み [確認済] |
| prefix sum | `util/prefixsum/inital3.comp` (subgroup版) / `simple.comp` (fallback) が既存 [確認済] |
| subgroup | `subgroupSize = 32`, `subgroupSizeControl = true` → subgroup 版が使える [実測済] |
| コマンド生成 | 既に compute (`cmdgen.comp`) で生成しており、生成先を変えるだけ [確認済] |
| 64bit | `shaderInt64 = true` → `quad_format.glsl` の uint64 実装を維持可 [実測済] |

#### 測定の限界 [実測済 — 同レポート §3.2]

全三角形を同一位置に重ねているため early-z で大半が棄却されており、**フラグメント処理のコストは含まれていない**。本測定の目的は「draw 数に起因する増分」の抽出であり、その成分はジオメトリ配置に依存しないため判定は有効だが、**実描画スループットは Phase 4 で別途測定する必要がある**。

**難易度: 要設計 (困難ではない)。**

---

### 🟡 準ブロッカー (成立性は損なわないが工数を大きく左右する)

#### B-2: `glMemoryBarrier` 42 箇所の意味論変換

[確認済 — 件数] / [推測 — 難易度]

§3.7 の通り、GL の粗いバリアを Vulkan の (stage, access) ペアへ変換する作業。**1:1 変換が不可能で、各箇所の意図を読み解く必要がある**。しかも GL で暗黙に保証されていた同期が Vulkan では明示必要になるため、**バリアが書かれていない箇所にも追加が必要**になる可能性が高い。

デバッグ難易度も高い: 同期漏れは「たまに描画が壊れる」形で現れ、再現性が低い。**移植工数が最も集中する箇所**と見込む。バリデーションレイヤ + `VK_LAYER_KHRONOS_synchronization2` の活用が必須。

**難易度: 困難。**

#### B-3 の詳細 (go/no-go 本体は上記 🔴 を参照)

接続点の内訳 [確認済]:
- `client/mixin/sodium/MixinDefaultChunkRenderer.java:61` (Sodium 経路)
- `client/mixin/nvidium/MixinRenderPipeline.java:23` (Nvidium 経路)
- `client/core/rendering/util/LightMapHelper.java:30` (ライトマップテクスチャ)
- `client/core/model/bakery/BakedBlockEntityModel.java:37` (ブロックエンティティテクスチャ)
- `client/core/model/bakery/SoftwareModelTextureBakery.java:82` (`glGetTextureImage` による読み戻し)

**良いニュース**: 接続点が **5 箇所、しかもすべて「GL テクスチャ ID を渡す」という単一の形**に統一されている。interop 層のインターフェースは小さくて済む。

**悪いニュース**: 双方向である。後者 (MC → Voxy) はブロックテクスチャアトラス全体を Vulkan 側にコピーする必要があり、テクスチャ更新のたびに転送が要る [推測]。

なお **ユニファイドメモリが確定した** [実測済] ことは interop にも有利に働く可能性がある [推測] — IOSurface は元々 CPU/GPU 共有メモリの仕組みであり、ステージングを挟まずに扱える見込み。ただしこれは実測で確認すべき事項であり、楽観の根拠にはしない。

#### B-4: `AbstractSectionRenderer` 抽象の実効性が未検証

[確認済 — 実装が 1 つしかないこと]

§2.3 の通り、バックエンド抽象は存在するが**実装は `MDICSectionRenderer` 1 つのみ**で、`getRenderBackendFactory()` はそれをハードコードしている (`TODO` コメント付き)。

抽象が「2 つ目を刺せる形」になっているかは、実際に刺してみるまで分からない [推測]。特に `Viewport<T>` が `GlBuffer` を直接フィールドに持っている点 [確認済 — `MDICViewport.java` は `GlBuffer` を 5 個保持] は、Vulkan 版で `Viewport` 基底クラス側の変更を要求する可能性がある。

**この項目は draw 統合の決定により重要度が上がった** [実測済を受けた推測]。新 renderer は `MDICSectionRenderer` の 1:1 移植ではなく**描画粒度そのものが異なる実装**になるため、`AbstractSectionRenderer` の抽象境界により強い負荷がかかる。具体的には `IGeometryData` / `Viewport` が「セクション単位 draw」を暗黙に前提していないかの確認が必要。

**難易度: 要設計 (小規模)。**

---

### ✅ ブロッカーで **ない** ことが確認できた項目

| 項目 | 状況 [確認済] |
|---|---|
| `geometryShader = false` | **geometry shader は一切不使用。** `ShaderType` enum に GEOMETRY が存在すらしない (VERTEX/FRAGMENT/COMPUTE/MESH/TASK のみ)。`.geom` ファイル 0 件。**事前の仮説は正しかった。** |
| `transformFeedback` 不在 | **transform feedback は一切不使用。** `TransformFeedback` / `GL_TRANSFORM_FEEDBACK` すべて 0 件。 |
| `sparseBinding = false` | sparse **texture** 不使用。sparse **buffer** は NVIDIA+Windows 限定フォールバック経路のみ。macOS では自動無効化。**作業不要。** |
| bindless texture | **完全に不使用。** descriptorIndexing での代替検討すら不要。 |
| `drawIndirectCount = false` | 該当 2 箇所のみ [確認済]。**draw 統合により制約自体が消滅する** [実測済]。統合後は draw 数が数百なので上限発行のコストが無視できる。degenerate 単体案は棄却 (§3.6)。 |
| `shaderInt64` | `true` [実測済]。`quad_format.glsl` の uint64 実装をそのまま維持可。uvec2 分解不要。 |
| `drawIndirectFirstInstance` | `true` [実測済]。伝達も実機確認済 (200 → 200)。ただし **draw 統合により本経路は採用しない見込み** (§6.6)。性能への影響はゼロと確認されているため、捨てても損失なし。 |
| `maxPushConstantsSize` | **4096** [実測済]。デフォルト uniform 15 箇所は全て push constant で収まり、UBO 化不要 (§6.7a)。 |
| メモリタイプ | `DEVICE_LOCAL｜HOST_VISIBLE｜HOST_COHERENT｜HOST_CACHED` が存在、ヒープ 1 つ [実測済]。**ステージング経路の実装が丸ごと不要**(§8.2)。 |
| `timestampValidBits` / `timestampPeriod` | 64 / 1.0 [実測済]。`GPUTiming` の移植先として `VkQueryPool` が完全に使える。ティック = ナノ秒。 |
| GLSL → SPIR-V | shaderc 経由で疎通確認済 [実測済]。Phase 2-3 でそのまま使える。 |
| subgroup | `subgroupSize = 32`, `subgroupSizeControl = true` [実測済]。`prefixsum/inital3.comp` の subgroup 版が使える (fallback も既存)。 |
| storage 層の GL 漏れ | **漏れなし。** `common` + `commonImpl` 8,992 行に GL import 0 件。 |
| mesh shader 依存 | `ShaderType.MESH/TASK` の enum 定義はあるが**使用箇所 0**。`IUsesMeshlets` インターフェースも実装クラスなし。 |
| シェーダリフレクション依存 | なし。バインディングはすべて `#define` による明示的な数値。 |
| 頂点入力レイアウト | **VAO を実質使わない。** `GlVertexArray.STATIC_VAO` は空の VAO で、頂点データは `gl_VertexID` による SSBO からの vertex pulling (`quads3.vert:55`)。Vulkan では vertex input state が空で済み、**むしろ有利**。 |

---

## 10. 推奨アプローチと段階分け

### Phase 0: 着手前の必須実測 (Voxy のコードに触れない)

| # | 内容 | 状況 |
|---|---|---|
| 1 | MoltenVK 単体テストで大量 indirect draw のコストを測定 (§9 B-1) | **✅ 完了** — `phase0-b1-mdi-measurement.md` |
| 3 | subgroup capability と push constant 上限の確認 | **✅ 完了** — 副次的に測定 |
| 2 | GL–Vulkan interop (IOSurface + 深度合成) の往復コストを測定 (§9 B-3) | **⬜ 未実施 — 次のステップ。これが唯一の go/no-go** |

**1 の判断結果** [実測済]: 破滅的に遅かったが、**アーキテクチャ変更にはならなかった**。draw 統合 (目標 1,000 draws 以下) を Phase 4 の設計前提に組み込むことで解決する。追加工数 300〜600 行。

**2 の撤退ライン**: 合計 3〜4ms。超過した場合、macOS での Voxy は「軽量」という存在意義を失うため計画を撤回する。JNI または Panama (JDK 25 の FFM API) が必要。

### Phase 1: 切り離しと足場固め (Voxy を GL のまま動かす)

1. Iris 経路の削除 (§7, 1,863 行削除 + 11 ファイル編集)
2. 未参照シェーダ 6 本の削除、`HiZBuffer2` / NV 経路の整理
3. `Capabilities` を「バックエンド非依存の capability インターフェース + GL 実装」に分離
4. `AbstractRenderPipeline` から GL 具象コード (stencil 設定、framebuffer 管理、バリア) を抽象メソッドへ押し出す

**この段階では GL バックエンドのまま動作すること**を維持する。回帰が検出しやすい。

### Phase 2: Vulkan 基盤 (描画なし)

1. instance / device / queue / allocator / command pool の初期化
   — Phase 0 の `Vk.java` / `Offscreen.java` / `Buf.java` / `Pipeline.java` / `Timing.java` が**そのまま出発点になる** [実測済]
2. `client/core/gl/` に対応する Vulkan ラッパ (`GlBuffer` → `VkBufferWrapper` 等)
   — ユニファイドメモリ確定によりステージング経路は**実装しない** [実測済]
3. shaderc による GLSL → SPIR-V コンパイル経路 + キャッシュ (疎通確認済 [実測済])
4. デフォルト uniform 15 箇所を **全て push constant へ**移行 (`maxPushConstantsSize = 4096` [実測済]、UBO 化不要)
5. `#define` バインディング番号 → descriptor set レイアウトの写像規約を決定

### Phase 3: compute パスの移植 (描画なし、検証しやすい)

compute だけなら interop なしで単体検証できる。**ここでバリア記述の作法を確立する** (B-2 対策)。

1. `util/scatter.comp`, `util/memcpy.comp`, `util/prefixsum/simple.comp`
2. `lod/hierarchical/` 系 (traversal, cleaner) — `HierarchicalOcclusionTraverser`, `NodeCleaner`
3. `lod/gl46/cmdgen.comp`, `prep.comp`

各パスの出力を GL 版とビット単位で比較する検証を用意すると B-2 の事故を早期に捕まえられる [推測]。

### Phase 4: 描画パスの移植 — **draw 統合型の新 renderer**

> **改訂 (Phase 0 実測後)**: 初版は「`MDICSectionRenderer` → `VulkanSectionRenderer` の移植」としていた。**これは 1:1 移植ではなく、描画粒度を変える新規実装になる** [実測済]。セクション単位 draw (88,000 件) は維持できず、**1,000 draws 以下**への統合が前提となる。

#### 4-1. draw 統合の設計 (**Phase 4 の中核。ここから着手する**)

現行の「1 セクション = 4.4 draw」を捨て、**可視セクションをバッチに束ねる**構成へ移行する。

- **統合の目標: 1,000 draws 以下** [実測済]。「1 draw まで畳む」必要はない
- **面方向別 6 ドロー構成はコストゼロ** (増分 0.016ms) [実測済] — 面ごとに分けたままでよい
- 折れ点は約 7,300 件 [実測済] なので、数百件に収まれば余裕がある

#### 4-2. `cmdgen.comp` (220行) の再設計

| 現行 | 統合後 |
|---|---|
| 88,000 件の `DrawCommand` を生成 | **数百件の `DrawCommand` + セクションオフセットテーブル**を生成 |
| `cmd.baseInstance = drawId` でセクション ID を伝達 | **廃止** |
| `atomicAdd(opaqueDrawCount, cmdCnt)` でコマンドを密に詰める | オフセットテーブルの構築に変更 |

#### 4-3. prefix sum による `gl_VertexIndex` → (section, quad) 解決

`baseInstance` 経由のセクション ID 伝達を廃止するため、頂点側でセクションを逆引きする必要がある。

- **既存の `util/prefixsum/inital3.comp` (subgroup版) / `simple.comp` (fallback) を流用できる** [確認済]
- `subgroupSize = 32`, `subgroupSizeControl = true` [実測済] なので subgroup 版が使える
- `quads3.vert:54` の `positionBuffer[gl_BaseInstance]` を、prefix sum テーブルの二分探索または直接索引に置き換える

**この方式が成立する根拠** [確認済]: Voxy は既に VAO を使わず `gl_VertexID` による SSBO からの vertex pulling を行っている。頂点索引から任意のデータを引く構造が**最初から存在する**ため、索引の計算方法を変えるだけで済む。

#### 4-4. 残りの作業

4. `drawIndirectCount` 対策の degenerate 埋め (数十行) — **統合後は draw 数が数百なのでコストは無視できる** (1,000 件で 0.10ms) [実測済]
5. `quads3.vert` / `quads.frag` の SPIR-V 化
6. `HiZBuffer` (ラスタ版) の移植
7. オフスクリーンレンダーターゲットへの描画のみ (interop なし) で画が出ることを確認
8. **実描画スループットの測定** — Phase 0 の測定は early-z により大半が棄却されており、フラグメント処理コストを含んでいない [実測済 — 測定の限界]。ここで初めて実地の描画性能が分かる

**追加工数見積り**: 4-2 + 4-3 + `quads3.vert` の索引変更で **300〜600 行** [実測済]。

### Phase 5: interop と統合

1. IOSurface 経由の GL–Vulkan テクスチャ共有
2. 深度の R32F 受け渡し + `gl_FragDepth` 書き戻し
3. Sodium 経路 (`MixinDefaultChunkRenderer`) への接続
4. ライトマップ / ブロックアトラスの MC → Vulkan 転送

### Phase 6: 仕上げ

1. `GPUTiming` の `VkQueryPool` 化
2. `SSAO` (任意機能)
3. デバッグ機能 (`PrintfInjector`, `DebugRenderer`) — 優先度低

---

## 11. 総括

### 工数がどこに集中するか

質問の核心に答える。**工数は以下の 3 点に集中し、それ以外はほぼ機械的作業である。**

| 集中点 | 規模 | 難易度 | 備考 |
|---|---|---|---|
| **1. `glMemoryBarrier` 42 箇所の Vulkan 同期への変換** | 42 箇所 + 潜在的な追加 | **困難** | 1:1 変換不可。バグの再現性が低い。**ここが最大の工数** |
| **2. `client/core/gl/` の Vulkan 再実装** | 1,487 行 → 2,500〜4,000 行 | 要設計 | 機械的だが量がある。ステージング不要で初版想定より軽い [実測済] |
| **3. GL–Vulkan interop 層 (新規)** | 0 → 1,000〜2,500 行 | 要設計 | 既存対応物なし。接続点は 5 箇所と少ない。**成立性リスクを伴う唯一の項目** |
| **4. draw 統合 (新規追加)** | 300〜600 行 [実測済] | 要設計 | `cmdgen.comp` 再設計 + prefix sum。**工数は小さいが Phase 4 の設計前提を変える** |

一方、**工数が集中しないと確認できた箇所**:

- シェーダ移植 — 3,076 行中 **約 91% が無改変**で SPIR-V 化可能
- storage / world / LoD ロジック — **8,992 + 12,240 行が完全に無改変**
- メッシュ生成 (`RenderDataFactory` 1,806行) と ノード階層 (`NodeManager` 1,718行) — **GL 非依存、無改変**
- sparse / bindless — **作業ゼロ**
- Iris 切り離し — 疎結合、フォールバック構造が既存
- **デフォルト uniform の移行** — 全て push constant に収まり UBO 化不要 [実測済]
- **ホスト↔デバイス転送** — ユニファイドメモリによりステージング経路の実装が不要 [実測済]

### 定量まとめ

```
総コード           31,012 行 (Java) + 3,076 行 (GLSL)
├─ 無改変で再利用   21,232 行 (68%)
├─ 移植対象         7,917 行 (26%)  ← うち実 GL 呼び出しは 871 行のみ
└─ 削除 (Iris)      1,863 行 (6%)

新規実装見積り      6,750 〜 11,350 行  (初版 6,550〜10,900 から微増)
  ├─ draw 統合分     +300 〜   600 行 [実測済]
  └─ Upload/Download −100 〜   150 行 [実測済を受けた下方修正]
GLSL 改変           約 280 行 / 3,076 行 (9%) + draw 統合に伴う索引変更
```

### 最終所見 (Phase 0 実測を反映)

**移植の技術的成立性は依然として高い。** 事前調査で懸念されていた MoltenVK 制約 (geometryShader / transformFeedback / sparseBinding / bindless) は**すべて Voxy が該当機能を使っていないため無効化される** [確認済]。

Phase 0 の実測は、**懸念を 1 つ潰し、条件を 3 つ改善した**:

| 項目 | 実測前 | 実測後 [実測済] |
|---|---|---|
| B-1 (MDI 性能) | 唯一の go/no-go | **解消。** draw 統合 (目標 1,000 draws 以下、追加 300〜600 行) で対処可能 |
| デフォルト uniform | 要設計 (UBO 化が必要かも) | **容易。** `maxPushConstantsSize = 4096` で全て push constant |
| ホスト↔デバイス転送 | 「有利化する可能性」 | **確定。** ユニファイドメモリでステージング経路が不要 |
| GPU 計測 / SPIR-V 生成 | 未検証 | **疎通確認済。** `VkQueryPool` と shaderc がそのまま使える |

**一方で、初版の楽観が 1 点覆った**: `drawIndirectCount = false` に対する degenerate draw 単体案は、degenerate であっても 49 ns/draw が課金されるため予算を 2.8 倍超過し、**成立しない** [実測済]。ただしこれは draw 統合の一部として解消され、統合の目標値自体が緩い (1,000 draws 以下、1 draw まで畳む必要なし) ため、致命傷にはならなかった。

**残る唯一の成立性リスクは B-3 (GL↔Vulkan interop コスト)** に移った。これは B-1 と性質が異なる — **Voxy 側の設計努力で減らせない**。ドライバと OS の境界を毎フレーム跨ぐコストだからである。撤退ライン 3〜4ms を超えれば、macOS 版 Voxy は「軽量」という存在意義を失う。

**Phase 0 の残り (B-3 の実測) を移植着手の前提条件とすることを引き続き強く推奨する。**

---

## 付録: 検証に使用したコマンド

本レポートの数値は以下の方法で取得した。再検証可能。

```bash
# GL import を持つファイルの特定
grep -rl 'org\.lwjgl\.opengl' src/main/java

# common/commonImpl の GL 非依存確認 (出力が空であること)
grep -rl 'org\.lwjgl\.opengl' src/main/java/me/cortex/voxy/common src/main/java/me/cortex/voxy/commonImpl

# 使用 GL 関数の種類数
grep -rhno '\bn\?gl[A-Z][A-Za-z0-9]*' src/main/java | sed 's/.*://' | sort -u | wc -l

# シェーダの #version 分布
grep -rh '#version' src/main/resources/assets/voxy/shaders | sort | uniq -c | sort -rn

# 未参照シェーダの検出
for f in $(find src/main/resources/assets/voxy/shaders -type f ! -name '*.glsl'); do
  n=${f#src/main/resources/assets/voxy/shaders/}
  grep -rq "voxy:$n" src/main/java || echo "UNREF $n"
done
```
