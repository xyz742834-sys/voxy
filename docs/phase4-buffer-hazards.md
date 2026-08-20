# バッファ別ハザード洗い出し (Stage 1)

> **⚠ 本ドキュメントが主、`phase3-barrier-survey.md` の対応表は従。**
>
> 当初は「GL のバリアビットから stage/access が機械的に決まる」方針だったが、
> **少なくとも 4 箇所で成り立たなかった** (§10.2)。
> バリアを書く際は<b>本ドキュメントのアクセス系列表を根拠とし</b>、
> 対応表は出発点・照合用として使うこと。

**なぜ必要か**: `glMemoryBarrier` の 33 箇所を翻訳するだけでは足りない。
**GL は WAR の順序を実装の順序保証に委ねるため、ソース上にバリアが現れない**
(`phase3-barrier-survey.md` §15)。しかも同期バリデーションがこの環境で
機能しないため実行時に検出できない。バッファごとにアクセス系列を追うのが唯一の手段。

**凡例**: `IR` = 間接コマンド読み / `SR` `SW` = SSBO 読み/書き /
`UR` = UBO 読み / `HW` = ホスト書き込み / `TR` = 転送読み

---

## 0. フレーム内の実行順 [確認済 — `AbstractRenderPipeline.runPipeline`]

```
① renderOpaque       bindRenderingBuffers → MDI 描画
② innerPrimaryWork   HiZ → [download / nodeManager / nodeCleaner / traversal]
③ buildDrawCalls     prep → cull → cmdgen → prefixsum → translucentGen
④ renderTemporal     MDI 描画
⑤ renderTranslucent  MDI 描画 (ブレンド)
```

---

## 1. `drawCallBuffer` — 🔴 **WAR が 2 つ。GL に対応バリア無し**

| 順 | 段階 | アクセス | 箇所 |
|---|---|---|---|
| ① | renderOpaque | **IR** (`GL_DRAW_INDIRECT_BUFFER`) | `MDIC:192` |
| ③ | cmdgen | **SW** | `MDIC:320` |
| ④ | renderTemporal | **IR** | `MDIC:192` |
| ③ | translucentGen | **SW** | `MDIC:362` |
| ⑤ | renderTranslucent | **IR** | `MDIC:192` |

| ハザード | GL のバリア | 必要な Vulkan バリア |
|---|---|---|
| ①IR → ③cmdgen SW = **WAR** | **無し** (GL は実装順序に依存) | `src: DRAW_INDIRECT / INDIRECT_COMMAND_READ`<br>`dst: COMPUTE_SHADER / SHADER_STORAGE_WRITE` |
| ③cmdgen SW → ④IR = RAW | `MDIC:336` (P2) | `computeToIndirect(INDIRECT_DRAW_CONSUMERS)` |
| ④IR → ③translucentGen SW = **WAR** | **無し** | 上と同じ WAR パターン |
| ③translucentGen SW → ⑤IR = RAW | `MDIC:371` (P2) | `computeToIndirect(INDIRECT_DRAW_CONSUMERS)` |

> ⚠ **順序に注意**: ③ の translucentGen は ④ renderTemporal より
> ソース上は前だが、`buildDrawCalls` 内で完結するため
> 実行順は ③ → ④ → ⑤ である。したがって
> 「④IR → translucentGen SW」の WAR は**成立しない**。
> ③ 内で cmdgen SW → translucentGen SW の **WAW** になる [確認済 — 実行順の再確認]。

**訂正後の一覧**:

| ハザード | GL | Vulkan |
|---|---|---|
| ①IR → ③cmdgen SW = **WAR** | **無し** 🔴 | WAR バリアが要る |
| ③cmdgen SW → ③translucentGen SW = WAW | `MDIC:358` (P1) | `computeToCompute` |
| ③ SW → ④⑤ IR = RAW | `MDIC:336`/`371` (P2) | `computeToIndirect` |

---

## 2. `drawCountCallBuffer` — 🔴 **最も複雑。WAR あり**

count / dispatch サイズ / 間接コマンドを兼ねており、5 段すべてが触る。

| 順 | 段階 | アクセス | 箇所 |
|---|---|---|---|
| ① | renderOpaque | **IR** (`GL_PARAMETER_BUFFER_ARB`) | `MDIC:193` |
| ③ | prep | **SW** | `MDIC:279` |
| ③ | cull | **IR** (`GL_DRAW_INDIRECT_BUFFER`) | `MDIC:298` |
| ③ | cmdgen | **SR+SW** / **IR** (`DISPATCH_INDIRECT`) | `MDIC:321`, `333` |
| ③ | translucentGen | **SR+SW** / **IR** | `MDIC:363`, `368` |
| ④⑤ | renderTemporal/Translucent | **IR** | `MDIC:193` |

| ハザード | GL | Vulkan |
|---|---|---|
| ①IR → ③prep SW = **WAR** | **無し** 🔴 | WAR バリア |
| ③prep SW → ③cull IR = RAW | `MDIC:283` (P1) | ⚠ P1 では**不足**。dst に `INDIRECT_COMMAND_READ` が要る |
| ③cull IR → ③cmdgen SW = **WAR** | **無し** 🔴 | WAR バリア |
| ③cmdgen SW → dispatchIndirect IR = RAW | `MDIC:334` (P1) | ⚠ 同上。P2 相当が要る |
| ③ SW → ④⑤ IR = RAW | `MDIC:336`/`371` | `computeToIndirect` |

**`MDIC:283` と `MDIC:334` は対応表の分類 (A/P1) では不足する** —
直後が間接読みなので `INDIRECT_COMMAND_READ` を含む P2 が正しい。
**対応表 §11 の #4 / #6 を訂正する必要がある。**

---

## 3. `positionScratchBuffer` — 🔴 WAR

| 順 | 段階 | アクセス |
|---|---|---|
| ① | renderOpaque (VS) | **SR** (`quads3.vert` の `positionBuffer[gl_BaseInstance]`) |
| ③ | cmdgen | **SW** (`MDIC:325`) |
| ④⑤ | renderTemporal/Translucent (VS) | **SR** |

| ハザード | GL | Vulkan |
|---|---|---|
| ①SR (VS) → ③SW = **WAR** | **無し** 🔴 | `src: VERTEX_SHADER / SHADER_STORAGE_READ`<br>`dst: COMPUTE_SHADER / SHADER_STORAGE_WRITE` |
| ③SW → ④SR = RAW | `MDIC:336` (P2 に含まれる) | `computeToIndirect(INDIRECT_DRAW_CONSUMERS)` が VS 読みも覆う |

---

## 4. `visibilityBuffer`

| 順 | 段階 | アクセス |
|---|---|---|
| ③ | cull (FS) | **SW** (`raster.frag` が可視フラグを書く) |
| ③ | cmdgen | **SR** (`MDIC:323`) |

| ハザード | GL | Vulkan |
|---|---|---|
| cull FS SW → cmdgen SR = RAW | `MDIC:334` | `src: FRAGMENT_SHADER / SHADER_STORAGE_WRITE`<br>`dst: COMPUTE_SHADER / SHADER_STORAGE_READ` |

**P1 (`computeToCompute`) では src が誤り** — 書き手は compute ではなく
**フラグメントシェーダ**。専用に書く必要がある。

---

## 5. `indirectLookupBuffer` (= `viewport.getRenderList()`)

| 順 | 段階 | アクセス |
|---|---|---|
| ② | traversal | **SW** (可視セクション ID を書き出す) |
| ③ | prep | **SR** (`MDIC:280`) |
| ③ | cull (VS) | **SR** (`MDIC:297`) |
| ③ | cmdgen | **SR** (`MDIC:324`) |
| ③ | translucentGen | **SR** (`MDIC:365`) |

| ハザード | GL | Vulkan |
|---|---|---|
| ②traversal SW → ③ 各段 SR = RAW | `Traverser:346` (D 区分) | 読み手が複数ステージ (compute + VS)。`dst` に両方が要る |
| 次フレーム ②SW との **WAR** | **無し** 🔴 | フレーム跨ぎ。in-flight=1 の fence が覆う [推測] |

`Traverser:346` が D 区分 (読み手が関数外で特定できない) だったのは、
**読み手が別クラスの複数段にまたがる**ためだと判明した。

---

## 6. `distanceCountBuffer` — ホスト書き込みを含む

| 順 | 段階 | アクセス |
|---|---|---|
| ③ | (CPU) | **HW** `zeroRange(0, 1024*4)` (`MDIC:317`) |
| ③ | cmdgen | **SW** (`atomicAdd`) (`MDIC:326`) |
| ③ | prefixsum | **SR+SW** (`MDIC:355`) |
| ③ | translucentGen | **SR** (`MDIC:366`) |

| ハザード | GL | Vulkan |
|---|---|---|
| HW → cmdgen SW | 暗黙 | **in-flight=1 では記録中に GPU がアイドル**なので安全 [推測]。多重化時は `HOST_WRITE → SHADER_WRITE` が要る |
| cmdgen SW → prefixsum SR = RAW | `MDIC:356` (**D 区分**) | `computeToCompute` |
| prefixsum SW → translucentGen SR = RAW | `MDIC:358` (P1) | `computeToCompute` |

**`MDIC:356` が D 区分 (原作者が「必要か不明」と明記) だった件について**:
アクセス系列を追うと **cmdgen の `atomicAdd` → prefixsum の読みという明確な RAW**
であり、**必要である** [確認済 — アクセス系列から導出]。
D 区分から A/P1 に格下げできる。

---

## 7. `statisticsBuffer` — 統計有効時のみ

| 順 | 段階 | アクセス |
|---|---|---|
| ③ | (CPU) | **HW** `zero()` (`MDIC:329`) |
| ③ | cmdgen | **SW** (`atomicAdd`) |
| ③ | (download) | **TR** (`MDIC:339`) |

| ハザード | GL | Vulkan |
|---|---|---|
| cmdgen SW → download TR = RAW | `MDIC:336` に含まれる | `VkDownloadStream.download` が既に発行する |

---

## 8. `uniform` (SceneUniform) — 全段が読む

| 順 | 段階 | アクセス |
|---|---|---|
| ① | (CPU 経由 UploadStream) | **HW** (`MDIC:161`) |
| ①③④⑤ | ほぼ全段 | **UR** |

Vulkan 版では `VkUploadStream` モード 1 により**対象へ直接書く**ため、
GL の `glCopyNamedBufferSubData` に伴うバリアは消える。
記録窓 assert が「GPU アイドル中の書き込み」を保証する。

---

## 9. ジオメトリ / メタデータ / モデル系

| バッファ | アクセス | ハザード |
|---|---|---|
| ジオメトリ (`getGeometryBuffer`) | ①④⑤ VS が **SR**。②`multiMemcpy` が **SW** | ②SW → ④SR = RAW (`AsyncNodeManager:567`)。①SR → ②SW = **WAR** 🔴 |
| `sectionMetadataBuffer` | ①③④⑤ **SR**。②`scatterWrite` が **SW** | 同上の構造 |
| `modelBuffer` / `modelColourBuffer` | VS/FS が **SR**。モデル更新時に **HW** | 更新は稀。記録窓内なら安全 [推測] |

**②が①の後に走る構造なので、ジオメトリ系にも WAR が存在する** 🔴。
`AsyncNodeManager:565` は P1 に分類していたが、
**src が「前段の描画による VS 読み」**である可能性がある [未検証 — 要精査]。

---

## 10. まとめ

### 10.1 GL にバリアが無い WAR — **6 箇所** 🔴

| # | バッファ | ハザード |
|---|---|---|
| 1 | `drawCallBuffer` | renderOpaque IR → cmdgen SW |
| 2 | `drawCountCallBuffer` | renderOpaque IR → prep SW |
| 3 | `drawCountCallBuffer` | cull IR → cmdgen SW |
| 4 | `positionScratchBuffer` | renderOpaque VS SR → cmdgen SW |
| 5 | ジオメトリ | 描画 VS SR → `multiMemcpy` SW |
| 6 | `sectionMetadataBuffer` | 描画 SR → `scatterWrite` SW |

**すべて「前フレーム分を描く ① が、今フレーム分を作る ②③ より前」
という temporal 構造に起因する。** GL では実装順序が救っていた。

### 10.2 対応表 (§`phase3-barrier-survey.md` §11) の訂正

| # | 箇所 | 旧分類 | 訂正 | 理由 |
|---|---|---|---|---|
| 4 | `MDIC:283` | A/P1 | **P2** | 直後が `cull` の間接読み |
| 6 | `MDIC:334` | A/P1 | **P2** | 直後が `dispatchIndirect` |
| 8 | `MDIC:356` | **D** | **A/P1** | cmdgen の atomicAdd → prefixsum 読みの明確な RAW |
| — | `MDIC:334` (visibility 側) | A/P1 | 個別 | src が **フラグメントシェーダ** |
| 22 | `AsyncNodeManager:565` | A/P1 | 要精査 | src が描画の VS 読みの可能性 [未検証] |

**「GL のビットから機械的に決まる」は、少なくとも 4 箇所で成り立たなかった。**
アクセス系列を追って初めて正しい分類が出る。

### 10.3 これが意味すること

Stage 1 のバリアは**対応表を引くだけでは書けない**。
本ドキュメントの系列表を主、対応表を従として使う。

`ALL_COMMANDS` の保守的バリアを暫定で置き、
**動く絵が出てから系列表に沿って絞る**のが現実的 [推測]。
