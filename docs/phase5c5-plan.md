# Phase 5c-5 — <b>半透明・temporal を実データで戻す</b>。Phase 5 を閉じる段

**状態**: 調査完了。5c-5a 着手。

---

## 1. 調査で分かったこと — <b>テーブル側は既に走っている</b>

5c-4c の時点で `VkMergedTableBuilder.record` は<b>毎フレーム 7 段</b>を記録している
[確認済 — 実物]:

```
① prep → ② cmdgen → ③ merged prefix
④ temporal prefix
⑤ bucket scan → ⑥ translucent gen → ⑦ translucent prefix
```

**繋がっていないのは描画の 2 本だけ**である。`VkHierarchicalScene` が
`Pass.OPAQUE` のレンダラを 1 個しか作っていない。

| | テーブル | 描画 |
|---|---|---|
| 不透明 | ✅ 走っている | ✅ 繋がっている |
| temporal | ✅ 走っている | ❌ **レンダラが無い** |
| 半透明 | ✅ 走っている | ❌ **レンダラが無い** |

頂点シェーダ・エントリ配列は 3 パス共通で、`MERGED_PREFIX_BINDING` に張る
バッファだけが違う [`VkTerrainRenderer.bindDescriptors`]。**新しいシェーダは要らない。**

---

## 2. ⚠⚠ temporal は今の構成では<b>構造的に空</b>になる

```glsl
// cmdgen.comp:103
renderTemporally = (dat & 0x80000000u) == 0u;
```

```glsl
// cull_raster.vert:60-63
uint previous = visibilityData[sid]&0x7fffffffu;
bool wasVisibleLastFrame = previous==(frameId-1);
value = (frameId&0x7fffffffu)|(uint(wasVisibleLastFrame)<<31);
```

**bit 31 を書いているのはカルパスの頂点シェーダだけである。**
今は `VkHierarchicalScene.markEverythingVisible` が全セクションに bit 31 を立てるので、
<b>temporal に回るセクションは 1 つも無い</b>。

> **繋いだだけでは「何も描かれない」が PASS と読める形になる** [規約 11]。
> temporal の実データ検証は
> <b>カルパスを繋ぐか、可視をホストで作為的に作るか</b>のどちらかを要求する。

---

## 3. 段の分け方 — <b>予測できる形を 1 段はさむ</b>

5c-4b / 5c-4c と同じ構造を、可視集合に対して行う。

| 段 | 変える 1 変数 | 可視 | temporal の期待集合 |
|---|---|---|---|
| **5c-5a** | <b>temporal の描画</b> | **ホストが作為的に書く** | **予測できる** |
| **5c-5b** | <b>可視が GPU で決まる</b> | カルパス | ⚠ 予測できない |
| **5c-5c** | <b>半透明の描画</b> | カルパス | — |

**5c-5a が要である。** 可視をホストが書くなら<b>両極を作れる</b> —
Phase 4 の `VkTemporalTest` と<b>同じ形の対照が実データで使える</b>。

---

## 4. 5c-5a の対照 — <b>両方向</b>

| 可視の書き方 | 主張 | 何を落とすか |
|---|---|---|
| 全セクション bit 31 <b>立てる</b> | temporal は **空** | 「常に不透明と同じものを描く」実装 |
| 全セクション bit 31 <b>落とす</b> | temporal == **不透明** (quad 数が一致) | 「temporal が常に空」実装 |
| <b>一部だけ</b> 落とす | temporal は<b>真部分集合</b>で非空 | 上の 2 つを同時に落とす |

> ⚠ **片方だけでは駄目である。** 「空」だけを見ると「常に空」が通り、
> 「一致」だけを見ると「常に不透明と同じ」が通る。5c-1e と同じ型。

⚠ **不透明側が影響を受けないことも同時に要求する。**
可視の書き方を変えて不透明の quad 数が動いたら、
それは temporal のフィルタが不透明側に漏れている。

---

## 5. 5c-5b — <b>記録順を参照実装に合わせる</b>

### 5.1 なぜ変えるのか

`VkCullPass` は<b>不透明パスが書いた深度</b>に対して深度テストする
[`VkCullPass` javadoc: 「不透明パスの直後、テーブル生成の直前」]。
今の記録順は<b>描画が末尾</b>なので、そのままでは cull がテストする深度が無い。

参照実装 [`AbstractRenderPipeline.runPipeline`]:

```
renderOpaque (前フレームのテーブル)
  → innerPrimaryWork (HiZ + traversal)
  → buildDrawCalls (prep / cull / cmdgen / prefix / translucentGen)
  → renderTemporal (今フレームのテーブル)
  → renderTranslucent (今フレームのテーブル)
```

### 5.2 ⚠ 何が変わるかの列挙

**記録順の変更は「打ち消し合う変換」が入りやすい** [規約 3]。
変える前に、変わるものを全部書き出す。

| # | 変わるもの | 現状 | 5c-5b 後 | ⚠ 危険 |
|---|---|---|---|---|
| 1 | **不透明の描画位置** | `table` の後 | **フレーム先頭** | 描くテーブルは<b>どちらでも前フレームのもの</b>。`lastDrawnSections` の 1 フレーム遅れは<b>変わらない</b> |
| 2 | **深度クリアの担い手** | 不透明が `VkDepth.CLEAR` | 同じ (先頭なので依然クリア) | temporal / 半透明は<b>必ず load</b>。クリアすると不透明の絵が消える |
| 3 | **cull の深度の出どころ** | — | <b>今フレームの不透明</b> | 前フレームの深度を読むと 1 フレーム古い遮蔽になる。<b>絵は出るので気付けない</b> |
| 4 | **可視バッファの書き手** | ホスト (`markEverythingVisible`) | **cull の frag** | ホスト書き込みを<b>必ず外す</b>。残すと cull の結果を上書きして `wasVisibleLastFrame` が常に偽になり、<b>全部が temporal に回る</b> |
| 5 | **`frameId` の連続性** | 任意 | **1 ずつ増えること** | `previous==(frameId-1)` が前提。飛ぶと<b>全部が新規可視</b>になる |
| 6 | **`SPANS`** | `hiz traversal table draw resolve` | `opaque hiz traversal cull table temporal translucent resolve` | 5c-4c の数字と<b>直接は比べられない</b>。§5.3 の対応で読む |
| 7 | **HiZ の入力** | 前フレームの解決済み深度 | <b>変えない</b> | 不透明を先頭に動かしても HiZ が読むのは interop の深度で、別テクスチャである |
| 8 | **`table` の WAR バリア** | 「前フレームの描画 → テーブル生成」 | <b>同じ意味のまま</b> | 記録順では描画がテーブル生成の<b>前</b>に来るので、`beforeTableBuild` の前提が<b>むしろ素直になる</b> |

### 5.3 ⚠ 内訳を取り直す — <b>「カル未接続で上振れ」の但し書きを外す</b>

5c-4c の数字は<b>セクション単位の遮蔽が効いていない</b>状態のものである。
5c-5b でカルパスが繋がったら、**同じ 5 区間で取り直す**。

| 5c-4c の区間 | 5c-5b での対応 |
|---|---|
| `hiz` | `hiz` (同じ) |
| `traversal` | `traversal` (同じ) |
| `table` | `table` (同じ) |
| `draw` | **`opaque` + `temporal`** (⚠ 分かれる) |
| `resolve` | `resolve` (同じ) |
| — | **`cull`** (新設) |
| — | **`translucent`** (5c-5c で新設) |

> **この数字が Phase 7 の乗り換え後との比較基準になる。**
> 上振れした値を基準にすると、乗り換えの効果が測れない。

---

## 6. 5c-5c — 半透明。<b>測定であって回帰検査ではない</b>

半透明のバケット割り当ては `atomicAdd` に依存するので<b>非決定的</b>である
[確認済 — 提案書 §5.2 障害 B]。画素比較はしない。

### 6.1 記録すること

| 記録 | なぜ |
|---|---|
| カメラ固定で N フレーム描いたときの<b>揺れる画素数</b> (最小/中央/最大) | 揺れの大きさの実測値 |
| <b>バケット割り当ての差</b> (どのセクションがどのバケットに入ったか) | 揺れの<b>出どころ</b> |
| 揺れが<b>同一バケット内の順序だけ</b>か、<b>割り当て自体</b>が変わるか | ⚠ 下記 |

### 6.2 ⚠⚠ 割り当て自体が揺れたら、原因は `atomicAdd` ではない

**距離バケットは距離から決まる。カメラを固定すれば割り当ては決定的なはずである。**
それが揺れているなら、非決定性の原因が `atomicAdd` <b>以外</b>にもあることになる。

> `atomicAdd` が生むのは<b>同一バケット内の順序</b>だけである。
> 「非決定的だから仕方ない」で片付けると、別の原因を見逃す [規約 22]。

### 6.3 ⚠ 閾値を置いて自動判定にはしない

揺れ幅は<b>壊れても揺れ幅</b>なので、落ちる基準を決められない。
GPU の負荷やドライバの状態で偽陽性が出る。

**docs に「この構成の特性」として残す。** Phase 7 で比較できる形で。

---

## 7. 着手順

1. **5c-5a** — 深度クリアの制御 + temporal レンダラ + 可視の書き方をモードにする。両極の対照
2. **5c-5b** — 記録順を参照実装に合わせ、カルパスを繋ぐ。**5 区間の内訳を取り直す**
3. **5c-5c** — 半透明レンダラ + 揺れ幅の測定

---

## 8. 5c-5a 実装完了 — <b>temporal を重ねる</b>

### 8.1 何を変えたか

| 箇所 | 変更 |
|---|---|
| `VkTerrainRenderer.record` | **深度クリアを引数にした**。既存シグネチャは `VkDepth.CLEAR` を渡して委譲 |
| `VkHierarchicalScene` | `Visibility` 列挙 + `visibilityWord` + temporal レンダラ + `temporalTableTotals()` |
| `VkInteropProbe` | `-Pvoxy5c5=all\|none\|even\|odd\|cycle` と分割の恒等式の報告 |

⚠ **既定 (`all`) の挙動は 5c-4c と同じ**である。温度差なく戻せる。

### 8.2 ⚠ 「重ねる」を「置き換える」に変える壊し方

temporal は<b>色も深度もクリアしてはならない</b>。クリアすると不透明の絵が丸ごと消える。

> ⚠ **落ちない。** temporal は不透明の<b>部分集合</b>なので
> 「絵が薄くなった」ようにしか見えず、バリデーションも何も言わない。

**2 つ 1 組で塞いだ** [規約 11]:

| 検査 | 主張 |
|---|---|
| `temporalLayeredOnOpaqueLeavesTheOpaqueImageIntact` | 重ねても<b>差分ゼロ</b> (同じ深度の再描画は GEQUAL を通り、同じ色を書く) |
| **`layeringMustNotClearWhatTheOpaquePassDrew`** | ⚠ <b>対照</b>。クリアすると<b>実際に絵が変わる</b> |

片方だけでは駄目である — 上だけなら「temporal が何も描かない」も通り、
下だけなら「常にクリアしている」も通る。
さらに上の検査は temporal が<b>真部分集合かつ非空</b>であることを同時に要求する。

### 8.3 分割の恒等式は<b>数え方に依存しない</b>

`EVEN_NEW` と `ODD_NEW` は<b>互いに素で全体を覆う</b>ので:

```
temporal(EVEN) + temporal(ODD) == temporal(NONE_VISIBLE) == opaque
```

> ⚠ **面ごとの quad 数の式をこちらで書き直していない。**
> 書き直すと<b>予測と実装が同じ規約を共有する</b> [規約 4]。
> 主張しているのは<b>フィルタの分割性</b>だけである。

⚠ 空虚に満たす道は 3 つ塞いだ:

| 空虚に満たす方法 | 塞ぎ方 |
|---|---|
| drawn=0 で全部 0 | `opaque <= 0` なら<b>何も主張していない</b>と明示して打ち切る [規約 18] |
| 片側が空 (∅ + 全体 = 全体) | `EVEN` と `ODD` が<b>両方とも真部分集合</b>であることを別に言う |
| モード間で選択が動いた | `drawn` が 4 モードで同じでなければ<b>読まない</b> [規約 22] |

### 8.4 検査

**JUnit 254 PASS / 3 SKIP** (245 → 254、+9)、`interopCompositeCheck` **45 項目 PASS** (変化なし)。

| 新設 | 中身 |
|---|---|
| `VkVisibilityModeTest` (6) | 可視の語。装置不要。⚠ `CULL` は語を求めること自体が誤り |
| `VkTemporalTest` (+2) | 重ねる / クリアの対照 |

### 8.5 ⚠ MC で見る項目 (先に書き出す)

```bash
./gradlew runClient -Pvoxy5c4=on -Pvoxy5c5=cycle
```

| # | 見るもの | PASS の条件 |
|---|---|---|
| 1 | `[5c-5a] ALL_VISIBLE: temporalQuads=` | **0** |
| 2 | `[5c-5a] NONE_VISIBLE: temporalQuads=` | **opaqueQuads と一致** |
| 3 | `EVEN + ODD == NONE` | PASS |
| 4 | `EVEN and ODD are both strict subsets` | PASS |
| 5 | `opaque unchanged across modes` | PASS (⚠ 動いたらフィルタが不透明側に漏れている) |
| 6 | **画面** | 4 モードを回っても<b>絵が変わらない</b> |

⚠ **6 が要である。** temporal は不透明の部分集合を同じ深度に描き直すので、
<b>どのモードでも絵は同じでなければならない</b>。
モードで絵が変わったら、temporal がクリアしているか別の色を書いている。

⚠ **カメラを止めて読むこと。** 動いていると `drawn` が変わり、3〜5 は打ち切られる。

### 8.6 次 — 5c-5b

§5.2 の 8 項目を順に潰す。**4 番 (ホスト書き込みを外す) を忘れると
`previous==(frameId-1)` が常に偽になり、全セクションが temporal に回る。**
