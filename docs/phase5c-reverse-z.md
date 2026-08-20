# Phase 5c 準備 — 逆Z への切り替えと再検証

**状態**: 完了。**175 PASS / 1 SKIP、バリデーション指摘 0、`interopCompositeCheck` 全項目 PASS。**
**判断**: 案 A (切り替えて再検証)。両方サポート・境界で変換はいずれも却下。

---

## 1. なぜ切り替えたか

**Minecraft 26.2 は逆Zである** [確認済 —
`com.mojang.blaze3d.pipeline.DepthStencilState.DEFAULT` が
`CompareOp.GREATER_THAN_OR_EQUAL`]。
MC のフレームバッファに合成する以上、Voxy 側も揃えるしかない。

**Phase 4 は非逆Zで組み、非逆Zで検証していた。**
つまり<b>170 件のテストが検証していたのは本番で使う設定ではなかった</b>。

### 却下した案

| 案 | 却下の理由 |
|---|---|
| B: 両方サポート | テストが 2 倍。**使わないほうの経路が腐っても気づけない** (GL 版の `#ifdef` を却下したのと同じ理由) |
| C: 境界で深度を変換 | <b>「打ち消し合う変換」の温床</b>。5b の上下反転がまさにそれで、端点の一致では捕まらなかった |

---

## 2. 変更点 — 約束事を 1 箇所に集めた

新設 [`VkDepth`](../src/main/java/me/cortex/voxy/client/core/vk/VkDepth.java):

| 定数 | 逆Z (現行) | 非逆Z (捨てた) |
|---|---|---|
| `NEAR` 最も手前 | **1.0** | 0.0 |
| `FAR` 最も奥 | **0.0** | 1.0 |
| `CLEAR` 深度クリア | **0.0** | 1.0 |
| `COMPARE_OP` | **`GREATER_OR_EQUAL`** | `LESS_OR_EQUAL` |
| `BOUND_NEUTRAL` 深度境界の中立値 | **1.0** | 0.0 |
| シェーダ define | `USE_ZERO_ONE_DEPTH` + **`USE_REVERSE_Z`** | `USE_ZERO_ONE_DEPTH` のみ |

**切り替えフラグは置いていない。** 定数は全て逆Zの値である。
フラグがあると「使わないほうが腐る」問題が戻ってくるため。

### 移行した箇所 [確認済 — grep で全件]

| 箇所 | 変更 |
|---|---|
| `VkSceneUniform.perspective` | `m[10]` / `m[14]` で near と far を入れ替え (near→1, far→0) |
| `VkGraphicsPipeline.Builder` の既定 | `VkDepth.COMPARE_OP` |
| `VkTerrainRenderer` | define / 比較 / 深度クリア / `depthBound` の中立値 |
| `VkCullPass` | define / 比較 |
| `VkRenderTarget` の呼び出し側 (テスト含む) | 深度クリアを `VkDepth.CLEAR` に |
| `InteropCompositeCheck` | 非クリア判定と深度 PNG の正規化 (逆Zでは<b>明暗が反転する</b>) |

> ⚠ **`depthBound` の中立値の反転が最も危険だった。**
> `quads.frag` は `DEPTH_SCALAR_COMPARE(z, bound)` が真なら discard する。
> 逆Zでは `(z > bound)` なので中立値は最大値 (1.0) になる。
> 非逆Zの 0.0 を残すと<b>ほぼ全ての断片が discard され、地形が一切出ない</b>。

---

## 3. ⚠ 最大の発見 — 既存の 168 件は 1 件も落ちなかった

切り替え直後にテストを回したところ **168 PASS / 0 FAIL**。

**これは不具合ではなく逆Zの性質である。** 投影・クリア・比較を揃えて反転すれば
<b>同じ絵が出る</b>。変わるのは深度値の分布 (遠方の分解能) だけである。

**つまり Phase 4 のテストは深度の約束事を一度も検証していなかった。**
色だけを見ていたためで、これは 5b の上下反転と同じ型の穴である —
<b>端点 (色) の一致は途中 (深度) の正しさを保証しない</b>。

> 「テストが落ちなかったから移行は成功」と結論していたら、
> <b>切り替えが効いていない状態</b>と区別が付いていなかった。
> 落ちないこと自体を疑ったのが分かれ目だった。

### 塞いだ形

深度を観測可能にした (`VkRenderTarget.recordDepthReadback` / `depthAt` /
`closestDepth` / `depthCoverage`) うえで、
[`VkDepthConventionTest`](../src/test/java/me/cortex/voxy/vk/VkDepthConventionTest.java) を新設:

| 検査 | 何を捕まえるか |
|---|---|
| `constantsDescribeReverseZ` | 定数どうしの矛盾 |
| `theProjectionMapsNearToOneAndFarToZero` | **行列に値を通して** near→1 / far→0 と単調性を確認。定数だけ直して行列を直し忘れる形を捕まえる |
| `backgroundIsFarAndGeometryIsCloser` | 背景がクリア値、地形がそれより手前。**被覆画素数の下限**がクリア/比較の食い違い・**シェーダの define 漏れ**・境界中立値の取り違えを同時に捕まえる |
| `movingTheCameraCloserIncreasesTheClosestDepth` | **逆Zの向きそのもの**。投影を非逆Zのまま残すとこの不等号だけが反転する (絵は同じまま) |

`StencilMaskEquivalenceTest` に
`theReverseZBranchMatchesTheVulkanPath` を足し、
<b>モデルの逆Z側が `VkDepth` の値と一致すること</b>を要求した。
ずれると「モデルの上では等価だが実装は別の規約」という最も気づきにくい形で食い違う。

### この検査を空虚に満たす方法と、その塞ぎ方

| 空虚に満たす方法 | 塞ぎ方 |
|---|---|
| 定数どうしを見比べるだけ | 行列に値を通す / 実際にコンパイルされたシェーダの挙動を見る |
| 絵が出ることしか見ない | **深度値そのもの**を読み戻して比べる |
| 投影だけ / 比較だけが合っている | クリアと比較が食い違えば<b>何も描かれない</b> → 被覆画素数の下限で捕まる |

---

## 4. 再検証の結果

| | 結果 |
|---|---|
| JUnit | **175 PASS / 1 SKIP / 0 FAIL** (バリデーション有効、指摘 0) |
| `interopCompositeCheck` | **全 12 項目 PASS** (色・深度解決・`gl_FragDepth` 書き戻し・感度対照 C2/C4/C6/C7) |
| 基準画像 | 取り直し済み。**絵は切り替え前と同一** (逆Zの性質どおり) |
| 深度 PNG | 背景が黒 (クリア = 0.0)、地形が明るい (手前 = 大) に反転 |

5b の深度連鎖 (Vulkan の深度 → 解決パス → interop R32F → GL の `gl_FragDepth`) は
逆Zでもビット一致した。合成パスは `GL_ALWAYS` で `gl_FragDepth` を直接書くため、
深度比較の向きに依存しない [確認済 — `GlInteropCompositor`]。

---

## 5. 5c-1 に持ち込む注意

- **MC から来る `viewport.MVP` は GL 由来である。** そのまま渡すと
  <b>上下が反転する</b> ([`VkSceneUniform`] のクラス javadoc に既述)。
  逆Zは揃ったが、**Y の向きは別問題**として残っている
- `VkSceneUniform.perspective` は<b>テスト用</b>である。
  5c-1 では MC の行列を使うので、この関数は本番経路から外れる。
  ただし<b>テストの基準がこの関数に依存している</b>ので消さない
- MC の深度テクスチャの実フォーマット確認は 5c-1 の最初にやる [未検証]
