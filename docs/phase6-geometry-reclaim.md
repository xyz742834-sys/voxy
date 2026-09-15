# Phase 6 — ジオメトリの回収

**状態**: 完了。装置なしテスト 4 件 (変異で確認済み)。実機は未確認。

---

## 1. なぜ着手したか

5c-5a はジオメトリ領域が満杯になると<b>止まったまま二度と再開しない</b>仕様
([`docs/phase5-status.md`](phase5-status.md) §1.2 の欠陥 3)。
プレイヤーが動き回る実運用ではこれはいずれ必ず起きる — 他の最適化項目より
「移植の完成」に近い、と判断して優先した。

---

## 2. GL 版を読んで分かったこと

`NodeCleaner` は `AsyncNodeManager` (1033 行) に依存しているが、
**実際に要る API は `NodeManager.removeNodeGeometry(long pos)` 1 本だけ**だった
[確認済 — `AsyncNodeManager` のワーカースレッドが最終的に呼ぶのはこのメソッド]。
`AsyncNodeManager` の残りは<b>マルチスレッド処理の配管</b>であり、
Vulkan 側は既に同期設計 (「5c-4c の縮小: 先に全部メッシュ化して渡す」) なので不要。

### 2.1 ⚠ 回収の基準は「最近描かれたか」ではない

`NodeCleaner.visibilityBuffer` は {@code ICleaner.alloc} でリセットされるだけで、
描画選択の {@code lastRenderFrame} (Vulkan では {@code VkTraversal.renderTracker})
とは<b>別物</b>だった [確認済 — `NodeCleaner`/`AsyncNodeManager` のどこからも
`lastRenderFrame` を読んでいない]。**回収の基準は
「(再) 確保されてから何フレーム経ったか」**である。

> **だから GPU 側の render tracker を読み返す必要が無い。**
> `NodeManager.setClear` の alloc/free イベントだけで足りる —
> 当初 GPU バッファの読み戻しが要ると考えていたが、そうではなかった。

### 2.2 ⚠⚠ トップレベルノードの防御は「葉のときだけ」— <b>装置なしテストで踏んだ</b>

GL 版 `sort_visibility.comp` の `shouldSortId` は
`node.lodLevel == 4` (最上位 LoD) を<b>候補選びの時点で</b>弾いている。

当初これを<b>不要</b>と判断した — `NodeManager.removeNodeGeometry` 自身が
トップレベルへの適用を拒否するので、除外ロジックを二重に持つ必要は無いと考えた。

**これは誤りだった。** `removeNodeGeometry` のトップレベル防御は
<b>{@code NODE_TYPE_LEAF} のときにしか効かない</b> — 子ができて
{@code NODE_TYPE_INNER} になったトップレベルノードを候補にしたところ、
`clearGeometryInternal` が実際に `IllegalStateException` を投げた
(`Unwatching position for geometry removal ... resulted in full removal`)。

> **「壊れなかった」対照を 1 つ作って安全と判断したのが甘かった。**
> 最初の版のテストは<b>葉のままのトップレベル</b>でしか確かめていなかった —
> 子を持たせた対照を後から足して初めて踏んだ [規約 11 の実例が、
> まさにこの調査の中で起きた]。

**直した**: `GeometryReclaimer` は `TopLevelNodeQueue` が既に追跡している集合を
そのまま使い、<b>候補選びの時点で</b>トップレベルを除外する。GL 版と同じ形。

---

## 3. 設計 (最終)

### 3.1 `NodeManager` への追加 — 1 行

```java
public long positionOf(int nodeId) { return this.nodeData.nodePosition(nodeId); }
```

`NodeStore.nodePosition` は既に CPU 常駐データなので、GPU 読み戻しが要らない。

### 3.2 `GeometryReclaimer` (新設、`VkHierarchicalScene` の静的ネストクラス)

`TopLevelNodeQueue` と同じ形式 (装置を要らない、`NodeManager` を直接受け取る)。

| 責務 | 実装 |
|---|---|
| 生死の追跡 | `NodeManager.ICleaner` を実装。`alloc`/`free` で `id → 確保フレーム` の Map を更新 |
| `move` | <b>意図的に no-op</b>。GL 版 `AsyncNodeManager` 自身が同じ判断をしている |
| 候補選び | 非トップレベルの中から最古の id を線形走査で選ぶ |
| 回収 | `NodeManager.removeNodeGeometry(positionOf(id))` |
| 上限 | `maxEvictions` (既定 64) で無限ループを防ぐ |

### 3.3 ⚠ 何を単純化したか

GL 版は GPU 上のロックフリー近似ソートで<b>256 件を 1 回</b>で選ぶ
(CAS リトライの上限つき — Phase 0 の GPU リセットの教訓が詰まっている)。

こちらは<b>1 件ずつ</b>線形走査で最古を選ぶ。回収が要るのは容量が逼迫した
稀なフレームだけで、ノード数は高々数万なので、線形走査でも無視できるコストである。
GPU 並列ソートの複雑さを Vulkan 側で再現する理由が無い。

### 3.4 `acceptGeometry` への統合

Phase 5c-5a は容量不足で<b>止まったまま二度と再開しない</b>仕様だった。

```
入らない → reclaimWhile(容量チェック, maxEvictions) → 入るか再確認
  → 入った: 通常どおり処理、geometryExhausted = false
  → まだ入らない: 本当に止める (トップレベルしか残っていない等)
```

---

## 4. 検査 (装置なし、変異で確認済み)

`VkGeometryReclaimTest` (4 件):

| 検査 | 主張 |
|---|---|
| `reclaimingWithNothingAllocatedDoesNothing` | 対照。何も無ければ何もしない |
| `allocAndFreeAreTrackedThroughNodeManager` | alloc/free の配線そのもの |
| **`topLevelNodesAreNeverAttemptedEvenWhenTheyAreOldest`** | ⚠⚠ 本命。より古いトップレベルは選ばれず、より新しい非トップレベルが選ばれる |
| `reclaimStopsAtTheCapEvenWithCandidatesRemaining` | 候補が残っていても上限で止まる (無限ループにならない) |

**変異で確かめた**: トップレベル除外の 1 行を消すと、2 件が正しく落ちた。

---

## 5. ⚠ 未確認のこと

- **実機での動作は未確認。** `acceptGeometry` から `reclaimWhile` が呼ばれる
  実際のフレームループでの挙動は、次に実機を回すときに見る
- **`move` を no-op にした影響の実測は無い。** GL 版が許容している誤差
  (「回収の優先順位が少し狂う」) を Vulkan 側でも許容できるかは未測定
- 回収の閾値・頻度のチューニングは行っていない。動くことを確かめただけ
