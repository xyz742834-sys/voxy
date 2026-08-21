# Phase 5c 着手前 — MC 26.2 の Vulkan レンダラは使えるか

**状態**: 調査のみ。実装には入っていない。
**日付**: 2026-08-17
**環境**: Apple M4 Pro / macOS / MC 26.2 / Fabric / Sodium 0.9.2-alpha.3 / LWJGL 3.4.1

---

## 1. 結論

| # | 問い | 答え |
|---|---|---|
| 1 | macOS / Apple Silicon で MC 26.2 の Vulkan レンダラが起動するか | **起動する [確認済 — 実機]** |
| 1b | `VK_KHR_push_descriptor` が MoltenVK で使えるか | **使える [確認済 — 実機で列挙 + MC が実際に有効化]** |
| 2 | Sodium が MC の Vulkan バックエンドで動くか | ✅ **動く。地形が描かれる [確認済 — 実機 2026-08-21]** (§4.4) |
| 3 | MC の `VkDevice` に mod からアクセスできるか | **できる [確認済 — public メソッド + 既存の前例]** |

**判定**: ⚠ **3 つとも通った** (2026-08-21 に §4.4 で確定)。

> ⚠ **副産物として、判断より重い問題が 1 つ見つかった。**
> **Voxy は MC を Vulkan にした瞬間に起動時クラッシュしていた** (§5)。
> デバイス共有をするかどうかとは無関係な実害バグで、**修正済み**。

---

## 2. 問い 1 — MC の Vulkan レンダラは macOS で起動する

### 2.1 設定の場所 [確認済 — 実機]

**`options.txt` のキーは `graphicsApi` ではなく `preferredGraphicsBackend`** である。

```
preferredGraphicsBackend:"vulkan"      ← 値は引用符付き (Codec 由来)
```

`graphicsApi` は<b>翻訳キー側の名前</b> (`options.graphicsApi`) であって、
永続化キーではない。`graphicsApi:vulkan` と書いても<b>黙って無視され、
次回の保存で消える</b>。実際に一度これで空振りした。

### 2.2 起動した証拠 [確認済 — 実機のクラッシュレポート]

```
Graphics Device: Apple M4 Pro
Graphics Backend: Vulkan
Graphics Vendor: APPLE
Graphics Drivers: 1.2.334 MoltenVK 1.4.2
Graphics Device Extensions: VK_KHR_portability_subset (D), VK_KHR_synchronization2 (D),
  VK_EXT_metal_surface (I), VK_EXT_vertex_attribute_divisor (D), VK_KHR_swapchain (D),
  VK_KHR_surface (I), VK_KHR_push_descriptor (D), VK_EXT_debug_utils (I),
  VK_KHR_dynamic_rendering (D)
Graphics Device Type: INTEGRATED
Graphics Device Features: DeviceFeatures[shaderDrawParameters=true,
  multiDrawDirectInterleaved=false, multiDrawDirectSeparate=false, multiDrawIndirect=true,
  drawIndirect=true, nonZeroFirstInstance=true, persistentMapping=true]
```

**`VkDevice` の生成まで到達している。OpenGL へのフォールバックではない。**
`VK_KHR_push_descriptor (D)` が<b>実際に有効化された拡張として列挙されている</b>。

### 2.3 ⚠ 「Device is suitable」はフォールバックの否定にならない

```
[DEBUG] (com.mojang.blaze3d.vulkan.VulkanBackend) Device [Apple M4 Pro] is suitable
```

この行は **OpenGL で起動したときにも出る** [確認済 — 既定設定での実行ログ]。
`Minecraft` のコンストラクタが警告表示用に `VulkanBackend.checkBackendAvailable()` を
無条件に呼ぶためである。

**どちらが使われたかは以下でしか判定できない**:
- `Using graphics backend {OpenGL|Vulkan}, using drivers: ...` (INFO)
- クラッシュレポート / F3 の `Graphics Backend:`

### 2.4 MC が要求するもの vs MoltenVK [確認済 — バイトコードと実機の突き合わせ]

`VulkanBackend.REQUIRED_DEVICE_EXTENSIONS` (5 個、欠けたら失敗):

| 拡張 | LWJGL 同梱 MoltenVK (1.2.334) | brew ローダー (1.4.357) |
|---|---|---|
| `VK_KHR_dynamic_rendering` | YES | YES |
| **`VK_KHR_push_descriptor`** | **YES** | **YES (rev 2)** |
| `VK_KHR_synchronization2` | YES | YES |
| `VK_EXT_vertex_attribute_divisor` | YES | YES |
| `VK_KHR_swapchain` | YES | YES |

`REQUIRED_DEVICE_FEATURES` (9 個) も<b>全て true</b>:
`multiDrawIndirect` / `fillModeNonSolid` / `samplerAnisotropy` / `shaderDrawParameters` /
`timelineSemaphore` / `hostQueryReset` / `synchronization2` / `dynamicRendering` /
`vertexAttributeInstanceRateDivisor`。

任意扱い (`hasDeviceExtension` で条件付き):
`VK_KHR_portability_subset` (あり) / `VK_AMD_buffer_marker` (なし) /
`VK_NV_device_diagnostic_checkpoints` (なし) / **`VK_EXT_multi_draw` (なし)**。

> **`VK_EXT_multi_draw` が無いことが Sodium の経路選択に効く** (§4)。

### 2.5 Phase 3 の未確認項目が閉じた

`VK_KHR_push_descriptor` は Phase 3 の descriptor 調査で
「MoltenVK で使えるか未確認」として調べずに置いた項目だった。
**使える。** Voxy 側の descriptor 戦略を見直す材料になる
(現状は `VkDescriptorSetGroup` によるセット管理)。ただし今の設計を変える理由には
まだなっていない — 見直すなら Phase 6 で実データの更新頻度を測ってから。

---

## 3. 問い 3 — `VkDevice` には手が届く

`com.mojang.blaze3d.vulkan.VulkanDevice` [確認済 — javap]:

```java
public class VulkanDevice implements GpuDeviceBackend {
    public VkDevice vkDevice();          // ★ public
    public VulkanInstance instance();
    public VulkanQueue graphicsQueue();
    public VulkanQueue computeQueue();
    public VulkanQueue transferQueue();
    public long vma();                    // VMA アロケータのハンドル
}
```

到達経路は 1 段だけ塞がっている:

```
RenderSystem.getDevice()  →  GpuDevice
GpuDevice.backend         →  private final、getter 無し   ← ここ
                          →  (VulkanDevice) にキャスト → vkDevice()
```

**`GpuDevice.backend` はアクセスワイドナか mixin Accessor で開ける。**
Voxy は既にアクセスワイドナを持っている (`voxy.accesswidener`)。

> **前例がある** [確認済 — Sodium 0.9.2-alpha.3]:
> ```java
> public interface GpuDeviceAccessor {
>     GpuDeviceBackend sodium$getBackend();
> }
> ```
> Sodium は `core.GpuDeviceAccessor` mixin でまさにこれをやっており、
> `DrawBackend.chooseBackend()` から呼んでいる。

---

## 4. 問い 2 — Sodium は対応しているが、実行時に確かめられていない

### 4.1 静的な証拠は強い [確認済 — jar の中身]

Sodium 0.9.2-alpha.3 (`mc26.2-0.9.2-alpha.3-fabric`) には Vulkan 経路が入っている:

```
client/gpu/device/context/VKDrawContext        client/gpu/device/batch/VKIndirectDrawBatch
client/gpu/device/context/VKIndirectContext    client/gpu/device/batch/VKMultiDrawBatch
client/gpu/device/context/VKMultiDrawContext
mixin/core/VulkanPipelineMixin                 mixin/core/VulkanRenderPassAccessor
```

`VulkanPipelineMixin` / `VulkanRenderPassAccessor` は
`sodium-common.mixins.json` の `client` 一覧に<b>無条件で載っている</b>。

`DrawBackend.chooseBackend()` [確認済 — バイトコード]:

```
RenderSystem.getDevice() → GpuDeviceAccessor.sodium$getBackend()
  instanceof VulkanDevice ?
      features.multiDrawDirectInterleaved() ? VK_MULTIDRAW
    : features.multiDrawIndirect()          ? VK_INDIRECT
    : throw IllegalStateException("Somehow, none of the multidraw backends are supported")
  : OPENGL
```

**この機体では `multiDrawDirectInterleaved=false` / `multiDrawIndirect=true` なので
`VK_INDIRECT` が選ばれる** (§2.2 の DeviceFeatures)。経路は存在する。

さらに Sodium 0.9.1 の changelog に
「Fix crash when rendering very many sections on Vulkan by making the indirect context
ring buffer dynamically sized」とある [確認済 — 実行ログ中の ModMenu 更新情報]。
**Vulkan 経路は実際に使われて不具合修正が回っている。**

### 4.2 実行時 — メインメニューまでは到達する [確認済]。地形は未確認

§5 のクラッシュを直したあと、MC + Sodium + Voxy を Vulkan で起動できた:

```
[Voxy] No OpenGL context on this thread (IllegalStateException); ... Voxy will disable itself.
[Voxy] Voxy is unsupported on your system.
[Minecraft] Using graphics backend Vulkan, using drivers: 1.2.334 MoltenVK 1.4.2
```

| 確認できたこと [確認済] | 未確認 [未検証] |
|---|---|
| Vulkan バックエンドで起動する | **ワールドに入って地形が描かれるか** |
| Sodium の mixin が全て適用される (エラー無し) | **Sodium が `VK_INDIRECT` を選ぶか** (実観測) |
| アトラスの構築・メインメニューまで到達する | |
| MoltenVK の警告のみで致命的エラー無し | |

MoltenVK の警告は `VK_ERROR_FEATURE_NOT_PRESENT: Metal does not support disabling
primitive restart.` のみ。非致命的。

**残りはワールドに入る操作が要る。**
`DrawBackend.BACKEND` は `DefaultChunkRenderer` の生成時 (= ワールド読み込み時) に
初めて評価されるため、メインメニューでは決まらない。
Sodium はこの選択を<b>ログに出さない</b> [確認済 — バイトコードに該当する出力なし]。

> ⚠ **MC は前回の起動がクラッシュすると `preferredGraphicsBackend` を
> `"default"` に戻す** [確認済 — 実機で再現]。
> Vulkan で試すときは<b>直前の起動が正常終了していること</b>を確かめること。
> これを知らずに一度「Vulkan にしたのに OpenGL で起動した」と誤認した。

### 4.2b ⚠ Voxy は MC が OpenGL のときしか動かない [確認済]

Voxy が MC の深度・色テクスチャを受け取る箇所は Sodium 経由で、
<b>`GlTextureView` に直接キャストしている</b>:

```java
renderer.renderOpaque(viewport,
    ((GlTextureView) target.getDepthTextureView()).glId(),
    ((GlTextureView) target.getColorTextureView()).glId());
```

[確認済 — `MixinDefaultChunkRenderer.doRender`]

MC が Vulkan バックエンドなら `VulkanGpuTextureView` が来るので
**`ClassCastException` になる**。つまり **MC-Vulkan と Voxy の同居は現状そもそも成立しない**。

**これは interop 経路を主軸に据えた判断の裏づけである。**
仮に MC-Vulkan でデバイスを共有する未来を採るとしても、
この受け渡し部分の作り直しが前提条件として先に来る。

### 4.3 Voxy の Sodium mixin 9 個への当たり [推測]

Voxy が触っているクラスは<b>いずれも描画バックエンドに依存しない層</b>にある:

| mixin | 対象 | バックエンド依存 |
|---|---|---|
| `MixinDefaultChunkRenderer` | `DefaultChunkRenderer` | **無し** — 抽象 `DrawContext` を持つだけ [確認済 — javap] |
| `MixinRenderSectionManager` / `MixinRenderRegionManager` | セクション管理 | 無し |
| `MixinVisibleChunkCollector` / `MixinFallbackVisibleChunkCollector` | 可視収集 | 無し |
| `MixinSodiumWorldRenderer` / `AccessorSodiumWorldRenderer` | 全体制御 | 無し |
| `AccessorChunkTracker` / `MixinChunkJobQueue` | チャンク管理 | 無し |

`DefaultChunkRenderer` は `DrawContext.create()` で GL/VK を切り替える作りなので、
**クラス自体は両経路で存在する**。Voxy の注入点 (`render` の HEAD と
`ShaderChunkRenderer.end` の直前) もバックエンドに依存しない。

→ **9 個の mixin が原理的に成立しなくなる、という事態は起きなさそう** [推測]。
ただし実行時に確かめていないので推測のままである。

---

## 5. ⚠ 副産物 — Voxy は MC-Vulkan で起動時クラッシュしていた (**修正済み**)

> **修正済み。** `Capabilities` が GL コンテキストの不在を許容するようにした。
> 回帰テストは `CapabilitiesWithoutGlTest` (素の JVM 4 件)。
> 本家への報告候補として `docs/upstream-issue-candidates.md` §1 に記録。
> 以下は修正前の記録である。

```
java.lang.ExceptionInInitializerError
  at me.cortex.voxy.client.VoxyClient.initVoxyClient(VoxyClient.java:24)
  at com.mojang.blaze3d.systems.RenderSystem.handler$bbp000$voxy$injectInit(RenderSystem.java:518)
Caused by: java.lang.IllegalStateException: No GLCapabilities instance set for the current thread.
  at org.lwjgl.opengl.GL.getCapabilities(GL.java:273)
  at me.cortex.voxy.client.core.gl.Capabilities.<init>(Capabilities.java:58)
  at me.cortex.voxy.client.core.gl.Capabilities.<clinit>(Capabilities.java:36)
```

`Capabilities` の静的初期化が `GL.getCapabilities()` を無条件に呼ぶ。
MC が Vulkan バックエンドを選ぶと **GL コンテキストが存在しない**ので落ちる。

**これはデバイス共有の是非とは独立した問題である。**
利用者が MC の設定を Vulkan にしただけで、Voxy がゲームごと落とす。
interop 経路 (5b で完成) を主軸にする場合でも、
最低限「GL コンテキストが無い環境では静かに自己無効化する」必要がある。

> Voxy には既に自己無効化の仕組みがある
> (GL 版はこの Mac で `Not creating renderer due to disabled` と出て無効化される)。
> **その判定より前に `Capabilities` の clinit が走ってしまう**のが問題である。

---

## 6. 判断

user の分岐条件に照らすと:

| | 結果 |
|---|---|
| 1 (MC-Vulkan が macOS で起動) | **通った** |
| 3 (`VkDevice` に手が届く) | **通った** |
| 2 (Sodium が動く) | **静的には通っている。実行時は Voxy のクラッシュで未確認** |

したがって「3 つとも通る」とも「どれか通らない」とも言い切れない。
**§5 を直せば 2 は 1 回の起動で判定できる。**

いずれにせよ **interop 経路 (5b) は主軸のまま**である。理由は user の指示どおり:
MC-Vulkan は実験的で既定が OpenGL に戻されており、モッド互換も発展途上のため。

**Phase 0〜5b の成果はどちらの分岐でもほぼ全て生きる。**
`VkBuffer` / シェーダ / draw 統合 / バリア設計 / 検証基盤は
デバイスを誰が所有するかに依存しない。
デバイス共有が可能になった場合に消えるのは
`VkContext` の<b>デバイス生成部分だけ</b>で、interop (§5b) が不要になる。

---

## 7. 指示を仰ぎたいこと

### 質問 1: §5 のクラッシュを直すか (実装が要る)

`Capabilities` が GL コンテキストの不在を許容するようにする。最小の形は
「`GL.getCapabilities()` が失敗したら全機能 false の Capabilities を返し、
Voxy を自己無効化する」。**10〜20 行程度** [推測]。

- **案 A**: 今直す。直せば問い 2 が 1 回の起動で判定でき、
  かつ<b>利用者がクラッシュしなくなる</b> ← 推す
- **案 B**: 5c の本流 (MC への接続) の中で直す。ただし問い 2 は未確認のまま残る

「調査のみ」の指示に反するので独断では入れない。

### 質問 2: 問い 2 の確認をどこまでやるか

§5 を直したうえで MC-Vulkan + Sodium を起動し、

- 起動して地形が描かれるか (目視 + スクリーンショット)
- Sodium が `VK_INDIRECT` を選んだか (ログ)

まで見るか。これは<b>MC 起動が要る検証</b>なので手動確認になる
(判断 5 で「自動化を諦める」とした層)。

---

## 8. 調査の再現手順

`docs/probes/` に単一ファイルの調査プログラムを 2 本置いた。
**ビルドには含まれない** — LWJGL 3.4.1 のクラスパスを渡して直接実行する。

```bash
# MC 26.2 が要求する拡張/機能が MoltenVK にあるか (素の JVM、MC 不要)
CP=$(find ~/.gradle/caches/modules-2/files-2.1/org.lwjgl \
       -name "lwjgl-3.4.1.jar" -o -name "lwjgl-3.4.1-natives-macos-arm64.jar" \
    -o -name "lwjgl-vulkan-3.4.1.jar" -o -name "lwjgl-vulkan-3.4.1-natives-macos-arm64.jar" \
    | tr '\n' ':')
java --enable-native-access=ALL-UNNAMED -cp "$CP" docs/probes/ExtProbe.java
java --enable-native-access=ALL-UNNAMED -cp "$CP" docs/probes/FeatProbe.java

# brew のローダー経由 (別ビルドの MoltenVK) と比べる場合
java --enable-native-access=ALL-UNNAMED \
     -Dorg.lwjgl.vulkan.libname=/opt/homebrew/lib/libvulkan.dylib \
     -cp "$CP" docs/probes/ExtProbe.java

# MC の Vulkan バックエンドで起動する
#   run/options.txt:  preferredGraphicsBackend:"vulkan"
./gradlew runClient
#   確認: ログの "Using graphics backend ..." / クラッシュレポートの "Graphics Backend:"
#   ⚠ "Device [...] is suitable" は OpenGL で起動しても出る。判定に使ってはならない
```

MC / Sodium のバイトコード調査は `javap -c -p` と
クラスファイルへの `LC_ALL=C grep -a` で行った
(macOS の `strings` は Mach-O 用でクラスファイルを読めない)。

---

## 9. 残っている [未検証]

- **Sodium が MC-Vulkan で実際に地形を描くか** (§4.2)
- **Voxy の Sodium mixin 9 個が Vulkan 経路で実際に効くか** (§4.3 は推測)
- **MC-Vulkan で `VkDevice` を共有した場合のキュー競合**。
  MC は graphics / compute / transfer の 3 キューを持つが、
  Voxy が同じキューに積むのか別に取るのかは設計事項
- **MC-Vulkan の in-flight フレーム数**。Voxy は in-flight = 1 前提で
  `VkTexture` のレイアウト追跡を組んでいる [確認済 — `VkTexture` の javadoc]。
  MC が 2〜3 フレームを重ねるなら、この前提が崩れる


---

## 10. ⚠⚠ 追記 (2026-08-21) — <b>問い 2 が通った</b>

§9 の筆頭に置いていた未確認項目
「Sodium が MC-Vulkan で実際に地形を描くか」を<b>実機で確かめた</b>。

```
[21:51:08] [Voxy] No OpenGL context on this thread; Minecraft is probably not using
                  the OpenGL backend. Voxy will disable itself.
[21:51:08] [Minecraft] Using graphics backend Vulkan, using drivers: 1.2.334 MoltenVK 1.4.2
[21:51:39] [Minecraft] Stopping!
```

| 見たもの | 結果 |
|---|---|
| Vulkan バックエンドで起動 | ✅ |
| **ワールドに入って地形が描かれる** | ✅ |
| **フレーム時間** | ✅ <b>向上</b> (目視。数値は未記録) |
| MoltenVK の警告 | <b>1 件も無し</b> (§4.2 で見えた primitive restart の警告すら出ない) |
| 終了 | 正常 (クラッシュレポート無し) |

⚠ Voxy は自己無効化した (§5 の修正が効いている)。
**つまりこの観測は「MC + Sodium が Vulkan で動く」ことだけを言っており、
Voxy が乗るかどうかは別問題である。**

### 10.1 これで何が変わるか

§4.2b で「MC-Vulkan と Voxy の同居は現状そもそも成立しない」と書いたが、
<b>塞いでいるのは 1 箇所のキャストだけ</b>である:

```java
((GlTextureView) target.getDepthTextureView()).glId()
```

**設計上の障壁ではなく、受け渡しの実装である。**

MC が Vulkan なら、Voxy は<b>MC の {@code VkImage} へ直接描ける</b>。
interop がまるごと不要になる:

| 消せるもの | |
|---|---|
| `VkInteropImage` / IOSurface | 形式が BGRA と R32F に限られる制約も消える |
| `GlInteropCompositor` / `GlDepthImport` / `GlScratchFramebuffer` | GL 4.1 の合成器 |
| `GlVkSync` | GL→Vulkan の同期 (Phase 0 で 0.3ms と測ったもの) |
| **GL 4.5 DSA の地雷 6 個** | GL に一切触れなくなる |

<b>Phase 0〜5c の成果はほぼ全て生きる</b> — `VkTerrainRenderer` / `VkHiZ` /
`VkTraversal` / `VkMergedTableBuilder` はデバイスの持ち主に依存しない。

### 10.2 ⚠ ただし残る未検証 — <b>1 つは設計の前提</b>

| | |
|---|---|
| Voxy の Sodium mixin 9 個が Vulkan 経路で効くか | 推測のまま。<b>今回は Voxy が無効化されたので何も通っていない</b> |
| `VkContext` が MC のデバイスを借りられるか | 実装事項 (今は自前で作っている) |
| キューの取り合い (MC は graphics/compute/transfer の 3 本) | 設計事項 |
| **MC の in-flight フレーム数** | ⚠ <b>Voxy は in-flight = 1 前提</b>で `VkTexture` のレイアウト追跡を組んでいる。MC が 2〜3 枚重ねるなら<b>追跡の作り直し</b>になる |

最後のものだけは費用が読めない。**乗り換えを決める前にここを調べること。**
