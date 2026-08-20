# Phase 4 Stage 3 完了記録 — cmdgen を GPU に戻す

**状態**: prep / cmdgen / prefix sum を GPU 化。テスト 124 件 PASS / 1 SKIP、バリデーション指摘ゼロ。

**結果**: GPU が作ったテーブルが CPU 版と<b>バイト単位で一致</b>し、
それで描いた絵が Stage 2b と<b>差分ゼロ</b>。

> ⚠ **5 段のうち cull と translucentGen は実装していない。** 理由は §5。

---

## 1. 作ったもの

| ファイル | 内容 |
|---|---|
| `lod/vk/prep.comp` (新) | ディスパッチサイズとエントリ数 |
| `lod/vk/cmdgen.comp` (新) | (面, セクション) ごとのテーブルエントリ + positionBuffer |
| `lod/vk/merged_prefix.comp` (新) | 走査 + 面ごとの 7 DrawCommand |
| `VkMergedTableBuilder` (新) | 3 段の記録とバリア |
| `SyntheticTerrain` (変更) | 面方向マスク、密なテーブル、可視フラグ |
| `VkGpuTableTest` (新) | Stage 3 の検証 8 件 |

### 1.1 段の対応

| GL の段 | Stage 3 | 備考 |
|---|---|---|
| prep | ✅ `prep.comp` | 不透明の描画カウンタは不要になった |
| cull (ラスタ) | ❌ | §5.1 |
| cmdgen | ✅ `cmdgen.comp` | DrawCommand ではなくテーブルを書く |
| prefixsum | ✅ `merged_prefix.comp` | 既存シェーダは流用できず (§2.3) |
| translucentGen | ❌ | 半透明描画そのものが Stage 4 |

---

## 2. 設計上の判断 3 つ

### 2.1 テーブルを**密**にした — GPU を決定的にするため

エントリのスロットを `face * sectionCount + i` に固定し、
**quad 数 0 の面も長さ 0 のエントリとしてスロットを占める**。

詰めて並べるには `atomicAdd` でスロットを取り合うことになり、
**同じ入力でも実行ごとに並びが変わる**。そうなると
「CPU が作ったテーブルと GPU が作ったテーブルが一致するか」を
バイト単位で比べられなくなり、Stage 3 の検証手段が 1 つ失われる。

密なら atomic が不要で、CPU と GPU がまったく同じ配列を作る
[確認済 — `gpuTableIsDeterministic` が 4 回連続でバイト一致]。

**代償**: 配列が `7 * sectionCount` 固定。20,000 セクションで 1.1MB。
ユニファイドメモリでは無視できる。

**quad 通し番号 → quad の対応は詰めた版と同一**である。
空エントリは prefix に平坦部を作るだけで、非空エントリの並びを変えない。
二分探索は `prefix[lo] <= q < prefix[lo+1]` を満たす lo に収束し、
この条件は長さ 0 のエントリでは成立しないので<b>平坦部を必ず飛ばす</b>
[確認済 — `binarySearchNeverLandsOnAnEmptyEntry` が全通し番号で検査]。

> Stage 2 で「prefix は狭義単調増加」としていた不変条件は
> 「単調非減少 + 二分探索は空エントリに着地しない」に置き換えた。

### 2.2 面方向マスク (`msk`) を CPU 側にも入れた

`cmdgen.comp` は `relative.y > -1` のような条件で<b>裏を向いている面を落とす</b>。
GPU 版だけがこれを持つと CPU 版と差分が出るため、
`SyntheticTerrain.faceMask` として CPU 側にも実装した。

ラスタ由来の可視判定と違い、**カメラのセクション座標だけで決まり外部依存が無い**ので
CPU で完全に再現できる。Stage 1/2 の CPU 生成にも遡って適用してある。

**副作用**: 境界条件のセクションが丸ごとマスクで落ちるようになった (§4.2)。

### 2.3 既存の prefix sum シェーダは流用できなかった

`util/prefixsum/inital3.comp` と `simple.comp` は
**ちょうど 1024 要素** (256 スレッド × uvec4) を 1 ディスパッチで処理する形に固定されており、
**チャンクをまたぐキャリーを持たない** [確認済 — どちらも
`ioCount[gl_GlobalInvocationID.x]` を 1 回読んで 1 回書くだけ]。
半透明の距離バケット (`TRANSLUCENT_WRITE_BASE = 1024`) 専用の作りである。

統合テーブルは `7 * sectionCount` 要素で、20,000 セクションなら 140,000 要素。
そこで **単一ワークグループ + チャンクごとのキャリー**で任意長を走査する形にした。
複数ワークグループにするとディスパッチ間同期が要り段数が増えるが、
1 ワークグループなら `barrier()` だけで閉じる。

**ループには上限を切ってある** (`MAX_CHUNKS = 65536`)。
`mergedEntryCount` が壊れていてもハングしない。
外側ループの終了条件が全スレッドで同一 (uniform) であることが
`barrier()` との両立に要る — ここを崩すと未定義動作になる。

---

## 3. 検証

### 3.1 テーブルのバイト比較が主

| 検査 | 結果 |
|---|---|
| GPU のテーブル == CPU の参照 (entries / prefix / 7 draw / positionBuffer) | **バイト一致** |
| 毒 (`0xDEADBEEF`) で埋めた領域がすべて上書きされている | ✅ |
| 4 回作り直してもバイト一致 (決定性) | ✅ |
| 可視判定が効く / 切り替えると総 quad 数が変わる | 189 → 175 |
| GPU 生成テーブルで描いた絵が Stage 2b と一致 | **差分 0 px** |
| 絞ったバリアでもテーブルと絵が一致 | ✅ |

**毒埋めを入れた理由**: 「一致した」が「そもそも書いていない」でないことを分けるため。
ゼロ初期化のままだと、書き忘れと「正しく 0 を書いた」が区別できない。

### 3.2 バイト比較と絵の両方を見る理由

- テーブルだけ → 「テーブルは合っているが描画に届いていない」を見逃す
- 絵だけ → 「見えない部分の索引の誤り」を見逃す

---

## 4. バリア — **ここが系列表の本番だった**

### 4.1 実在した WAR

描画をテーブル生成より**前**に置く構成
(GL の `renderOpaque` → `buildDrawCalls` と同じ) にしたので、
`phase4-buffer-hazards.md` §10.1 の WAR が実際に現れた:

| バッファ | 読み手 (前) | 書き手 (後) |
|---|---|---|
| `mergedDraw` | 間接コマンド読み (`DRAW_INDIRECT`) | `merged_prefix` |
| `mergedEntry` / `mergedPrefix` | 頂点シェーダの SSBO 読み | `cmdgen` / `merged_prefix` |
| `positionScratch` | 頂点シェーダの SSBO 読み | `cmdgen` |

`VkMergedTableBuilder.beforeTableBuild` がこれを 1 本のバリアで断ち切る。
**GL には対応するバリアが無い** (実装の順序保証に委ねていた)。

### 4.2 段間のバリアと、対応表の訂正の実地確認

| 箇所 | 使ったもの | 対応表との関係 |
|---|---|---|
| prep → cmdgen | `computeToIndirect(COMPUTE)` | **訂正 #4 が正しかった**。直後が `vkCmdDispatchIndirect` なので P1 では足りず `INDIRECT_COMMAND_READ` が要る |
| cmdgen → prefix | `computeToCompute` | P1 のまま |
| prefix → 描画 | `computeToIndirect(INDIRECT_DRAW_CONSUMERS)` | P2。間接コマンドと頂点 SSBO の両方 |

`phase4-buffer-hazards.md` §10.2 の訂正 #4 / #6 は、
**実際に間接ディスパッチを使う経路を組んだうえで妥当だった**ことを確認した
(ただし §4.3 の限界つき)。

### 4.3 ⚠ 「絞っても壊れなかった」の限界は Stage 1 と変わらない

`CONSERVATIVE` と `NARROW` でテーブルも絵も一致した。しかし:

1. **同期バリデーションが機能しない** — 記述漏れを検出する手段が無い
2. **in-flight = 1** — フレーム境界で必ず GPU がアイドルになるため、
   フレームを跨ぐハザードは fence が覆ってしまう
3. 上の WAR は<b>同一コマンドバッファ内</b>なので fence では覆われない。
   その意味で Stage 1 より強い検査にはなっている

**それでも「バリアが十分である証明」ではない。**
足りないバリアは Apple GPU のスケジューリング次第で表に出ないことがある [推測]。

---

## 5. 実装していない 2 段

### 5.1 cull ラスタパス — **成功条件と衝突するため見送った**

cull は各セクションの AABB を深度テスト付きで描き、
通ったものに `visibilityData[sid] = frameId` を書くパス
[確認済 — `cull/raster.vert` + `raster.frag`]。

**Stage 3 の成功条件は「Stage 2b と差分ゼロ」である。**
cull を入れると可視セクションの集合が遮蔽に応じて変わるので、
そのままでは差分ゼロにならない。
「深度を遠クリアして誰も遮蔽しない状態で走らせる」なら差分ゼロを保てるが、
それは cull を実質無効化して走らせるだけで、得るものが薄い。

**移植自体の見通しは立っている** (以下は調査済みで、実装だけが残っている):

| 項目 | 状態 |
|---|---|
| `gl_InstanceID` | `prep.comp` が `cullDrawIndirectCommand.baseInstance = 0` を書く [確認済] ので、`phase2-glsl-compat.md` §3.1 の条件 (baseInstance が 0) を満たし `gl_InstanceIndex` にそのまま置換してよい |
| 立方体インデックス | GL 側は `GL_UNSIGNED_BYTE`。Vulkan コアに 8bit インデックスは無い (`VK_KHR_index_type_uint8` が要る) ので、16bit の立方体用インデックスバッファを別に作る必要がある |
| パイプライン | カラーアタッチメント無しの深度専用。`VkGraphicsPipeline.Builder` は現在カラーを必ず 1 枚要求するので分岐が要る |
| フラグメントからの SSBO 書き | `fragmentStoresAndAtomics` は有効済み [確認済 — `VkContext`] |

**意味のある検証をどう設計するかに判断が要る**ため、指示を仰ぎたい (§7)。

### 5.2 translucentGen — Stage 4

半透明の**描画パスそのものが未実装**である。
コマンドだけ生成しても消費側が無いので検証できない。
`buildtranslucents.comp` は距離バケットのソートを伴い、
`prefixsum` の既存シェーダ (1024 要素固定) はそちら用である。

---

## 6. 対照実験 — **4 例目の失敗**

`phase4-stage1-completion.md` §5.2 の表に追記した。

| # | 何が起きたか |
|---|---|
| 1 | z エンコードの取り違え (往復テストで検出) |
| 2 | quad の重なりで索引ずれが絵に出ない |
| 3 | 統合テーブルを 1 本だけ壊す対照が、壊した先が画面外で無効化 |
| **4** | **同じ対照が、密なレイアウト移行で再び無効化**。`prefix[1]` が長さ 0 のエントリの境界になり、壊しても解決結果が 1 件も変わらなかった。**非空エントリ同士の境界を探して壊す**形に変更 |

3 と 4 は**同じ対照が別の理由で 2 回無効化された**。
対照実験は書いたあとも壊れる。

さらに Stage 1 の教訓と同じ型がもう 1 件:

**面方向マスクを入れた結果、境界条件が 2 つマスクで全部落ちた**
(2 の冪ちょうどの EAST 64 枚、負座標の WEST 5 枚)。
落ちれば絵にもテーブルにも現れず、**その境界条件は何も検査していない**。
セクション座標を条件が通る位置に移し、
`SyntheticTerrainTest.everyBoundaryCaseSurvivesTheFaceMask` を常設検査にした。

---

## 7. 32bit インデックスバッファ — **見積もりが想定より大きい**

「代償はメモリが倍になることだけ」という前提が成り立たない。

### 7.1 素直に 32bit 化した場合

統合すると **1 draw に 1 つの面の全 quad が入る**ため、
共有インデックスバッファは<b>最大の面 1 つぶん</b>を覆う必要がある。
1 quad = 6 インデックス × 4 バイト = **24 バイト/quad**。
ジオメトリ側は 8 バイト/quad なので、**インデックスバッファがジオメトリの 3 倍**になる。

ジオメトリバッファ容量は 512MB〜4GiB [確認済 — `RenderResourceReuse.getGeometryBufferSize`]、
1 quad 8 バイト [確認済 — `GEOMETRY_ELEMENT_SIZE`]:

| ジオメトリ容量 | 総 quad | 1 面 = 全体 | 1 面 = 1/3 | 1 面 = 1/6 |
|---|---:|---:|---:|---:|
| 512 MiB (下限) | 67.1M | 1.50 GiB | 0.50 GiB | 0.25 GiB |
| 2 GiB | 268.4M | 6.00 GiB | 2.00 GiB | 1.00 GiB |
| 4 GiB (上限) | 536.9M | **12.00 GiB** | 4.00 GiB | 2.00 GiB |

### 7.2 提案: 共有バッファを固定サイズに切り、面を複数 draw に分ける

インデックスバッファは<b>共有</b>資源なので、全体を覆う必要はない。
T quad ぶんに固定し、1 面が T を超えたら `baseVertex` を進めて複数 draw にする。

**頂点シェーダは一切変わらない。** `baseVertex` を進めるだけで
`gl_VertexIndex >> 2` はグローバルな quad 通し番号のままだからである。
Stage 2b で差分ゼロを確認したシェーダロジックに触れずに済む
(非インデックス描画案を却下した理由がそのまま生きる)。

| T (quad) | インデックスバッファ | draws @537M quad | draws @67M quad |
|---:|---:|---:|---:|
| 16,380 (現行 16bit) | 0.4 MiB | 32,790 | 4,097 |
| 262,144 | 6.0 MiB | 2,055 | 262 |
| **1,048,576** | **24.0 MiB** | **519** | **70** |
| 4,194,304 | 96.0 MiB | 135 | 22 |

Phase 0 の実測では **1,000 draws 以下なら差は 0.5%** なので、
**T = 1M (24MiB) が上限規模でも 519 draws** に収まり余裕がある。

**T の決定は指示を仰ぎたい。** Stage 3 の合成データは現行の 16bit で足りるため、
この判断は Phase 5 (実データ) までブロックにならない。

---

## 8. Stage 4 への申し送り

| 項目 | 状態 |
|---|---|
| prep / cmdgen / prefix sum の GPU 化 | ✅ |
| 面方向マスク (CPU / GPU 一致) | ✅ |
| 可視判定の受け口 (`visibilityData`) | ✅ (書き手が CPU) |
| cull ラスタパス | ❌ §5.1。判断待ち |
| temporal パス | ❌ Stage 4 |
| translucentGen + 半透明描画 | ❌ Stage 4 |
| 旧経路 (`Mode.PER_SECTION`) の削除 | 保留。GPU 版の参照として残す価値がある |
| 32bit インデックス | ❌ §7。判断待ち |

### 8.1 実行方法

```bash
./gradlew test -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true
```

`build/vk-test-output/terrain-gpu-table.png` が GPU 生成テーブルでの描画結果。
`terrain-merged.png` (Stage 2b) と同一になる。
