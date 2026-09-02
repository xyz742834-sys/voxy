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

---

## 9. ⚠⚠ 5c-5a の実機で見つかった — <b>トラバーサルの入口が繋がっていなかった</b>

### 9.1 観測

```
[5c-5a] ALL_VISIBLE:  drawn=0 opaqueQuads=0 temporalQuads=0
[5c-5a] NONE_VISIBLE: drawn=0 opaqueQuads=0 temporalQuads=0
[5c-5a] EVEN_NEW:     drawn=0 opaqueQuads=0 temporalQuads=0
[5c-5a] ODD_NEW:      drawn=0 opaqueQuads=0 temporalQuads=0
[5c-5a] ⚠ the opaque table is empty, so every claim below holds vacuously
[5c-4c] drawn=0 of 287 meshed (top-level nodes 27)  requests=0  dropped: pushes=0 reads=0
```

> **空虚成立のガードが偽 PASS を止めた。** 恒等式は 0+0=0 で成立するので、
> §8.3 の 3 つ目の穴を塞いでいなければ<b>「4 項目 PASS」と読めていた</b>。

### 9.2 ⚠ 原因 — <b>`topNodeIds` を書くコードが存在しない</b>

トラバーサルの 0 回目は {@code topNodeIds} を<b>ソースキュー</b>として読む
[確認済 — `VkDescriptorSetGroup` の variant 0]。
GL 版は `NodeManager` のコールバックでこれを維持している
[確認済 — `HierarchicalOcclusionTraverser:96` の `addTLN` / `remTLN`]。

**`VkHierarchicalScene` はこれを繋いでいなかった。**
`VkBuffer(long)` はゼロ初期化するので、バッファは<b>ゼロのまま</b>である。

```
reset(27) → 0 回目に 27 スレッド → topNodeIds[0..26] は全部 0
          → ノード id 0 を 27 回訪問する
```

### 9.3 ⚠⚠ なぜ 5c-4c で気付かなかったのか

**id 0 はたまたま実在の最上位ノードである**
[確認済 — `NodeStore.allocate` → `allocateNext()` は空の集合で 0 を返し、
`populate` は最上位の要求を先に解決するので<b>最初の割り当ては最上位ノード</b>になる]。
したがって<b>そこから降りた分だけ絵が出る</b>。

> **27 個の根のうち 1 個で動いていたのに、動いているように見えていた。**
> `drawn=513` は<b>1 個の根から降りた結果</b>だった。

今回 `drawn=0` になったのは、この世界では id 0 の根が視錐台の外だったためである
(`requests=0` `dropped=0` と揃うのは<b>根が全部弾かれた枝しかない</b>)。

### 9.4 ⚠ 5c-4c の数字は取り直しになる

| 記録 | 状態 |
|---|---|
| `drawn=513 sections` | ⚠ <b>1 個の根から降りた数</b>。木全体ではない |
| `hiz / traversal / table / draw / resolve` の内訳 | ⚠ <b>その状態で測った値</b> |

**5c-5b で内訳を取り直すときに、この修正の後の値を基準にする。**

### 9.5 直した内容

| | |
|---|---|
| `TopLevelNodeQueue` | GL 版 `addTLN` / `remTLN` と同じ<b>末尾入れ替え</b>方式。書き出し先を切って装置なしで検査できるようにした |
| `setTLNCallbacks` | `VkHierarchicalScene` の構築時に繋ぐ |
| `topLevelCount` の分離 | <b>要求した位置の数</b>と<b>実際に入口になった数</b>を別々に持つ [規約 18] |

⚠ **2 つの数を 1 つにしていたのが遠因である。**
`insertTopLevelNode` が作るのは<b>要求</b>で、ノード id が生まれるのは
ジオメトリが届いたときである [確認済 — `NodeManager.finishRequest`]。
`this.topLevelCount++` は<b>要求の数</b>を数えていたのに、
それを<b>入口の数</b>としてトラバーサルに渡していた。

> **【規約 24】数えているものと使っているものが同じか確かめる。**
> 「27」は正しい数だった — <b>27 個の何の数なのか</b>が違った。

### 9.6 検査

`VkTopLevelQueueTest` (7 件、装置不要)。主張は<b>「[0, count) が生きている集合と
ちょうど一致し、重複が無い」</b>である。

> ⚠ 「何か書かれた」では駄目である。<b>ゼロのまま</b>も<b>同じ id が並ぶ</b>も
> 数だけなら合ってしまう。

加えて実行時のガード: 要求が 0 でないのに入口が 0 なら<b>警告して名指しする</b>。
これは今回の壊れ方 (コールバックを繋ぎ忘れる) を<b>そのまま検出する</b>。

**JUnit 261 PASS / 3 SKIP** (254 → 261)。

---

## 10. 修正後の実機 — <b>入口が繋がり、次の上限に届いた</b>

### 10.1 修正が効いたことの数字

```
[5c-4c] populated 16 top-level nodes of 27 requested   ← 1 個 → 16 個
[5c-4c] drawn=7 → 10 → 151   opaqueQuads=174186
[5c-5a] ALL_VISIBLE: drawn=151 opaqueQuads=174186 temporalQuads=0
```

**§8.5 の項目 1 が空虚でなく PASS した** — 不透明が 174186 quad ある状態で
temporal が 0 である。ガードが「何も主張していない」と言わなくなった。

> ⚠ 16/27 なのは<b>正常</b>である。この世界は LoD 4 が 24/125 しか揃っていない
> [確認済 — `meshed 24 sections ... 101 not in the world yet`]。
> <b>要求した数と入口の数を分けたので、これが読める</b>。

### 10.2 ⚠ そして落ちた — <b>ジオメトリ領域の枯渇</b>

```
IllegalStateException: Geometry OOM. requested allocation size: 1115,
  Heap size at top remaining: 256, used elements: 1999744
  at BasicAsyncGeometryManager.createMeta
  at VkHierarchicalScene.serviceRequests
```

**木全体を辿るようになって初めて届いた上限である。** 1 個の根で動いていた間は届かなかった。

⚠ この段は <b>{@code NodeCleaner} を繋いでいない</b>ので、領域は<b>増える一方</b>である。
上限に届くのは<b>時間の問題</b>であって異常ではない。

### 10.3 なぜ「例外を捕まえる」で済ませないのか

`createMeta` が投げる時点で <b>{@code allocationSet.allocateNext()} は成功済み</b>である
[確認済 — `uploadReplaceSection` の順序]。
捕まえても<b>セクション id が漏れた状態</b>が残る。

> **受け取る前に決めるしかない。** 切り上げ (127 要素単位) を見た上で
> 入るかを判定し、入らなければ<b>こちらで解放して止める</b>。

⚠ **止めたことは必ず言う。** トラバーサルは要求を出し続けるので、
<b>答えていないことが見えなければ「描かれない」と「選ばれなかった」が区別できない</b> [規約 18]。

### 10.4 直した内容

| | |
|---|---|
| `acceptGeometry` | 入るなら渡す、入らないなら<b>解放して止める</b>。`populate` と `serviceRequests` の両方を通す |
| `geometryExhausted()` | 止まったことを公開。毎フレームのログに出す |
| `-Pvoxy5c4Quads` | 階層経路のジオメトリ容量。⚠ <b>5c-3b の既定値は動かさない</b> (あちらの測定条件) |

⚠ 既定を 16M quad に上げたのは<b>階層経路だけ</b>である。
5c-3b はカメラ周りを一度メッシュ化するだけだが、階層経路は<b>要求に答え続ける</b>ので
必要量が桁で違う。<b>同じ定数を共有していたのが誤りだった。</b>

### 10.5 ⚠ 容量ガードの初版が<b>空のセクションで落ちた</b> (自分で踏んだ)

```
NullPointerException: Cannot read field "size" because "built.geometryBuffer" is null
```

`BuiltSection` は <b>{@code geometryBuffer == null} を「空」の表現に使っている</b>
[確認済 — {@code BuiltSection.isEmpty}]。見積もりを 1 行で書いて踏んだ。

⚠ **空でも木には渡さなければならない。** {@code childExistence} を運んでいるので、
捨てると要求が満たされず<b>トラバーサルが降りられなくなる</b> —
落ちない代わりに<b>遠景が粗いまま止まる</b>。

**計算を {@code geometryBytesNeeded} に切り出して装置なしの検査を付けた** (`VkGeometryBudgetTest`)。
切り上げを見ないと足りない見積もりになることも<b>対照で示した</b>。

> ⚠ **ガードを足すときに新しい落ち方を作った。**
> 「上限に当たっても落ちないようにする」変更が、<b>上限と無関係な入力で落ちた</b>。
> 例外処理の追加は<b>正常系を通る回数のほうが多い</b>ので、
> **異常系だけを見て書くと正常系を壊す。**

### 10.6 ⚠ 直したときに二重解放を作りかけた

`populate` で断られた分を解放する処理を足したが、
<b>{@code acceptGeometry} が失敗時に既に解放していた</b>。
所有権の移動先を<b>1 箇所に決める</b> — 断られた分は {@code acceptGeometry} が持つ。

**JUnit 264 PASS / 3 SKIP** (261 → 264)。

---

## 11. 5c-5a 完了 — <b>temporal が実データで両極とも成立した</b>

### 11.1 結果 (MC 実機、カメラ静止)

```
[5c-5a] ---- temporal on real data ----
  opaque unchanged across modes:        PASS  (1527918 / 1527918 / 1527918 / 1527918)
  ALL_VISIBLE  -> temporal empty:       PASS  (0)
  NONE_VISIBLE -> temporal == opaque:   PASS  (1527918 of 1527918)
  EVEN + ODD   == NONE:                 PASS  (802636 + 725282 = 1527918)
  EVEN and ODD are both strict subsets: PASS
```

**§8.5 の項目 1〜5 が全て、空虚でなく成立した。**

> 「選択が動いている間は読まない」ガードが繰り返し働き、
> `drawn` が 1305 で落ち着いてから初めて恒等式を出した [規約 22 / 23]。
> <b>ガードが無ければ、動いている最中の食い違いを「FAIL」と読んでいた。</b>

### 11.2 規模と時間 (⚠ <b>暫定</b>)

| | |
|---|---|
| 入口 | 16 / 27 (この世界は LoD 4 が 24/125 しか無い) |
| メッシュ化 | 6943 セクション |
| 選択 | **1305 セクション / 1527918 quad** |
| ジオメトリ領域 | 16M quad で<b>足りた</b> (`MESHING STOPPED` は 0 回) |

```
hiz=0.95〜2.71ms  traversal=0.060〜0.066ms  table=0.12〜0.21ms
draw=1.92〜4.21ms  resolve=0.026〜0.033ms   total=3.10〜7.21ms
```

⚠ **この数字は基準にしない。** カルパス未接続で、記録順も参照実装と違う。
<b>5c-5b で取り直したものが Phase 7 との比較基準になる</b>。

> ただし<b>比の傾向は 5c-4c と変わっていない</b> —
> `traversal + table` は合計の 5% 未満のままである。
> 木が 1 個の根から 16 個になり、選択が 151 → 1305 セクションに増えても変わらなかった。

### 11.3 ⚠ 残る 2 症状は<b>この段の既知の簡略化</b>である

実機で「ちらつく」「視点を動かすと残る」が観測された。

| 症状 | 出どころ | 直る段 |
|---|---|---|
| 視点を動かすと遅れる / 残る | <b>描画は前フレームのテーブル</b> (設計どおり) | 5c-5b (記録順) |
| 回転でちらつく | <b>HiZ の元が前フレームの解決済み深度</b> — 前の向きの深度で遮蔽を判定する | 5c-5b (不透明を先頭へ) |
| 遠景が多すぎる | <b>カルパス未接続</b> | 5c-5b |

**カメラを止めると `drawn` は 1305 で安定する** [確認済 — 1042→2083→1389→1305→1305→1305]。
<b>静止時に振動していない</b>ので、HiZ の帰還が発振しているのではなく、
<b>動いている間だけ 1 フレーム古い情報で判定している</b>という読みと合う。

⚠ **temporal がこれらの原因ではない。** temporal は不透明と<b>同じエントリ配列の部分集合</b>を
<b>同じ深度に</b>描くので、絵を変えられない。
確かめ方: `-Pvoxy5c5=all` は temporal を<b>1 quad も描かせない</b> —
それでも同じ症状が出るなら temporal は無関係である。

---

## 12. 黒いちらつきの切り分け — <b>Voxy 側は数字で除外された</b>

### 12.1 連続フレームの観測 (`-Pvoxy5c5=all -Pvoxy5c4Interval=1`、カメラ静止)

```
drawn=838095 of 1639680  bbox=[0,0 .. 1707,575]  nearestDepth=8.6094794E-4
colour=60c4cb8bf8062379  black=0        ← 同じ秒内の 14 サンプルすべて同一
```

| 分かったこと | 根拠 |
|---|---|
| **temporal は無関係** | `-Pvoxy5c5=all` で temporal は<b>1 quad も描いていない</b> |
| **Voxy の色は決定的** | 連続フレームで<b>総和がビット同一</b>。120 フレーム間隔ではない |
| **Voxy は黒を出していない** | 描画画素 838095 のうち<b>黒は 0</b> |
| **同期の破れでもない** | `TERRAIN_CLEAR` は<b>橙 (0.9, 0.45, 0.05)</b> である。GL が描き終わる前の画像を読んだなら<b>橙が見える</b>はずで、黒にはならない |

> **3 つの候補が数字で消えた。** 残るのは<b>合成でどちらが勝つか</b>だけである。

### 12.2 ⚠ 静止していれば合成も決定的なはずである

Voxy の出力が不変で、MC の絵も静止していれば、<b>画素ごとの勝者は毎フレーム同じ</b>になる。
したがって<b>ちらつきには動きが要る</b>。

**動いている間に古いのは 2 つある**:

| 古いもの | 何が起きるか | 直る段 |
|---|---|---|
| <b>描画は前フレームのテーブル</b> | 選ばれたセクションが 1 フレーム前のもの | 5c-5b |
| <b>HiZ の元が前フレームの解決済み深度</b> | <b>前の向きの深度</b>で遮蔽を判定する | 5c-5b |

⚠ **参照実装が HiZ とトラバーサルを不透明描画の後に置いているのはこのためである。**
記録順を合わせると、どちらも<b>今フレームのもの</b>になる。

### 12.3 ⚠ 深度境界がこの段では効いていない [未検証の影響]

`quads.frag:164` は<b>深度境界より手前の画素を discard する</b>:

```glsl
if (DEPTH_SCALAR_COMPARE(gl_FragCoord.z, texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r)) discard;
```

Vulkan 経路の `depthBound` は <b>{@code BOUND_NEUTRAL}</b> (誰も落とさない) のままで、
GL 版の `BoundRenderer` に相当するものを繋いでいない。
`-Pvoxy5c4Distance` も既定 -1 (無制限) である。

その結果 Voxy は <b>MC が描いているのと同じ近景にも描いている</b> —
`bbox=[0,0 .. 1707,575]`、画面の 51%。

> ⚠ 5c-4c の頃は `drawn=16213` (1%) だったので<b>見えていなかっただけ</b>の可能性がある。
> 入口が 16 根になって初めて表に出た、という筋である。**[推測 — 未検証]**

---

## 13. 5c-5b1 完了 — <b>記録順を参照実装に揃え、HiZ の元を直した</b>

### 13.1 ⚠ HiZ が別の空間の深度を読んでいた (本番コードの実バグ)

上流を読んで確定した [確認済 — 実物]:

```java
// NormalRenderPipeline.setup
return this.fb.getDepthTex().id;          // Voxy 自前 FB の深度

// AbstractRenderPipeline.runPipeline
int depthTexture = this.setup(...);
rs.renderOpaque(viewport);                 // その深度に書く
this.innerPrimaryWork(viewport, depthTexture);   // hiZBuffer.buildMipChain(depthTexture)
```

**HiZ は Voxy 自身の深度アタッチメントを、不透明パスが書いた後に読む。**

5c-4c までの Vulkan 経路は <b>interop の解決済み深度</b>を読んでいた。これは

| | |
|---|---|
| <b>再投影済み</b> | MC の投影空間。トラバーサルの MVP と<b>別の空間</b>である |
| <b>1 フレーム古い</b> | 解決はフレームの最後に走る |

⚠ **絵は出るので気付けない型**である。遮蔽判定が別空間の深度と比較していた。

### 13.2 記録順

```
不透明 (前フレームのテーブル)  →  HiZ  →  traversal  →  table  →  temporal (今フレーム)
```

⚠ **不透明が先頭にあることが HiZ の前提である。** 入れ替えると HiZ が 1 フレーム
古い深度を見て、回転で遮蔽判定がずれる。

`SPANS` を `{opaque, hiz, traversal, table, temporal, resolve}` に再定義した。
⚠ 旧 `draw` は `opaque` と `temporal` に分かれたので、<b>5c-4c の内訳とは直接比べられない</b>。

### 13.3 ⚠ 装置ありで先に潰したこと

**{@code VkHiZ} が深度フォーマットを読むのはこれが初めて**だった。
読めなければ (アスペクトの取り違え等) <b>0 かゴミが返るだけで絵は出る</b>。

`VkHiZDepthSourceTest` (2 件、装置あり・MC 不要):

| 検査 | 主張 |
|---|---|
| `theHiZCanReadADepthAttachment` | クリアした深度が HiZ に届く |
| **`adifferentDepthGivesADifferentHiZ`** | ⚠ <b>対照</b>。別の深度なら別の値。定数を返す実装を落とす |

> 「0 でない」では足りない [規約 11]。**入力に追随すること**を要求する。

**JUnit 266 PASS / 3 SKIP** (264 → 266)。

### 13.4 進め方の訂正

ちらつき / 焼き付きの追跡は <b>{@code VkInteropProbe} (5c の足場) の欠陥</b>で、
本番経路の欠陥ではなかった。**上流の構造を先に読めば 1 回で済んだ**。

以後:
1. 装置なし / 装置ありで確かめられることは<b>こちらで確かめる</b>
2. 実験の前に<b>上流 Voxy と MC のコードを読む</b>
3. <b>Vulkan 移植に要るかを都度判断する</b>

---

## 14. 5c-5b2 完了 — <b>カルパスを繋いだ</b>

### 14.1 ⚠ ホストがセクション数を渡してはならなかった

参照実装は <b>prep → cull のラスタ → cmdgen</b> の順で、cull は
<b>prep が書いた間接描画コマンド</b>を使う
[確認済 — {@code MDICSectionRenderer.buildDrawCalls} と {@code gl46/prep.comp} の
{@code cullDrawIndirectCommand}]。

階層トラバーサルでは<b>セクション数を GPU が決める</b>ので、ホストは
<b>前フレームの数しか知らない</b>。それを渡すと:

| ずれ方 | 何が起きるか |
|---|---|
| 今フレームのほうが<b>多い</b> | 末尾のセクションが<b>可視の印を貰えない</b> → cmdgen が 0 quad 扱い → <b>点滅する</b> |
| 今フレームのほうが<b>少ない</b> | 範囲外の古い id に印を書く。cmdgen は先頭 {@code sectionCount} 件しか見ないので<b>害は無い</b> |

**片方が「消える」側なので、正確な数が要る。** ⚠ 当初「1 フレーム遅れは保守的だから
許容できる」と判断しかけたが、<b>片側が消える方向だと気付いて撤回した</b>。

### 14.2 直した内容

| | |
|---|---|
| `lod/vk/prep.comp` | cull の間接描画コマンドを書く。⚠ {@code firstIndex} は GL 版と違い <b>0</b> — Vulkan は cull 専用の立方体インデックスバッファを持つ |
| `VkTerrainResources.cullDraw` | 5 uint の間接コマンド |
| `VkMergedTableBuilder` | {@code record} を {@code recordPrep} / {@code recordAfterPrep} に割った。cull はその間に入る |
| `VkCullPass.recordIndirect` | ホストの数ではなく間接コマンドで描く |
| `VkHierarchicalScene` | {@code Visibility.CULL} のときだけ cull を記録する |

⚠ **ホスト書き込みと cull を同時に走らせてはならない。** ホストの書き込みは記録の
<b>前</b>、cull は記録の<b>中</b>なので、両方走らせると<b>常に cull が勝ち</b>、
ホストのモードが黙って no-op になる。

### 14.3 記録順 (最終形)

```
不透明 → HiZ → traversal → prep → cull → cmdgen/prefix/temporal/translucent → temporal描画 → resolve
```

`SPANS` = `{opaque, hiz, traversal, cull, table, temporal, resolve}`。
⚠ 区間 `cull` は prep を含む (prep は 1 ディスパッチなので無視できる)。

### 14.4 検査 — <b>変異で噛むことを確かめた</b>

`VkCullIndirectTest` (3 件、装置あり・MC 不要):

| 検査 | 主張 |
|---|---|
| `prepWritesTheCullDrawCommand` | 5 要素の中身。⚠ {@code baseInstance} は 0 でなければならない |
| **`theInstanceCountFollowsTheSectionCount`** | ⚠ <b>対照</b>。定数を書く実装を落とす |
| `theIndirectCullMatchesTheDirectCullWhenTheCountIsExact` | 正確な数を渡した直接版と<b>可視集合が一致</b>。⚠ <b>対照</b>: 実際に何かが落ちていること |

**変異 ({@code cullInstanceCount = 7u} 固定) で 3 件とも落ちた。**
シェーダ → バッファ → cull ラスタ → 可視の読み戻しまで、鎖全体が覆われている。

**JUnit 269 PASS / 3 SKIP** (266 → 269)。

---

## 15. 5c-5c 実装完了 — <b>半透明を繋ぎ、揺れ幅の測定を用意した</b>

### 15.1 繋いだもの

```
不透明 → HiZ → traversal → prep → cull → table → temporal → 半透明 → resolve
```

⚠ 半透明の<b>描画数はバケット数</b> ({@code TRANSLUCENT_BUCKETS} = 1024) であって
エントリ数ではない [確認済 — {@code VkTranslucentTest} と GL 版 {@code renderTranslucent}]。

⚠ temporal と同じく<b>色も深度もクリアしない</b>。

### 15.2 揺れ幅の測定 — <b>測定であって検査ではない</b>

閾値を置いた自動判定は<b>しない</b>。揺れ幅は壊れても揺れ幅で、落ちる基準を決められない。

⚠ **カメラが静止しているサンプルだけを集める。** 判定は目視ではなく
<b>MVP が前サンプルと同一であること</b>で行う。動いている分を混ぜると
<b>何も主張しない数字</b>になる。

出すもの:

| | |
|---|---|
| 揺れた画素数 | 最小 / 中央 / 最大 (60 サンプル) |
| <b>バケット割り当て</b>が変わった回数 | ⚠ 距離から決まるので<b>静止なら 0 のはず</b> |
| <b>順序だけ</b>が変わった回数 | {@code atomicAdd} 由来。想定内 |

> ⚠⚠ **割り当てが揺れていたら原因は {@code atomicAdd} ではない。**
> 「非決定的だから仕方ない」で片付けると<b>別の原因を見逃す</b> [規約 22]。

**JUnit 269 PASS / 3 SKIP** (変化なし — 半透明の描画そのものは Phase 4 の
{@code VkTranslucentTest} が既に覆っている)。

---

## 16. 5c-5c 完了 — <b>汚染を除いた測定</b>

### 16.1 揺れ幅 (MC 実機、カメラ静止、1920x1200、60 サンプル x 3 窓)

```
flickering pixels: min=0  median=16232 / 16889  max=180379 / 234328   of 2304000
```

| | |
|---|---|
| 中央値 | 約 **0.7%** |
| 最大 | 約 **8〜10%** |
| **最小 = 0** | ⚠ <b>これが要である</b> |

> **{@code min=0} は「連続する 2 フレームがビット一致した」ことを意味する。**
> 入力が変わらなければ経路は<b>決定的である</b>。
> 「全部ノイズ」という読みをここで落とせる。

### 16.2 ⚠ 揺れの主因は半透明ではない — <b>選択が動いている</b>

```
the SELECTED SET changed: 42 / 60, 49 / 60
```

**カメラ静止でも、選ばれるセクションの集合が 7〜8 割のフレームで変わる。**
半透明のバケットはそれに追随しているだけである。

⚠ 心当たりは <b>HiZ の帰還</b>である: 選択 → 不透明の深度 → HiZ → 次の選択。
参照実装も同じ構造なので<b>ある程度は本来の挙動</b>だが、7〜8 割は多い。
**[未検証]** — Phase 6 で扱う。

### 16.3 バケット割り当て — <b>1 件では原因を名指しできない</b>

```
with the SAME set: assignment changed 1, order-only changed 41 / 17 / 10
```

汚染を除く前は 44/60 だったが、集合の変化で切り分けたら<b>各窓 1 件</b>になった。

> ⚠ **1 件で原因を名指ししない** [規約 23]。
> しかも<b>メッシュ化が走り続けている</b> (meshed 3447 → 4417) ので、
> 同じ集合でも {@code sectionMetadata} が更新されてバケットが動く。
> <b>穏当な説明が既にある</b>。

**順序だけの変化が大半** ({@code atomicAdd} 由来) で、これは想定どおりである。

### 16.4 ⚠ 5c-5c 完了時の内訳 (Phase 7 との比較基準)

**1920x1200、drawn=1090 セクション、カルパス接続済み、記録順は参照実装。**

| 区間 | 時間 |
|---|---|
| `opaque` | **2.31 〜 3.49 ms** |
| `hiz` | 0.07 〜 1.11 ms |
| `traversal` | 0.061 〜 0.067 ms |
| `cull` | 0.085 〜 0.101 ms |
| `table` | 0.121 〜 0.137 ms |
| `temporal` | 0.006 〜 0.007 ms |
| `translucent` | 0.052 〜 0.058 ms |
| `resolve` | 0.031 〜 0.041 ms |
| **合計** | **3.25 〜 4.14 ms** |

**描画以外の全段を足しても合計の 8% 未満。** Phase 4 の設計が、
木が 16 根になり選択が 1090 セクションになっても効いている。

⚠ 5c-4c の数字とは<b>直接比べられない</b> (旧 `draw` が `opaque` と `temporal` に
分かれ、カルが繋がり、解像度と選択数が違う)。**基準はこちらである。**
