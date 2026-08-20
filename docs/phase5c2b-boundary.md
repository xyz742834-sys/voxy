# Phase 5c-2b — モデルの置き場所を GL / Vulkan で分岐させる

**状態**: ✅ **完了。実機で全項目 PASS** (§10)。
**自動検査**: JUnit **194 PASS** (`VkModelUploadTargetTest` 3 件を新設)、
`interopCompositeCheck` **39 項目 PASS**。
**実機**: 実ブロックのテクスチャで描画 / MC の地形に隠される / 落ちない / ログ 4 行とも期待どおり。
**判断**: 案 A ({@code ModelStore} をバックエンド抽象にする)。

---

## 1. 「GL 版を無傷で残す」との関係

方針の意図は <b>`lod/gl46/` のシェーダと GL レンダラを参照仕様として保つ</b>ことだった —
バリアの意味論やバッファの読み取り範囲を判断する<b>唯一の正解</b>を残すため
(`MDIC:356` が「原作者も不明」から「系列上必要」に確定したのはそれがあったから)。

**ここでやったのは参照仕様の書き換えではなく<b>分岐点を足す</b>ことである。**
GL 実装はそのまま残っている。Phase 1 で `RenderPipelineFactory` に分岐を入れたときと同じ構造。

---

## 2. 境界をどこに置いたか — <b>CPU 側のベイク済みバッファ</b>

ベイクは<b>最後まで CPU で完結している</b>:

```
SoftwareModelTextureBakery → ColourDepthTextureData → ModelBakeResultUpload の MemoryBuffer
                                                       ↑ ここまで GPU に触れない
```

GPU に触れるのは<b>並べ終わったバッファを上げる 1 手だけ</b>なので、そこを
{@link ModelUploadTarget} にした:

```java
void uploadModel(int modelId, MemoryBuffer model);
void uploadBiomeColours(int index, MemoryBuffer colours);
void uploadModelTexture(int modelId, MemoryBuffer texture, int mipLevels);
```

| 実装 | 中身 |
|---|---|
| `ModelStore` (GL) | `UploadStream` + `glTextureSubImage2D`。<b>式は元のまま移しただけ</b> |
| `VkModelUploadTarget` | バッファは<b>直接書く</b> (host visible)。アトラスは staging に溜めて `recordUploads` で流す |

**この境界の利点は「両者が同じ入力を受け取る」ことである。**
`ModelFactory` (ベイク側) は<b>一切変えていない</b> — あちらは
`Minecraft.getInstance().level` に依存するので、触らないほうがよい。

---

## 3. ⚠ 「同じものを作る」を<b>比較ではなく構造で</b>保証した

アトラス内の配置は<b>3 者で一致していなければならない</b>:

1. GL のアップロード
2. Vulkan のアップロード
3. **シェーダの UV 計算** (`quads.frag` の `1.0/256.0`)

同じ式を 3 回書くと<b>1 つだけ直し忘れたときに絵が静かにずれる</b> (Stage 1 の型)。
そこで {@link ModelAtlasLayout} に<b>唯一の式</b>として集め、GL / Vulkan の両実装が
そこを引くようにした。**ずれようがない。**

> GL 実装はこの環境で走らせられない (DSA で JVM ごと落ちる) ので、
> <b>実行して比較することはできない</b>。だから「同じ式を使う」という
> <b>構造の保証</b>に置き換えた。

---

## 4. ⚠⚠ 検査が変異を素通しした — <b>規約 4 が自分の検査に出た</b> (失敗例 19)

`bakedTexturesLandOnTheTileTheShaderWillSample` を書いて、
配置の式を<b>タイルの刻み 256 → 16</b> に取り違える変異を入れたところ:

```
theLayoutAgreesWithTheBakerAndTheShader        FAILED   ← 捕まえた
bakedTexturesLandOnTheTileTheShaderWillSample  PASSED   ← ★素通り
```

**原因**: 書き込みも読み戻しも {@link ModelAtlasLayout} を使っていた。
式が間違っていても<b>両方が同じだけずれて一致する</b>。

**修正**: 読み戻しの位置を<b>literal で書いた</b>
(`(modelId & 0xFF) * MODEL_TEXTURE_SIZE * 3`)。
これで変異は<b>両方で落ちる</b>。

> **規約 4 は今回で 4 度目である** (11 例目 / 5c-1d の向き / 5c-1d の深度規約 / ここ)。
> <b>「予測と実装が同じ式を参照していないか」を、検査を書いた直後に必ず問う。</b>
> 共有ヘルパを作った直後は特に危ない — <b>共有した瞬間に打ち消しの経路ができる</b>。

---

## 5. 自動検査

| 検査 | 主張 | 依存 |
|---|---|---|
| `theLayoutAgreesWithTheBakerAndTheShader` | 式が<b>ベイカーの定数とシェーダの格子</b>に一致 | literal のみ |
| `bakedTexturesLandOnTheTileTheShaderWillSample` | 置いたものが<b>置くべきテクセル</b>から読める (全ミップ・複数モデル) | 読み戻しは literal |
| `modelIdsOutsideTheRangeAreRejected` | 範囲外の id を弾く。<b>範囲内は通す</b> (弾きすぎの対照) | — |

⚠ `ModelFactory.MODEL_TEXTURE_SIZE` は<b>コンパイル時定数</b>なので、
テストから参照しても `ModelFactory` は読み込まれない
(読み込むと `Minecraft.getInstance()` に触れて落ちる)。

---

## 6. 配線 — <b>MC 起動で確かめる段階</b>

| 追加したもの | 中身 |
|---|---|
| `VkRealModelBakery` | `Mapper` + `ModelFactory` を<b>直に</b>作る。{@code ModelBakerySubsystem} を通さない |
| `VkRealModelBakery.InMemoryMappingStorage` | `IMappingStorage` は 4 メソッドなので、<b>ワールドの永続化なしで `Mapper` が作れる</b> |
| `SyntheticTerrain.writeGeometry(buf, stateIdMap)` | 合成の stateId を<b>焼けたモデル id へ写す</b> |
| `VkTerrainResources.useExternalAtlasContent()` | 合成の中身で<b>焼いたタイルを上書きしない</b> |
| `-Pvoxy5c2=real` | 切り替え |

### 6.1 ⚠ `ModelStore` を作らせない経路

`ModelBakerySubsystem` は<b>コンストラクタで `ModelStore` を作る</b>。
`ModelStore` はアトラスを {@code GlTexture().store(...)} = <b>GL 4.5 DSA</b> で作るので、
通れば<b>JVM ごと abort</b> する (地雷 5 つ目)。

**したがって `ModelFactory` を直に作る。** 分岐点を入れたので
`ModelFactory` は置き場所を {@code ModelUploadTarget} として受け取り、GL の資源を要求しない。

### 6.2 ⚠⚠ 静的初期化で MC に触れて検査を落とした (失敗例 20)

焼くブロックの一覧を <b>{@code static final} フィールド</b>に書いたところ、
`VkInteropProbe` を<b>読み込むだけで</b> `net.minecraft.world.level.block.Blocks` の
初期化が走り、オフスクリーンの `interopCompositeCheck` が
`ExceptionInInitializerError` で落ちた。

> **5c-1a §7 の「GL 専用の静的初期化」と同じ型が、MC のクラスで出たものである。**
> 遅延評価 (メソッド) に直した。
>
> **【規約 14】ホスト (MC / GL) のクラスに<b>静的初期化子から触れない</b>。**
> そのクラスを<b>読み込むだけ</b>でホストが要るようになり、
> <b>ホストの外で動く検査が全部落ちる</b>。

### 6.3 ⚠ 規約 1 が実データでどうなるか

合成データは stateId から色を作っていたので「索引がずれたら絵に出る」が
<b>構造で保証されていた</b>。実データでは:

| 性質 | 実データでどうなるか |
|---|---|
| <b>位置</b>のずれが絵に出る | ✅ 保たれる。位置はワールド空間で分かれたまま |
| <b>モデル</b>のずれが絵に出る | ⚠ <b>弱まる</b>。写像が単射でないので、複数の quad が同じモデルを指す |
| 焼けたブロックどうしの区別 | `VkRealModelBakery.assertTilesAreDistinguishable` が突き合わせる |

**何が守られていないかを承知して使うこと。** 5c-3 で実ジオメトリを入れると
位置も実データになるので、そこで改めて問われる。

---

## 7. 残っていること — <b>MC 起動で確かめる</b>

抽象と Vulkan 実装は揃ったが、<b>実際のブロックテクスチャを流していない</b>。
それには `ModelFactory` が要り、`Minecraft.getInstance().level` に依存するので
<b>ワールドに入った状態でしか走らない</b>。5c-2b の残りは MC 起動が前提である。

| 残作業 |
|---|
| Vulkan 経路で `ModelFactory` を作り、`VkModelUploadTarget` へ流す配線 |
| 合成地形の stateId を<b>実際に焼けたモデル id</b> に差し替える |
| ⚠ `ModelStore` (GL) を<b>作らせない</b>こと — `GlTexture().store(...)` は DSA で JVM ごと落ちる |

> **⚠ `ModelStore` を GL 4.5 DSA の地雷一覧に加えること**
> [docs/phase5c1a-completion.md §7]。既知は
> `FullscreenBlit` / `UploadStream` / `DownloadStream` / `GlFramebuffer` で、
> <b>これが 5 つ目</b>である。

---

## 8. 配線を実機で回した結果 — <b>境界に穴が 2 つ、実バグが 1 つ</b>

`-Pvoxy5c2=real` で起動して<b>落ちた</b>。JVM の abort ではなく MC のクラッシュレポート
(`java.lang.NullPointerException` / `Render Frame`) だった。

### 8.1 ⚠⚠ 地雷 6 つ目 — <b>`SoftwareModelTextureBakery.setupTexture()`</b> (失敗例 21)

```
org.lwjgl.system.Checks.check(Checks.java:188)
org.lwjgl.opengl.GL45C.glGetTextureImage(GL45C.java:2435)
SoftwareModelTextureBakery.setupTexture(SoftwareModelTextureBakery.java:82)
ModelFactory.<init>(ModelFactory.java:146)
VkRealModelBakery.<init>(VkRealModelBakery.java:68)
```

`ModelStore` は<b>作らせずに済んだ</b> (§6.1 の対処は効いた) が、
`ModelFactory` のコンストラクタが作る<b>ベイカー自身</b>が DSA を使っていた。

| | 内容 |
|---|---|
| **なぜ一覧から漏れたか** | 5c-1a の洗い出しは<b>静的初期化子</b>を対象にしていた。これは<b>普通のメソッドの中の 1 行</b>で、その grep には出ない |
| **対処** | 束縛 + `glGetTexImage` (GL 1.0) に置換。<b>どちらの GL でも動く</b>ので GL 経路も壊れない |
| **一覧の直し方** | 「静的初期化子」ではなく<b>「DSA 関数名」で洗う</b>に変えた [5c-1a §7 に追記] |

> ⚠ **落ち方が違った。** これまでの 5 つは JVM ごと abort したが、これは<b>例外</b>だった。
> 機構は [未検証]。実務上は <b>「hs_err が無い」を「DSA を踏んでいない」の証拠にしてはならない</b>
> [規約 11]。

### 8.2 ⚠ 生の GL でホストの状態を奪ったまま返していた

`setupTexture()` は `glBindFramebuffer(GL_FRAMEBUFFER, 0)` と pack 指定を
<b>変えたまま戻していなかった</b>。上流は起動時に 1 度だけ呼ぶので問題にならなかったが、
Vulkan 経路では<b>フレームの途中</b> (`DefaultChunkRenderer.doRender` の中) で呼ぶ。

**MC の描画先を奪ったままにすると、そのフレームが壊れる** —
5c-1b の黒画面 (サンプラを戻さなかった) と<b>同じ型</b>である。
FBO (draw/read)・pack バッファ・pack 指定 5 つ・テクスチャ束縛を<b>全て保存して戻す</b>ようにした。

> **【規約 15】ホストのフレームの中で生の GL を呼ぶなら、変えた状態を<b>1 つ残らず戻す</b>。**
> 「戻す必要があるか」ではなく<b>「変えたか」</b>で判断する。
> 同じ関数でも、<b>呼ばれる時点が変われば影響範囲が変わる</b>。

### 8.3 ⚠ 境界に穴 — <b>共有経路に `UploadStream` が残っていた</b>

`ModelFactory.processUploads()` は<b>置き場所を呼び分けたあと</b>に
`UploadStream.INSTANCE.commit()` を直に呼んでいた (地雷 2 つ目)。
生の `glPixelStorei` も同様。**8.1 を直せば次はここで落ちていた。**

境界に 2 つ足した:

```java
void beginUploads();   // GL: アンパック指定   / Vulkan: 何もしない
void commitUploads();  // GL: UploadStream 転送 / Vulkan: 何もしない
```

<b>既定実装を置かなかった</b>のは意図的である。空の既定にすると
新しい置き場所が<b>黙って忘れる</b> — GL 側で忘れれば<b>何も GPU に届かない</b>。
併せて `ModelFactory` から <b>GL の import を全て外した</b>ので、
「ベイク側は GL に触れない」が<b>grep で確かめられる</b>形になった。

> **境界を切ったつもりでも、<b>切った側に 1 行残っていれば落ちる</b>。**
> 「インタフェース経由になったか」ではなく<b>「生の呼び出しが 0 件か」</b>で確かめること。

### 8.4 ⚠⚠ 実バグ — <b>ブロック状態 id をモデル id として使っていた</b> (失敗例 22)

`VkRealModelBakery.bake()` は `mapper.getIdForBlockState(...)` の戻り値を
そのまま「モデル id」として返していた。**これは別物である。**

| id | 採番 | 何の索引か |
|---|---|---|
| ブロック状態 id | `Mapper` | `ModelFactory.addEntry` の入力 |
| **モデル id** | `ModelFactory` | <b>`uploadModel` の索引</b>。シェーダが引くのはこちら |

`ModelFactory.getModelId(blockId)` を通すよう直した。

> **これは落ちない種類の間違いである。** 絵は出るが<b>中身が別のモデル</b>になる。
> バリデーションも捕まえないし、規約 1 (取り違えたら絵に出る) も<b>捕まえない</b> —
> 規約 1 が守るのは「索引がずれたら分かる」であって、
> <b>「最初から違う索引を渡した」ことは守らない</b>。
>
> ⚠ <b>「2 つの id 空間があり、どちらも int である」場所は、規約 1 の外である。</b>
> 5c-3 で実ジオメトリを入れると、この形 (セクション id / ノード id / モデル id) が増える。

### 8.5 規約 1 の実データ版を<b>実際に呼ぶようにした</b>

`assertTilesAreDistinguishable` は<b>定義されていたが呼ばれていなかった</b>。
`VkModelUploadTarget.stagedTileAddress` を足して、
<b>アトラスへ入る直前の中身</b>を突き合わせるようにした (転送前でないと読めない)。

**成り立っているときも言わせる** — 「警告が出なかった」ではなく
`規約 1 holds for the real data: all N baked tiles are pairwise distinguishable` を出す [規約 11]。

### 8.6 アトラスの読み戻しも<b>確かめる</b>

`glGetTexImage` が黙って失敗すると配列は 0 のままで、
<b>焼けたモデルが全て透明の同じ絵になる</b> — 規約 1 が静かに壊れる。
GL エラーの確認に加え、<b>不透明なテクセルが 1 つでもあるか</b>を見て、無ければ例外にした。

### 8.7 検査

| | |
|---|---|
| JUnit | **197 PASS** |
| `interopCompositeCheck` | **39 項目 PASS** |
| `./gradlew build` | SUCCESSFUL |

**実機での確認はこれから** (§7 の 3 点)。

---

## 9. 実機 1 回目 — <b>草と葉だけが真っ黒</b> (失敗例 23)

| 見る項目 | 結果 |
|---|---|
| 1. 実際のブロックのテクスチャで描かれる | ⚠ **6 種は正しい。草と葉だけ真っ黒** |
| 2. MC の地形に隠される | ✅ |
| 3. 落ちない | ✅ |
| 4. ログ | ✅ `規約 1 holds for the real data: all 8 baked tiles are pairwise distinguishable` |

### 9.1 黒くなった 2 つは<b>バイオーム着色される 2 つ</b>だった

焼いた 8 種のうち、バイオームで色が変わるのは<b>草ブロックと樫の葉だけ</b>である。
石・原木・砂・レンガ・金・ネザーラックは着色されない。**症状と原因の対応が 1 対 1。**

```glsl
// quad_util.glsl
uint tintColour = model.colourTint;
if (modelHasBiomeLUT(model)) {
    tintColour = colourData[tintColour + extractBiomeId(quad)];   // ← ここが 0
}
// quads.frag
if (doTint) { colour *= uint2vec4RGBA(interData.z).yzwx; }        // ← 0 を掛けて黒
```

### 9.2 ⚠⚠ 原因 — <b>直に作ると、本番が外側でやっている配線が付いてこない</b>

```java
// ModelBakerySubsystem — 本番はこれを持っている
public void addBiome(Mapper.BiomeEntry biomeEntry) { this.factory.addBiome(biomeEntry); ... }
```

`ModelFactory` の `biomes` は<b>誰かが `addBiome` を呼んで初めて埋まる</b>。
`ModelBakerySubsystem` を通さない選択 (§6.1 — DSA を避けるため) の<b>副作用</b>で、
この配線が落ちていた。`biomes` が空なので:

```java
if (!this.biomes.isEmpty()) {   // ← ここを通らない
    uploadResult.biomeUploadIndex = biomeIndex;   // 色が 1 つも上がらない
}
```

> **【規約 16】本番の経路を迂回したら、<b>迂回した側が何をしていたか</b>を数え上げる。**
> 「作らない」ことの影響は<b>作らなかったオブジェクトの中</b>だけではない。
> `ModelBakerySubsystem` を通さない判断は正しかったが、
> あれは `ModelStore` を持っていただけでなく<b>コールバックの配線</b>もしていた。
> ⚠ <b>迂回したクラスの public メソッドを 1 つずつ見る</b>のが確実である
> (`requestBlockBake` / **`addBiome`** / `tick` / `shutdown`)。

### 9.3 ⚠ バイオームは<b>1 つでは足りない</b> — 合成データ側の事情

合成地形は<b>バイオーム id にセクション番号を書いている</b>
[`SyntheticTerrain.writeRun`: `sectionIdx & 0x1FF`]。これは規約 1 のためである
(索引がずれたら色が変わる)。

したがって `colourData[base + sectionIdx]` を引くので、
<b>セクション数だけバイオームを登録しなければ色表の外を読む</b> (PAIR で 18)。
`bake(states, biomeCount)` に変え、`terrain.sectionCount()` を渡すようにした。

> **合成データが規約 1 のために入れた性質が、実データでは<b>要求</b>に変わった。**
> 「索引がずれたら絵に出る」ための仕掛けは、<b>その索引が実在することを前提にする</b>。

### 9.4 ⚠ 登録の順序 — <b>バイオームはモデルより後</b>

`ModelFactory.addBiome0` は<b>色が要るモデルが 1 つも無いと何も作らずに返す</b>
[確認済 — `modelsRequiringBiomeColours.isEmpty()` で早期 return]。
先にバイオームを登録すると<b>色表が空のまま残る</b>。
ベイクを回しきってから登録し、もう一度回すようにした。

### 9.5 【規約 11】黒は<b>落ちない</b>ので、見に行かせる

`reportBiomeTints` を足した。焼けたモデルの `flagsA` を読んでバイオーム LUT を使うものを選び、
<b>その基点の色表エントリが空でないか</b>を見る。

- 空 → `⚠ model N uses the biome table at index B but that entry is empty — it would render black`
- 着色されるモデルが 0 個 → `⚠ the biome path is NOT being exercised`
  (今回の**逆側の見落とし**を防ぐ — 「警告が出ない」が「経路を通った」を意味しないため)
- 正常 → `biome tinting is usable for all N tinted models (e.g. 0x...)`

### 9.6 ⚠ ④ (id 空間の取り違え) は<b>今回の絵を変えていない</b>

ログは `[1,2,3,4,5,6,7,8] -> model ids [1,2,3,4,5,6,7,8]` だった。
新しい `Mapper` と新しい `ModelFactory` が<b>どちらも 1 から順に採番した</b>ので、
2 つの id 空間が<b>たまたま一致している</b>。

> **修正は正しいが、この構成では<b>効果を観測できない</b>。**
> 逆に言えば、<b>この構成でのテストは id の取り違えを検出できない</b> —
> 5c-3 で実ジオメトリを入れると一致しなくなるので、そこで初めて効く。
> <b>「直したから直った」と「今は同じだから見えない」を混同しないこと</b> [規約 11]。

---

## 10. 実機 2 回目 — <b>全項目 PASS</b>

| 見る項目 | 結果 |
|---|---|
| 1. 実際のブロックのテクスチャで描かれる (草・葉を含む) | ✅ |
| 2. MC の地形に隠される (5c-1e の性質が保たれている) | ✅ |
| 3. 落ちない | ✅ |
| 4. ログ | ✅ 4 行とも期待どおり |

```
[5c-2b] registered 18 biomes (ids 0..17)
[5c-2b] baked 8 block states [1..8] -> model ids [1..8]; 9 textures queued for transfer
[5c-2b] 規約 1 holds for the real data: all 8 baked tiles are pairwise distinguishable
[5c-2b] biome tinting is usable for all 2 tinted models (e.g. 0xff90814d)
```

> **Minecraft の本物のブロックテクスチャが、Vulkan のアトラスを経由して画面に出た。**
> ベイクは CPU で完結しているので、<b>GL 実装と Vulkan 実装は同じ入力を受け取っている</b> —
> 「同じものを作っているか」を比較ではなく<b>構造で</b>保証した形が、実データで通ったことになる。

⚠ **`0xff90814d`** は ARGB (144,129,77) — 乾いた系のバイオームの草色である。
合成地形は<b>バイオーム id にセクション番号を書く</b>ので、
草の色味がセクションごとに変わって見えるのは<b>正常</b>である (規約 1 の仕掛け)。

### 10.1 ⚠ この段で<b>検証できていないこと</b>

| 主張 | 状態 |
|---|---|
| 実ブロックのテクスチャがアトラスに入り、シェーダが引ける | ✅ 確認済 |
| 焼けたタイルが互いに区別できる (規約 1) | ✅ 確認済 |
| バイオーム着色が空でない値を引く | ✅ 確認済 |
| **色が GL 版と同じか** | ❌ <b>検証できない</b> — GL バックエンドがこの Mac で動かない |
| **モデル id の取り違えが起きていないか** | ⚠ <b>この構成では検出できない</b> (§9.6 — 2 つの id 空間がたまたま一致している) |
| フレーム時間 | ❌ 未測定。合成地形なので測っても意味が薄い |
