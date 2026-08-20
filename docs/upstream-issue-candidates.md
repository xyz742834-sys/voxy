# 上流 (Voxy 本家) への報告候補

このフォークで見つけた、**フォーク固有ではない**問題の一覧。
Vulkan 移植とは無関係に本家の利用者が踏むものだけをここに置く。

---

## 1. MC 26.2 で Vulkan バックエンドを選ぶと起動時クラッシュする — **修正済み (このフォーク)**

**影響**: MC 26.2 で `preferredGraphicsBackend:"vulkan"` を選んだ<b>全ユーザー</b>。
Voxy が入っているとゲームが起動しない。

**症状** [確認済 — 実機]:

```
java.lang.ExceptionInInitializerError
  at me.cortex.voxy.client.VoxyClient.initVoxyClient(VoxyClient.java:24)
  at com.mojang.blaze3d.systems.RenderSystem.handler$bbp000$voxy$injectInit
Caused by: java.lang.IllegalStateException: No GLCapabilities instance set for the current thread.
  at org.lwjgl.opengl.GL.getCapabilities(GL.java:273)
  at me.cortex.voxy.client.core.gl.Capabilities.<init>(Capabilities.java:58)
  at me.cortex.voxy.client.core.gl.Capabilities.<clinit>(Capabilities.java:36)
```

**原因**: `Capabilities` の `static final INSTANCE` 初期化が
`GL.getCapabilities()` を無条件に呼ぶ。MC が Vulkan バックエンドを選ぶと
GL コンテキストが存在しないので例外になり、`static` 初期化中の例外なので
`ExceptionInInitializerError` に化けて<b>ゲームごと落ちる</b>。

**なぜ自己無効化が効かないか**: Voxy は
`compute && indirectParameters` で `systemSupported` を判定して自己無効化する仕組みを
持っているが、<b>その判定より前に clinit が走る</b>。

**このフォークでの修正** (`Capabilities.java`):
GL コンテキストが取れなければ全機能 false の `Capabilities` を返し、
既存の `systemSupported` 判定に自己無効化を委ねる。
Phase 1 で `IrisUtil` をスタブ化したのと同じ形。

回帰テスト: `CapabilitiesWithoutGlTest` (素の JVM 4 件)。
**Gradle の test worker には GL コンテキストが無いので、再現条件そのもので走る。**

> 本家に出すときは Vulkan 移植の話を持ち込まず、
> 「MC 26.2 の Vulkan バックエンドで起動時クラッシュする」単体の修正として出せる。

---

## 2. `DebugRenderer.debugShader` はコンパイルできない — **未修正**

**影響**: 現状は顕在化しない (下記)。

**原因** [確認済 — `docs/phase2-binding-audit.md` §6]:

- `node.glsl:2` の `layout(binding = NODE_DATA_BINDING, ...)` に `#ifdef` ガードが無い
- `node_outline.vert:13` が `node.glsl` を import しているが、
  定義しているのは `NODE_DATA_INDEX` であって `NODE_DATA_BINDING` ではない
- `DebugRenderer` 側も `NODE_DATA_BINDING` を define していない

→ `NODE_DATA_BINDING` が未定義トークンのまま残り、整数定数として解釈できずコンパイルエラー。

**顕在化していない理由**: `DebugRenderer` は<b>どこからもインスタンス化されていない</b>
[確認済 — `grep -rn 'DebugRenderer'` が自ファイル以外 0 件]。

**このフォークでの扱い**: 修正していない。Vulkan 移植の対象外 (デバッグ機能)。
移植時に「動くはずのものが動かない」と誤認しないための記録として残している。

---

## 記録の方針

ここに載せるのは<b>本家の利用者が踏むもの</b>だけである。
macOS/Vulkan フォーク固有の判断・設計は各 phase の doc に置く。
