# バリデーションレイヤのセットアップ (macOS / MoltenVK)

**なぜ必要か**: `glMemoryBarrier` 42 箇所を Vulkan の
`(srcStage, srcAccess, dstStage, dstAccess)` へ翻訳する作業が控えている。
バリア記述漏れは「たまに描画が壊れる」形で現れて再現性が低く、
**同期バリデーションが実質唯一の防御線**になる。

**必須依存ではない。** レイヤが無い環境では警告を出して続行する [確認済]。

---

## 1. 手順

```bash
brew install vulkan-loader vulkan-validationlayers
```

```bash
./gradlew test \
  -PvkLibname=/opt/homebrew/lib/libvulkan.dylib \
  -PvkValidation=true
```

`build.gradle` の `test` タスクが `VK_LAYER_PATH` と `DYLD_LIBRARY_PATH` を
自動で設定するため、環境変数を手で export する必要はない。

| 設定 | 値 | 役割 |
|---|---|---|
| `-PvkLibname` | `/opt/homebrew/lib/libvulkan.dylib` | LWJGL に Khronos ローダーをロードさせる |
| `-PvkValidation` | `true` | `voxy.vk.validation` システムプロパティ |
| `VK_LAYER_PATH` | `.../vulkan-validationlayers/share/vulkan/explicit_layer.d` | レイヤマニフェストの場所 |
| `DYLD_LIBRARY_PATH` | `.../vulkan-validationlayers/lib` | §2.2 参照 |

---

## 2. 導入時に踏んだ 3 つの問題 [確認済]

同じことを再度踏まないための記録。いずれも解決済み。

### 2.1 LWJGL 同梱 MoltenVK ではレイヤが一切使えない

LWJGL は既定で**同梱の MoltenVK を直接ロード**し、Khronos ローダーを経由しない。
explicit layer はローダーが注入する仕組みなので、ローダーが無ければ使えない。

```
=== instance layers (1) ===
  MoltenVK  (spec 1.4.334)      ← これだけ。validation は無い
```

→ `-Dorg.lwjgl.vulkan.libname` でローダーを指定する。

### 2.2 `VK_ERROR_INCOMPATIBLE_DRIVER` (-9)

ローダー経由にすると MoltenVK は **portability ドライバ**として扱われ、
`VK_KHR_portability_enumeration` を有効にして
`VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR` を立てないと
`vkCreateInstance` が -9 で失敗する。

**逆に同梱 MoltenVK 直結ではこの拡張が存在しない**ため、指定してはいけない。
`VkContext` は「拡張があれば使う」形で両対応している [確認済]。

### 2.3 `VK_ERROR_LAYER_NOT_PRESENT` (-6)

レイヤは列挙できるのに `vkCreateInstance` が -6 で落ちる。
brew のマニフェストが

```json
"library_path": "libVkLayer_khronos_validation.dylib"
```

と**相対名**で書いているのに、実体は `../lib/` にあるため、
ローダーがマニフェストの隣を探して見つけられない。

→ `DYLD_LIBRARY_PATH` に `.../vulkan-validationlayers/lib` を足して解決させる。
(formula の caveats は `VK_LAYER_PATH` にしか触れていないので気づきにくい)

### 2.4 有効化直後に検出された実バグ

導入した瞬間、既存のスペック違反が 1 件出た:

```
vkCreateDevice(): VK_KHR_portability_subset must be enabled because
physical device supports it.  (VUID-VkDeviceCreateInfo-pProperties-04451)
```

MoltenVK は portability 実装なので、対応していれば
`VK_KHR_portability_subset` デバイス拡張の有効化が**必須**。
`VkContext` で条件付きで有効化するよう修正済み [確認済]。

---

## 3. 同期バリデーションについて

**`VK_LAYER_KHRONOS_validation` を有効にするだけでは、バリア漏れは検出されない。**
同期バリデーション (`SYNC-HAZARD-*`) は既定で OFF。

`VkContext` は `VkValidationFeaturesEXT` の
`VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT` を
インスタンス生成時に有効化している [確認済 — `syncValidation=true` をログ出力]。

### `VK_LAYER_KHRONOS_synchronization2` について [確認済]

これは**別物**であり、今回は導入していない。

- 提供元は `vulkan-extensionlayer` (未インストール)
- 役割は「`VK_KHR_synchronization2` を**ネイティブ非対応のドライバ上でエミュレートする**」
  ことであって、バリア記述の検証ではない
- **MoltenVK は `VK_KHR_synchronization2` をネイティブ対応している** [確認済 —
  デバイス拡張の列挙で `NATIVE` を確認]。したがってエミュレーション層は不要

バリア検証を強化するのは §3 冒頭の同期バリデーション機能のほう。

---

## 4. どの MoltenVK で動いているかの識別

**ローダー経由と同梱直結で MoltenVK のビルドが変わる。**
「テストは通るのに本番で落ちる」を避けるため、`VkContext` が起動時に必ず出力する。

```
Vulkan library: /opt/homebrew/lib/libvulkan.dylib
Vulkan driver: Apple M4 Pro api=1.2.357 driver=0.2.2210 vendorID=0x106b deviceID=0x1a040209
```

実測での差 [確認済]:

| 経路 | ライブラリ | apiVersion | driverVersion |
|---|---|---|---|
| 既定 (同梱) | LWJGL bundled MoltenVK | **1.2.334** | 0.2.2210 |
| ローダー経由 | `/opt/homebrew/lib/libvulkan.dylib` | **1.2.357** | 0.2.2210 |

`driverVersion` は同一だが `apiVersion` のパッチ番号が異なる = **別ビルド**である。

### 4.1 ✅ 解決済: Vulkan 1.4 へ引き上げた (Phase 3)

当初 `VkContext` は `apiVersion = 1.2` を要求しており、そのため両経路とも
**1.2.x** と報告されていた。これは**要求バージョンによる頭打ちであって
ドライバの上限ではなかった** [確認済]。

1.4 要求へ変更した結果:

| 経路 | 変更前 (1.2 要求) | 変更後 (1.4 要求) |
|---|---|---|
| 既定 (同梱 MoltenVK) | 1.2.334 | **1.4.334** |
| ローダー経由 (brew) | 1.2.357 | **1.4.357** |

**両経路とも 1.4 に対応しており、全 53 テストが通る** [確認済]。
懸念していた「同梱経路が 1.4 要求で動かなくなる」は**起きなかった**。

`dynamic_rendering` / `synchronization2` / `timeline_semaphore` を
コア機能として使える。`VkContext.hasVulkan14()` で確認できる。

**フォールバックを入れてある**: `vkEnumerateInstanceVersion()` で実際に使える上限を調べ、
`min(available, 1.4)` を要求する。1.4 非対応のローダー/ICD でも
`VK_ERROR_INCOMPATIBLE_DRIVER` にならず、その場合はログで警告する。

---

## 5. レイヤ無し環境での挙動 [確認済]

`-PvkValidation=true` だけを付けてローダーを指定しない場合:

```
[WARN] Vulkan validation requested but VK_LAYER_KHRONOS_validation is not available.
       LWJGL loads its bundled MoltenVK directly, which provides no explicit layers.
       Route through the Khronos loader (...) and install the layers (...).
[INFO] Vulkan: ... validation=false syncValidation=false
```

**警告を出して続行する。テストは全件通る。**
検証は開発時の任意機能であり、必須依存にはしない。

---

## 6. 現状

- 全 37 テストが**両経路で** PASS [確認済]
- ローダー + バリデーション + 同期バリデーションで**指摘ゼロ** [確認済]
- 以降の作業 (バリア翻訳を含む) はバリデーション有効で行う

---

## 7. ⚠ 同期バリデーションはこのスタックで機能していない [確認済]

**「指摘ゼロ」をバリア正しさの証拠として使ってはいけない。**

### 7.1 何が起きたか

`glMemoryBarrier` 翻訳のヘルパを検証する際、**ネガティブ対照**を用意した:

> 同じ SSBO を read-modify-write する compute ディスパッチを、
> **バリア無しで 2 つ並べる**。同期バリデーションが有効なら
> `SYNC-HAZARD-*` を報告するはず。

**報告されなかった** (`VkBarriersTest.missingBarrierIsDetected`)。

### 7.2 切り分け済みの事項

| 確認 | 結果 |
|---|---|
| メッセンジャは動くか | **動く**。同じ経路で descriptor 未更新と portability_subset の指摘が出ている [確認済] |
| レイヤは読み込まれているか | **読み込まれている** (`VK_LAYER_KHRONOS_validation` spec 1.4.357) |
| `VK_EXT_validation_features` は有効か | **有効**。当初これが漏れていた (§7.3) が、直しても結果は変わらず |
| 環境変数 `VK_LAYER_ENABLES` 経由ではどうか | **同じく報告なし** |

→ **一般バリデーションは機能しているが、同期バリデーションだけ指摘を出さない。**
MoltenVK / portability 環境での制約と考えられる [推測 — レイヤ側の実装は未調査]。

### 7.3 途中で見つかった自分側のバグ (修正済み)

`VK_EXT_validation_features` は**レイヤが提供する**拡張であり、
`vkEnumerateInstanceExtensionProperties(null, ...)` には**現れない**。
レイヤ名を指定して列挙する必要がある。

当初この検査を `null` 列挙で行っていたため常に false となり、
拡張が有効化されないまま `syncValidationEnabled = true` を報告していた。
**「有効にしたつもりで実は無効」という最悪の状態**であり、
ネガティブ対照が無ければ気づけなかった。

現在は列挙をレイヤ名付きで行い、見つからなければ例外にしている。

### 7.4 これが意味すること

バリア翻訳の安全性について、当初想定していた防御線が**存在しない**。

| 当初の想定 | 実際 |
|---|---|
| バリア漏れは同期バリデーションが検出する | **検出されない** |
| 「指摘ゼロ」= バリアが正しい | **「見えていない」だけの可能性がある** |

**代替手段を用意する必要がある** [未検証 — 以下は候補]:
- 計算結果の期待値をテストで突き合わせる (バリア漏れは結果の不定性として現れる)
- GL 版と Vulkan 版で同じ入力に対する出力を比較する
- 保守的バリアで動く状態を作り、削る実験は結果比較とセットで行う

**D 区分 (意図不明の 4 箇所) を「削って様子を見る」方針は、
この状況では成立しない。** 削って壊れても検出できない。
