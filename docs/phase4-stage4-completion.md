# Phase 4 Stage 4 完了記録 — 半透明 / cull / 32bit インデックス / temporal

**状態**: 4a / 4b / 4c / 4d すべて完了。テスト 155 件 PASS / 1 SKIP、バリデーション指摘ゼロ。

| 項目 | 状態 |
|---|---|
| 4a 半透明パス | ✅ (§4) |
| 4b cull | ✅ (§2) |
| 4c 32bit インデックス (T = 1M) | ✅ (§1) |
| 4d temporal パス | ✅ (§6) |

---

## 1. 4c — 32bit インデックスバッファと面の分割

### 1.1 作ったもの

| ファイル | 内容 |
|---|---|
| `VkQuadIndexBuffer` (新) | 32bit 共有インデックス。容量ごとに 1 本キャッシュ |
| `SyntheticTerrain` (変更) | `writeFaceDraws` が T 超過を分割、`faceDrawCount` / `maxFaceDrawCount` |
| `lod/vk/merged_prefix.comp` (変更) | GPU 側でも同じ規則で分割 |
| `VkIndexSplitTest` (新) | 検証 6 件 |

### 1.2 T = 1M の帰結

インデックスバッファは<b>共有</b>資源なので面全体を覆う必要はない。
T quad ぶんに固定し、超えたら {@code baseVertex} を進めて複数 draw に分ける。

```
T = 1,048,576 quad -> 24 MiB
上限規模 (537M quad) で 519 draws  <- Phase 0 の 1,000 draws 閾値内
```

**頂点シェーダには一切触っていない。**
`gl_VertexIndex = baseVertex + インデックス値` で `baseVertex` は 4 の倍数なので、
`>>2` はグローバル quad 通し番号、`&3` は corner のまま。
Stage 2b で差分ゼロを確認したロジックがそのまま生きる。

### 1.3 空の面も 1 本出す

面と draw の対応を<b>データ非依存</b>に保つため、quad 数 0 の面も
`instanceCount = 0` の no-op を 1 本出す。
GPU 側は実際の quad 数を CPU から見せられないので、
`maxFaceDrawCount = 7 + 総quad/T` の上限本数を発行し、余りは no-op で埋める。

### 1.4 合成データでは分割が起きないので T を絞って試す

実データ用の T = 1M に対し合成データは 189 quad しかない。
**T を小さくしなければ分割経路は 1 行も実行されない。**
`VkIndexSplitTest` は T = 1M / 64 / 8 / 4 / 1 で描いて<b>全て同じ絵</b>になることを確かめ、
`smallCapacitiesActuallySplit` が「T=4 では実際に 7 → 50 draws に割れている」ことを
対照として押さえている (割れていなければ上のテストは何も検査していない)。

GPU 側の分割が CPU 側とバイト一致することも別途確認済み
(`gpuSplitMatchesTheCpuSplit`、T=8 で 27 draws)。

---

## 2. 4b — 遮蔽カリング

### 2.1 作ったもの

| ファイル | 内容 |
|---|---|
| `lod/vk/cull_raster.vert` (新) | AABB の箱を描く。`gl_InstanceID` → `gl_InstanceIndex` |
| `lod/vk/cull_raster.frag` (新) | `early_fragment_tests` + `visibilityData` 書き込み |
| `VkCullPass` (新) | 深度専用パイプライン、立方体インデックス、バリア |
| `VkGraphicsPipeline` (変更) | `depthOnly()` — カラーアタッチメント無し |
| `VkRenderTarget` (変更) | `beginRenderingDepthOnly()` |
| `SyntheticTerrain.Section` (変更) | `aabb(offset, size)` |
| `VkCullTest` (新) | 検証 8 件 |

### 2.2 移植で必要だった 3 点 [確認済]

| 項目 | 対応 |
|---|---|
| `gl_InstanceID` | `prep` が `baseInstance = 0` を書く [確認済 — `lod/gl46/prep.comp`] ので、`phase2-glsl-compat.md` §3.1 の条件を満たし `gl_InstanceIndex` に置換可 |
| 立方体インデックス | GL は `GL_UNSIGNED_BYTE`。Vulkan コアに 8bit インデックスは無い (`VK_KHR_index_type_uint8` が要る) ので 32bit の専用バッファを作った。<br>⚠ その後 Phase 5 の調査で **この拡張は実機で利用可能**と判明した [確認済 — `phase5-proposal.md` §8.1]。記述は正確 (コアには無い) だが、8bit も選べた。インデックス 36 個なので実害は無い |
| 深度専用パス | カラーアタッチメント無しの `VkPipelineRenderingCreateInfo` + `LOAD` の深度 |

`baseInstance = 0` の前提は `VkCullTest.cullDrawUsesZeroBaseInstance` が常設で見張る。
ここが変わると<b>静かにセクションがずれる</b>。

### 2.3 判定基準 — 2 方向

**cull の正しさは「同じ絵が出ること」ではない。** 可視セクションの集合が変わるのが目的だから、
Stage 2b との差分ゼロは成功条件にできない。

| 向き | 何を防ぐか | 検査 | 結果 |
|---|---|---|---|
| 見えているものを落としていない | 過剰カリング | cull あり/なしで絵が変わらない | **差分 0 px** |
| 見えないものは落ちている | カリングが効いていない | 遮蔽したセクションが不可視になる | ✅ |
| — | 「常に不可視」が通らないこと | 遮蔽を外すと可視に戻る | ✅ |
| — | 段をまたいで効いている | テーブルの quad 数が減る | 1040 → 1024 (ちょうど 16 枚) |

片方だけでは成立しない。前者だけなら「何もカリングしない」実装が通り、
後者だけなら「全部落とす」実装が通る。

### 2.4 遮蔽関係の作り方

32x32 の壁 (SOUTH 面 1024 枚、ワールド x 0..32 / y 0..32 / z=1) を手前に置き、
その真後ろ (z = -32 のセクション) に AABB を 10..18 に絞ったセクションを置く。

**AABB を絞る必要がある。** cull が描くのは箱であって quad ではないので、
箱が壁からはみ出すと可視と判定される。しかも箱は ±1 拡張される
[確認済 — `cull_raster.vert` の `EXPANSION`]。
そのため `Section.aabb(offset, size)` を追加した。

### 2.5 バリア

| 箇所 | 内容 |
|---|---|
| 不透明パスの深度書き → cull の深度テスト | レイアウト遷移込みで `beginRenderingDepthOnly` が持つ |
| 前フレームの cmdgen の `visibilityData` 読み → cull の書き (WAR) | `beforeCull` |
| cull の `visibilityData` 書き → cmdgen の読み (RAW) | `afterCull` |

最後のものが `phase4-buffer-hazards.md` §4 の「**P1 では src が誤り**」の箇所で、
<b>書き手がフラグメントシェーダ</b>である。`computeToCompute` を使うと src が compute になり合わない。
専用に書いた:

```
src: FRAGMENT_SHADER / SHADER_STORAGE_WRITE
dst: COMPUTE_SHADER  / SHADER_STORAGE_READ
```

**対応表の指摘が実地で正しかった**ことを確認した (3 例目)。
絞ったバリアでも可視結果・絵ともに一致 (§`phase4-stage3-completion.md` 4.3 と同じ限界つき)。

---

## 3. 対照実験 — 6〜9 例目

`phase4-stage1-completion.md` §5.2 の表に追記した。今回は 4 件出た。

### 3.1 判定の初期値が検査を殺していた (6 例目)

cull は<b>通ったセクションに書き込むだけで、落としたセクションを消さない</b>
[確認済 — `cull/raster.frag`]。落ちたことは「値が今の frameId と一致しない」で表す。

テストで `visibilityData` を frameId で埋めて始めたため、
**cull が 1 つも落とさなくても全セクションが可視のまま**になり、
「カリングされない」ように見えた。frameId を毎フレーム進める形に直した
(本番はそうなっている)。

> 検査対象ではなく<b>判定の初期条件</b>が壊れていた例。
> 「cull が動いていない」と「テストが動いていない」を分けるのに
> 深度バッファを直接読み戻す必要があった。

### 3.2 対照が別の理由で無効になっていた (7 例目)

「遮蔽を外すと可視に戻る」という対照で、セクションを横に 2 区画ずらしたところ
<b>視錐台の外に出てしまい</b>、遮蔽ではなくフレームアウトで不可視になった。
対照としては「可視に戻らなかった」ので失敗が見えたが、
逆向き (可視に戻ってしまう位置) に外していたら気づけなかった。

壁の影の広がりを深度から計算し、<b>画面内に残る範囲</b>でずらす形に直した。

### 3.3 規約の検査自体に穴があった (8 例目)

「半透明 quad が画面上で重ならない」検査が、<b>画面外の quad を素通し</b>していた。
画面外なら自明に重ならないので、4 枚のうち 1 枚がまったく検査されないまま通っていた。
「全 quad が画面内にあること」を同時に検査する形に直した。

> **規約を足しても、その規約の検査に穴があれば意味がない。**
> 「この検査を通る自明な抜け道は何か」を毎回問うこと。

### 3.4 枚数の閾値では弱かった (9 例目)

「半透明パスで絵が変わる」を画素数の閾値で見ていたが、
1x1 quad は 4 セクションを収める視点では数画素にしかならず、
「たまたま増えた」と区別が付かない。
CPU で射影した<b>中心の画素</b>が 1 枚ずつ変わることを確かめる形に直した。

---

## 4. 4a — 半透明パス

### 4.0 作ったもの

| ファイル | 内容 |
|---|---|
| `lod/vk/translucent_gen.comp` (新) | 距離バケット順への並べ替え |
| `lod/vk/translucent_prefix.comp` (新) | quad の走査 + バケットごとの DrawCommand |
| `lod/vk/prep.comp` / `cmdgen.comp` (変更) | バケット計数とセクション登録、毎フレームのリセット |
| `VkTerrainRenderer.Pass` (新) | `OPAQUE` / `TRANSLUCENT` |
| `VkTranslucentTest` (新) | 検証 9 件 |

`util/prefixsum/inital3.comp` は<b>無改造でそのまま流用した</b> (§4.2)。

### 4.0.1 頂点シェーダを共有している

半透明パスは `lod/vk/quads3.vert` を<b>そのまま</b>使う。
binding 10/11 に不透明のテーブルを張るか半透明のテーブルを張るかだけが違い、
索引解決のコードは 1 行も変えていない。
Stage 2b で差分ゼロを確認したロジックがそのまま効く。

フラグメント側は `TRANSLUCENT` define でアルファ判定が変わり、
パイプラインは `alphaBlend(true)` (GL 側 `glBlendFuncSeparate` と同じ組み合わせ [確認済])。

### 4.0.2 段の構成

```
cmdgen            半透明セクションを登録 + 距離バケットを atomicAdd で計数
inital3.comp      1024 バケットを走査 -> スロット開始位置
translucent_gen   atomicAdd でスロットを取り、バケット順にエントリを書く
translucent_prefix  スロットごとの quad 数を走査 -> バケットごとに 1 draw
```

### 4.0.3 検証 — 集合として見る

| 検査 | 結果 |
|---|---|
| 各セクションが正しいバケットにちょうど 1 回 | `{1020=[3], 1021=[1,2], 1022=[0]}` |
| バケットが遠い順に並ぶ (baseVertex 昇順) | ✅ |
| 半透明 quad が予測した画素に現れる | 4 枚とも ✅ |
| 4 回描いて同じ絵 | ✅ |
| バケットが T を超えていない | 最大 2 quad、切り詰め 0 |
| 半透明ゼロのシーンでも壊れない | ✅ |
| 絞ったバリアでも一致 | ✅ |

**バケット内の順序は検証していない。** 非決定的だからで、
`{1021=[1,2]}` は集合として比べている。

### 4.0.4 ⚠ 1 バケットが T を超えたときは切り詰める

不透明側は面を複数 draw に分割するが、半透明はバケットが 1024 本あり
draw スロットの上限がデータ依存になるため、分割を実装していない。
代わりに<b>シェーダ側で count を T に切り詰め</b>、
切り詰めた回数を診断バッファに残す。

- **メモリ安全**: 共有インデックスバッファの外は読まない
- **絵は欠ける**: 切り詰められたぶんの quad が描かれない
- **検出できる**: `clampedBuckets != 0` を CPU が確かめられる (テストが常設で見ている)

実データで `clampedBuckets` が 0 でなくなったら分割の実装が要る [未検証]。

### 4.1 GL 側の構造 [確認済 — `buildtranslucents.comp`]

```
① cmdgen      半透明 quad を持つセクションを translucentCommandData[BASE+i] に登録し、
              距離バケット translucentCommandData[dist] を atomicAdd で数える
② prefixsum   1024 バケットを走査 -> 各バケットの開始位置
③ translucentGen  各セクションのバケット内スロットを atomicAdd で取り、
                  DrawCommand を TRANSLUCENT_OFFSET + slot に書く
④ renderTranslucent  ブレンド有効で MDI
```

距離は `(|rel.x|+|rel.y|+|rel.z|) << detail` を 1023 で頭打ちにし、
**遠いほど小さい番号**になるよう反転する (遠くから描くため)。

### 4.2 ここで既存の prefix sum が<b>使える</b>

`util/prefixsum/inital3.comp` は<b>ちょうど 1024 要素</b>を 1 ディスパッチで処理する作りで、
これは距離バケット (`TRANSLUCENT_WRITE_BASE = 1024`) 専用に書かれたものである
[確認済]。Stage 3 の統合テーブルには使えなかったが、<b>半透明にはそのまま使える</b>。

### 4.3 バケット内の順序 — **案 A を採用した**

Phase 0 の設計は「バケット単位で 1 draw、最大 1,024」だが、
GL 版は `atomicAdd` でスロットを取るので<b>バケット内のセクション順が実行ごとに変わる</b>。

半透明はブレンドが<b>順序依存</b>なので、これは:

- 同じ入力でも<b>絵が実行ごとに変わりうる</b> (重なった半透明 quad がある場合)
- Stage 1〜3 で使ってきた「差分ゼロ」の比較が<b>そのままでは成立しない</b>

GL 版もこの非決定性を持っている [確認済 — `atomicAdd` によるスロット確保] ので
「忠実な移植」としては atomicAdd のままが正しい。しかし検証方法が変わる:

| 案 | 内容 | 代償 |
|---|---|---|
| A | atomicAdd のまま。合成データで<b>半透明 quad が画面上で重ならない</b>ようにして絵を決定的にする | 「重なり無し」を別途常設検査する必要がある (規約が 1 つ増える) |
| B | 決定的な安定ソートにする (カウンティングソート) | GL 版と挙動が変わる。バケット内 O(1024 x セクション数) の走査が要る |

**案 A を採用**。`atomicAdd` のままにして GL 版に忠実を保ち、
合成データ側で「半透明 quad は画面上で重ならない」ことを規約 2 にした
(`phase4-stage1-completion.md` §5.1b)。

Phase 5 で「GL 版と同じ絵か」を問うとき、
ここで挙動を変えていると原因が半透明ソートか他かを切り分けられなくなる。

### 4.4 その他

- 半透明は<b>面方向マスクの対象外</b> (両面描画なので) [確認済 — cmdgen は
  `counts.x&0xFFFF` を msk と無関係に扱う]
- **temporal パスは未着手。** 構造は不透明の 7 draws をもう一組作るだけ [推測]。
  `renderTemporal` は「今フレーム新たに可視になったセクション」を描くもので、
  `visibilityData` の bit 31 (`wasVisibleLastFrame`) が入力になる

---

## 6. 4d — temporal パス

### 6.1 何のためのパスか

不透明パスは<b>前フレームに生成されたコマンド</b>で描くので、1 フレーム遅れの
可視集合しか出せない [確認済 — `AbstractRenderPipeline.runPipeline` の呼び出し順]。
今フレーム新たに可視になったセクションはそこに含まれないため、
temporal パスが<b>今フレームのテーブル</b>でそれだけを描いて埋める。

対象かどうかは {@code visibilityData} の bit 31 (cull が立てる
「前フレームも可視だった」印) が<b>立っていない</b>ことで決まる
[確認済 — `cmdgen.comp:67` / `cull/raster.vert:55-56`]。

### 6.2 エントリ配列を共有している

**temporal テーブルは不透明テーブルと同じランを、絞り込んだだけのものである。**
ラン先頭の quad 番号もセクション番号も 1 バイトも変わらず、
違うのは<b>quad 数 (= prefix) だけ</b>
[確認済 — GL 版 cmdgen も同じ `ptr` / `count` で `writeCmd` している]。

したがって:

| | 不透明 | temporal |
|---|---|---|
| エントリ配列 | `mergedEntry` | **共有** |
| prefix | `mergedPrefix` | `temporalPrefix` |
| DrawCommand | `mergedDraw` | `temporalDraw` |
| 頂点シェーダ | `lod/vk/quads3.vert` | **同一** |
| 走査シェーダ | `merged_prefix.comp` | **同一ソース**を binding だけ変えて 2 本目のパイプラインに |

新しいシェーダは 1 本も書いていない。`cmdgen.comp` が
`temporalCounts[slot] = renderTemporally ? count : 0` を追加で書くだけである。

### 6.3 検証 — 2 つの極端で挟む

temporal は不透明の絞り込みなので、絞り込みが<b>恒等</b>と<b>全消し</b>になる条件が
そのまま強い検査になる:

| 条件 | 期待 | 結果 |
|---|---|---|
| 全セクションが前フレーム不可視 | temporal == 不透明 | prefix 全一致、**絵が差分 0 px** |
| 全セクションが前フレーム可視 | temporal は空 | quad 0、7 draw すべて no-op、絵は背景のみ |
| 一部だけ除外 | 真部分集合 | 175 / 189 quad、CPU 参照とバイト一致 |

片方だけでは成立しない。前者だけなら「常に全部」の実装が通り、
後者だけなら「常に空」の実装が通る。

**「空のまま通っていないか」の対照** (6 例目の型) は
`temporalIsNeitherEmptyNorEverything` が
`0 < temporalQuads < opaqueQuads` を明示的に要求する形で置いた。
さらに `markingSectionsAsAlreadyVisibleChangesTheTemporalImage` が
「除外すると絵が 5,525 px 変わる」ことを確かめ、
bit 31 のフィルタが<b>描画まで届いている</b>ことを押さえている。

### 6.4 この検査を空虚に満たす方法は無いか (8 例目を受けた確認)

| 抜け道 | 塞いだか |
|---|---|
| temporal が常に空 | ✅ `temporalQuads > 0` を要求 |
| temporal が常に不透明と同じ | ✅ `temporalQuads < opaqueQuads` を要求 |
| テーブルは合っているが描画に届かない | ✅ 絵の差分ゼロ / 絵が変わる、の両方を見る |
| 絵が空で「差分ゼロ」 | ✅ `covered > 1000` を先に確認 |
| セクション 0 が画面外で「除外しても変わらない」 | ✅ 除外で 5,525 px 変わることを確認 (至近の視点に映っているセクションを選んだ) |

---

## 5. 実行方法

```bash
./gradlew test -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true
```

`build/vk-test-output/cull-scene.png` がカリングのテストシーン (32x32 の壁)、
`terrain-translucent.png` が半透明パスの出力。
