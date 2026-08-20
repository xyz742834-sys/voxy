# UploadStream / DownloadStream の Vulkan 版 — 具体案

**状態**: 提案。実装前。§5 の未決事項に回答が必要。

---

## 1. 事前確認の結果

### 1-1. GL 版は容量を使い切ったときどうしているか [確認済]

**`glFinish()` + `tick()` を最大 10 回繰り返し、それでも空かなければ例外。**

`UploadStream:88-101` / `DownloadStream:75-86` に同じ構造がある:

```java
this.caddr = this.allocationArena.alloc((int) size);
if (this.caddr == SIZE_LIMIT) {
    Logger.error("Upload stream full, preemptively committing, this could cause bad things to happen");
    int attempts = 10;
    while (--attempts != 0 && this.caddr == SIZE_LIMIT) {
        glFinish();          // GPU 完了待ち
        this.tick(false);    // 完了フレームの確保を arena に返す
        this.caddr = this.allocationArena.alloc((int) size);
    }
    if (this.caddr == SIZE_LIMIT) throw new IllegalStateException("...");
}
```

つまり **「フラッシュして待つ」→ ダメなら例外**。バッファ拡張はしない。
バッファサイズは固定 64MB (`1<<26`) [確認済]。

### 1-2. DownloadStream の同期構造 [確認済]

- `download(...)` は**非同期**。`glCopyNamedBufferSubData` で対象バッファ →
  ダウンロード用ステージングへコピーし、フェンスを積む
- フェンスが signal された `tick()` で**コールバックが呼ばれる** (≥1 フレーム遅延)
- 呼び出し側はブロックしない [確認済 — `MDICSectionRenderer` の統計、
  `NodeCleaner` の remove batch がいずれも遅延コールバック]

`glFinish` を挟むのは**強制フラッシュ経路のみ** (`waitDiscard`, `flushWaitClear`) で、
シャットダウンやワールド切り替えで使う。**通常フレームでは `glFinish` しない。**

---

## 2. 提案: UploadStream は 2 つに割れる

GL 版の `upload()` は**ステージングバッファ**である。
`commit()` で `glCopyNamedBufferSubData` により対象バッファへ転送している [確認済]。

使われ方は 2 通りに分かれる [確認済]:

| モード | API | 呼び出し箇所 | 用途 |
|---|---|---:|---|
| **1** | `upload(buf, offset, size)` / `uploadTo(buf)` | **19** | ステージング → 対象バッファへコピー |
| **2** | `rawUploadAddress(size)` | **3** | アップロードバッファ自体を SSBO として直接バインド |

### 2.1 モード 1 は**丸ごと不要**になる

ユニファイドメモリでは全 `VkBuffer` が常時マップされている。
**対象バッファのマップ先に直接書けばよく、ステージングもコピーも要らない。**

```java
// GL: ステージングに書く -> commit() で対象へコピー
public long upload(VkBuffer target, long offset, long size) {
    return target.addr() + offset;   // これだけ
}
```

- `glCopyNamedBufferSubData` 相当が消える
- `uploadList` / `UploadData` が消える
- `glFlushMappedNamedBufferRange` が消える (`HOST_COHERENT`)
- **19 箇所の呼び出し側は API 形状が同じなので無変更**

### 2.2 モード 2 だけがスクラッチバッファを必要とする

3 箇所 (`AsyncNodeManager` ×2、`NodeCleaner` ×1) はアップロードバッファ自体を
SSBO としてバインドする。これは dynamic offset で扱う (カテゴリ C)。

ここだけリングバッファが要る。ただし:
- `AllocationArena` は不要 → **フレーム境界で全リセットするバンプアロケータ**で足りる
- `GlFence` / `UploadFrame` の世代キューも不要 (in-flight = 1)

### 2.3 消えるもの / 残るもの

| GL 版の要素 | Vulkan 版 | 理由 |
|---|---|---|
| `GlPersistentMappedBuffer` | → 通常の `VkBuffer` | 全バッファが常時マップ |
| `glFlushMappedNamedBufferRange` | **不要** | `HOST_COHERENT` |
| `GL_MAP_UNSYNCHRONIZED_BIT` / `GL_CLIENT_STORAGE_BIT` | **不要** | メモリ配置のヒントに意味が無い |
| `glCopyNamedBufferSubData` (モード 1) | **不要** | 対象へ直接書く |
| `GlFence` + `UploadFrame` キュー | **不要** | in-flight = 1 |
| `AllocationArena` | **不要** | バンプアロケータ + フレーム境界リセット |
| リングバッファ (モード 2) | **残る** | dynamic offset 用のスクラッチ |

**約 187 行 → 60〜80 行程度になる見込み** [推測]。

---

## 3. 提案: DownloadStream も同様に単純化できる

同じ理屈でステージングが不要になる。

```java
// GL: 対象 -> ダウンロードバッファへコピー -> フェンス -> コールバック
// VK: 要求を積むだけ。フレーム完了後に対象の addr() から直接読む
public void download(VkBuffer target, long offset, long size, Consumer<...> c) {
    this.pending.add(new Request(target, offset, size, c));
}
// VkFrameTracker.beginFrame() の直後 (= GPU アイドル) に配送
```

- ステージングバッファ・`AllocationArena`・`GlFence` キューがすべて消える
- 遅延は GL 版と同じ 1 フレーム
- `glFinish` 17 箇所のうち Download 系 6 箇所は
  **`VkFrameTracker.beginFrame()` の fence 待ちに吸収される**

### 3.0 download 呼び出し箇所の全数調査 [確認済]

「同一フレーム内でその領域が再度書かれうるか」を全 7 箇所について確認した。

| # | 箇所 | 対象 | 同一フレーム内の再書き込み | 判定 |
|---|---|---|---|---|
| 1 | `HierarchicalOcclusionTraverser:352` | `requestBuffer` | **あり — 次の行で clear** | 🔴 **要別扱い** |
| 2 | `HierarchicalOcclusionTraverser:268` | `statisticsBuffer` | FREX 有効時のみ | 🟡 条件付き |
| 3 | `NodeCleaner:129` | `outputBuffer` の範囲 | FREX 有効時のみ | 🟡 条件付き |
| 4 | `MDICSectionRenderer:339` | `statisticsBuffer` | なし | ✅ 安全 |
| 5 | `PrintfInjector:229` | `textBuffer` | デバッグ専用・既定 OFF | — |
| 6 | `BufferArena:76` | arena 領域 | **到達不能 (デッドコード)** | — |
| 7 | `GPUTiming:197` | — | **コメントアウト済み** | — |

#### 🔴 #1 が決定的にまずい

```java
private void downloadResetRequestQueue() {
    glMemoryBarrier(GL_SHADER_STORAGE_BARRIER_BIT);
    DownloadStream.INSTANCE.download(this.requestBuffer, this::forwardDownloadResult);
    nglClearNamedBufferSubData(this.requestBuffer.id, GL_R32UI, 0, 4, ...);  // ← 直後に count を 0 クリア
}
```

download の**次の行**で先頭 4 バイト (リクエスト件数) をクリアしている。
GL はコピーによるスナップショットなのでコールバックはクリア前の値を見るが、
**フレーム最終状態を直接読むと必ず count = 0 になり、リクエストキューが完全に機能しなくなる。**
条件付きではなく毎フレーム発生する。

#### 🟡 #2 / #3 は FREX 有効時のみ

`AbstractRenderPipeline.innerPrimaryWork` の `do-while` ループが
`nodeCleaner.tick()` と `traversal.doTraversal()` を**複数回まわしうる** [確認済]。
ループ条件 `frexStillHasWork()` は **FREX 非有効時は常に false** [確認済 —
`VoxyRenderSystem:499-502`] なので、通常経路では 1 回で抜ける。

FREX 有効時は 2 周目の `outputBuffer.fill()` / `statisticsBuffer` の
ゼロクリアが 1 周目の未配送ダウンロードを潰す。

#### 付随して見つかったデッドコード [確認済]

- **`RawDownloadStream` (97行) はどこからも使われていない**
- `BufferArena` → `BasicSectionGeometryManager` 経由でしか使われないが、
  その `BasicSectionGeometryManager` 自体が未使用
  (`VoxyRenderSystem` が使うのは `BasicSectionGeometryData`)

いずれも Vulkan 移植の対象外にできる。

### 3.1 ⚠ 意味論が 1 点変わる

GL 版はコミット時点で**スナップショットをコピー**するため、
コールバックは「コマンドストリームのその時点」の内容を見る。

直接読みでは「**フレーム全体が終わった後の最終状態**」を見る。
同一バッファがフレーム内で複数回書かれる場合、両者は一致しない。

§3.0 の全数調査により、**1 箇所は無害でないことが確定した**。

**したがって「全面的にスナップショットを捨てる」案は採れない。**
`requestBuffer` (§3.0 #1) だけは download 時点の内容を保持する必要がある。

考えられる扱い:

| 案 | 内容 |
|---|---|
| **A. 該当箇所だけコピーを残す** | `requestBuffer` のみ `vkCmdCopyBuffer` でスクラッチへ退避。他 3 箇所は直接読み。ステージングは小さくて済む |
| **B. clear をやめて二重バッファにする** | シェーダ側で書き込み先をフレームごとに切り替え、CPU は前フレーム側を読む。`downloadResetRequestQueue` の構造変更が要る |
| **C. clear のタイミングを次フレーム先頭へ移す** | 配送後にクリアすれば直接読みで足りる。最小変更だが、クリアが記録順序に依存するため要検証 |

**A が最も安全で変更が小さい** [推測]。FREX 有効時の #2 / #3 も同じ扱いに寄せられる。

---

## 4. 前提となる不変条件

モード 1 の直接書き込みが安全なのは、**in-flight = 1 かつ
「CPU の書き込みが GPU の実行と重ならない」**からである。

```
beginFrame()  … 前フレームの fence を待つ → ここから GPU はアイドル
  ├─ 記録 + CPU 書き込み          ← ここは安全
endFrame()    … サブミット → GPU 実行開始
  ├─ ★ この窓で CPU が書くと競合する
beginFrame()  … 再び待つ
```

→ **すべてのアップロードは `beginFrame()` 〜 `endFrame()` の間で行う**という
規則が必要。`VkFrameTracker.isRecording()` で assert できる。

**[未検証]** 現在の 19 箇所がすべてこの窓に収まるかは未確認。
`UploadStream.tick()` は `VoxyRenderSystem:322`(フレーム内) と `:504`(終了時) から、
`commit()` は 17 箇所から呼ばれている [確認済]。
`ModelFactory` のモデルアップロードなど、フレーム外で走る経路がありうる。

---

## 5. 未決事項 (回答が必要)

### 5-1. モード 1 の直接書き込みで assert を入れるか

「アップロードは記録窓の中でのみ」という規則を実行時に強制するか。

- **強制する**: 窓の外からの呼び出しが即座に例外になる。安全だが、
  現在フレーム外で呼んでいる経路があれば**その経路を先に直す必要がある**
- 強制しない: 静かに競合しうる。in-flight=1 では窓が広いので実害は出にくいが、
  多重化した瞬間に壊れる

### 5-2. モード 2 のスクラッチ枯渇時の挙動

GL 版は「フラッシュして待つ→ダメなら例外」だが、
Vulkan でフレーム途中にリセットはできない (記録済みコマンドがまだ参照している)。

| 案 | 評価 |
|---|---|
| **バッファ拡張 + `freeAtFrameEnd` で旧バッファ解放** | ステージングが無いので拡張コストは低い。ただし**拡張すると descriptor の指すバッファが変わる**ため、既にバインド済みなら記録済みコマンドの読み先が変わる |
| **フレーム途中でサブミット** (GL の `glFinish`+`tick` 相当) | GL の挙動に忠実。`VkFrameTracker` に「中間サブミット」を足す必要がある |
| **例外にする** | 最も単純。64MB で足りるかは実測次第 |

現行 64MB で 1 フレームのモード 2 使用量が収まるかは **[未検証]**。
モード 1 が消えるぶん使用量は大幅に減るはずだが、
`AsyncNodeManager:551` のジオメトリアップロードは大きくなりうる。

### 5-3. DownloadStream の意味論変更 (§3.1) を許容するか

「スナップショット」→「フレーム最終状態」の変更を受け入れるか、
それとも GL 同様にコピーを挟んでスナップショット性を保つか。
コピーを挟むならステージングバッファが復活する。

---

## 6. 実装順序 (案)

1. `VkUploadStream` モード 1 のみ (直接書き込み)。19 箇所が対象
2. `VkUploadStream` モード 2 (バンプアロケータ + dynamic offset)。3 箇所が対象
3. `VkDownloadStream` (要求キュー + フレーム境界配送)
4. `VkFrameTracker` に配送フックを足す

1 と 3 は独立しているので並行して進められる。
