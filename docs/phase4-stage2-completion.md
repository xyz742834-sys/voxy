# Phase 4 Stage 2 完了記録 — draw 統合 (13 draws → 7 draws)

**状態**: Stage 2a / 2b 完了。テスト 115 件 PASS / 1 SKIP、バリデーション指摘ゼロ。

**結果**: 統合前 (セクション×面ごとに 1 draw) と統合後 (面方向別 7 draws) が
**すべての視点・すべての境界条件でピクセル単位で一致**した。

---

## 1. 何を作ったか

| 追加/変更 | 内容 |
|---|---|
| `lod/vk/quad_index.glsl` (新) | 統合テーブルの宣言と二分探索。**Vulkan 専用** |
| `lod/vk/quads3.vert` (新) | 統合版の地形頂点シェーダ。**Vulkan 専用** |
| `lod/vk/index_probe.comp` (新) | 索引解決の全数検査用 (テスト専用) |
| `SyntheticTerrain` (変更) | `mergedTable` / `footprints` / バッファ書き出し |
| `VkTerrainResources` (変更) | `mergedEntry` / `mergedPrefix` / `mergedDraw` |
| `VkTerrainRenderer` (変更) | `Mode.PER_SECTION` / `Mode.MERGED` |
| `MergedTableTest` (新) | Stage 2a の CPU 全数検査 10 件 |
| `VkMergedIndexTest` (新) | GPU 二分探索の全数検査 3 件 |
| `VkTerrainRenderTest` (変更) | Stage 2b の差分ゼロ比較 5 件 |

### 1.1 GL 版は一切触っていない (案 B)

`lod/gl46/` 以下は無変更。Vulkan 専用のものは **`lod/vk/` に置く**
(提案時の `*.vk.vert` 命名ではなくディレクトリで分けた。
「GL 版は触らない」が一目で分かるほうがよいと判断した)。

---

## 2. テーブルの設計

### 2.1 単一のグローバル prefix sum + `baseVertex` で 7 draws を撃ち分ける

```
エントリ i  = (セクション, 面) のラン 1 本   uvec2 { quadStart, drawId }
prefix[i]   = エントリ i の手前までの累計 quad 数 (狭義単調増加)
prefix[n]   = 総 quad 数 (番兵)
```

エントリは**面優先**に並べる。面 f のエントリは連続区間を占めるので、
面ごとの draw は `baseVertex = 面の先頭 quad 通し番号 * 4` を渡すだけでよい。
すると頂点シェーダは `gl_VertexIndex >> 2` で
**7 draws をまたいだグローバルな quad 通し番号**を直接得られる。

```glsl
uint quadOrdinal = uint(gl_VertexIndex) >> 2;
MergedQuadRef ref = resolveQuad(quadOrdinal);     // 二分探索
uvec2 pos = positionBuffer[ref.drawId];
setupQuad(quad, quadData[ref.quadIndex], pos, cornerId == 1u);
```

**面ごとに別テーブルを持って `gl_DrawID` で切り替える案は採らなかった。**
組み込み変数を 1 つ増やすことになるうえ、`baseVertex` は Stage 1 で既に
使っている仕組みなので流用が効く。

### 2.2 空ランはテーブルに載せない

quad 数 0 のランを載せると prefix に平坦部ができ、
**どのエントリにも属さない通し番号**が生まれて解が一意でなくなる。
載せないので prefix は**狭義**単調増加になる (`prefixIsStrictlyIncreasingAndEndsAtTheTotal`)。

### 2.3 探索ループには上限を切ってある

```glsl
#define QUAD_INDEX_MAX_STEPS 32
for (int step = 0; step < QUAD_INDEX_MAX_STEPS && (lo + 1u) < hi; step++) { ... }
```

テーブルが壊れていても**ハングしない**(間違った答えを返すだけ)。
Phase 0 で OS レベルの GPU リセットを起こしているので、
GPU 上の探索ループには例外なく上限を付ける。
32 回で 2^32 エントリまで収束する [確認済 — `thirtyTwoStepsIsEnoughForAnyTableWeCanBuild`]。

### 2.4 `gl_BaseInstance` が不要になった

統合版はセクションをテーブルの `drawId` から得るので、
`positionBuffer[gl_BaseInstance]` を使わない。
GLSL 互換の要注意箇所 (`phase2-glsl-compat.md` §3.2) が 1 つ消えた。

---

## 3. 検証 — 3 層で押さえた

### 3.1 層 1: テーブルの不変条件 (CPU、全数) — `MergedTableTest`

| 検査 | 内容 |
|---|---|
| prefix が 0 始まり・**狭義**単調増加・末尾が総 quad 数 | 解の一意性 |
| 面ごとの区間が連続・重ならない・順に並ぶ | 7 draws が全 quad をちょうど覆う |
| 二分探索 == 線形探索 (全通し番号) | アルゴリズムの一致 |
| **統合テーブルと Stage 1 のコマンド列が同じ集合を描く** | §3.4 |
| 境界条件がテーブルの形に届いている | 空セクション/64/7 面区分/100/負座標 |
| 全部空でも壊れない / 半透明の読み飛ばしが一致 | 端 |

### 3.2 層 2: GPU の索引解決 (全数) — `VkMergedIndexTest`

`index_probe.comp` が **頂点シェーダとまったく同じ `resolveQuad`** を呼び、
0..188 のすべての通し番号を解決して書き出す。CPU の**線形**探索と全件突き合わせ。

> 「テスト用に書いた別実装が合っていた」ではなく
> **本番のコードが合っている**ことの検査になっている点が要。

**なぜ絵の比較だけでは足りないか**: 隠れた quad の解決が間違っていても絵には出ない。
索引を単独で、しかも全通り見る必要がある。

### 3.3 層 3: 絵の差分ゼロ — `VkTerrainRenderTest`

| 検査 | 結果 |
|---|---|
| 至近視点で Stage 1 (13 draws) vs Stage 2b (7 draws) | **差分 0 px** |
| 俯瞰視点でも同じ | **差分 0 px** |
| 境界条件 6 種を**単独で**描いて比較 | 全て差分 0 px |
| Stage 2a: テーブルを作っても Stage 1 の絵は変わらない | 差分 0 px |

境界条件を単独で描くのは、**まとめて描くとあるセクションの誤りが
別のセクションに隠される**ため。

```
[vk] ① 最小 (quad 1 枚): 6008 px, 1 -> 7 draws
[vk] ② 1 方向だけ:       7239 px, 1 -> 7 draws
[vk] ③ 空セクションを挟む: 844 px, 2 -> 7 draws
[vk] ⑥ quad 数ちょうど 64: 2272 px, 1 -> 7 draws
[vk] ⑦ 7 面区分すべて:    2440 px, 7 -> 7 draws
[vk] ⑧ 負座標:           2050 px, 1 -> 7 draws
```

### 3.4 いちばん強いのは §3.1 の「同じ集合を描く」

Stage 1 のコマンド列を quad 単位に展開した集合と、
統合テーブルを 0..total-1 まで解決した集合が、
**(quad 番号, セクション) の組として完全一致**することを検査している。
さらに双方に重複が無いこと (同じ quad を 2 回描いていない) も見ている。

これが通れば「同じ quad を同じセクション位置で描く」ことが**絵を出す前に確定する**。
絵の比較はその上での確認になる。

### 3.5 対照実験 — すべての検証に付けてある

| 対照 | 結果 |
|---|---|
| prefix を 1 つ壊す → GPU の解決が変わるか | 2 件が不一致 ✅ |
| 統合テーブルの全エントリを 1 ずらす → 絵が変わるか | 735 px 差分 ✅ |
| フットプリント重複検査が実際に重複を捕まえるか | ✅ |

> ⚠ **対照が 1 度失敗している。** 当初「エントリ 0 だけを壊す」対照にしていたが、
> 壊した先 (セクション 3 の DOWN 面) がその視点では画面外で、**差分 0 になった**。
> 全エントリを壊す形に変えた。
> **対照実験それ自体が無力化されうる**という例がまた 1 つ増えた
> (`phase4-stage1-completion.md` §5.2 の表に追記済み)。

---

## 4. バリア — **該当なし**

Stage 2 で新しく増えたハザードは**無い**。

| 増えたアクセス | 系列 | 扱い |
|---|---|---|
| `mergedEntry` / `mergedPrefix` | ホスト書き込み → 頂点シェーダの SSBO 読み | 既存の B1 (`hostWriteBarrier`) がそのまま覆う |
| `mergedDraw` | ホスト書き込み → 間接コマンド読み | 同上 (`INDIRECT_COMMAND_READ` を含む) |

テーブルは CPU で作っているため、**compute → vertex の RAW はまだ存在しない**。
これが出てくるのは Stage 3 で cmdgen を GPU に戻したときで、
そこが `phase4-buffer-hazards.md` の系列表の本番になる。

無理に記録を作らず「該当なし」とする。

---

## 5. 判明した制約と、Stage 3 への申し送り

### 5.1 🔴 16bit 共有インデックスバッファが 1 draw = 16,380 quad で頭打ち

統合すると **1 つの draw に 1 つの面の全 quad が入る**。
共有インデックスバッファは GL 版と同じ 16bit で、
インデックス値が `4*quadCount-1 < 65536` に収まる必要がある
→ **1 draw あたり 16,384 quad が上限**。

合成データ (最大 103 quad/面) では問題にならないので、
Stage 2 では**上限を超えたら例外にする**形で明示的に止めてある
(`writeFaceDraws` / `oversizedFaceDrawIsRejected`)。

**実データでは確実に超える。** Phase 0 の実測では 88,000 draws が
セクション単位で出ており、面あたりの quad 数はこれをはるかに上回る。

考えられる対処 (いずれも**未検討**、Stage 3 以降で判断が要る):

| 案 | 備考 |
|---|---|
| 32bit インデックスバッファ | 単純だが `4 * 総quad数` 要素ぶんのメモリが要る |
| インデックスバッファを使わない非インデックス描画 | `gl_VertexIndex % 6` から corner を引く。provoking vertex と `(gl_VertexID&3)==1` の意味論が変わる |
| 面をさらに分割して 1 draw を 16,380 quad 以下に保つ | draw 数が増えるが統合の利点は大半残る |

**これは設計判断なので、Stage 3 に入る前に指示を仰ぎたい。**

### 5.2 描画順が変わっている (今回は無害)

Stage 1 はセクション順、Stage 2b は面順に発行する。
**共平面の quad があると後勝ちの結果が変わる**ため、
`everyQuadOccupiesADistinctVisiblePosition` の規約
(`phase4-stage1-completion.md` §5.1) がこの比較の前提になっている。

実データでは共平面が起こりうる (隣接セクションの境界面など) [推測]。
そのとき「GL 版と同じ絵か」を問うなら、描画順の差が効いてくる可能性がある [未検証]。

### 5.3 まだやっていないこと

| 項目 | Stage |
|---|---|
| 旧経路 (`writeCmd` / per-section コマンド) の削除 | Stage 3 |
| GPU 側でのテーブル生成 (cmdgen 改造 + prefix sum) | Stage 3 |
| temporal / 半透明パス | Stage 4 |
| 実データ | Phase 5 |

`Mode.PER_SECTION` は**参照実装として残す**。
Stage 3 で GPU 生成に切り替えたとき、
「CPU 生成のテーブル vs GPU 生成のテーブル」の突き合わせに使える。

---

## 6. 実行方法

```bash
./gradlew test -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true
```

PNG は `build/vk-test-output/` に出る。
`terrain-closeup.png` (Stage 1) と `terrain-merged.png` (Stage 2b) は同一になる。
