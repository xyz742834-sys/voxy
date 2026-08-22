# Phase 6 着手前 — MC の VkDevice を借りられるか / in-flight = 1 はどこまで崩れるか

**状態**: 調査と見積もりのみ。実装していない。
**日付**: 2026-08-22
**環境**: Apple M4 Pro / macOS / MC 26.2 / MoltenVK 1.4.2 / Sodium 0.9.2-alpha.3

---

## 0. 結論

| 問い | 答え |
|---|---|
| MC は何枚重ねるか | **2 枚** [確認済 — `MAX_SUBMITS_IN_FLIGHT = 2` は public 定数] |
| 境界はフレームか | ⚠ **違う。「提出 (submit)」単位である** |
| mod がその境界を知れるか | <b>直接は不可</b> (private)。ただし<b>知る必要がほぼ無い</b> (§1.3) |
| `VkTexture` のレイアウト追跡は壊れるか | ⚠ **壊れない。当初の見立ては誤り** (§2.1) |
| 最大の費用は | **ホストが直接書いているバッファ** (§2.4) |
| デバイス借用は可能か | **可能。ただし interop とは排他になる** (§4.2) |
| 総見積もり | <b>正味 +250 〜 +400 行</b>。⚠ ただし<b>消える行のほうが多い</b> (§3) |

---

## 1. MC のフレーム管理 [確認済 — バイトコード]

### 1.1 in-flight は 2、単位は「提出」

`com.mojang.blaze3d.vulkan.VulkanCommandEncoder`:

```java
public static final int MAX_SUBMITS_IN_FLIGHT = 2;   // ★ public
private final long submitSemaphore;                  // タイムラインセマフォ
private long currentSubmitIndex;                     // 2 から始まる
private long completedSubmitIndex;
private final DestructionQueue<Destroyable> destroyQueue;   // new DestructionQueue(2, ...)
private final VulkanCommandPool[] commandPools;             // new VulkanCommandPool[2]
```

`submit()` の骨格:

```
endCommandBuffer()
transientMemory.endSubmit()
signalSemaphore(submitSemaphore, currentSubmitIndex, ...)
submissionBuilder.close()                    // ここで実際に提出
currentSubmitIndex++
awaitSubmitCompletion(currentSubmitIndex - 2, 5_000_000_000L)   // ★ 2 世代前を待つ
destroyQueue.rotate()
```

> ⚠ **fence ではなくタイムラインセマフォである。** Voxy は fence を使っている。
> 借用する場合、<b>待ち方が変わる</b> (§2.5)。
>
> ⚠⚠ **境界は「フレーム」ではなく「提出」である。**
> MC は 1 フレームに複数回 `submit()` しうる (レンダーパスの切れ目、
> transient memory が埋まったとき)。
> <b>「フレーム境界でリセット」という Voxy の設計はそのままでは移せない。</b>

### 1.2 ⚠ 想定より mod 向けの接続面が広い

`VulkanCommandEncoder` の public メソッドに、そのまま使えるものがある:

| メソッド | 何ができるか |
|---|---|
| **`execute(VkCommandBuffer)`** | <b>記録済みのコマンドバッファを MC の提出に載せる</b> |
| **`queueForDestroy(Destroyable)`** | <b>破棄を MC の 2 世代キューに任せる</b> |
| `waitSemaphore` / `signalSemaphore` | タイムラインの待ち / シグナル |
| `createFence()` → `GpuFence.awaitCompletion(ns)` | 完了待ち |
| `allocateAndBeginTransientCommandBuffer()` | MC のプールから一時バッファ |
| `submit()` | 明示的に提出 |

**この 2 つ (`execute` と `queueForDestroy`) が、いちばん重い 2 問題を解く。**
Voxy が自前でキューへ提出する必要も、世代付き解放キューを自作する必要も無くなる。

### 1.3 境界を知る手段

`currentSubmitIndex` は private でゲッターが無い。ただし:

- **知る必要がほぼ無い。** 解放は `queueForDestroy` に委ねられる
- 必要なら<b>アクセスワイドナか mixin Accessor</b>。Voxy は既に
  `voxy.accesswidener` を持ち、Sodium が `GpuDeviceAccessor` で同じことをしている前例がある
- あるいは `createFence()` を毎フレーム作って自前で世代を数える

---

## 2. Voxy 側で in-flight = 1 に依存している箇所 (全数)

### 2.1 ⚠ `VkTexture` のレイアウト追跡 — <b>壊れない</b>

当初「多重化すると追跡を作り直す」と見積もったが、**誤りである**。

javadoc の前提はこう書いてある:

> 保持しているのは「コマンドバッファに<b>記録された時点</b>」のレイアウトであって、
> GPU が実行している時点の状態ではない。<b>記録順と異なる順序で呼ぶと破綻する</b>。

**多重化しても記録順 == 実行順は保たれる** — 単一キューに順に提出する限り、
フレーム N+1 の記録はフレーム N の記録が終わってから始まり、実行も同順である。
崩れるのは<b>複数キュー</b>か<b>記録の交錯</b>であって、パイプライン化ではない。

> ⚠ **ただし借用時に新しい前提が要る**: MC も自分のテクスチャのレイアウトを変える。
> <b>Voxy が MC のテクスチャのレイアウトを追跡してはならない</b>。
> 現状 interop 画像を追跡しているので、そこは境界の引き直しになる。

**見積もり: +20 行 (ガードと doc)。作り直しではない。**

### 2.2 `VkFrameTracker` (254 行) — <b>縮む</b>

| 現状 | 借用後 |
|---|---|
| 自前の fence + `waitForFrame()` | ⚠ 不要 — MC が `submit()` で待つ |
| `freeAtFrameEnd` の FIFO | ⚠ 不要 — `queueForDestroy` に委ねる |
| コマンドバッファ 1 本の自前管理 | ⚠ 不要 — `allocateAndBeginTransientCommandBuffer` |
| 世代カウンタ (`current` / `completed`) | **残す** (in-flight 検出が使う) |

**見積もり: 254 行 → 約 100 行。正味 -150 行。**

### 2.3 `VkUploadStream` (171) / `VkDownloadStream` (215) — <b>増える</b>

どちらも「フレーム境界で GPU がアイドル」に乗っている:

- **Upload**: バンプアロケータをフレーム境界でリセット。
  `assertInRecordingWindow` が「記録窓の外で書くな」を強制している。
  ⚠ <b>2 枚重なると記録窓の中でも安全でない</b> — 前の提出がまだ読んでいる。
  → リングを<b>世代ぶん (2 本)</b> 持つ。**+60 〜 80 行**
- **Download**: 退避スクラッチを 1 本持ち、フレーム完了後に読む。
  → スクラッチを<b>世代ぶん</b>持ち、配送を世代で追う。**+40 〜 60 行**

### 2.4 ⚠⚠ <b>ホストが直接書いているバッファ</b> — これが最大

Voxy は<b>ユニファイドメモリを前提に、多くのバッファへ直接書いている</b>。
`.addr()` を使うファイルが 16 本、階層経路だけで毎フレーム 16 箇所。

**in-flight = 2 では、フレーム N+1 の書き込みが実行中の N を壊す。**

| 分類 | 例 | 対処 |
|---|---|---|
| 毎フレーム書く<b>小さいもの</b> | scene uniform / traversal uniform / queueMeta / request カウンタ / limits / visibility / indirectLookup の先頭 | <b>世代ぶん二重化</b> (7 本程度)。**+80 行** |
| 変更時に書く<b>大きいもの</b> | geometry / sectionMetadata / nodeData / model / atlas staging | <b>staging 経由のコピーに切り替える</b> (`VkUploadStream` のモード 1 が既にある)。**+120 行** (呼び出し側の書き換え) |

> ⚠ **これは「落ちない」型の壊れ方である。** 前フレームの絵に次フレームの
> ユニフォームが混ざるだけなので、<b>ちらつきとしてしか出ない</b>。
> 5c-4c で踏んだ 5 件と同じ性質で、<b>検査を先に用意しなければ気付けない</b>。

### 2.5 fence → タイムラインセマフォ

Voxy は `VkFence` で待つ。MC はタイムラインセマフォ。
借用して `execute()` に載せるなら<b>待ちは MC 側</b>なので、
Voxy 側の fence は<b>消える</b>。**-30 行程度。**

### 2.6 Phase 2 の in-flight 検出機構 — <b>残すが意味が変わる</b>

`VkAutoBindingShader` は「GPU が参照中の descriptor set を書き換えた」を
`VkFrameTracker` の世代照合で検出する。

- in-flight = 1 では「記録中に書き換えた」しか検出しない (窓が狭い)
- **in-flight = 2 では本来の意味で効く** — 前の提出が参照中の set を弾く

⚠ ただし<b>検出できても対処が無い</b>。set を世代ぶん持つ必要がある
(`VkDescriptorSetGroup` は既に variant を持つので、そこに載せられる)。
**+40 行。**

---

## 3. 見積もりまとめ

| 箇所 | 増減 |
|---|---|
| `VkFrameTracker` | **-150** |
| fence 関連 | **-30** |
| interop 一式 (`VkInteropImage` / `GlInteropCompositor` / `GlDepthImport` / `GlVkSync` / `GlScratchFramebuffer`) | **-800 〜 -1000** (削除) |
| `VkUploadStream` | +60 〜 80 |
| `VkDownloadStream` | +40 〜 60 |
| 毎フレームの小バッファ二重化 | +80 |
| 大バッファの staging 化 | +120 |
| descriptor set の世代化 | +40 |
| `VkTexture` のガード | +20 |
| `VkContext` の借用経路 | +100 |
| 合成を Vulkan パスに | +150 |
| **正味** | **-400 前後 (減る)** |

> **新規に書く量は +250 〜 +400 行。** それに対して<b>消える量が 1000 行前後</b>ある。
> ⚠ ただし<b>消える行は既に検証済みの行</b>で、増える行は未検証である。
> 行数は工数の代理として弱い。<b>危険なのは §2.4 の「落ちない」型</b>。

---

## 4. デバイス借用の実現可能性

### 4.1 経路はある

```
RenderSystem.getDevice() → GpuDevice
GpuDevice.backend        → private (アクセスワイドナか mixin Accessor で開ける)
                         → (VulkanDevice) → vkDevice() / graphicsQueue() / vma()
```

`VulkanDevice` は `vkDevice()` / `instance()` / `graphicsQueue()` /
`computeQueue()` / `transferQueue()` / `vma()` を<b>全て public</b> で持つ。
Sodium が `GpuDeviceAccessor` で同じことをしている [確認済]。

`VkContext` (564 行) は「作る」しかできないので、<b>借りる経路を足す</b>。
物理デバイス情報 (`memProps` / limits) は借りたデバイスから引き直せる。**+100 行。**

### 4.2 ⚠⚠ interop とは<b>排他</b>になる

Voxy は IOSurface を `VkImage` にするのに **`VK_EXT_metal_objects`** を要求する。
**MC のデバイスはこれを有効化していない** [確認済 — 有効な拡張 9 個の一覧に無い]。

> **借りたデバイスでは interop 画像を作れない。**
> したがって「MC が GL のときは interop、Vulkan のときは借用」という
> <b>2 経路を同じ `VkContext` で持つことになる</b> — フォールバックとして
> 同時に持つことはできない。
>
> ⚠ これは<b>設計上の分岐点が 1 つ増える</b>ということで、
> 「interop が消える」という単純な話ではない。

### 4.3 キューの取り合い

MC は graphics / compute / transfer の 3 本を持つ。選択肢:

| 案 | 中身 | 評価 |
|---|---|---|
| **A** | `execute()` で MC の提出に載せる | ⭐ <b>取り合いが起きない</b>。順序も MC が保証 |
| B | `graphicsQueue()` に自前で提出 | 同じキューへの並行提出は<b>外部同期が要る</b> (`VkQueue` はスレッド安全でない) |
| C | `computeQueue()` を使う | キュー間の同期を自前で張ることになる |

**A を推す。** `execute()` があるので自前提出の理由が無い。

⚠ ただし A では<b>Voxy のコマンドが MC のフレームのどこに入るか</b>を
MC の呼び出し順が決める。現在の注入点 (`DefaultChunkRenderer`) がそのまま使えるかは
<b>未検証</b>である。

### 4.4 残る未検証

| | |
|---|---|
| Voxy の Sodium mixin 9 個が Vulkan 経路で効くか | 5c 前の調査から<b>変わらず推測のまま</b> |
| `execute()` に載せたコマンドが注入点の意図した位置に入るか | **未検証** |
| MC の `VulkanTransientMemory` と Voxy の upload が干渉しないか | 未検証 |
| MoltenVK で 2 枚重ねたときの実効フレーム時間 | 未測定 |

---

## 5. 判断材料

**技術的には可能で、正味のコード量はむしろ減る。**
最大の費用は行数ではなく <b>§2.4「ホスト直接書き込み」の作り直し</b>で、
これは<b>落ちない型の壊れ方</b>をするため、<b>検査を先に用意する必要がある</b>。

⚠ **当初「読めない費用」とした `VkTexture` のレイアウト追跡は、読み直したら問題なかった。**
崩れるのは複数キューか記録の交錯であって、パイプライン化ではない。

**比較対象は既にある** — 5c-4c で interop 経路の内訳を取ってある
(`hiz` 0.26–1.43 / `traversal` 0.05 / `table` 0.08 / `draw` 1.5–2.2 / `resolve` 0.03 ms)。
乗り換え後に同じ区間を測れば、<b>何がどれだけ効いたか</b>を言える。
