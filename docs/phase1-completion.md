# Phase 1 完了記録 — 切り離しと足場固め

**ブランチ**: `vulkan-macos` (from `dev` @ `337b919d`)
**方針**: GL バックエンドのまま動作を維持しながら、移植対象を減らす。

---

## 1. 完了した項目

### 1.1 Iris 連携の削除 (commit `b39c3e06`)

**1,911 行削除 / 16 行追加。**

Iris 連携は Vulkan バックエンドと原理的に両立しない。`IrisShaderPatch.java` (397行) が
シェーダパックの GLSL ソースを**文字列レベルでパッチする**実装であり、
SPIR-V にコンパイルした時点で成立しなくなるため。

| 対象 | 内容 |
|---|---|
| 削除 | `client/iris/` (7ファイル), `client/mixin/iris/` (10ファイル) |
| 削除 | `IrisVoxyRenderPipeline.java` |
| 書き換え | `RenderPipelineFactory` → 常に `NormalRenderPipeline` を返す (フォールバック構造が既存だった) |
| 書き換え | `RenderProperties` → `irisUseBlockAtlasUv()` を削除 (既に `return false` のみの死んだ関数だった) |
| **スタブ化** | `IrisUtil` → `IRIS_INSTALLED = false` 固定の no-op |
| 設定 | `client.voxy.mixins.json` から `iris.*` 10 エントリを削除 |
| ビルド | `build.gradle` の `smartDependency("iris_version")`, `gradle.properties` の該当行 |

**設計判断**: `IrisUtil` を削除せずスタブとして残した。
呼び出し元が 8 ファイルに散っていたが、元の実装が既に
「`IRIS_INSTALLED` でガードして実処理は `*0()` に分離」という形だったため、
定数を `false` に固定して `*0()` を削るだけで済んだ。**呼び出し側は 1 行も触っていない。**

レポートの見積り (1,863行削除 + 150〜250行の編集) に対し、編集はほぼ発生しなかった。

### 1.2 デッドコードの削除 (commit `7bb021d5`)

| 対象 | 理由 |
|---|---|
| 未参照シェーダ 6 本 | Java からも `#import` からも参照なし (自前で確認) |
| `HiZBuffer2.java` | 実参照は `Viewport.java` のコメントアウト 1 行のみ |
| `hiz/hiz.comp` | `HiZBuffer2` 専用 |

削除した 6 本: `post/depth_copy.frag`, `post/blit_texture_cutout.frag`, `post/depth0.frag`,
`util/set.comp`, `lod/gl46/test/raw.frag`, `lod/gl46/test/raw.vert`

**`hiz.comp` の削除により、移植対象から以下が消えた**:
- subgroup 操作 (`subgroupClusteredMax`, `subgroupMax`, `subgroupBarrier`)
- `r32f writeonly image2D` × 6 の image store

稼働している `HiZBuffer` (ラスタ版) が使う `hiz/blit.vsh` + `hiz/blit.fsh` は共有のため残置。

### 1.3 意図的に残したもの

| 対象 | 理由 |
|---|---|
| `IUsesMeshlets` (4行のマーカーIF) | 実装クラスは 0 だが、消すと `RenderGenerationService` のシグネチャ変更に波及。得るものに対し差分が大きい。draw 統合設計は meshlet 的な構造に近いため将来使う可能性もある |
| `ShaderType.MESH/TASK` | enum 2 エントリのみ。`ShaderType` 自体を Vulkan 移植時に書き直すので、そこで自然に消える |
| NV 経路 (`USE_NV_JANK` / `USE_NV_BARRY`) | `MDICSectionRenderer` の define が**両方コメントアウト済み**。`#ifdef` は決して有効化されないため無害 |

---

## 2. 保留した項目とその理由

レポート §10 Phase 1 の項目 3・4 は**意図的に実施しなかった**。

### 2.1 `Capabilities` のバックエンド非依存化 — 保留

使用実態を調査した結果 (`grep -rn "Capabilities.INSTANCE\."`):

```
7 getFreeDedicatedGpuMemory     2 totalDedicatedMemory    1 ssboBindingAlignment
6 canQueryGpuMemory             2 subgroup                1 nvBarryCoords
4 sparseBuffer                  2 repFragTest             1 isAmd
3 ssboMaxSize                   2 isNvidia / isIntel / hasBrokenDepthSampler
```

**使われていないフィールド**: `compute`, `INT64_t`, `indirectParameters`, `meshShaders`, `isMesa`, `totalDynamicMemory`

**保留理由**:
- 最多用途が `getFreeDedicatedGpuMemory` (7箇所) だが、**ユニファイドメモリ環境で何を返すべきかが未確定**。
  `VK_EXT_memory_budget` の実挙動を見ないとインターフェースが切れない。
  (Phase 0 で `DEVICE_LOCAL | HOST_VISIBLE`、ヒープ 1 つと判明しており、「専用 VRAM」という概念自体が変わる)
- ベンダ判定 (`isNvidia`/`isIntel`/`isAmd`/`isMesa`) を抽象に含めると Vulkan 実装で無意味な値を返すことになる
- 検出手段が GL 依存 (`testShaderCompilesOk` によるシェーダ試験コンパイル、`testDepthSampler`)。
  Vulkan では capability クエリで直接引ける

### 2.2 `AbstractRenderPipeline` の GL 具象コード押し出し — 保留

実物を読んだ結果、**レポートの評価を修正すべきと判断した**。

このクラスは「抽象化されていない」のではなく、**GL 固有の合成処理そのもの**である。
`initDepthStencil()` の中身:

1. d32 の深度を d24s8 にフルスクリーンパスでコピー (フォーマット不一致で blit できないため)
2. ステンシルで「MC 地形が無い領域」だけに Voxy 地形を描くようマスク

**これは B-3 の interop でやることと同じ問題である。** Phase 0 §8 で確立した経路も
「深度を R32F で渡して `gl_FragDepth` に書き戻す」であり、構造が一致する。

→ Vulkan 版では `initDepthStencil` 相当のパスが **interop の合成パスを兼ねる**可能性が高い。
今この関数を抽象メソッドに押し出しても中身が空になるだけで、抽象として機能しない。
**両者をまとめて Phase 5 で設計するのが正しい。**

### 2.3 共通する判断

どちらも「**2 つ目の実装がない抽象は機能しない**」という同じ理由。
これは `AbstractSectionRenderer` が実証済みで、リフレクションベースの Factory 機構まで
用意されているにもかかわらず実装は `MDICSectionRenderer` 1 つ、
`getRenderBackendFactory()` は `TODO` コメント付きでハードコードされている。

抽象化は Phase 2 (Vulkan 基盤) で実物を見ながら行う。

---

## 3. 成果

| 指標 | 変化 |
|---|---|
| 削除行数 | 1,911 + シェーダ 7 本 |
| 移植対象 (GL 依存 Java) | 7,917 行 → 約 6,600 行 |
| シェーダ | 47 本 3,076 行 → 40 本 |
| 消えた移植課題 | Iris 連携全般、subgroup 依存、image store 6 個 |
| ビルド | `BUILD SUCCESSFUL` (JDK 25 + Gradle 9.6) |
| 動作 | GL バックエンドのまま維持 |

---

## 4. Phase 2 への引き継ぎ

### 着手時に決めること

1. **`Capabilities` の分離** — Vulkan 側の capability クエリを書きながら、
   どのフィールドを抽象に上げるか決める。`getFreeDedicatedGpuMemory` の
   ユニファイドメモリ環境での意味付けが焦点。
2. **`AbstractRenderPipeline` の分割** — interop の合成パス設計と一体で行う。

### Phase 0 から確定している設計制約

- draw 統合必須 (opaque 7 / temporal 7 / translucent 最大1024)
- `cmdgen.comp` は面ごとのセクションリスト構築 + prefix sum に再設計
- quad → section の逆引きは二分探索 (コストは測定ノイズ以下)
- push constant 4096 バイト使えるため、デフォルト uniform 15 箇所は全て push constant で足りる
- ステージングバッファ不要 (`DEVICE_LOCAL | HOST_VISIBLE | HOST_COHERENT | HOST_CACHED`)
- interop は IOSurface 経由、色 `'BGRA'` / 深度 `'r00f'`、コスト 0.6ms
- **GPU 上の探索ループには必ず上限を切る** (ハングで OS レベルの GPU リセットが起きる)
