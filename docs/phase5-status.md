# Phase 5 現状まとめ (5a 〜 5c-1 全段 + **5c-2a** 完了時点)

**最終確認**: JUnit **194 PASS / 0 FAIL**、`interopCompositeCheck` **39 項目 PASS**、
MC 実機で 5c-1b / 5c-1c / **5c-1d** の手動確認**全項目 PASS**
(地形が出る / 水・MOB が正しい前後 / **MC の地形に隠される** / 橙が出ない)。

> **Voxy 自身が描いた絵が、初めて Minecraft の画面に出た。**
> データは合成だが<b>経路は本番と同じ</b>で、深度も MC のバッファへ書いている。

MC 実機で **5c-1e の手動確認も全項目 PASS**
(2 組とも見える / 丘を挟むと**奥だけが隠れ、手前は出たまま**)。

> **これで 5c-1 は全段 (a〜e) 完了。**
> <b>Voxy の絵が MC の画面に、正しい向き・正しい前後関係で出る</b>ところまで来た。
> 残るは<b>実データの投入</b> (5c-2〜5)。

> **⚠ 5c-1b 時点の「178 PASS / 1 SKIP」と数え方が違う。**
> 今回の測定環境では<b>同期バリデーションを有効にできず</b>
> (`validation=false syncValidation=false`)、それを要求する `VkBarriersTest` の
> 3 件が SKIP になる。有効な環境ではうち 2 件が走るので、同じ木で 182 PASS / 1 SKIP になる。
>
> | | 総数 | PASS | SKIP |
> |---|---:|---:|---:|
> | 5c-1b 時点 (同期バリデーション有効) | 179 | 178 | 1 |
> | 5c-1c 時点 (同上・推定) | 183 | 182 | 1 |
> | 5c-1c 時点 (**実測**・同期バリデーション無効) | 183 | **180** | 3 |
>
> **失われたテストは無い** (179 + 新設 4 = 183)。差は全て同期バリデーションの有無による。

---

## 1. 到達点

**Vulkan で描いた地形が、Minecraft の画面に正しい向き・正しい前後関係で出るところまで来た。**

```
合成地形 ──(VkTerrainRenderer)──→ IOSurface(BGRA) ──┐
                └(VkDepthResolve)→ IOSurface(R32F) ──┴→ GL 合成 → MC の色 + 深度 → 画面
                                                          (深度テスト: MC より手前だけ)
```

| 段 | 状態 |
|---|---|
| 5a — GL → Vulkan の同期コスト実測 | ✅ 完了 |
| 5b — interop 画像への描画と GL 合成 (オフスクリーン) | ✅ 完了 |
| 5b 前提 — ステンシルは深度で置換可能か | ✅ 完了 (置換可能) |
| 5c 着手前 — MC の Vulkan レンダラ調査 | ✅ 完了 |
| 5c 準備 — 逆Z / Y 規約の統一 | ✅ 完了 |
| 5c-1a — バックエンド選択の配線 (何も描かない) | ✅ 完了 |
| 5c-1b — 非対称パターンの合成 (色経路の向き) | ✅ 完了 |
| 5c-1c — MC の深度を Vulkan に渡して可視化 (深度経路の向き) | ✅ 完了 |
| 5c-1d — 合成地形を描いて合成する (**Voxy の絵が初めて MC に出た**) | ✅ **完了** |
| 5c-1e — MC 地形との前後関係 (手前と奥の両方で) | ✅ **完了** |
| 5c-2a — アトラスを**実寸**に (中身は合成のまま) | ✅ **完了** |
| 5c-2b — GL / Vulkan の**分岐点**を作る (案 A) | ✅ **完了** ([phase5c2b-boundary.md](phase5c2b-boundary.md)) |
| 5c-2b — **実データの配線** (`-Pvoxy5c2=real`) | ✅ **完了**。落ちた 4 件 + 黒くなった 1 件を修正 ([§8–10](phase5c2b-boundary.md)) |
| ↳ 地雷 6 つ目 `SoftwareModelTextureBakery` の `glGetTextureImage` | ✅ 非 DSA に置換 |
| ↳ 生の GL がホストの FBO / pack 指定を戻していなかった | ✅ 全て保存・復元 [規約 15] |
| ↳ 境界の穴 — 共有経路の `UploadStream.commit()` | ✅ `beginUploads` / `commitUploads` に移動 |
| ↳ **ブロック状態 id をモデル id として使っていた** | ✅ `getModelId` を通す (⚠ 今回の構成では id が一致するので<b>絵は変わらない</b>) |
| ↳ 実機 1 回目: **草と葉だけ真っ黒** = バイオーム着色の 2 種 | ✅ `setBiomeCallback` の配線 + セクション数だけ登録 [規約 16] |
| ↳ **実機 2 回目: 全項目 PASS** | ✅ **5c-2b 完了** |
| 5c-3 以降 | ⬜ 未着手 |

---

## 2. 測って分かったこと

| 問い | 答え |
|---|---|
| GL → Vulkan の同期コスト | **0.3 ms 未満**。実行間のばらつきと同程度 |
| なぜ安いのか | 同期を呼ばなくても待ちは発生している。**待つ場所が変わるだけ** |
| 同期は本当に要るか | **不明**。陳腐な読みを 1 度も構成できなかった [未検証] |
| IOSurface の依存追跡 | GPU の取り合いではなく**データ依存**をシステムが追跡している [確認済 — C4] |
| MC 26.2 の Vulkan レンダラは macOS で動くか | **動く**。`VK_KHR_push_descriptor` も利用可 |
| MC-Vulkan と Voxy は同居できるか | **できない**。Sodium 経由の受け取りが `GlTextureView` にキャストしている |
| MC の深度 | **D32_FLOAT・ステンシル無し**。d24s8 は Voxy 自前の FBO だった |
| **MC の投影行列の深度規約** | **逆Z・-1..1** [確認済 — 5c-1d の実機の数値]。⚠ Vulkan のクリップ空間は常に 0..1 なので<b>そのまま渡すと全部クリップされる</b>。`VoxyRenderSystem.computeProjectionMat` が m22/m32 を書き換えているのはこのため |
| **注入点での空の深度** | **クリア値 (`FAR`) のまま** [確認済 — 実機。空がマゼンタ = `z == FAR` の完全一致]。⚠ バイトコード上は `SKY` が writeDepth=true なので<b>推論と観測が食い違う</b>。機構は [未検証] |
| **合成後に MC が描くもの** | 半透明 (水)・エンティティは<b>注入点の後</b>に描かれ、合成の上に乗る [確認済 — 実機。水が青く正しい前後で出た] |
| MC の深度規約 | **逆Z** (`GREATER_THAN_OR_EQUAL`) |

---

## 3. 確定した設計判断

| # | 判断 | 理由 |
|---|---|---|
| 1 | **深度は逆Z**、非逆Zは捨てる | MC 26.2 が逆Z。両対応は「使わない経路が腐る」 |
| 2 | **Y は GL 規約のまま通す** (反転ゼロ) | 反転が 1 つも無ければ「打ち消し合う変換」が原理的に起きない |
| 3 | **ステンシルは要らない** | `initDepthStencil` が MC 地形の画素に NEAR を書くので深度で等価 |
| 4 | **バリデーションの既知ノイズを抑制** | VUID 3 つを interop ハンドル限定で。範囲は常設テストが見張る |
| 5 | **同期は FENCE 相当** | コスト差 0.3ms 未満なら正しさが確認できているほうを選ぶ |
| 6 | **アトラスは Vulkan 画像に作り直す** | 402MB を interop で共有するよりリスクが低い |
| 7 | **バックエンド選択は `VkContext.init()` の成功 + GL コンテキストの存在** | 「対応しているのに動かない」を避ける / 合成に GL が要る |
| 8 | **SSAO は 5c では無効化** | Phase 6。push constant 未移行で Vulkan 経路に載っていない |
| 9 | **最適化は測ってから** | MDI の件で一般論のほうが間違っていた |

---

## 4. 追加した検証資産

| 層 | 中身 |
|---|---|
| JUnit (素の JVM) | **183 件**。`VkDepthConventionTest` / `VkOrientationTest` / `StencilMaskEquivalenceTest` / `CapabilitiesWithoutGlTest` / `InteropValidationSuppressionTest` / **`VkDepthVisualiseTest`** を新設 |
| GL + Vulkan (JavaExec) | `glToVkSyncBench` (5a)、`interopCompositeCheck` (**39 項目**、C1〜C15) |
| MC 起動 (手動) | 見る項目を先に書き出す運用。切り分けスイッチ `-Pvoxy5c1d=off\|state\|pattern\|solid\|depth\|terrain` |

---

## 5. ⚠ 対照実験の失敗例が 9 → 14 に増えた

Phase 5 で 5 例増え、うち 4 例が<b>新しい型</b>だった。
詳細と規約は `phase4-stage1-completion.md` §5.2。

| # | 何が起きたか | 追加した規約 |
|---|---|---|
| 10 | **打ち消し合う 2 つの変換。** 合成が上下逆さまなのに `glReadPixels` も反転して配列は完全一致。機械的検査 10 項目が全て PASS | **規約 3**: 経路の端点だけでなく途中も見る。機械的検査だけに寄せない |
| 11 | **予測と実装が同じ規約を共有していた。** CPU 予測 vs GPU 結果は独立に見えるが、行列の Y 符号を反転すると予測も反転して再び一致。<b>検査の内側で打ち消しが起きた</b> | **規約 4**: 少なくとも 1 つは<b>規約に依存しない外部の事実</b>を主張する |
| 12 | **復帰処理が no-op だった。** 素の GL で変えてキャッシュ API で戻したため、キャッシュが「既にその値」と判断して GL を呼ばず、mob が地形を貫通 | **規約 5**: 対照は<b>本番が通る経路</b>を通す (再発防止の検査が変異を検出できなかった) |
| 13 | **調査で分かった制約が実装に入っていなかった。** 「MC-Vulkan と同居不可」と doc に書いた後、バックエンド選択にその条件を入れ忘れて実機クラッシュ | **規約 6**: 制約は<b>同じ変更の中で</b>ガードとテストにする |
| 14 | **対照環境に存在しない要素だった。** 黒画面の原因は MC が残したサンプラオブジェクト。ベンチには誰も bind しないので再現しない | **規約 7**: ホストが残す状態を洗い出す。<b>既存コードの「一見不要な後始末」の理由を先に読む</b> |

> **共通する形**: 「検査が通った」と「正しい」の間に隙間がある。
> Phase 5 では<b>既存テストが 2 度続けて 1 件も落ちなかった</b> (逆Z / Y 規約) ことが、
> 隙間の存在を教えた。**落ちないこと自体を疑う**のが分かれ目だった。

---

## 6. ⚠ GL 4.5 DSA の地雷

Apple の GL 4.1 に無い関数を呼ぶと落ちる。
`glCreate*` (DSA) は全て 4.5 なので、**GL のクラスに触れた瞬間に落ちると考えてよい**。

| クラス | 状態 |
|---|---|
| `SharedIndexBuffer` | 踏んだ → `BACKEND == OPENGL` で囲った |
| `GlFramebuffer` | 踏みかけた → `GlScratchFramebuffer` (GL 3.0 のみ) で回避 |
| `ModelStore` | 5 つ目 → `ModelUploadTarget` で分岐し、Vulkan 経路では作らない |
| **`SoftwareModelTextureBakery`** | **6 つ目。5c-2b で踏んだ** → `glGetTexImage` (非 DSA) に置換 |
| `FullscreenBlit` / `UploadStream` / `DownloadStream` | **未踏の地雷**。Vulkan 経路から触らないこと |

> ⚠ **一覧の洗い方を変えた。** 5 つ目までは「静的初期化子で GL を呼ぶクラス」で洗っていたが、
> 6 つ目は<b>普通のメソッドの中の DSA 1 行</b>で、その grep に出なかった。
> **「DSA の関数名」で洗うこと** — `glGetTextureImage` / `glTextureSubImage*` /
> `glTextureParameter*` / `glBindTextureUnit` / `glGetNamedBufferSubData` なども DSA である。

> ⚠ **落ち方は一定でない。** 6 つ目は abort ではなく `NullPointerException`
> (LWJGL の `Checks.check`) で、MC のクラッシュレポートとして出た。機構は [未検証]。
> <b>「hs_err が無い」を「DSA を踏んでいない」の証拠にしてはならない</b> [規約 11]。

---

## 7. 上流への報告候補

`upstream-issue-candidates.md`:

1. **MC 26.2 で Vulkan を選ぶと Voxy が起動時クラッシュ** (`Capabilities` の clinit) — このフォークで修正済み
2. `DebugRenderer.debugShader` がコンパイルできない (`NODE_DATA_BINDING` 未定義) — 未修正

---

## 8. 次にやること

### 8.1 次は 5c-2b の残り — <b>実データを流す</b> (MC 起動が要る)

**分岐点はできた** — `ModelUploadTarget` が CPU 側のベイク済みバッファを境界にし、
GL 実装 (`ModelStore`) と Vulkan 実装 (`VkModelUploadTarget`) が<b>同じ入力</b>を受ける。
配置の式は `ModelAtlasLayout` に集めたので<b>ずれようがない</b>
([phase5c2b-boundary.md](phase5c2b-boundary.md))。

**5c-2b は完了した。** 実機で全項目 PASS
([phase5c2b-boundary.md §10](phase5c2b-boundary.md))。
<b>Minecraft の本物のブロックテクスチャが Vulkan のアトラス経由で画面に出ている。</b>

⚠ ただし<b>この段で検証できていないこと</b>を承知しておくこと:

| 主張 | 状態 |
|---|---|
| 色が **GL 版と同じか** | ❌ 検証できない (GL バックエンドがこの Mac で動かない) |
| **モデル id の取り違え**が起きていないか | ⚠ この構成では検出できない — 2 つの id 空間がたまたま一致している [§9.6] |
| フレーム時間 | ❌ 未測定。地形が合成なので測る意味が薄い |

### 8.2 その後

5c-3 (実ジオメトリ) → 5c-4 (HiZ + traversal) → 5c-5 (半透明・temporal)。

⚠ **投影空間の問題は 5c-3 で確実に来る** [5c-1d §2.3] — 実データの遠景 LoD は
MC の far 平面の外に出る。**GL 版の `transformBlitDepth` が答えを持っている**
([phase5c2a-completion.md](phase5c2a-completion.md) §6 に読み解きを記録)。

⚠ **フレーム時間の実測値が初めて得られる**のもこの先である。

**5c-1e に効く既知の事実**:

- 空の画素の深度は<b>クリア値のまま</b> [5c-1c] → 遠景の LoD は空の領域に描ける
- 半透明・エンティティは<b>合成の後</b>に描かれる → Voxy の絵の上に乗るのが正常
- ⚠ **深度の投影空間** [5c-1d §2.3] — 5c-1d は MC の投影で描いて回避しているが、
  <b>本番の Voxy は near=16 / far=48000 の自前投影</b>で描く。
  そのままでは MC の深度と比較できず、GL 経路は `transformBlitDepth` で
  <b>再投影してから</b>書き戻している。**5c-2 で実データを入れると表面化する**

**道具立ては揃っている** — 切り分けスイッチ、C9 (状態復帰)、規約 3〜7、
`VoxyRenderSystem` の reset ブロック (ホストが残す状態の一覧)。
