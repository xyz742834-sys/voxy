# Vulkan 経路の GLSL 互換変換 — 一覧

**「Vulkan 経路だけソースが違う」状態を一箇所で追えるようにするための記録。**

GL バックエンドのシェーダソース (`src/main/resources/assets/voxy/shaders/`) は
**一切変更していない**。Vulkan 経路 (`VkShaderLoader.parse`) がロード時に変換を加えている。

---

## 1. 適用される変換 (全 3 種)

`VkShaderLoader.parse()` が生成するソースの先頭は以下になる:

```glsl
#version 460
#define gl_VertexID gl_VertexIndex
<... #import を再帰展開した本体 ...>
```

| # | 変換 | 実装箇所 | 理由 |
|---|---|---|---|
| **T-1** | `#version 460 core` → `#version 460` | `VkShaderLoader.parse` | Vulkan GLSL に core プロファイル指定は無い |
| **T-2** | `#define gl_VertexID gl_VertexIndex` を注入 | `VkShaderLoader.COMPAT_PRELUDE` | §2 |
| **T-3** | `#import <ns:path>` の再帰展開 | `VkShaderLoader.expand` | GL 側 `ShaderLoader` と同一規則。Vulkan 固有ではない |
| **T-4** | `#ifdef VULKAN` によるバックエンド分岐 | **注入しない** (下記) | §2.5 |

これに加えて `VkShader.Builder.compile()` が `#define` 群 (`.define(...)` で積んだもの) を
`#version` 行の直後に注入する。これは GL 側 `Shader.Builder` と同じ挙動。

---

## 2. T-2: `gl_VertexID` → `gl_VertexIndex`

**対象**: 7 シェーダ・10 箇所 [確認済]

```
post/fullscreen.vert:7          hiz/blit.vsh:7
post/fullscreen2.vert:6         lod/gl46/cull/raster.vert:39
lod/gl46/quads3.vert:58,60,81   chunkoutline/outline.vsh:41
lod/hierarchical/debug/node_outline.vert:28
```

**なぜ一括置換して安全か**: 両者の意味論が一致するため [確認済 — 仕様ベース]。

| | GL `gl_VertexID` | Vulkan `gl_VertexIndex` |
|---|---|---|
| インデックス描画 | インデックス値 + `basevertex` | インデックス値 + `vertexOffset` |
| 非インデックス描画 | `first` + i | `firstVertex` + i |

Voxy の地形描画は `DrawCommand.baseVertex = int(offset)<<2` を使う
インデックス描画だが、両者とも baseVertex を含むため差が出ない。

---

## 2.5 T-4: `#ifdef VULKAN` によるバックエンド分岐

GL と Vulkan で書き分けが必要な宣言 (デフォルトブロック uniform → push constant など) は、
**1 つのファイル内に両方を置き `#ifdef VULKAN` で分岐させる**。

```glsl
#ifdef VULKAN
layout(push_constant) uniform TraversalPushConstants {
    uint queueIdx;
};
#else
layout(location = NODE_QUEUE_INDEX_BINDING) uniform uint queueIdx;
#endif
```

### なぜ GL 側を残すのか

**GL 版のシェーダソースは、この Mac で動かなくても「移植の参照仕様」として価値がある。**
バリアの意味論やバッファの読み取り範囲を判断する際、**唯一の正解を持っているのが GL 版**である。
壊すと移植の正しさを判断する基準が消える。

加えて、既存の `#ifdef` スタイル (`USE_NV_JANK`, `HAS_STATISTICS`, `DEBUG`) と一貫する。

### 実装上の規約

1. **`VULKAN` マクロは互換プレリュードで注入しない。**
   shaderc / glslang が Vulkan ターゲット時に `VULKAN = 100` を**自動定義する** [確認済]。
   自前で `#define VULKAN 1` すると
   `error: '#define' : Macro redefined; different substitutions: VULKAN` になる。
   GL 側の glslang は定義しないため、`#ifdef VULKAN` がそのまま backend 判別として働く。

2. **`#ifdef` の両側で変数「名」を揃える。**
   使用箇所のコードが分岐しないようにするため。
   push constant ブロックは**インスタンス名を付けずに**宣言すると
   メンバを `queueIdx` のまま参照できる (`pc.queueIdx` にしない)。

3. 分岐を追加したら本ドキュメントの §4 一覧に追記する。

### 診断コードへの影響 [確認済]

`#ifdef` 分岐を導入したことで、**未移行検出を生ソースの正規表現で行うと誤検出する**
(実際にはプリプロセッサに落とされる GL 分岐を拾ってしまう)。

そのため `VkShader.Builder` の診断は以下の形に変えた:

- コンパイル**前**の検査はしない。shaderc が通ったならそのシェーダは定義上正しい Vulkan GLSL
- コンパイル**失敗時のみ** `SpirvCompiler.preprocess()` で `#ifdef` 展開後のテキストを取得し、
  そこに既知の未移行パターン (デフォルトブロック uniform / `gl_InstanceID`) があれば
  説明を差し替える

---

## 3. 意図的に変換して **いない** もの

### 3.1 `gl_InstanceID` — 意味論が違う

**対象**: 3 箇所 [確認済]

| 箇所 | 状況 |
|---|---|
| `lod/gl46/cull/raster.vert:22` | `indirectLookup[gl_InstanceID]`。稼働中 |
| `chunkoutline/outline.vsh:31` | `(gl_InstanceID<<5)+gl_BaseInstance+...`。BoundRenderer 用 (Phase 2 対象外) |
| `lod/hierarchical/debug/node_outline.vert:24` | DebugRenderer。**未インスタンス化のデッドコード** |

**なぜ置換しないか**:

| | GL `gl_InstanceID` | Vulkan `gl_InstanceIndex` |
|---|---|---|
| 開始値 | **常に 0** (baseInstance を含まない) | `firstInstance` + i (**含む**) |

機械的に `#define gl_InstanceID gl_InstanceIndex` とすると、
**baseInstance が 0 でない描画で静かに壊れる**。

特に `outline.vsh:31` は `gl_InstanceID + gl_BaseInstance` と明示的に足しており、
これは Vulkan の `gl_InstanceIndex` そのものである。置換すると**二重に加算される**。

**扱い**: `VkShader.Builder.checkNoUnshimmedInstanceId` が
コンパイル前に検出し、以下のエラーで落とす
(`checkNoDefaultBlockUniform` と同じ扱い — 黙って失敗させない)。

```
Shader '...' (VERTEX) uses gl_InstanceID, which is deliberately NOT shimmed for Vulkan.
  GL's gl_InstanceID excludes baseInstance; Vulkan's gl_InstanceIndex includes it, so a blind
  #define would silently corrupt any draw with a non-zero baseInstance.
  Port this shader by hand and confirm how baseInstance should be handled at each use site.
```

**移行時の確認事項**: 各使用箇所で baseInstance が実際に 0 かどうか。
0 なら `gl_InstanceIndex` にそのまま置換してよい。
0 でなければ `gl_InstanceIndex - gl_BaseInstance` が GL の `gl_InstanceID` に相当する。

**`cull/raster.vert` は移行済み** (Phase 4 Stage 4b)。
`prep` が `cullDrawIndirectCommand.baseInstance = 0` を書く [確認済 — `lod/gl46/prep.comp`]
ので条件を満たし、`lod/vk/cull_raster.vert` で `gl_InstanceIndex` に置換した。
**この前提は `VkCullTest.cullDrawUsesZeroBaseInstance` が常設で見張っている** —
発行側が baseInstance を変えると静かにセクションがずれるため。

### 3.2 `gl_BaseInstance` — 変換不要

**対象**: 2 箇所 (`quads3.vert:57`, `outline.vsh:31`) [確認済]。

Vulkan GLSL でも `gl_BaseInstance` という同名の組み込みが使える
(`shaderDrawParameters` が必要。`VkContext` が有効化済み [確認済])。
GL 側は `GL_ARB_shader_draw_parameters` に依存する。**名前も意味も同じなので変換不要。**

**Stage 1 で実地に確認した** [確認済 — `VkTerrainRenderTest`]:
`positionBuffer[gl_BaseInstance]` 経由でセクションごとに異なる位置に描かれること、
および間接描画の `firstInstance` がそのまま `gl_BaseInstance` として届くことを
描画結果で確認した (`drawsMultipleSectionsAtDistinctPositions`)。

ただし draw 統合 (Phase 4) でこの経路自体が廃止される見込み
[詳細は `vulkan-port-feasibility.md` §6.6]。

### 3.3 `gl_PrimitiveID` — 変換不要

1 箇所 (`quads.frag:129`)。両者で同名・同義。
なお当該行は `USE_NV_BARRY` (`GL_NV_fragment_shader_barycentric`) 経路の中にあり、
現状 define されていないため無効 [確認済]。

### 3.35 `GL_ARB_gpu_shader_int64` — 変換不要だが**分岐が実質バックエンド依存**

`quads3.vert` は拡張が有効なら `Quad = uint64_t`、無効なら `Quad = ivec2` を選ぶ
(`quad_format.glsl`)。**両者は stateId の取り出し方が違う**:

| | 64bit 経路 | ivec2 経路 |
|---|---|---|
| `extractStateId` | bit 26..41 (16bit) | bit 26..31 と 32..45 を組み立て (20bit) |

shaderc/glslang は Vulkan ターゲットでこの拡張をサポートしており、
**64bit 側が選ばれる** [確認済 — `VkTerrainRenderTest.vertexShaderUsesThe64BitQuadPath`
がプリプロセス結果を検査している]。

`SyntheticTerrain` は 64bit 側の配置で quad を書いているため、
ここが変わると**基準画像が静かに間違う**。回帰検出のためテストを常設した。

### 3.4 デフォルトブロック uniform — 別途追跡

`layout(location=N) uniform ...` は Vulkan GLSL で禁止。
変換ではなく**移行**が必要なため、[`phase2-pushconstant-todo.md`](phase2-pushconstant-todo.md) で追跡。

---

## 4. シェーダ側が要求する外部 define

変換ではないが、Vulkan 経路で地形シェーダをコンパイルするのに
**Java 側から供給が必須**な define。テスト時に見落として
"undeclared identifier" になったため記録する。

| define | 供給元 | 備考 |
|---|---|---|
| `NO_SHADE_FACE_TINT` | `AbstractSectionRenderer.addDirectionalFaceTint` | `ClientLevel.cardinalLighting()` 由来の float |
| `UP_FACE_TINT` | 同上 | |
| `DOWN_FACE_TINT` | 同上 | |
| `Z_AXIS_FACE_TINT` | 同上 | |
| `X_AXIS_FACE_TINT` | 同上 | |

いずれも `#ifdef` ガードが無く、未定義だとコンパイルエラーになる [確認済]。
**これらは Minecraft のワールド状態に依存する**ため、
Vulkan 版でも `ClientLevel` から取る必要がある (テストでは代表値を与えている)。

---

## 4.5 `#ifdef VULKAN` 分岐の一覧

T-4 を適用した箇所。**追加したら必ずここに追記すること。**

| ファイル | 分岐内容 | Phase |
|---|---|---|
| `lod/hierarchical/queue.glsl:3` | `queueIdx` を push constant / デフォルトブロック uniform で分岐 | Phase 3 ✅ |

残り 6 ファイルは [`phase2-pushconstant-todo.md`](phase2-pushconstant-todo.md) を参照。

---

## 4.6 Vulkan 専用シェーダ (案 B) の一覧

`#ifdef` で分岐させず**ファイルごと分けた**もの。
「Vulkan 経路だけソースが違う」箇所を追える状態を保つため、
追加したら必ずここに載せること。

**置き場所は `lod/vk/`。** GL 版 (`lod/gl46/`) は無変更で参照仕様として残る
(判断の経緯は [`phase4-proposal.md`](phase4-proposal.md) §3.3)。

| ファイル | 対応する GL 版 | Phase |
|---|---|---|
| `lod/vk/quad_index.glsl` | (新規。統合テーブルの二分探索) | Phase 4 Stage 2 ✅ |
| `lod/vk/quads3.vert` | `lod/gl46/quads3.vert` | Phase 4 Stage 2 ✅ |
| `lod/vk/index_probe.comp` | (テスト専用。全数検査用の調査シェーダ) | Phase 4 Stage 2 ✅ |
| `lod/vk/prep.comp` | `lod/gl46/prep.comp` | Phase 4 Stage 3 ✅ |
| `lod/vk/cmdgen.comp` | `lod/gl46/cmdgen.comp` | Phase 4 Stage 3 ✅ |
| `lod/vk/merged_prefix.comp` | (新規。`util/prefixsum/*` は流用できず) | Phase 4 Stage 3 ✅ |
| `lod/vk/cull_raster.vert` | `lod/gl46/cull/raster.vert` | Phase 4 Stage 4b ✅ |
| `lod/vk/cull_raster.frag` | `lod/gl46/cull/raster.frag` | Phase 4 Stage 4b ✅ |
| `lod/vk/translucent_gen.comp` | `lod/gl46/buildtranslucents.comp` | Phase 4 Stage 4a ✅ |
| `lod/vk/translucent_prefix.comp` | (新規) | Phase 4 Stage 4a ✅ |

temporal パスは<b>新しいファイルを作っていない</b>。
`lod/vk/merged_prefix.comp` を binding の define だけ変えて 2 本目のパイプラインにし、
頂点シェーダとエントリ配列は不透明と共有している (Phase 4 Stage 4d)。

`lod/vk/quads3.vert` が GL 版から落としたもの
(いずれも現状 define されておらず出力に影響しない [確認済]):
`USE_NV_JANK` / `USE_NV_BARRY` / `USE_SINGLE_TRI` / `DEBUG_RENDER`。

**バインディング番号は GL 版と揃える方針を維持している** (§`phase2-binding-audit.md` §8)。
統合経路で新設した番号:

| # | 用途 |
|---|---|
| 10 | 統合テーブルのエントリ |
| 11 | 統合テーブルの prefix (先頭にエントリ数) |
| 12 | `cmdgen` の間接ディスパッチサイズ |
| 13 | 面ごとの 7 DrawCommand |

0..7 の地形パイプラインとも 8 (cmdgen の統計) とも衝突しない。

`lod/vk/cmdgen.comp` は GL 版と同じ番号 (metadata 3 / visibility 4 / lookup 5 /
positionScratch 6) を使う。**`gl_InstanceID` はここには現れない** —
セクションは `indirectLookup[gl_GlobalInvocationID.x]` で引くため。

---

## 5. 完了条件 / 見直し時期

- 3.1 の `gl_InstanceID` 3 箇所は、それぞれのシェーダを Vulkan に移す Phase で個別対応
- T-2 は draw 統合 (Phase 4) で `quads3.vert` を再設計しても引き続き必要
- 新しい変換を足すときは**必ず本ドキュメントに追記する**。
  「Vulkan だけソースが違う」箇所が追えなくなるのを防ぐため
