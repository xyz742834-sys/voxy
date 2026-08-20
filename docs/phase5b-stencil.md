# Phase 5b 前提調査 — ステンシルマスクは深度で置き換えられるか

**状態**: 調査完了。**案 A で進めてよい。**
**検査**: [`StencilMaskEquivalenceTest`](../src/test/java/me/cortex/voxy/vk/StencilMaskEquivalenceTest.java) (素の JVM、5 件)
**対象**: `phase5-proposal.md` §3.1 / §7 の質問 1

---

## 1. 結論

**`initDepthStencil` のステンシルマスクは、同じパスが書く深度で完全に置き換えられる。**
唯一の例外は<b>断片の深度がちょうど NEAR のとき</b>で、その場合<b>描画が増える側</b>に外れる。

| | 結論 |
|---|---|
| Vulkan 側にステンシルが要るか | **要らない** |
| 提案書の見立て (`depthTex` が同じ役割) | **見立ての結論は正しいが、理由が違う** (§4) |
| interop で運ぶもの | **深度だけ**。IOSurface に stencil aspect は無い [確認済 — Phase 0 §8.2] |

---

## 2. 実際に何が起きているか [確認済 — 実物を読んだ]

`AbstractRenderPipeline.initDepthStencil` + `post/setup_stencil_depth.frag` は、
Voxy 自前の d24s8 フレームバッファに対して<b>1 パスで 2 つのマスクを同時に作っている</b>。

```java
// 1. クリア: 深度 = FAR, ステンシル = 1
glClearNamedFramebufferfi(targetFb, GL_DEPTH_STENCIL, 0, properties.clearDepth(), 1);

// 2. 通った断片は深度に gl_FragDepth を、ステンシルに ref=0 を書く
glDepthFunc(GL_ALWAYS);
glStencilOp(GL_KEEP, GL_KEEP, GL_REPLACE);
glStencilFunc(GL_ALWAYS, 0, 0xFF);
```

```glsl
// setup_stencil_depth.frag
gl_FragDepth = NEAR;                              // ★ MC の深度ではなく NEAR
if (texture(depthTex, UV*scaleFactor).r == FAR) discard;   // MC が何も描いていない画素は捨てる
```

```java
// 3. 以降の地形描画に掛かる条件
glDepthFunc(this.properties.closerEqualDepthCompare());   // LEQUAL / GEQUAL
glStencilFunc(GL_EQUAL, 1, 0xFF);
```

結果として 1 画素の状態は 2 通りしかない:

| | ステンシル | 深度 | ステンシル判定 | 深度判定 |
|---|---|---|---|---|
| **MC 地形あり** | 0 | **NEAR** | 落とす | closerEqual(z, NEAR) → <b>ちょうど NEAR 以外は落とす</b> |
| **MC 地形なし** | 1 | FAR (クリア値) | 通す | closerEqual(z, FAR) → <b>常に通す</b> |

**NEAR は最も手前の深度値である** (逆Z なら 1.0、非逆Z なら 0.0)。
そこに closer-or-equal で比較する以上、通るのは<b>ちょうど NEAR の断片だけ</b>になる。

→ <b>ステンシルと深度は同じ画素集合を落としている。</b>

---

## 3. 検証

「読んで納得した」で終わらせず、両方のマスクを<b>独立した 2 つの関数</b>として実装し、
全組み合わせで突き合わせた。

| 検査 | 内容 |
|---|---|
| `stencilAndDepthMaskAgreeExceptExactlyAtNear` | 逆Z / 非逆Z × 地形あり / なし × 深度 9 通りで、不一致が「ちょうど NEAR」の 2 件だけ |
| `theTieCaseDrawsMoreWithoutStencil` | 唯一の不一致で、**深度のほうが緩い** = ステンシルを外すと描画が増える側に外れる |
| `everythingIsDrawnWhereMinecraftHasNoTerrain` | 地形が無い画素は<b>どの深度でも両方が通す</b> (取りこぼしが無い) |

### この検査を空虚に満たす方法と、その塞ぎ方

| 空虚に満たす方法 | 塞ぎ方 |
|---|---|
| 両者を同じ式から計算する | `stencilPasses` はステンシル値だけ、`depthPasses` は深度値だけを見る |
| 比べる深度値が薄い | NEAR / FAR ちょうどと、その両隣 (ulp) を必ず含める |
| **そもそも不一致を検出できない検査** | わざと壊した 2 実装で<b>不一致が出ること</b>を要求する (下記) |

**対照 1 — `detectsAStrictDepthCompare`**
深度比較を closer-or-equal ではなく strict にすると等価性が壊れることを示す。
`EQUAL` が効いていることが等価性の要であり、ここが自明でないことの記録。

**対照 2 — `detectsWritingTheMcDepthInstead`**
`gl_FragDepth = NEAR` ではなく<b>MC の深度そのもの</b>を書いていたら壊れることを示す。
どちらの読み方も自然に見えるので、<b>取り違えたまま案 A を採ると静かに壊れる</b>。
壊れ方は「MC の地形より手前にあるものが描かれてしまう」形になる。

---

## 4. ⚠ 提案書 §3.1 の見立ては、結論は合っているが理由が違う

提案書はこう書いていた:

> 現行の `depthTex` の仕組みが既に B であることに注目したい。
> `quads.frag` は `texelFetch(depthTex, ...)` と比べて手前なら discard する。
> つまり<b>ステンシルマスクの役割は深度境界バッファが既に担っている</b>可能性がある [推測]。

**`depthTex` (binding 2) は別物である** [確認済 — 実物を読んだ]。

| | 何か | 何をするか |
|---|---|---|
| `depthTex` (`quads.frag` binding 2) | <b>深度境界バッファ</b>。遮蔽カリング / 前段が書く | 境界より手前の断片を discard |
| Voxy の深度アタッチメント | `initDepthStencil` が NEAR / FAR で埋める | 深度テストで MC 地形の画素を落とす |

ステンシルを置き換えているのは<b>後者</b>である。
`depthTex` はカリングの仕組みで、MC 地形マスクとは無関係に動いている。

**結論 (案 A で足りる) は変わらないが、依存している事実が違う。**
案 A が成立する条件は「`depthTex` が同じ役割を担っていること」ではなく、
**「`initDepthStencil` が MC 地形の画素に NEAR を書くこと」**である。
対照 2 が守っているのはまさにこの条件である。

---

## 5. Phase 5 の実装への含意

interop で運ぶのは<b>深度だけ</b>でよい。Vulkan 側での組み立ては 2 通りある:

| 案 | GL 側 | Vulkan 側 |
|---|---|---|
| **(i)** | `initDepthStencil` 相当を GL で実行し、NEAR/FAR 化した深度を R32F に書く | 受け取った R32F を深度アタッチメントに載せる |
| **(ii)** | MC の生の深度を R32F へコピーするだけ | Vulkan 側で NEAR/FAR 化する |

**(ii) を推す。** マスクの生成規則が Vulkan 側の 1 箇所に集まり、
GL 側は「深度を運ぶだけ」になる。ただし<b>5b の範囲外</b> (5b は合成パスまで) なので、
5c で `VoxyRenderSystem` に繋ぐときに決める。

---

## 6. 残っている [未検証]

- **ちょうど NEAR の断片が実際に発生するか。** 幾何的には「近平面上の地形」であり、
  LoD 地形は遠景なので通常は起きない [推測]。実データで確かめる手段が無い
- **SSAO はステンシルテストが有効なまま走っている** [確認済 — `SSAO` はステンシルに触らない]。
  同じ深度アタッチメントを共有しているので同じ等価性が成り立つはずだが、
  SSAO が深度テストを有効にしているかは確認していない。
  提案書 §3.1 が「SSAO は GL のまま？」としている箇所と一緒に 5c で決める
- **MC 側の深度が `FAR` ちょうどで「何も描いていない」を表す前提。**
  `setup_stencil_depth.frag` がこれに依存している [確認済 — コード]。
  逆Z の MC で本当に 0.0 ちょうどが入るかは実機でしか確かめられない
