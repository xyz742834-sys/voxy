# 画像リソース抽象 (VkTexture / VkSampler)

**状態**: 実装済み [確認済 — `VkTextureTest`, `TraversalDispatchTest`]

---

## 1. レイアウト追跡の方針 — **レベル別 / 遷移は明示呼び出し**

`VkTexture` が **mip レベルごとに現在レイアウトを保持**し、
遷移は `barrier(cmd, level, newLayout, srcStage, srcAccess, dstStage, dstAccess)` で明示的に呼ぶ。

- **「いつ遷移するか」は呼び出し側が決める** — バリア翻訳で意図を書く先が残る
- **「何から遷移するか」はオブジェクトが知る** — `oldLayout` の取り違えという
  最も頻繁な Vulkan バグを構造的に消す

### 1.1 なぜレベル別が必須か [確認済]

HiZ の mip チェーン生成が **同一画像の level i-1 を読みながら level i に書く**
(`HiZBuffer.buildMipChain`)。GL は `GL_TEXTURE_BASE_LEVEL` / `MAX_LEVEL` で
読めるレベルを絞ってこれを実現しているが、**Vulkan に同等の仕組みは無い**。

したがって:
- レベルごとに別の `VkImageView` が要る (`view(int level)`)
- レイアウトもレベル単位で持つ必要がある

`VkTextureTest.differentLevelsHoldDifferentLayouts` がこの状態
(3 レベルが `SHADER_READ_ONLY`、1 レベルが `DEPTH_STENCIL_ATTACHMENT`) を検証している。

### 1.2 ⚠ 記録順の前提

**`VkTexture` が持つレイアウトは「コマンドバッファに記録された時点」の状態であって、
GPU が実行している時点の状態ではない。**

したがって **transition を記録順と異なる順序で呼ぶと追跡が破綻する**。

現状は安全である [確認済]:
- in-flight = 1
- コマンドバッファ 1 本を線形に記録

→ 記録順 == 実行順が成り立つ。

**この前提が崩れる変更**: フレームの多重化、複数コマンドバッファの併用、
セカンダリコマンドバッファの導入。いずれかを行う場合は追跡方式ごと見直すこと。

### 1.3 レイアウト遷移とメモリ同期を同じ API にした理由

Vulkan では両者が `VkImageMemoryBarrier` 1 つに同居しており分離できない。
また**レイアウトは変わらないが同期だけ必要**なケースが実在する
(例: `GENERAL` のまま 書き込み → 読み出し)。

そのため **レイアウトが同じでもアクセスマスクが指定されていればバリアを発行する**。
省略するのは「レイアウト据え置き **かつ** アクセスマスク 0」の完全な no-op のときだけ。
`VkTextureTest.sameLayoutStillEmitsWhenSyncIsNeeded` で検証。

---

## 2. サンプラ

GL 側は `glCreateSamplers` の int をテクスチャユニットに別途バインドしていた
(画像とサンプラが独立)。Vulkan の `COMBINED_IMAGE_SAMPLER` は両者を 1 descriptor に束ねる。

GL 側のサンプラは 6 個で、いずれも filter / wrap / compare の単純な組み合わせ [確認済 —
`HiZBuffer`, `ModelStore`, `LightMapHelper`, `SSAO`, `AbstractRenderPipeline`,
`HierarchicalOcclusionTraverser`]。`VkSampler` は設定をキーにキャッシュする。

---

## 3. 外部メモリ (IOSurface) — Phase 5 で差し込む

`VkTexture.wrapExternal(image, format, levels, w, h)` で既存の `VkImage` を包める。

- **メモリの所有権を持たない**。`free()` は `vkDestroyImage` も `vkFreeMemory` も呼ばず、
  ビューだけ破棄する (IOSurface backed の場合そもそもメモリをバインドしていない)
- **外部画像も同じレイアウト追跡に載る**。interop では GL に渡す前に特定のレイアウトへ
  遷移させる必要があり、レベル別追跡の仕組みがそのまま使える

**`VkImportMetalIOSurfaceInfoEXT` によるインポート自体は Phase 5 (interop) で実装する。**
参照実装は `~/dev/mdi-bench` の `Interop.java` に存在する。

---

## 4. 解消した [未検証]

前回「シェーダの実行結果は [未検証]」としていた traversal を、
hiZ サンプラを含む全 binding を割り当てたうえで
**実際に `vkCmdDispatch` し、サブミットして完了まで待つ**形に変更した。
バリデーション (同期バリデーション込み) の指摘はゼロ
[確認済 — `TraversalDispatchTest.recordsFullDispatchLoop`]。

---

## 5. 残っているギャップ

| 項目 | 状態 |
|---|---|
| 画像へのデータアップロード (`vkCmdCopyBufferToImage`) | 未実装。ブロックアトラス転送で要る |
| レンダーパス / アタッチメント | 未実装。Phase 4 (`dynamic_rendering` を使う想定) |
| `VkTexture` のデバッグ命名 | `VK_EXT_debug_utils` 未配線 |
