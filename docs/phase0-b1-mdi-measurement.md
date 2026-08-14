# Phase 0 / B-1 実測レポート — MoltenVK 上の indirect draw コスト

**測定日**: 2026-08-14
**環境**: Apple M4 Pro / MoltenVK (LWJGL 同梱版 Vulkan 1.2.296) / JDK 25 (Temurin, arm64)
**測定コード**: `~/dev/mdi-bench` (LWJGL 3.3.6 + shaderc、Vulkan 直叩き、ウィンドウなし)
**対象**: `vulkan-port-feasibility.md` §9 B-1「MoltenVK 上での MDI 実効性能」

---

## 1. 結論

**MDI 維持は不可能。draw 統合が必須。ただし統合目標は当初想定より遥かに緩い。**

| 判定項目 | 結果 |
|---|---|
| MoltenVK の MDI 実装 | **per-draw 課金。バッチ効果ゼロ。** ICB 経路は使われていない |
| セクション単位 88,000 draws の維持 | **不可**（18.0ms、予算の 12 倍） |
| degenerate draw による `drawIndirectCount` 代替 | **単体で不可**（88,000 件で 4.2ms、予算の 2.8 倍） |
| draw 統合の目標値 | **1,000 draws 以下**（"1 draw まで畳む"必要はない） |
| B-1 の位置づけ | go/no-go では**なかった**。設計分岐の判定材料 |

**残る唯一の go/no-go は B-3（GL↔Vulkan interop コスト）。**

---

## 2. 測定 1 — draw 数とコストの関係

三角形 1 枚 = 1 draw。`drawCount` を変えて計測。単位 ms、20 回の中央値。

| drawCount | content | cpu_ms | gpu_ms | reuse_ms |
|---|---|---:|---:|---:|
| 1,000 | ALL_REAL | 0.012 | 0.756 | 0.398 |
| 1,000 | ALL_DEGENERATE | 0.006 | 0.041 | 0.231 |
| 10,000 | ALL_REAL | 0.019 | 1.671 | 2.125 |
| 10,000 | ALL_DEGENERATE | 0.006 | 0.211 | 0.651 |
| 10,000 | HALF | 0.006 | 0.962 | 1.435 |
| 50,000 | ALL_REAL | 0.011 | 8.838 | 10.626 |
| 50,000 | ALL_DEGENERATE | 0.006 | 0.967 | 2.525 |
| 100,000 | ALL_REAL | 0.008 | 17.520 | 20.795 |
| 100,000 | ALL_DEGENERATE | 0.006 | 1.912 | 5.162 |
| 200,000 | ALL_REAL | 0.006 | 34.954 | 41.030 |
| 200,000 | ALL_DEGENERATE | 0.004 | 3.788 | 9.902 |
| 200,000 | HALF | 0.004 | 19.361 | 25.391 |

### 2.1 読み取り

**`cpu_ms` は drawCount に依存せず一定 (0.004〜0.019ms)。**
MoltenVK は `vkCmdDrawIndexedIndirect` の時点では何もせず、実処理をサブミットまで遅延している。したがって「CPU エンコード律速」という事前の仮説は**形としては外れ**、コストは `gpu_ms` と `reuse_ms` に現れた。

**per-draw 課金の分解** (10,000 件以上の線形領域から算出):

| 成分 | 実測値 |
|---|---:|
| 実描画 (`gpu_ms`) | **175 ns/draw** |
| degenerate (`gpu_ms`) | **19 ns/draw** |
| サブミット時処理 (`reuse − gpu`) | **30 ns/draw**（内容非依存） |

**バッチ効果はゼロ。** `HALF`（実描画と degenerate が半々）が両者の算術平均に正確に一致する:

```
200,000件 HALF   実測 19.361 ms
                 算術平均 (34.954 + 3.788) / 2 = 19.371 ms   → 誤差 0.05%
```

これは MoltenVK が 1 コマンドを 1 つの Metal draw に展開していることを意味する。Metal の Indirect Command Buffer (ICB) による GPU 側展開は行われていない。

**`firstInstance` の有無は完全に無影響。** 全条件で誤差範囲。統合案で `firstInstance` を捨てても失うものがない。

### 2.2 予算との比較

1 フレーム 16.6ms のうち地形描画に 5ms、opaque/temporal/translucent の 3 パスで割って **1 パス 1.5ms** を予算とする。

`sectionCount = 20,000` → `min(20000 * 4.4 + 128, 400000)` = **88,128 draws** での試算:

| 条件 | コスト | 予算比 |
|---|---:|---:|
| 実描画 88,000 件 | **18.0 ms** | **12.0 倍超過** |
| 全件 degenerate 88,000 件 | **4.2 ms** | **2.8 倍超過** |
| 予算 1.5ms に収まる draw 数 | **約 7,300 件** | — |

> **重要**: `drawIndirectCount = false` の代替として提案されていた「末尾を `indexCount=0` で埋める」方式は、**それ単体で予算を 2.8 倍超過する**。degenerate でも 19 + 30 = 49 ns/draw かかるため。
> `vulkan-port-feasibility.md` §3.6 の「実装は容易」という評価はこの数字によって覆る。

---

## 3. 測定 2 — 統合の目標値

**三角形の総数を 200,000 に固定**し、draw 数だけを変える。三角形総数が同じなので、`total_ms` の差はすべて draw 数に起因する。

| drawCount | tris/draw | gpu_ms | total_ms | draw 起因の増分 |
|---|---:|---:|---:|---:|
| 1 | 200,000 | 18.172 | 18.444 | — (基準) |
| 6 | 33,333 | 18.205 | 18.460 | +0.016 ms |
| 100 | 2,000 | 18.292 | 18.500 | +0.056 ms |
| 1,000 | 200 | 18.312 | **18.545** | **+0.101 ms** |
| 7,300 | 27 | 18.364 | **18.794** | **+0.350 ms** |
| 20,000 | 10 | 19.366 | 20.068 | +1.624 ms |
| 88,000 | 2 | 23.216 | 26.075 | +7.631 ms |
| 200,000 | 1 | 38.036 | 44.334 | +25.890 ms |

### 3.1 読み取り

**折れ点は約 7,300 件。** そこまでは draw 起因のコストが 0.35ms 以下に収まり実質フラット、超えると急速に立ち上がる。

測定 1 から独立に算出した「予算 1.5ms に収まる 7,300 件」と一致した。同じ per-draw 課金構造を別角度から測っているため。

**最重要の発見**:

> `drawCount = 1` で 200,000 三角形 → 18.444 ms
> `drawCount = 1,000` に分割 → 18.545 ms
> **差は 0.5%**

**統合の目標は「1 ドロー」ではなく「1,000 ドロー以下」で十分。** 実装の自由度が大きく上がる。

`drawCount = 6`（面方向別）の `ns/draw` が 2670 と大きく見えるのは分母が 6 しかないための見かけ上の値。絶対増分は 0.016ms で誤差の範囲 → **面方向別 6 ドロー構成はコストゼロで採れる。**

### 3.2 測定の限界

全三角形を同一位置に重ねているため early-z で大半が棄却されており、フラグメント処理のコストは含まれない。ただし本測定の目的は「draw 数に起因する増分」の抽出であり、その成分はジオメトリ配置に依存しないため判定は有効。実描画スループットは Phase 4 で別途測定する。

---

## 4. 設計への反映

### 4.1 確定した方針

- **セクション単位の draw（88,000 件）は廃止**
- **面方向別 6 ドロー、または可視セクションを数百のバッチに束ねる構成へ**
- `cmdgen.comp` (220行): 「88,000 件の DrawCommand 生成」→「数百件の DrawCommand + セクションオフセットテーブル生成」
- `firstInstance` によるセクション ID 伝達は廃止 → **prefix sum で `gl_VertexIndex` → (section, quad) を解決**
- `drawIndirectCount = false` の問題は**消滅**。draw 数が数百なら CPU 側で上限発行しても degenerate のコストは無視できる（1,000 件で 0.10ms）

### 4.2 統合案が実行可能である根拠

すべて調査済みの事実:

| 前提 | 状況 |
|---|---|
| 頂点入力 | VAO 不使用、`gl_VertexID` による SSBO からの vertex pulling が既に実装済み |
| prefix sum | `util/prefixsum/inital3.comp` (subgroup版) / `simple.comp` (fallback) が既存 |
| subgroup | `subgroupSize = 32`, `subgroupSizeControl = true` → subgroup 版が使える |
| コマンド生成 | 既に compute (`cmdgen.comp`) で生成しており、生成先を変えるだけ |
| 64bit | `shaderInt64 = true` → `quad_format.glsl` の uint64 実装を維持可 |

### 4.3 追加工数

`cmdgen.comp` 再設計 + prefix sum 適用 + `quads3.vert` の索引変更で **300〜600 行程度**。
`vulkan-port-feasibility.md` の総見積り 6,550〜10,900 行に対して誤差の範囲。

### 4.4 副次的な確認事項

Step 1〜3 で以下も実機確認済み:

| 項目 | 結果 | 意味 |
|---|---|---|
| メモリタイプ | `DEVICE_LOCAL \| HOST_VISIBLE \| HOST_COHERENT \| HOST_CACHED` が存在、ヒープは 1 つ | ユニファイドメモリ。**ステージングバッファ・コピーコマンドが一切不要**。`UploadStream`/`DownloadStream` (473行) は大幅に単純化される見込み |
| `timestampValidBits` | 64 / `timestampPeriod` = 1.0 | GPU 計測が完全に使える。ティック = ナノ秒 |
| `firstInstance` → `gl_InstanceIndex` | **伝達を実機確認** (200 を設定 → 200 が届く) | `instanceCount = 1` なら `gl_InstanceIndex` == section ID。`shaderDrawParameters = true` なので `gl_BaseInstance` も使えるが、**両経路が使える** |
| GLSL → SPIR-V | shaderc 経由で成功 | Phase 2-3 でそのまま使える部品 |
| `maxPushConstantsSize` | 4096 | デフォルト uniform 15 箇所は**全て push constant で済む**（UBO 化不要）。§6.7(a) は「要設計」→「容易」に格下げ |

---

## 5. 次のステップ

**B-3: GL↔Vulkan interop コストの実測** — これが真の go/no-go。

Minecraft 本体は GL のままなので、Vulkan で描いた LoD を GL のフレームバッファに深度付きで合成する必要がある。macOS に公式の interop 拡張はないため IOSurface 経由となる。

測定内容:
1. 合成 + 深度書き戻しパスのコスト (ms)
2. GL↔Vulkan 同期で失うコスト (ms)

**撤退ライン: 合計 3〜4ms**。60fps 予算の 1/4 を毎フレーム interop に払うことになるため。

必要なもの: JNI または Panama (JDK 25 なので FFM API が正式機能として使える)。
接続点は 5 箇所、すべて「GL テクスチャ ID を渡す」形に統一されている (調査済み)。ただし双方向 (MC → Voxy のライトマップ・ブロックアトラス転送も必要)。

---

## 付録: 測定コードの構成

```
~/dev/mdi-bench/
├── libs/                    LWJGL 3.3.6 (core, vulkan, shaderc) + macos-arm64 natives
└── src/main/java/bench/
    ├── Vk.java              instance/device/queue/commandPool、メモリタイプ検索
    ├── Glsl.java            shaderc による GLSL → SPIR-V
    ├── Offscreen.java       color(RGBA8) + depth(D32F) のオフスクリーンターゲット
    ├── Buf.java             HOST_VISIBLE|DEVICE_LOCAL 永続マップバッファ
    ├── Pipeline.java        頂点入力なし・深度テスト有効のパイプライン
    ├── Timing.java          VkQueryPool タイムスタンプ、中央値
    ├── Smoke.java           接続確認
    ├── Step1.java           デバイス初期化 + メモリタイプ一覧
    ├── Step2a.java          shaderc 疎通
    ├── Step2b.java          レンダーパス + クリア + 読み戻し検証
    ├── Step3.java           indirect draw 1 件 + firstInstance 伝達確認
    ├── Step4.java           測定 1: drawCount × content × firstInstance
    └── Step5.java           測定 2: 三角形総数固定、draw 数のみ可変
```

ビルド・実行:

```bash
cd ~/dev/mdi-bench
javac -cp "libs/*" -d out src/main/java/bench/*.java
java --enable-native-access=ALL-UNNAMED -cp "out:libs/*" bench.Step5
```

### 注意点

- LWJGL 同梱の MoltenVK は **Vulkan 1.2.296**。brew 版 1.4.2 (`vulkaninfo` で確認したもの) とは別物。
  ベンチには影響しないが、移植本体では 1.4 を基準にする（`dynamic_rendering` 等がコアで使える）。
- `VK_KHR_portability_enumeration` はローダー提供の拡張。MoltenVK 直結では**指定してはいけない**。
- `MemoryStack` はループ内でフレームを切ること。`record`/`submitAndWait` 内で `stackPush()` する。
