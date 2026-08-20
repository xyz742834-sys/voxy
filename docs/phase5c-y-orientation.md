# Phase 5c 準備 — Y の向きを GL 規約に統一する

**状態**: 完了。**175 PASS / 1 SKIP、`interopCompositeCheck` 全 12 項目 PASS。**
**判断**: 案 B (GL 規約のまま通す)。**経路上の Y 反転は 0 個。**

---

## 1. 何を決めたか

Vulkan のクリップ空間は Y が下向き、GL は上向きである。
どちらに合わせるかで、経路上に必要な反転の数が変わる。

| | 案 A: Vulkan 規約 | **案 B: GL 規約 (採用)** |
|---|---|---|
| 投影行列 | Y 反転を掛ける | **掛けない** (`m[5] = +f`) |
| framebuffer の 0 行目 | 絵の上端 | **絵の下端** (GL と同じ) |
| 色の合成 (GL) | 反転が要る | **反転しない** |
| 深度の受け渡し | 反転が要る | **反転しない** |
| **Y 反転の総数** | **3 箇所** | **0 箇所** |

**反転が 1 つも無ければ「打ち消し合う変換」が原理的に起きない。**
5b で機械的検査 10 項目が全て通ってしまったのは、
合成の反転と読み戻しの反転が<b>打ち消し合った</b>ためである
[docs/phase5b-composite.md §6]。

> 5b の実装が合成側で反転していたのは、Vulkan 側が Vulkan 規約で描いていたからで、
> **反転を要する設計を選んだこと自体が誤りだった。**
> 本番の行列は MC 由来 (`viewport.MVP`) で GL 規約なので、そちらに寄せるのが自然でもある。

---

## 2. 変更点

| 箇所 | 変更 |
|---|---|
| `VkSceneUniform.perspective` | `m[5] = -f` → **`+f`**。Vulkan の Y 反転を入れない |
| `GlInteropCompositor` | `height-1-y` の読み替えを**削除** |
| `GlInteropCompositor.Defect` | `NOT_FLIPPED` → **`FLIPPED`** (反転すること自体が誤りになった) |
| `InteropCompositeCheck` の比較 | 反転して比べる → **そのまま添字で比べる** |
| `InteropCompositeCheck` の C7 | 意味が反転 (「直接比較で捕まる」「反転比較なら素通り」) |
| `VkRenderTarget.writePng` / `writeDiffPng` | **反転を追加** (0 行目が絵の下端になったため) |

> PNG 側の反転は<b>表示のための反転</b>であって経路上の変換ではない。
> BufferedImage は 0 行目が上端なので、そこで一度だけ入れ替える。

**`VkSceneUniform.perspective` の反転を落としたことが重要である。**
落とさないと、逆Zで直したのと同じ
「テストが本番でない設定を検証している」状態が Y 軸で再発していた。

---

## 3. ⚠ また落ちなかった — 既存 173 件は Y の向きにも無反応

切り替え後にテストを回して **173 PASS / 0 FAIL**。逆Zのときと同じである。

Phase 4 のテストは<b>描いた結果どうしを比べている</b>ので、
全体が一様に反転しても差が出ない。**2 度続けて同じ穴に当たった。**

そこで [`VkOrientationTest`](../src/test/java/me/cortex/voxy/vk/VkOrientationTest.java) を新設した。
ジオメトリの世界座標を CPU で投影して<b>出るべき行を予測</b>し、
実際に塗られた画素の重心と突き合わせる。

---

## 4. ⚠⚠ 対照実験の失敗例 11 例目 — **自分の検査の中で打ち消しが起きた**

`VkOrientationTest` を書いた後、対照として `m[5]` を `-f` に戻して走らせた。

```
VkOrientationTest > geometryLandsOnThePredictedRow()  PASSED   ← ★ 通ってしまった
VkOrientationTest > discriminatingViewpoint()         FAILED
```

**予測 (`VkSceneUniform.project`) と実装 (GPU) が同じ行列を共有していた。**
行列の Y 符号を反転すると<b>予測も一緒に反転</b>し、両者は再び一致する。
CPU と GPU という別経路を使っていても、<b>入力を共有していれば打ち消しは起きる</b>。

> これは 5b の 10 例目と同じ型が、**検査の内側**で再発したものである。
> 「予測と実装が独立」と書いたつもりだったが、独立していたのは<b>計算の経路</b>だけで、
> <b>規約そのもの</b>は共有していた。

### 塞ぎ方 — 行列に依存しない事実を別に主張する

```java
// 「ジオメトリより上を見れば、ジオメトリは画面の下半分に出る」
// これは投影行列の Y 符号とは無関係に成り立つ物理的な事実である
assertTrue(cov.centroidRow() < H / 2.0, ...);
```

この 1 行を足した後、対照は**両方とも落ちる**ようになった:

```
VkOrientationTest > geometryLandsOnThePredictedRow()  FAILED
VkOrientationTest > discriminatingViewpoint()         FAILED
```

### 運用に足す規則

> **「予測 vs 実装」型の検査では、予測が実装と<b>同じ規約</b>を参照していないか確かめる。**
> 計算経路が別 (CPU / GPU) でも、規約を共有していれば規約の誤りは打ち消し合う。
> 少なくとも 1 つは<b>規約に依存しない外部の事実</b>を主張すること。

---

## 5. 2 本の役割分担

| 検査 | 捕まえるもの | GPU |
|---|---|---|
| `discriminatingViewpoint` | **行列の Y 規約**。予測行と鏡像行が十分離れていることも先に確かめる | 不要 |
| `geometryLandsOnThePredictedRow` | **行列とラスタライザの食い違い** (viewport / scissor / 読み戻しの行順)、<b>および</b>実測が下半分に来ること | 要る |

`interopCompositeCheck` の C7 は<b>合成経路</b>の反転を見張る。
5c-1 では<b>深度経路</b>にも独立した向き検査を置く (5c-1c) —
色と深度で別々に確かめれば、片方の反転が他方を打ち消せない。
