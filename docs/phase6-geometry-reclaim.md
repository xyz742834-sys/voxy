# Phase 6 — ジオメトリの回収

**状態**: 完了。装置なしテスト 9 件 (変異で確認済み)。実機クラッシュ 2 件を
発見・修正済み (§6, §7)、修正後の実機再確認は未実施。

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

`VkGeometryReclaimTest` (6 件、実機クラッシュ後に 2 件追加 — §6):

| 検査 | 主張 |
|---|---|
| `reclaimingWithNothingAllocatedDoesNothing` | 対照。何も無ければ何もしない |
| `allocAndFreeAreTrackedThroughNodeManager` | alloc/free の配線そのもの |
| **`topLevelNodesAreNeverAttemptedEvenWhenTheyAreOldest`** | ⚠⚠ より古いトップレベルは選ばれず、より新しい非トップレベルが選ばれる |
| `reclaimStopsAtTheCapEvenWithCandidatesRemaining` | 候補が残っていても上限で止まる (無限ループにならない) |
| **`reclaimingANonTopLevelInnerNodeDirectly`** | ⚠⚠ 実機クラッシュの再現 (§6.2)。`Watcher.unwatch` の常時 true バグ |
| **`nodesWithAnInFlightChildRequestAreNeverAttempted`** | ⚠ in-flight な `NODE_TYPE_INNER` が候補から外れること (§6.3) |

**変異で確かめた**: トップレベル除外の 1 行、`Watcher.unwatch` の戻り値、
`isSafeToReclaimGeometry` の除外 — それぞれ元に戻すと対応するテストだけが落ちた。

---

## 5. ⚠ 未確認のこと

- **`move` を no-op にした影響の実測は無い。** GL 版が許容している誤差
  (「回収の優先順位が少し狂う」) を Vulkan 側でも許容できるかは未測定
- 回収の閾値・頻度のチューニングは行っていない。動くことを確かめただけ

---

## 6. ⚠⚠ 実機クラッシュ (2026-09-21) — 根本原因とその修正

装置なしテストでは踏めなかった実機限定のバグが 2 つ見つかった。

### 6.1 症状

```
java.lang.IllegalStateException: Unwatching position for geometry removal
    at: 3@[-3, 0, 0] resulted in full removal
	at NodeManager.clearGeometryInternal(NodeManager.java:1354)
	at NodeManager.removeNodeGeometry(NodeManager.java:1267)
	at VkHierarchicalScene$GeometryReclaimer.reclaimWhile(VkHierarchicalScene.java:242)
```

`-Pvoxy5c4Quads=500000` で容量を絞って動き回ってから約 30 秒で発生。

### 6.2 原因 ① (根本原因): `VkHierarchicalScene.Watcher.unwatch` が常に `true` を返していた

```java
@Override public boolean unwatch(long position, int types) {
    int v = this.watched.get(position) & ~types;
    if (v == 0) this.watched.remove(position); else this.watched.put(position, v);
    return true;   // ← ここ。v の値を見ずに常に true
}
```

`NodeManager.clearGeometryInternal` は `NODE_TYPE_INNER` のジオメトリを消す際、
`unwatch(pos, BLOCK_BIT)` の戻り値を「部分アンウォッチのはずが<b>他のビット
(CHILD_EXISTENCE_BIT) まで巻き込んで全消去された</b> = 異常」の検出に使っている
[確認済 — GL 側の成熟した実装 `SectionUpdateRouter.unwatch` は
`removed = (現在値==0)` を正しく返す契約になっている]。

常に `true` を返す実装では、`NODE_TYPE_INNER` のジオメトリを 1 回でも
`removeNodeGeometry` で消そうとした瞬間に<b>必ず</b>この例外が飛ぶ。
`GeometryReclaimer` 導入以前は、この「部分ビットだけ unwatch する」呼び出し
自体がどの経路からも一度も発生していなかったため、既存バグとして潜伏していた。

**装置なしテストでの再現**: `VkGeometryReclaimTest.reclaimingANonTopLevelInnerNodeDirectly`
— T (top) → C (T の子、INNER に昇格) → GC (C の子) の 3 段を組み、
in-flight な要求が一切無い状態の C を直接 `removeNodeGeometry` するだけで
同一の例外が再現した。テスト自身の `InMemoryWatcher` も同じバグのコピーだった
ので合わせて直した。

**修正**: `v == 0` を返すようにする (1 行)。ミューテーションテストで確認済み
(元の「常に true」に戻すとこのテストだけが失敗する)。

### 6.3 原因 ②: `NODE_TYPE_INNER` が in-flight のまま候補に選ばれうる

`NodeManager.updateChildSectionsInner` 経由で、`NODE_TYPE_INNER` は<b>自分の
メッシュを有効なまま</b>新しい子ノードの要求を追加できる
(GL 参照 `sort_visibility.comp` の `shouldSortId` にある `hasRequested` 相当の
除外が、こちらには無かった)。この状態のノードを回収候補に選んでしまうと
危険なので、`NodeManager.isSafeToReclaimGeometry(int)` を新設し
`GeometryReclaimer.reclaimWhile` の候補選びに追加した:

```java
public boolean isSafeToReclaimGeometry(int nodeId) {
    int geo = this.nodeData.getNodeGeometry(nodeId);
    if (geo == NULL_GEOMETRY_ID || geo == EMPTY_GEOMETRY_ID) return false;
    return !this.nodeData.isNodeRequestInFlight(nodeId);
}
```

**装置なしテストでの再現**: `VkGeometryReclaimTest.nodesWithAnInFlightChildRequestAreNeverAttempted`
— INNER に昇格させた非トップレベルノードへさらに子を追加要求し、
in-flight のまま回収候補に挙がらないことを確認。

⚠ このテストは `maxEvictions=1` で 1 回だけ回収させる形にしてある。
別の (安全な) 子ノードを回収すると `processLeafGeometryRemoval` が<b>同じ
呼び出しの中で即座に</b>親を INNER→LEAF に畳み込み、その副作用で親の
in-flight リクエストごとキャンセルされる — 畳み込み後の親は本当にもう
in-flight ではなくなるので、2 周目以降で選ばれるのは正しい。1 回だけで
見ないと「in-flight のその瞬間に候補から外れるか」を確かめられない。

### 6.4 ⚠ 途中で検討して撤回した設計

原因②だけを見ていた段階では、原因不明の防御として
`NODE_TYPE_INNER` を丸ごと候補から除外する (`getNodeType(id) != LEAF → false`)
という安全側の実装を一時的に入れていた。原因①の根本原因が確定したことで、
`NODE_TYPE_INNER` 自体は安全に回収できることが分かったため、この丸ごと除外は
撤回した — 残すと `GeometryReclaimer` が INNER ノードを一切回収できなくなり、
回収の実効性を不必要に落とすところだった。

### 6.5 まだ未確認のこと

- **修正後の実機での動作は未確認。** 6.1 の手順をもう一度実機で回す必要がある

---

## 7. ⚠⚠ 実機クラッシュ第2弾 (2026-09-22) — "Geometry OOM"、断片化

§6 の修正後、同じ手順 (`-Pvoxy5c4Quads=500000` で動き回る) で
<b>別の</b>クラッシュが発生した。

### 7.1 症状

```
java.lang.IllegalStateException: Geometry OOM. requested allocation size
    (in elements): 2028, Heap size at top remaining: 160, used elements: 497920
	at BasicAsyncGeometryManager.createMeta(BasicAsyncGeometryManager.java:116)
	at BasicAsyncGeometryManager.uploadReplaceSection(...)
	at NodeManager.processGeometryResult(...)
	at VkHierarchicalScene.acceptGeometry(VkHierarchicalScene.java:911)
```

### 7.2 原因: 合計バイトの比較は断片化を見ない

`acceptGeometry` は `getGeometryUsedBytes() + need <= geometryCapacityBytes`
という<b>合計バイトの比較だけ</b>で「入るか」を判定していた。しかし
`BasicAsyncGeometryManager` が内部で使う `AllocationArena`
(`common/util/AllocationArena.java`) は<b>size 以上の単一の連続空きブロックが
1 つ要る</b>方式のアロケータで、「空き容量の合計」では動かない。

`GeometryReclaimer` は最古のノードから<b>順に</b>消すだけで、それらが
ヒープ上で隣接している保証は無い。容量ぎりぎりまで使われた状態で回収すると、
<b>断片化した小さい穴</b>ばかりができうる — 実際の数字がそれを示している:
容量 500000 要素のうち使用 497920、残り 2080 要素<b>の合計</b>はあった
(要求は 2028→2048 要素に切り上げ) が、`AllocationArena` の空きブロックの
どれ一つとして 2048 要素に届かなかった。

### 7.3 修正: 実際に確保を試みず判定する `canAlloc`/`canFit`

`AllocationArena.alloc()` と<b>同じ分岐</b>を辿るが状態を変えない
`canAlloc(int size)` を新設し、`BasicAsyncGeometryManager.canFit(long bytes)`
(127 要素単位への切り上げも含めて同じ計算をする) を経由して
`VkHierarchicalScene.acceptGeometry` の判定をこちらに差し替えた:

```java
// Before (合計バイトのみ — 断片化を見ない)
if (this.geometry.getGeometryUsedBytes() + need > this.geometryCapacityBytes) { ... }

// After (実際に確保できるかを問い合わせる)
if (!this.geometry.canFit(need)) { ... }
```

これで回収ループの継続条件・最終判定の両方が「本当に入るか」を正しく
反映するようになった。回収の上限 (`RECLAIM_MAX_EVICTIONS=64`) に達しても
断片化が解消しない場合は、クラッシュではなく既存の
`geometryExhausted=true` (グレースフルな「止める」) 経路に落ちる。

### 7.4 検査 (装置なし、変異で確認済み)

`VkGeometryFragmentationTest` (3 件、`AllocationArena` と
`BasicAsyncGeometryManager` の両レベル):

| 検査 | 主張 |
|---|---|
| **`fragmentedFreeSpaceCanFailEvenWhenTheAggregateIsEnough`** | ⚠⚠ 本命。孤立した穴 4 個 (合計は足りる) で、`canFit` は false、実際の確保も同じ例外で失敗する。素朴な合計チェックはここで誤って「入る」と判定することも対照として示す |
| `adjacentFreesMergeAndBecomeFittable` | 隣接した空きは併合される。併合後は `canFit` が true になり、実際の確保も成功する |
| `allocationArenaCanAllocMatchesActualAllocOutcome` | `AllocationArena` 単体でも `canAlloc` の予測と実際の `alloc()` 結果が一致する |

**変異で確かめた**: `canAlloc` を「合計だけ見る」実装に戻すと、
3 件中 2 件が正しく落ちた。

### 7.5 まだ未確認のこと

- **修正後の実機での動作は未確認。** §6.1/§7.1 の手順をもう一度実機で
  回す必要がある。今回は容量をかなり切り詰めた設定 (`-Pvoxy5c4Quads=500000`)
  で踏んだので、同じ条件で確認すること
- 回収が断片化を<b>解消する</b>手段は無い (最古から消すだけで、併合を
  狙って隣接ノードを選ぶような工夫はしていない)。上限に達しても
  断片化が解消しなければ `geometryExhausted` に落ちて描画が止まる —
  クラッシュはしないが、実運用でどのくらいの頻度で起きるかは未測定
