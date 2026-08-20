# default-block uniform → push constant 移行 追跡リスト

**状態**: 未着手 (Phase 2 時点)
**対象**: 7 ファイル / 17 宣言 [確認済 — Phase 1 のデッドコード削除後の現存分]

---

## 1. なぜ必要か

Vulkan GLSL は**デフォルト uniform ブロックを禁止している**。

```glsl
layout(location = 0) uniform uint count;   // GL では合法、Vulkan GLSL では不可
```

これらを含むシェーダは **shaderc に投げた時点でコンパイルエラーになる**。
つまり「後で直せばよい」ではなく、**当該シェーダを Vulkan 側に持ってくるための前提条件**である。

`maxPushConstantsSize = 4096` [実測済 — Phase 0] であり、
最大の `ssao.comp` (mat4 × 6 = 384 バイト) でも上限の 9.4% に収まるため、
**全て push constant で処理でき UBO 化は不要**。

## 2. 現状の扱い

`VkShader.Builder.compile()` は shaderc に渡す**前に**ソースを検査し、
デフォルトブロック uniform が残っていれば以下の形で落とす
(`VkShader.checkNoDefaultBlockUniform`)。

```
Shader 'voxy:util/scatter.comp' (COMPUTE) still contains default-block uniforms,
which Vulkan GLSL forbids. These must be migrated to push constants before this
shader can be ported:
    layout(location=0) uniform uint count;
  Tracking list: docs/phase2-pushconstant-todo.md
```

黙って shaderc の分かりにくいエラーになるのを避けるための措置であり、
**このリストが空になるまで該当パイプラインは Vulkan で動かない**。

## 3. 一覧

| # | ファイル | 宣言数 | 使用パイプライン | 移行 Phase (予定) | 状態 |
|---|---|---:|---|---|---|
| 1 | `lod/hierarchical/queue.glsl` | 1 | **traversal** (Traverser) | Phase 3 | ✅ **完了** |
| 2 | `lod/hierarchical/cleaner/batch_visibility_set.comp` | 2 | batch_visibility_set (NodeCleaner) | Phase 3 | ⬜ |
| 3 | `lod/hierarchical/cleaner/result_transformer.comp` | 1 | result_transformer (NodeCleaner) | Phase 3 | ⬜ |
| 4 | `util/scatter.comp` | 1 | scatter (AsyncNodeManager) | Phase 3 | ⬜ |
| 5 | `post/setup_stencil_depth.frag` | 1 | setup_stencil_depth (AbstractRenderPipeline) | Phase 5 | ⬜ |
| 6 | `post/blit_texture_depth_cutout.frag` | 5 | blit_texture_depth_cutout (NormalRenderPipeline) | Phase 5 | ⬜ |
| 7 | `post/ssao.comp` | 6 | ssao (SSAO) | Phase 6 | ⬜ |

**地形パイプライン (`quads3.vert` / `quads.frag`) は該当なし** [確認済]。
そのため Phase 2 では地形シェーダで VkShader を検証できる。

### 3.1 詳細

#### 1. `lod/hierarchical/queue.glsl:3` — ✅ 完了 (Phase 3)

移行後:

```glsl
#ifdef VULKAN
layout(push_constant) uniform TraversalPushConstants {
    uint queueIdx;
};
#else
layout(location = NODE_QUEUE_INDEX_BINDING) uniform uint queueIdx;
#endif
```

インスタンス名を付けずに宣言しているため、**使用箇所 5 箇所は無変更**。

検証済み [確認済 — `TraversalShaderTest`]:
- `traversal_dev.comp` が SPIR-V にコンパイルできる
- `pushConstantSize == 4` (uint 1 個)
- `pushConstantStages == VK_SHADER_STAGE_COMPUTE_BIT` (リフレクション由来。全ステージには広げない)
- descriptor 側の binding 5 が消え、残り 9 バインディングが期待どおりの型
- GL 分岐がディスク上に残っている

`NODE_QUEUE_INDEX_BINDING` は `BINDING_COUNTER` から採番され **5** だった [確認済]。
Vulkan では descriptor を消費しなくなるが、**GL 側は引き続き使うため番号は詰めない**
(GL と Vulkan で番号を食い違わせない方針)。

**Java 側 (`HierarchicalOcclusionTraverser:318,331` の `glUniform1ui`) は未対応。**
Vulkan の traversal を実装する際に `flushPushConstants` に置き換える。
ディスパッチループは反復ごとに値が変わるため、**反復ごとに flush する**こと。

#### 2. `lod/hierarchical/cleaner/batch_visibility_set.comp:13-14`
```glsl
layout(location=0) uniform uint count;
layout(location=1) uniform uint setTo;
```
計 8 バイト。

#### 3. `lod/hierarchical/cleaner/result_transformer.comp:20`
```glsl
layout(location=0) uniform uint visibilityCounter;
```

#### 4. `util/scatter.comp:19`
```glsl
layout(location=0) uniform uint count;
```

#### 5. `post/setup_stencil_depth.frag:4`
```glsl
layout(location = 1) uniform vec2 scaleFactor;
```
Java 側: `AbstractRenderPipeline:166` の `glUniform2f(1, ...)`。

#### 6. `post/blit_texture_depth_cutout.frag:4,5,10,11,14`
```glsl
layout(location = 1) uniform mat4 invProjMat;
layout(location = 2) uniform mat4 projMat;
layout(location = 4) uniform vec4 endParams;
layout(location = 5) uniform vec4 fogColour;
layout(location = 6) uniform vec4 fadeParams;
```
mat4 × 2 + vec4 × 3 = 176 バイト。

#### 7. `post/ssao.comp:12-18`
```glsl
layout(location = 4) uniform mat4 Proj;
layout(location = 5) uniform mat4 invProj;
layout(location = 6) uniform mat4 MV;
layout(location = 7) uniform mat4 sourceInvProj;
layout(location = 3) uniform mat4 MVP;
layout(location = 4) uniform mat4 invMVP;
```
> **⚠ 未確定**: location 4 が `mat4 Proj` (12行目) と `mat4 invMVP` (18行目) で**重複している** [確認済]。
> `#ifdef` で排他になっているのか、それとも GL 側のバグなのかは**未調査** [未検証]。
> 自動変換を採らない理由の 1 つがこれで、機械的に写すとこの異常を黙って通してしまう。
> **調査は Phase 6 (ssao.comp を移行する Phase) で行う。**
> それまでこの行を「未確定」のまま残すこと。

最大 mat4 × 6 = 384 バイト (`maxPushConstantsSize = 4096` に対し 9.4%)。

## 4. 移行の規約 (確定済み)

### 4.1 offset 割当 — 宣言順に詰め、**リフレクションで拾う**

`location = N` からの機械的算出は**採らない**。
`ssao.comp` の location 4 重複のように、location 番号は連続性を持たないため。

**push constant ブロックのメンバオフセットは SPIR-V リフレクションで取得する。**
手計算では std430 のアラインメント規則 (mat4 は 16 バイト境界) でずれる。
「バイナリが真実」という既存方針 (`SpirvReflect`) と同じ。

実装: `SpirvReflect` が `OpMemberDecorate Offset` / `ArrayStride` / `MatrixStride` を読み、
`pushConstantSize()` を返す。

### 4.2 Java 側 setter API — シャドウバッファ + **任意回数 flush**

シェーダごとにホスト側のシャドウバッファを持ち、setter はそこに書くだけ。
`vkCmdPushConstants` は `flushPushConstants(cmd)` で流す。

**「ディスパッチ直前に一度だけ」ではない。** traversal のディスパッチループは
反復ごとに `queueIdx` が変わるため、**各ディスパッチ記録の直前に毎回** flush する
(`docs/phase3-descriptor-survey.md` §6)。

実装: `VkAutoBindingShader.pushUInt/pushFloat/pushBytes` + `flushPushConstants(cmd)`。

### 4.3 ステージ可視性 — リフレクションから導出

全ステージに見せる案は**採らない**。バリデーションレイヤが正確な値を期待するため。
`VkShader.Builder` は「実際に push constant ブロックを宣言しているステージ」のみを
`stageFlags` に立てる (`VkShader.pushConstantStages()`)。

### 4.4 自動変換はしない — 手作業

`IShaderProcessor` による自動変換は**採らない**。
7 ファイル 17 宣言と少なく、Java 側 setter と対で直す必要があるため
自動化の投資が回収できない。また `ssao.comp` の location 重複のような
異常を自動変換は黙って通す可能性がある。

**未移行の検出は残す。** 手作業の漏れの防波堤として、
`VkShader.Builder.explainCompileFailure` がコンパイル失敗時に
デフォルトブロック uniform を検出して説明する。

### 4.5 GL との両立 — `#ifdef VULKAN`

GL 側の宣言は消さず、1 ファイル内に両方を置く。
詳細と理由は [`phase2-glsl-compat.md`](phase2-glsl-compat.md) §2.5 (T-4)。

## 5. 完了条件

- 上表の 7 行がすべて ✅
- `VkShader.checkNoDefaultBlockUniform` が全パイプラインで発火しない
- 検査自体は残す (将来 GL 側から新しいシェーダを移す際の防波堤になるため)
