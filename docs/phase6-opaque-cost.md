# Phase 6 第二項目 — <b>`opaque` が支配的</b>

**状態**: 上流の構造を確定し、払い戻しを測る形を用意した。実機の測定待ち。

---

## 1. 観測

```
1920x1200 / drawn=1090:   opaque = 2.31 〜 3.49 ms   (合計 3.25 〜 4.14 ms の 7〜9 割)
3840x2400 / drawn=1785:   opaque = 4.03 〜 8.74 ms
```

⚠ **draw 統合とは別の話である。** 統合済みで draw は 7 本しかない。
疑うのは<b>断片処理</b>と<b>頂点処理</b>。

---

## 2. 上流の構造 — <b>深度境界は生きている</b>

```java
// VoxyRenderSystem:289-303
if (this.visbleSectionStream != null && !disableSodiumChunkRender() && !irisShadowActive()) {
    this.boundOutlineRenderer.render(viewport, store);      // depthBoundingBuffer を書く
} else {
    viewport.depthBoundingBuffer.clear(inverseClearDepth()); // ← 中立 (フォールバック)
}
```

`BoundRenderer` は<b>バニラの読み込み済みチャンクの AABB の裏面</b>を
{@code furtherDepthCompare} で描く [確認済 — `BoundRenderer.renderInner`]。
結果は<b>画素ごとの「バニラが地形を持っている領域の奥側」</b>になる。

`MDICSectionRenderer:189` がそれを {@code quads.frag} の {@code depthTex} に張り、

```glsl
// quads.frag:164
if (DEPTH_SCALAR_COMPARE(gl_FragCoord.z, texelFetch(depthTex, ivec2(gl_FragCoord.xy), 0).r)) discard;
```

**バニラが地形を持っている領域では LoD の断片を捨てる。**

### 2.1 Vulkan 経路の現状は<b>上流のフォールバック分岐と同じ</b>

`VkTerrainRenderer` の {@code depthBound} は {@code BOUND_NEUTRAL} で埋めたまま。
**上流の `else` 側と同じ状態**であって、それ自体は誤りではない。
ただし<b>近景の断片を 1 つも落としていない</b>。

⚠ 絵は<b>GL 合成の深度テスト</b>が MC の地形を勝たせるので大きくは崩れない。
境界の役目は主に:
1. <b>断片処理を減らす</b> (第二項目そのもの)
2. 深度が拮抗する画素での<b>Z ファイティングを消す</b>

---

## 3. ⚠ 実装の前に払い戻しを測る [規約 22]

境界を移植すると `BoundRenderer` + `IBoundStore` 系 (**677 行**) が要る。
**先に「どれだけ効くか」を知る。**

### 3.1 測り方 — <b>境界を定数で掃引する</b>

逆Zなので <b>1.0 = 誰も落とさない</b>、<b>0.0 = 全部落とす</b>。
Voxy の断片は {@code nearestDepth} が **0.0008 〜 0.0014** に出ている。

| 境界 | 意味 |
|---|---|
| `1.0` | 中立。**現状** |
| `0.0014` / `0.0010` / `0.0007` | 手前から順に落としていく |
| **`0.0`** | <b>全部落とす</b> = <b>断片処理を止めたときの床</b> |

> **`0.0` の測定が要である。** {@code opaque} のうち
> <b>どれだけが断片処理か</b>を上限として知ることができる。
> 床が高ければ、境界を移植しても<b>払い戻しは小さい</b>。

⚠ **これは境界の代用ではない。** 本番の境界は画素ごとに違う。
<b>払い戻しの上限を測るためだけ</b>のものである。

### 3.2 走らせ方

```bash
./gradlew runClient -Pvoxy5c4=on -Pvoxy5c5=cull -Pvoxy6Bound=sweep
```

**カメラを止めて、`meshed` が増えなくなるまで待つ。**
120 フレームごとに境界が変わり、`[5c-4c] GPU ... bound=<値>` が同じ行に出る。

⚠ 境界値を<b>同じ行に</b>出しているのは、別々の行だと
「どの値のときの測定か」を突き合わせられないためである。

---

## 4. ⚠ HSR と discard は同時に考える必要がある

Apple GPU の隠面消去 (HSR) は<b>断片シェーダが {@code discard} を含むと無効化される</b>
ことがある。現状は境界が中立なので:

- {@code discard} 自体は<b>シェーダに書かれている</b> (コンパイル時に消えない)
- したがって HSR は<b>すでに無効化されている可能性がある</b>

> ⚠ **「境界を有効にすれば速くなる」と単純に言えない。**
> 落とす断片は減るが、HSR の状態は変わらない (discard は元から在る)。
> <b>逆に、discard をシェーダから消せる構成があれば HSR が効く</b>かもしれない。
> これは別項目 (HSR と discard の分離) として残っている。

**§3 の掃引は HSR の状態を変えない** (シェーダは同じ、境界の値だけが変わる) ので、
<b>測定として綺麗である</b>。
