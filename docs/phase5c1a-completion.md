# Phase 5c-1a 完了 — バックエンド選択の配線 (何も描かない)

**状態**: 完了。**MC が Vulkan バックエンド選択のもとで正常に起動する。絵は一切変わらない。**
**前提**: 逆Z ([phase5c-reverse-z.md](phase5c-reverse-z.md))、
Y 規約 ([phase5c-y-orientation.md](phase5c-y-orientation.md)) の統一が完了していること。

---

## 1. この段で何をしたか

`docs/phase5c-plan.md` §9 の 5c-1a。**描画は一切繋いでいない。**

```
[Voxy] OpenGL does not meet Voxy's requirements; using the Vulkan backend.
[Minecraft] Using graphics backend OpenGL, using drivers: 4.1 Metal - 90.5
[Minecraft] Sound engine started
```

[確認済 — 実機で起動]

| 確認できたこと | 確認していないこと |
|---|---|
| バックエンド選択が実機で効く | 絵に関すること全部 |
| `VkContext` が MC の Render thread で初期化できる | ワールド入退出・リサイズでのリーク (要手動) |
| **GL 専用の初期化を踏まない** (§3) | 合成・深度・地形 |
| 起動が最後まで通り、エラーが出ない | |

---

## 2. バックエンド選択 — `VkContext.init()` の成功を条件にする

```java
boolean glSupported = compute && indirectParameters && !hasBrokenDepthSampler;

if (glSupported)            BACKEND = Backend.OPENGL;   // GL が使えるなら GL を優先
else if (vulkanIsUsable())  BACKEND = Backend.VULKAN;   // 足りなければ Vulkan
else                        BACKEND = null;             // どちらも駄目 -> 自己無効化

boolean systemSupported = BACKEND != null;
```

`vulkanIsUsable()` は **`VkContext.init()` を実際に呼んで成功したか**で判定する。
capability の有無だけで判定すると、拡張はあるのに `vkCreateDevice` が失敗する環境で
<b>「対応しているのに動かない」</b>状態になる。

`VkContext` はサーフェスもウィンドウも持たないので、
`RenderSystem.initRenderer` の中 (Render thread) で初期化してよい
[確認済 — 素の JVM のテストが同じことをしている / 実機でも成功]。

**GL を優先する順序**にしたのは、Vulkan を「GL が足りないときの受け皿」と位置づけるためで、
将来 GL 4.6 環境で動かす余地を残す。

---

## 3. ⚠ 踏んだ事故 — GL 専用の初期化で JVM ごと abort した

`systemSupported` が true になった瞬間、それまで通らなかった経路に入って落ちた:

```
FATAL ERROR in native method: Thread[#3,Render thread,10,main]:
  No context is current or a function that is not available in the current context was called.
  The JVM will abort execution.
	at org.lwjgl.opengl.GL45C.nglCreateBuffers(Native Method)
	at me.cortex.voxy.client.core.gl.GlBuffer.<init>
	at me.cortex.voxy.client.core.rendering.util.SharedIndexBuffer.<clinit>
	at me.cortex.voxy.client.VoxyClient.initVoxyClient
```

`SharedIndexBuffer` が `glCreateBuffers` (GL 4.5 DSA) を使う。
Apple の GL 4.1 には無いので、**例外ではなく JVM の abort** になる。

> ⚠ **「Voxy を有効にする」ことは「GL 専用の初期化を踏む」ことでもある。**
> 自己無効化していた間は隠れていた経路が、有効化した瞬間に一斉に開く。
> 5c-1a の価値の半分はここにあった — <b>描画を繋ぐ前にこれを踏めた</b>。

**対処**: GL 専用の先行確保を `BACKEND == OPENGL` で囲った。
Vulkan 経路では `VkQuadIndexBuffer` が同じ役割を持つ。

`subgroup` の警告も GL 経路に閉じた
(Vulkan 側は `VkContext.hasSubgroup` を別に持っている)。

---

## 4. 描画系は作らない

`MixinLevelRenderer.voxy$createRenderer` に

```java
if (VoxyClient.backend() == VoxyClient.Backend.VULKAN) {
    Logger.info("Not creating renderer: the Vulkan backend is selected but the render "
        + "path is not wired yet (Phase 5c-1a). Minecraft renders unchanged.");
    return;
}
```

を置いた。`VoxyRenderSystem` のコンストラクタは `glFinish` / `glGetIntegeri` /
`RenderResourceReuse` の GL バッファを直接叩くので、
**作れば必ず落ちる**。作らないことで「MC の絵が一切変わらない」状態を保つ。

この行が 5c-1b 以降で置き換わっていく。

---

## 5. 5c-1a の対照 — 「絵が変わらない」ことをどう確かめるか

**この段の合格条件は「何も起きないこと」である。** 検査としては弱い形なので、
何をもって合格とするかを決めておく。

| 空虚に満たす方法 | 塞ぎ方 |
|---|---|
| Voxy がそもそも無効化されている | ログに **`using the Vulkan backend`** が出ることを要求する。無効なら `unsupported on your system` になる |
| 起動しただけで、絵は見ていない | Voxy 無効時のスクリーンショットと**突き合わせる** (手動) |
| ワールドに入っていない | **ワールド入退出まで行う** (手動)。`Not creating renderer` が出ることを確認 |

**手動確認の項目** (MC 起動が要る層。何を見るか先に書き出す運用に従う):

1. タイトル画面が正常に出る
2. ログに `using the Vulkan backend` が出ている
3. ワールドに入る → `Not creating renderer: ... not wired yet` が出る
4. 地形・空・UI が Voxy 無効時と同じに見える
5. ウィンドウをリサイズしても落ちない
6. ワールドを出て入り直しても落ちない

> 1・2・5 相当は起動ログで確認済み。**3・4・6 はワールドに入る操作が要るので手動。**

---

## 6. 次の段 (5c-1b) に持ち込むもの

- **非対称なテストパターン**を interop 色に描いて合成する。
  単色では上下反転に無反応なので使わない
- `VoxyRenderSystem` を作らずに合成だけ行う経路が要る。
  現状の `runPipeline` は GL 前提なので、<b>5c-1b では別経路</b>を通す
- MC の framebuffer は **D32F・ステンシル無し** [確認済 — phase5c-plan.md §8.1]

---

## 7. 【監査】GL 専用の静的初期化はほかにもある

§3 の事故を受けて、**`static` 初期化子で GL を呼ぶ箇所**を洗い出した
[確認済 — grep で全件 + 各エントリポイントの GL バージョン確認]。

| クラス | 静的フィールド | 呼ぶ関数 | GL 版 | 判定 |
|---|---|---|---|---|
| `SharedIndexBuffer` | `INSTANCE` | `glCreateBuffers` | **4.5** | **踏んだ。対処済み** |
| `FullscreenBlit` | `EMPTY_VAO` | `glCreateVertexArrays` | **4.5** | ⚠ **未踏の地雷** |
| `UploadStream` | `INSTANCE` | `glCreateBuffers` (via `GlPersistentMappedBuffer`) | **4.5** | ⚠ **未踏の地雷** |
| `DownloadStream` | `INSTANCE` | `glCreateBuffers` | **4.5** | ⚠ **未踏の地雷** |
| **`GlFramebuffer`** | (インスタンス生成時) | `glCreateFramebuffers` / `glNamedFramebuffer*` | **4.5** | ⚠ **5c-1b で踏みかけた**。`GlScratchFramebuffer` で回避 |
| **`ModelStore`** | (インスタンス生成時) | `glCreateTextures` / `glTextureStorage2D` | **4.5** | ⚠ **5 つ目**。`ModelUploadTarget` で回避 [5c-2b] |
| **`SoftwareModelTextureBakery`** | (`setupTexture()` の中) | `glGetTextureImage` | **4.5** | ⚠ **6 つ目。5c-2b で踏んだ**。非 DSA に置換済み |
| `AbstractRenderPipeline` | `DEPTH_SAMPLER` | `glGenSamplers` | 3.3 | 安全 |
| `GlVertexArray` | `STATIC_VAO` | `glGenVertexArrays` | 3.0 | 安全 |
| `GPUTiming` | `INSTANCE` | `glGenQueries` | 1.5 | 安全 |

**判定の基準は「Apple の GL 4.1 に存在するか」である。**
存在しない関数を呼ぶと LWJGL の関数ポインタが null で、
<b>例外ではなく JVM の abort</b> になる (スタックトレースは出るが復帰できない)。

> ⚠ **DSA (`glCreate*`) は全て GL 4.5 である。**
> `glGen*` (旧来の生成) は古いので安全、という切り分けになる。
> Voxy は DSA を全面的に使っているので、<b>GL 側のクラスに触れた瞬間に落ちる</b>と考えてよい。

> ⚠⚠ **追記 (5c-2b): この一覧は「静的初期化子」で洗ったので、<b>取りこぼしがあった</b>。**
> 6 つ目の `SoftwareModelTextureBakery.setupTexture()` は<b>生成ではなく普通のメソッド</b>の中の
> DSA 1 行で、<b>静的初期化子を見る grep では出ない</b>。
> **今後は「DSA 関数名」で洗うこと** — `glCreate*` / `glNamed*` に加えて
> `glGetTextureImage` / `glTextureSubImage*` / `glTextureParameter*` /
> `glBindTextureUnit` / `glGetNamedBufferSubData` / `glCopyNamedBufferSubData` /
> `glClearNamed*` / `glTextureStorage*` / `glGenerateTextureMipmap` も DSA である。

> ⚠ **落ち方も想定と違った。** 6 つ目は<b>JVM の abort ではなく `NullPointerException`</b>
> (`org.lwjgl.system.Checks.check`) で、MC のクラッシュレポートとして出た
> [確認済 — run/crash-reports/crash-2026-08-20_12.51.04-client.txt]。
> **なぜ abort ではなく例外だったのかは [未検証]。**
> 実務上の含意はこちら: <b>「hs_err が出ていない」は「DSA を踏んでいない」の証拠にならない</b>
> [規約 11]。crash-reports のほうも見ること。

### Vulkan 経路で守るべきこと

- **`FullscreenBlit` を使わない。** 合成には `GlInteropCompositor` を使う

> ⚠ **追記 (Phase 5c-2b)**: `ModelStore` も同じ地雷である —
> `GlTexture().store(...)` = `glCreateTextures` / `glTextureStorage2D` (GL 4.5)。
> Vulkan 経路では<b>作らせないこと</b> [docs/phase5c2b-boundary.md §6]。

  — こちらは `glGenVertexArrays` (3.0) と GLSL 410 だけで書いてあり、
  <b>意図的に GL 4.1 の範囲に収めてある</b> [確認済 — 全エントリポイントを確認]
- **`UploadStream` / `DownloadStream` に触れない。** Vulkan 側は
  `VkUploadStream` / `VkDownloadStream` を持っている
- **`AbstractRenderPipeline` / `VoxyRenderSystem` を構築しない** (5c-1a で対処済み)

**5c-1b 以降は「GL のクラスを import した時点で疑う」という運用にする。**

> この運用は<b>初回から効いた</b> — 5c-1b で合成先の FBO が要ると分かったとき、
> `GlFramebuffer` を使わずに済んだ [docs/phase5c1b-completion.md §8.4]。
