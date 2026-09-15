# Phase 3 完了記録 — Vulkan 基盤と compute パスの土台

**ブランチ**: `vulkan-macos`
**方針**: レンダラ本体の移植 (Phase 4) に必要な土台をすべて揃える。

---

## 1. 完了した項目

### 1.1 フレーム同期 — in-flight = 1

`VkFrameTracker` (254行)。コマンドバッファ 1 本、fence 1 本。

**なぜ in-flight = 1 か** [実測済 — Phase 0]:
Voxy は自前でプレゼンテーションしない。合成は GL 側が行うため
**Vulkan の完了を待たないと GL が正しい内容を読めない** — 待つことが構造的に必要で、
多重化しても待ち自体は消えない。同期コストは 0.315ms (予算 16.6ms の 1.9%)。

これにより以下が単純化された:
- **リソースの遅延解放が世代管理不要**に。`freeAtFrameEnd(Runnable)` の単純な FIFO で足りる
- descriptor set の多重化が不要

in-flight 検出機構は**将来多重化したときの防波堤として残してある**。

### 1.2 descriptor 戦略 — 4 カテゴリすべて実装

全 compute パスを調査し (`phase3-descriptor-survey.md`)、4 つに分類して個別に対処した。

| カテゴリ | 内容 | 実装 |
|---|---|---|
| A (7 シェーダ) | 変化しない | `VkAutoBindingShader` の永続 1 set |
| B (traversal) | 有限 3 構成を巡回 | `VkDescriptorSetGroup` で事前生成 |
| C (3 シェーダ) | 真に動的 (リングバッファ) | dynamic offset |
| D (4 箇所) | 明示 range | `ssbo(index, buf, offset, range)` |

**カテゴリ C は当初の想定に無かった。** `UploadStream` のリングバッファを
呼び出しごとに異なるオフセットで bind しており、組み合わせが有限にならない。
`minStorageBufferOffsetAlignment = 16` に対し `UploadStream` の粒度も 16 で、
**アロケータの調整は不要**だった [実測済]。

### 1.3 UploadStream / DownloadStream — GL 版の構造を捨てた

GL 版の複雑さはすべて「CPU が書いた内容が GPU に見えるタイミングの制御」のためだった。
`HOST_COHERENT` + ユニファイドメモリ + in-flight = 1 でその制御が不要になる。

**発見**: GL 版の `upload()` は**ステージングバッファ**で、
`glCopyNamedBufferSubData` で対象へ転送していた。使われ方は 2 つに割れる [確認済]:

| モード | 箇所 | Vulkan 版 |
|---|---:|---|
| 1. ステージング → 対象へコピー | 19 | **丸ごと不要**。対象の `addr() + offset` を返すだけ |
| 2. アップロードバッファ自体を SSBO | 3 | リングバッファが必要 (dynamic offset) |

**「記録窓 assert」を入れた**: 直接書き込みが安全なのは
`beginFrame()` 〜 `endFrame()` の間だけ。多重化した瞬間に静かに壊れる類なので、
今のうちから実行時に落とす。

### 1.4 DownloadStream の意味論 — 退避が必要だった

**全 download 箇所を調べた結果、直接読みが安全なのは 1 箇所だけだった** [確認済]。

決定的だったのが `HierarchicalOcclusionTraverser:352`:

```java
DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
nglClearNamedBufferSubData(this.requestBuffer.id, ...);  // ← 次の行で count を 0 クリア
```

フレーム最終状態を直接読むと**必ず count = 0** になり、リクエストキューが機能しなくなる。
条件付きではなく毎フレーム発生する。

→ `download()` = **退避あり (既定・安全側)**、`downloadDirect()` = opt-in、という形にした。

### 1.5 画像リソース抽象

`VkTexture` (248行) / `VkSampler` (88行)。

**レイアウトを mip レベルごとに追跡する。** HiZ の mip チェーン生成が
**同一画像の level i-1 を読みながら level i に書く**ため [確認済 — `HiZBuffer.buildMipChain`]。
GL は `GL_TEXTURE_BASE_LEVEL`/`MAX_LEVEL` で実現しているが Vulkan に同等機構は無く、
**レベルごとの `VkImageView`** も必須。

遷移は明示呼び出し (`barrier(cmd, level, ...)`)。
「いつ」は呼び出し側、「何から」はオブジェクトが知る、という分担。

**レイアウト据え置きでもアクセスマスクがあればバリアを発行する。**
`GENERAL` のまま write → read のように、レイアウトは同じでも同期だけ必要なケースが実在するため。
省略するのは完全な no-op のときだけ。

### 1.6 バリア翻訳の規則と対応表

**適用はしていない (§2.1)。規則と表だけ確定した。**

- 件数を訂正: **42 ではなく 33** (import 行を数えていた)
- パターンは 10 種類。**P1 + P2 で 22 箇所 (67%)**
- 翻訳規則を仕様と照合して確定 (`phase3-barrier-survey.md` §10)
- **全 34 行の対応表**を作成 (§11)。区分は GL のビットから機械的に決まる

**実装時に踏むはずだった罠を先に潰した**: `SHADER_STORAGE_READ` は
`DRAW_INDIRECT` ステージでサポートされないため、`dstStage` には
`DRAW_INDIRECT` とシェーダステージの**両方**が要る。
ヘルパはこれを構造的に保証している。

### 1.7 バリデーション環境

`brew install vulkan-loader vulkan-validationlayers` + ローダー経由。
セットアップ手順と踏んだ問題は `vulkan-validation-setup.md`。

**導入直後に既存のスペック違反を 1 件検出した**:
`VK_KHR_portability_subset` の有効化漏れ (VUID-VkDeviceCreateInfo-pProperties-04451)。

### 1.8 Vulkan 1.4 への引き上げ

`dynamic_rendering` / `synchronization2` / `timeline_semaphore` をコア機能として使うため。

**「1.2.334 / 1.2.357」は要求バージョンによる頭打ちで、ドライバの上限ではなかった** [確認済]。
両経路とも 1.4 に対応しており、懸念していた同梱経路の破綻は起きなかった。
`vkEnumerateInstanceVersion()` によるフォールバックも入れてある。

### 1.9 GPU 探索ループの上限

`sort_visibility.comp` の `while (true)` × 2 (CAS リトライ) に
`MAX_CAS_RETRIES = 1024` を導入。超過時は要素をソートから落とす
(ノードクリーナなので次回リトライされる。描画の正しさには影響しない)。

**`traversal_dev.comp` は安全だった** — 階層トラバーサルは GPU 側のループではなく
**CPU 駆動の 5 回ディスパッチ**として実装されている [確認済]。
指示の前提と実装が食い違っていた箇所。

### 1.10 デッドコード削除 (341 行)

`RawDownloadStream` (97) / `BufferArena` (78) / `BasicSectionGeometryManager` (166)。
いずれも外部参照 0 件を確認して削除 [確認済]。

---

## 2. 意図的に保留した項目

### 2.1 バリア翻訳の適用

**適用先が存在しないため。** 全 33 箇所は GL レンダラのクラス内にあり、
`MDICSectionRenderer` / `NodeCleaner` / `HierarchicalOcclusionTraverser` /
`AsyncNodeManager` のいずれにも Vulkan 版が無い [確認済]。

**バリア翻訳は独立作業ではなくレンダラ移植 (Phase 4) の一部**である。
対応表があるので、移植時には引くだけで済む。

### 2.2 `GPUTiming`

`glFinish` + `glMemoryBarrier(-1)` の箇所。
`beginFrame()` の fence 待ちが `glFinish` 相当を既に行うため、そのまま写す意味がない。
**`VkQueryPool` への置換と一体で Phase 6 で設計する。**

### 2.3 `BoundRenderer` (`rendering/bounding/`)

Phase 2 で「Phase 4/5 で再検討」としていたが、
**in-flight = 1 の確定により問題が解けた** — `freeAtFrameEnd` で足りる。
ただし移植自体は補助機能なので優先度低のまま。

### 2.4 IOSurface インポート

`VkTexture.wrapExternal` で受け口だけ用意。
`VkImportMetalIOSurfaceInfoEXT` 自体は Phase 5 (interop)。
参照実装は `~/dev/mdi-bench` の `Interop.java`。

### 2.5 同期バリデーションが動く環境の調査

調査コストが読めないため実施しない (§3.1)。

---

## 3. 未解決の問題

### 3.1 🔴 同期バリデーションがこの環境で機能しない

**`SYNC-HAZARD-*` が一切報告されない** [確認済 — ネガティブ対照で確認]。

バリア無しで同じ SSBO を read-modify-write する compute ディスパッチを
2 つ並べても、何も報告されない。

切り分け済み:

| 確認 | 結果 |
|---|---|
| メッセンジャは動くか | **動く** (同じ経路で descriptor / portability の指摘が出る) |
| レイヤは読み込まれているか | **読み込まれている** |
| `VK_EXT_validation_features` は有効か | **有効** |
| 環境変数経由では | **同じく報告なし** |

→ MoltenVK / portability 環境の制約と推測 [未検証]。

> **⚠⚠ 訂正 (Phase 6):** この推測は誤りだった。Lavapipe (別 ICD) に替えても
> 同一の症状が再現し、原因は ICD 側ではなくこちらのメッセージ分類コードにあった
> (指摘の識別子は自由文ではなく構造化フィールドにしか乗っていなかった)。
> 直した結果、descriptor を介さないハザードは実際に検出できる。
> descriptor 経由の SSBO はレイヤ側の既知の制約として今も検出できない。
> 詳細: [`phase6-sync-validation.md`](phase6-sync-validation.md)。

**途中で自分側のバグも発見した**: `VK_EXT_validation_features` は
**レイヤが提供する**拡張であり `vkEnumerateInstanceExtensionProperties(null, ...)` に現れない。
`null` 列挙で検査していたため常に false となり、
**拡張が有効化されないまま `syncValidationEnabled = true` を報告していた**。
ネガティブ対照が無ければ気づけなかった。修正済み。

#### これによる方針変更

| 当初 | 変更後 |
|---|---|
| バリア漏れは同期バリデーションが検出する | **検出されない** |
| D 区分は保守的に張ってから削る実験 | **保守的なまま残す** (削っても壊れたことを検出できない) |
| 「指摘ゼロ」を安全の証拠にする | **証拠にしない**。翻訳規則の正しさで担保する |

### 3.2 GL 版との出力比較ができない

最終防衛線として GL 版との実出力比較を予定しているが、
**GL 版はこの Mac で動かない** (`phase2-binding-audit.md` §8.4)。
GL 4.6 環境が要る [未検証 — 実施方法未定]。

---

## 4. 成果

| 指標 | 値 |
|---|---|
| `client/core/vk/` | 15 クラス / 約 2,900 行 |
| テスト | **63** (ローダー + バリデーション両経路で PASS) |
| 削除 | 341 行 (デッドコード) |
| ドキュメント | 7 本 |

### 4.1 検証済みの実機事実

| 項目 | 値 |
|---|---|
| Vulkan API | 1.4 (両経路) |
| `minStorageBufferOffsetAlignment` | 16 |
| `maxPushConstantsSize` | 4096 |
| `subgroupSize` | 32 |
| メモリ | `DEVICE_LOCAL｜HOST_VISIBLE｜HOST_COHERENT｜HOST_CACHED`、ヒープ 1 |
| `VK_KHR_synchronization2` | ネイティブ対応 |

---

## 5. Phase 4 への引き継ぎ

### 5.1 使える土台

- `VkFrameTracker.beginFrame()` / `endFrame()` / `freeAtFrameEnd()`
- `VkShader` / `VkAutoBindingShader` / `VkDescriptorSetGroup`
- `VkBarriers.computeToCompute` / `computeToIndirect` / `conservative`
- `VkTexture.barrier` (レベル別レイアウト追跡)
- `VkUploadStream` / `VkDownloadStream`
- バリア対応表 34 行 (`phase3-barrier-survey.md` §11)

### 5.2 Phase 4 で決めること

- draw 統合の実装単位と順序
- `cmdgen.comp` / `quads3.vert` の GL 両対応をどうするか
- 検証方法 (同期バリデーションが使えない前提で)

### 5.3 残っているギャップ

| 項目 | 影響 |
|---|---|
| 画像へのアップロード (`vkCmdCopyBufferToImage`) | ブロックアトラス転送で必要 |
| レンダーパス / アタッチメント | Phase 4 の描画で必要 (`dynamic_rendering` 想定) |
| `VkTexture` のデバッグ命名 | `VK_EXT_debug_utils` 未配線 |
| グラフィクスパイプライン生成 | 未実装 (compute のみ確認済み) |
