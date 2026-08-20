# Phase 4 提案 — draw 統合とレンダラ移植

**状態**: 調査と提案のみ。実装は未着手。

---

## 1. 現行の描画経路の構造 [確認済]

### 1.1 呼び出し順 (`AbstractRenderPipeline.runPipeline`)

```
setup()                        深度/ステンシル準備 (interop 合成と不可分。Phase 5)
  ↓
renderOpaque(viewport)         ★ 不透明描画
  ↓
innerPrimaryWork()             HiZ mip 構築 → [download tick → nodeManager.tick
                               → nodeCleaner.tick → traversal] を do-while
  ↓
buildDrawCalls(viewport)       ★ 描画コマンド生成
  ↓
renderTemporal(viewport)       ★ 今フレーム新たに見えた分の描画
  ↓
postOpaquePreperation()
postOpaquePreTranslucent()
renderTranslucent(viewport)    ★ 半透明描画
  ↓
finish()
```

### 1.2 ⚠ 最も重要な構造: `renderOpaque` は `buildDrawCalls` **より前**

**不透明描画は「前フレームに生成されたコマンド」を使う** [確認済 —
`runPipeline` の 113 行目が `renderOpaque`、123 行目が `buildDrawCalls`]。

これは二段構えの temporal 方式である:

| パス | 何を描くか | 使うコマンド |
|---|---|---|
| `renderOpaque` | 前フレームに可視だったセクション | **前フレーム**の cmdgen 出力 |
| `renderTemporal` | 今フレーム新たに可視になったセクション | **今フレーム**の cmdgen 出力 |

利点は「不透明描画がトラバーサルの完了を待たない」こと。
GPU を遊ばせずに済む代わりに、1 フレーム遅れの可視集合を描く。

**Phase 4 への影響**: 描画コマンドバッファは**フレームを跨いで生き残る**。
in-flight = 1 なのでフレーム境界で GPU はアイドルだが、
「前フレームの内容を今フレームが読む」という依存が明示的に存在する。
バッファをフレーム境界でリセットしてはいけない。

### 1.3 各メソッドの中身

| メソッド | 内容 |
|---|---|
| `renderOpaque` | uniform 更新 → `renderTerrain(offset=0, maxDraw=min(sectionCount*4.4+128, 400k))` |
| `renderTemporal` | `renderTerrain(offset=TEMPORAL_OFFSET, maxDraw=min(sectionCount, 100k))` |
| `renderTranslucent` | ブレンド有効 → `renderTerrain` 相当 (半透明オフセット) |
| `buildDrawCalls` | ① prep dispatch(1,1,1) ② cull ラスタ ③ cmdgen dispatchIndirect ④ prefixsum dispatch(1,1,1) ⑤ translucentGen dispatchIndirect |

`renderTerrain` は 3 パスすべての共通ヘルパで、
`glMultiDrawElementsIndirectCountARB` を 1 回発行するだけ [確認済]。

### 1.4 `cmdgen.comp` の呼ばれ方

**1 フレームに 1 回、`glDispatchComputeIndirect` 経由** [確認済 — `MDIC:336`]。
ディスパッチサイズは prep シェーダが `drawCountCallBuffer` に書く。

構造は 220 行中:
- `writeCmd()` (41-49 行) — DrawCommand を書く。**統合で消える**
- `main()` (51-221 行) — セクションごとに可視判定 → 面ごとに `writeCmd`

### 1.5 `AbstractSectionRenderer` の抽象メソッド対応

| 抽象メソッド | MDIC 実装 | 統合の影響 |
|---|---|---|
| `renderOpaque(T)` | 前フレームコマンドで MDI | **大** |
| `buildDrawCalls(T)` | 5 段の compute/ラスタ | **大** |
| `renderTemporal(T)` | 今フレームコマンドで MDI | **大** |
| `renderTranslucent(T)` | ブレンド + MDI | 中 |
| `postOpaquePreperation(T)` | 既定実装は空 | 無 |
| `createViewport()` | `MDICViewport` (GlBuffer × 5) | **中** (VkBuffer 化) |
| `free()` | — | 小 |

抽象の形はそのまま使える [推測]。`Viewport<T>` が `GlBuffer` を直接持つ点だけ
`VkBuffer` 版が要る (Phase 2 §B-4 で指摘済み)。

---

## 2. 実装単位と順序の提案

**方針: 「Vulkan への移植」と「draw 統合」を分ける。** 一度に両方やると、
動かなかったときにどちらが原因か切り分けられない。

### Stage 0 — Vulkan レンダラの骨格 (描画なし)

`VkSectionRenderer` を `AbstractSectionRenderer` の 2 つ目の実装として作る。
中身は空で、フレーム構造 (`beginFrame` → 記録 → `endFrame`) だけ通す。

**確認**: サブミットが通る。バリデーション指摘ゼロ。

**必要な前提**: グラフィクスパイプライン生成と `dynamic_rendering` (未実装)。

### Stage 1 — **統合せずに** そのまま移植 ★ここが要

`cmdgen.comp` と `quads3.vert` を**変更せず**、
セクション単位の draw (88k) のまま Vulkan で動かす。

- 描画は遅い (18.0ms) [実測済 — Phase 0] が **正しい絵が出るはず**
- バリア対応表 (§`phase3-barrier-survey.md` §11) を引いて 11 箇所を書く
- descriptor は既存の `VkAutoBindingShader` / `VkDescriptorSetGroup` で足りる

**確認**: オフスクリーンに描いて読み戻し、絵が出る。

**この段階の価値**: ここで得られる出力が
**Stage 2 の参照基準になる** (§4)。GL 4.6 環境が要らない。

### Stage 2a — オフセットテーブルを**追加で**生成

`cmdgen.comp` を改造し、既存の DrawCommand 生成は**残したまま**、
面ごとのセクションリスト + prefix sum を**追加で**出力する。

**確認**: テーブルを `VkDownloadStream` で読み戻し、不変条件を検査する。
- 単調増加であること
- 総和が総 quad 数と一致すること
- 面ごとの区間が重ならないこと

**描画はまだ旧経路のまま。** 絵が変わらないことも確認する。

### Stage 2b — `quads3.vert` を切り替え、7 draws へ

`gl_VertexIndex` → 二分探索 → (section, quad) の解決に切り替え、
発行を面方向別 7 draws にする。

**確認**: Stage 1 の出力と**ピクセル単位で比較**する (§4)。

> **Stage 2a / 2b 完了。記録は [`phase4-stage2-completion.md`](phase4-stage2-completion.md)。**
> 13 draws → 7 draws への統合が、全視点・全境界条件で**差分 0 ピクセル**。
> 検証は 3 層 (テーブル不変条件の CPU 全数 / GPU 二分探索の全数 / 絵の差分ゼロ)。
> 新たに判明した制約: **16bit 共有インデックスバッファが 1 draw = 16,380 quad で頭打ち**
> になる (§`phase4-stage2-completion.md` 5.1)。実データでは確実に超えるため、
> Stage 3 に入る前に方針の判断が要る。

> **Stage 3 (cmdgen の GPU 化) 完了。記録は
> [`phase4-stage3-completion.md`](phase4-stage3-completion.md)。**
> prep / cmdgen / prefix sum を GPU 化。GPU が作ったテーブルが CPU 版と
> **バイト一致**し、描いた絵が Stage 2b と**差分ゼロ**。
> cull と translucentGen は未実装 (理由は §5)。
> 32bit インデックスの見積もりは想定より大きく、**判断待ち** (§7)。

### Stage 3 — 旧経路の削除

`writeCmd()` と per-section の DrawCommand 生成を削る。

> **Stage 4 完了 (4a 半透明 / 4b cull / 4c 32bit インデックス / 4d temporal)。
> 記録は [`phase4-stage4-completion.md`](phase4-stage4-completion.md)。**
> 半透明は距離バケットごとに 1 draw。バケット内の順序は GL 版と同じく非決定的なので、
> 検証は集合として行い、絵の決定性は「半透明 quad が画面上で重ならない」規約で担保する。
> cull は「見えているものを落としていない」「見えないものは落ちている」の 2 方向で検証。
> temporal は不透明とエントリ配列・頂点シェーダ・走査シェーダを共有し、
> 「全て新規可視なら不透明と差分ゼロ」「全て既可視なら空」の 2 つの極端で挟んで検証した。
>
> **Phase 4 全体の完了記録は [`phase4-completion.md`](phase4-completion.md)。**

### Stage 4 — temporal / translucent へ展開

opaque が通ってから temporal (7 draws)、translucent (最大 1,024) に広げる。

### 2.1 区切りの根拠

| 段階 | 動作確認できるか | 切り分けられる問題 |
|---|---|---|
| 0 | ✅ サブミット | フレーム構造・パイプライン生成 |
| 1 | ✅ **絵が出る** | Vulkan 移植そのもの (バリア・descriptor・シェーダ) |
| 2a | ✅ テーブル検査 | prefix sum の正しさ |
| 2b | ✅ **Stage 1 と比較** | 二分探索と draw 統合 |

**Stage 1 が最も重要な区切り**である。ここを通せば、以降の不具合は
統合ロジックに限定できる。

---

## 3. GL 版を壊さずに進められるか — 判断材料

### 3.1 `queue.glsl` と何が違うか

| | `queue.glsl` (Phase 3) | `cmdgen.comp` / `quads3.vert` (Phase 4) |
|---|---|---|
| 変更内容 | **宣言 3 行**の差し替え | **ロジック本体** |
| 使用箇所 | 5 箇所が無変更で済んだ | 出力データ構造ごと変わる |
| `#ifdef` の量 | 3 行 | `cmdgen.comp` の `main()` 170 行の大半 [推測] |

**同じ手が使えるとは限らない、という懸念は妥当**である。

### 3.2 案 A: `#ifdef VULKAN` で両対応

**利点**
- GL 版が「移植の参照仕様」として生き続ける (Phase 3 で確立した方針と一貫)
- 差分が 1 ファイル内に収まり、対応関係が見える

**欠点**
- `main()` の大半が分岐すると**両方とも読みにくくなる**
- **GL 分岐の腐敗を検出できない。** GL 版はこの Mac で動かない
  (`phase2-binding-audit.md` §8.4) ため、壊れても気づけない

### 3.3 案 B: Vulkan 専用ファイルに分ける

`cmdgen.vk.comp` / `quads3.vk.vert` を作り、GL 版は触らない。

**利点**
- **GL 版が完全に無傷で残る** — 参照仕様としての価値が最大
- Vulkan 版を読みやすく書ける (分岐が無い)
- ロジックが本質的に違うので、分けたほうが実態に即している

**欠点**
- 共有部分 (`quad_format.glsl` 等の `#import` 先) 以外は二重管理
- 片方だけ直す事故が起きうる。ただし **GL 版はもう変更しない予定**なので
  実質的なリスクは低い [推測]

### 3.4 判断材料のまとめ

決め手は「**GL 版を今後変更するか**」である。

- 変更しない (参照専用) なら → **案 B。** 二重管理のリスクが顕在化しない
- 変更する可能性があるなら → 案 A

Phase 1〜3 の経緯を見るかぎり **GL 版は参照専用**として扱ってきた
(バインディング番号の同期だけは維持した)。
その前提なら **案 B が素直** [推測]。

ただし**バインディング番号の同期方針** (`phase2-binding-audit.md` §8) は
案 B でも維持すべきである。番号がずれると Stage 1 の移植で照合できなくなる。

**この判断はご指示を仰ぎたい。**

---

## 4. 検証方法の提案

同期バリデーションが使えない (`phase3-completion.md` §3.1) 前提での案。

### 4.1 主軸: **Stage 1 の出力を参照基準にする**

GL 4.6 環境は要らない。**同じ Mac の同じ Vulkan デバイス上で**、
統合前 (Stage 1) と統合後 (Stage 2b) の出力を比較する。

```
Stage 1 (88k draws, 未統合)  ──→ オフスクリーンに描画 ──→ 読み戻し ──→ 基準画像
Stage 2b (7 draws, 統合後)   ──→ 同上                ──→ 比較
```

**この比較が成立する条件**:
- 同じワールドデータ・同じ視点・同じ uniform
- シェーダの**描画結果に影響する部分**は変えない (索引の解決方法だけ変える)

→ **差分ゼロが期待値。** 1 ピクセルでも違えば統合ロジックのバグ。

**なぜこれが強いか**: バリア漏れと違い、draw 統合の誤りは**決定的**に現れる。
索引がずれれば必ず絵が壊れる。非決定性に紛れない。

### 4.2 補助: テーブルの不変条件検査 (Stage 2a)

prefix sum の出力を読み戻して検査する:
- 単調非減少
- `table[faceCount]` == 総 quad 数
- 各面の区間が重ならない
- ランダムな `gl_VertexIndex` に対する二分探索結果が、
  線形探索の結果と一致する (CPU 側で照合)

**二分探索の正しさは CPU 側で全数検査できる** — テーブルさえ読み戻せば、
シェーダを動かさずに検証できる [推測]。

### 4.3 補助: 統計値の突き合わせ

`RenderStatistics` が既に
`visibleSectionCounts` / `quadCounts` を LOD レイヤ別に集計している [確認済 —
`cmdgen.comp` の `HAS_STATISTICS` 経路]。

統合前後でこれらが一致すれば、**可視判定は変わっていない**ことの証拠になる。
描画の索引だけが変わったことを切り分けられる。

### 4.4 やらないこと

| 案 | 却下理由 |
|---|---|
| GL 版との比較 | **GL 版がこの Mac で動かない**。GL 4.6 環境の入手が前提になる |
| 同期バリデーション | 機能しない (§`phase3-completion.md` §3.1) |
| 期待値の目視のみ | 索引ずれは局所的だと気づきにくい |

---

## 5. 着手前に決めたいこと

1. **§3 の案 A / 案 B** — GL 版を今後変更するかどうかで決まる
2. **Stage 0 の前提** — グラフィクスパイプライン生成と `dynamic_rendering` が未実装。
   これを Phase 4 の最初に作るという理解でよいか
3. **オフスクリーン描画と読み戻しの仕組み** — §4.1 の検証に必要。
   `VkTexture` + `vkCmdCopyImageToBuffer` で作れる [推測] が、
   これも未実装 (画像アップロード/ダウンロードのギャップ)

---

## 6. Stage 0 完了記録

### 6.1 実装したもの

| クラス | 行数 | 内容 |
|---|---:|---|
| `VkRenderTarget` | 191 | オフスクリーン color+depth、`dynamic_rendering` での描画開始/終了、`vkCmdCopyImageToBuffer` による読み戻し、**ターゲット比較** |
| `VkGraphicsPipeline` | 154 | `dynamic_rendering` ベースのパイプライン生成 (Builder) |

`VkContext` に `dynamicRendering` 機能を追加 (`synchronization2` と同じ `VkPhysicalDeviceVulkan13Features`)。

**頂点入力は空でよい** [確認済]。Voxy は VAO を使わず `gl_VertexIndex` から
SSBO を引く (vertex pulling) ため、頂点属性の記述がまるごと不要になる。

### 6.2 検証済み (6 テスト、バリデーション有効で指摘ゼロ)

- クリア → 読み戻しでクリア色が往復する
- **頂点バッファ無しで実際に描画** (フルスクリーン三角形) → 読み戻しで色が一致
- **同一内容の 2 ターゲットが差分 0**
- **異なる内容が全ピクセル差分として検出される** (比較機構の対照)
- レイアウトがフレームを跨いで追跡され続ける (3 フレーム連続)

§4.1 の検証手段 (Stage 1 と Stage 2b の突き合わせ) の土台が動く状態になった。

### 6.3 参照実装との差異

`~/dev/mdi-bench` の `Offscreen.java` は**旧来の `VkRenderPass` 方式**である。
画像生成と読み戻しの手順は踏襲したが、
`VkRenderPass` / `VkFramebuffer` / `VkAttachmentDescription` は使っていない。
`dynamic_rendering` では描画開始時に `VkRenderingAttachmentInfo` を直接渡す。

### 6.4 フレーム境界リセットとの衝突 — **無し** [確認済]

指摘のあった制約を確認した。

`VkFrameTracker.beginFrame()` のフックが行うのは以下 2 つ:
- `VkUploadStream.resetForNewFrame()` — **モード 2 のバンプアロケータのみ**
- `VkDownloadStream.deliver()` — ダウンロード結果の配送

**描画コマンドバッファ (`drawCallBuffer`) はどちらにも該当しない。**
viewport が持つ永続 `VkBuffer` であり、cmdgen が GPU 側で書く。
リセット対象ではないので、フレームを跨いで内容が残る。

→ **`renderOpaque` が前フレームのコマンドを読む構造と衝突しない。**

ただし**別のハザードが見つかった** — 同一フレーム内の WAR。
詳細は `phase3-barrier-survey.md` §15。

### 6.5 Stage 1 への申し送り

| 項目 | 状態 |
|---|---|
| グラフィクスパイプライン | ✅ |
| オフスクリーン + 読み戻し | ✅ |
| 比較機構 | ✅ |
| 画像へのアップロード (`vkCmdCopyBufferToImage`) | ❌ ブロックアトラス転送で必要 |
| `MDICViewport` の VkBuffer 版 | ❌ |
| WAR ハザードの洗い出し | ❌ (§`phase3-barrier-survey.md` §15.3) |

---

## 7. Stage 1 進捗

### 7.1 ⚠ Stage 1 の「正しい」の意味 — **自己整合性に限定される**

Stage 1 の基準画像は**合成データ**から作る。実データ (ワールド読み込み +
メッシュ生成 + ブロックアトラス) はこの環境で得られないため
[確認済 — Voxy は GL 4.1 の Mac で起動しない。`phase2-binding-audit.md` §8.4]。

**したがって Stage 1 が検証するのは以下だけである**:

| 検証する | 検証しない |
|---|---|
| 同じ入力から決定的に同じ絵が出ること | **GL 版と同じ絵が出ること** |
| 統合前 (Stage 1) と統合後 (Stage 2b) が一致すること | 実ワールドで正しく見えること |
| データ形式のエンコードが復号と往復すること | シェーダの意味論が GL と等価であること |

**「GL 版と同じ絵か」は検証していない。** これは Phase 5 で実データを
入れたときに問われる別の問いである (`phase3-completion.md` §3.2)。

draw 統合に問われているのは「統合前と統合後で同じ入力から同じ絵が出るか」
なので、この限定で足りる。

### 7.2 完了: 合成データ生成 (`SyntheticTerrain`, 268行)

シェーダ側の定義から形式を導出した [確認済 — `quad_format.glsl` /
`section.glsl` / `pos_util.glsl`]。

**狙って作った境界条件** (実データでは滅多に出ない):

| # | ケース | 狙い |
|---|---|---|
| ① | セクション 1 / quad 1 | 最小 |
| ② | 面が 1 方向だけ | 面ごとの分岐 |
| ③ | 空セクション (quad 0) | **prefix が進まない区間** |
| ④ | 直前が 0 で急増 (100 quads) | prefix sum の偏り |
| ⑥ | quad 数がちょうど 64 | **二分探索の境界** |
| ⑦ | 7 draw 種すべてを使う | 統合後の 7 draws 全経路 |
| ⑧ | 負座標 | 位置エンコードの符号拡張 |

各 quad には**セクション/ラン/連番から作った stateId** を振ってある。
索引がずれれば絵に出る (`quadsAreDistinguishable` が重複率を検査)。

> ⚠ **この「索引がずれれば絵に出る」は当初成り立っていなかった。**
> `quadsAreDistinguishable` は quad の**中身**が一意かを見ているだけで、
> **可視位置**が一意かは見ていない。位置をラン内連番から作っていたため
> 別のランが同じ格子点を共有し、全コマンドを 1 quad ずらしても
> 512x512 で差分 0 ピクセルだった [確認済 — 実測]。
> 位置をセクション内通し番号から作るよう修正済み。
> 詳細は [`phase4-stage1-completion.md`](phase4-stage1-completion.md) §2.2。

#### エンコードのバグを 1 件踏んで直した

`z` の下位 4bit は `packed.y` の**最上位** (bit 28..31) に入る —
復号側が `z |= int(packedPos.y>>28)` と読むため。
下位ビットに置いて z が失われるバグを往復テストが検出した [確認済]。

**この往復テストが無ければ、Stage 1 の基準画像が静かに間違ったまま
Stage 2b の比較に使われていた。**

### 7.3 進捗

| # | 項目 | 状態 |
|---|---|---|
| 2 | `MDICViewport` の VkBuffer 版 | ✅ `VkTerrainResources` |
| 3 | アトラス転送 (`vkCmdCopyBufferToImage`) | ✅ |
| 4 | レンダラ本体 (まず `conservative` で全バリア → 絵が出てから絞る) | ✅ `VkTerrainRenderer` |
| 5 | PNG での目視確認 | ✅ `build/vk-test-output/terrain-*.png` |

**Stage 1 完了。記録は [`phase4-stage1-completion.md`](phase4-stage1-completion.md)。**

要点だけ:

- 合成データに **3 件の実バグ**が見つかった (面 6 の範囲外読み / quad の重なりで
  索引ずれが絵に出ない / アトラスがシェーダの UV 計算と噛み合っていない)。
  **直す前の合成データは Stage 2b の基準として使えなかった。**
- CPU で予測した画素位置・RGBA と実測が完全一致した (§3.1)
- バリアは絞れたが、**このスコープでは壊れようがない**ため根拠は弱い (§4)。
  バリア設計の本番は 5 段を戻すとき
