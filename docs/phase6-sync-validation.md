# Phase 6 — 同期バリデーション: Lavapipe による切り分け

**状態**: 調査完了。実務上の結論あり。恒常的な環境変更は最小限 (`-PvkIcd` の追加のみ)。

---

## 1. 出発点

Phase 3 で「同期バリデーションがこの環境で機能しない」と結論していた
(`docs/phase3-completion.md` §3.1、`docs/vulkan-validation-setup.md` §7)。
Phase 5 の引き継ぎで「Lavapipe が唯一の解」として Phase 6 送りになっていた。

**当時の対照は 1 種類だけだった**: descriptor 経由の SSBO への
compute read-modify-write をバリア無しで 2 つ並べる。報告は 0 件。

---

## 2. Lavapipe の導入

macOS で Mesa の Vulkan ソフトウェアドライバ (通称 Lavapipe、Mesa 内の名称は
`swrast`) が使えるかを確認した。

```bash
brew info mesa
```

Homebrew の `mesa` フォーミュラは macOS 向けビルドで
`-Dvulkan-drivers=swrast` を指定しており、**bottle で配布されている**
[確認済 — フォーミュラのソースを直接読んだ]。ビルドは不要だった。

```bash
brew install mesa
```

ICD マニフェストは `share/vulkan/icd.d/lvp_icd.aarch64.json`、
ドライバ本体は `lib/libvulkan_lvp.dylib`。`api_version: 1.4.354` で、
プロジェクトが要求する 1.4 と互換。

```bash
VK_ICD_FILENAMES=<...>/lvp_icd.aarch64.json DYLD_LIBRARY_PATH=/opt/homebrew/lib \
  vulkaninfo --summary
```

```
deviceType = PHYSICAL_DEVICE_TYPE_CPU
deviceName = llvmpipe (LLVM 23.1.1, 128 bits)
driverID   = DRIVER_ID_MESA_LLVMPIPE
```

**動いた。** `build.gradle` の `test` タスクに `-PvkIcd=<icd.json>` を追加し、
`VK_ICD_FILENAMES` を設定してローダーにこの ICD だけを見せられるようにした。

`VkContext` 側の変更は不要だった — 物理デバイスは常に `devs.get(0)` を選ぶ実装で
(候補が 1 つしか無いので自動的に llvmpipe になる)、
`VK_KHR_portability_subset` / `VK_EXT_metal_objects` の有無はどちらも
「あれば使う」形で既に両対応していた。

---

## 3. 対照 1 (Phase 3 と同じ): descriptor 経由の SSBO — **Lavapipe でも 0 件**

`VkBarriersTest.missingBarrierIsDetected` をそのまま Lavapipe 経由で実行。

```bash
./gradlew test --tests 'me.cortex.voxy.vk.VkBarriersTest' \
  -PvkLibname=/opt/homebrew/lib/libvulkan.dylib \
  -PvkIcd=<...>/lvp_icd.aarch64.json \
  -PvkValidation=true -PvkSyncEnv=true
```

```
[vk] validation messages = 0, SYNC-HAZARD = 0
```

**MoltenVK と完全に同じ。** この時点でまだ「レイヤの制約」か
「こちらの読み方の誤り」かは分からない。

---

## 4. ⚠⚠ 対照 2 (新設): descriptor を使わない最小ハザード

`vkCmdFillBuffer` を同じ範囲へバリア無しで 2 回並べるだけの、
shader も descriptorset も要らない最小のケースを追加した。

```java
vkCmdFillBuffer(cmd, buf.handle, 0, 256, 1);
// バリア無し
vkCmdFillBuffer(cmd, buf.handle, 0, 256, 2);
```

**Lavapipe で実行すると報告が来た**:

```
[vk-validation] vkCmdFillBuffer(): WRITE_AFTER_WRITE hazard detected.
vkCmdFillBuffer writes to dstBuffer VkBuffer 0x..., which was previously
written by another vkCmdFillBuffer command.
No sufficient synchronization is present to ensure that a write
(VK_ACCESS_2_TRANSFER_WRITE_BIT) at VK_PIPELINE_STAGE_2_CLEAR_BIT does not
conflict with a prior write of the same type at the same stage.
```

ただし `VkContext.syncHazards()` は `m.contains("SYNC-HAZARD")` でフィルタしており、
このメッセージ本文には**そのリテラルが無い**。フィルタは 0 件のままだった:

```
[minimal] validation messages = 1, SYNC-HAZARD = 0
```

**MoltenVK で同じテストを走らせても同一の結果。** ここで「ICD に依存しない」
ことが確定した — つまり原因はレイヤの実装ではなく、**メッセージの読み方**にある。

---

## 5. ⚠⚠ 根本原因: 分類フィルタが違うフィールドを見ていた

`VkDebugUtilsMessengerCallbackDataEXT` には自由文の `pMessageString` とは別に、
**構造化された安定 ID** `pMessageIdName` がある。これを表示させると:

```
[SYNC-HAZARD-WRITE-AFTER-WRITE] vkCmdFillBuffer(): WRITE_AFTER_WRITE hazard detected. ...
```

**`"SYNC-HAZARD"` はここにしか無かった。** 自由文の言い回し
(`"WRITE_AFTER_WRITE hazard detected"`) は VVL のバージョンで変わりうる
言葉であって、安定した識別子ではない。当初のコードは
`pMessageString()` だけを保存し、それに対して部分一致していたため、
**同期バリデーションが正しく指摘を出していても、プロジェクト側が
それを「同期系の指摘」として分類できていなかった。**

### 5.1 直した内容

`VkContext.createDebugMessenger` で `pMessageIdNameString()` を取得し、
保存するメッセージの先頭に埋め込むようにした:

```java
String idName = data.pMessageIdNameString();
String msg = "[vk-validation] "
    + (idName != null && !idName.isEmpty() ? "[" + idName + "] " : "") + raw;
```

`syncHazards()` 自体のフィルタ (`contains("SYNC-HAZARD")`) は変更していない —
**壊れていたのは判定条件ではなく、判定対象に渡していたデータだった。**

---

## 6. 修正後の全回帰確認

### 6.1 MoltenVK — バリデーション有効、273 テスト中 272 が既存どおり

```bash
./gradlew test --continue \
  -PvkLibname=/opt/homebrew/lib/libvulkan.dylib -PvkValidation=true -PvkSyncEnv=true
```

```
273 tests completed, 1 failed, 2 skipped
```

失敗は 1 件のみ、**今回の修正とは無関係の既存の環境差分**
(`VkContextTest.maxPushConstantsSizeIsAtLeastWhatWeNeed` — §8 参照)。
`git stash` して修正前のコードでも同じ 1 件が同じ理由で落ちることを確認済み
[確認済]。

**barrier 系の「指摘ゼロ」を主張する既存テスト
(`computeToComputeSilencesTheHazard` / `computeToIndirectRecordsCleanly` /
`conservativeBarrierAlsoCovers`) はどれも壊れなかった。**
ただしこれは「バリアが正しいと確認できた」ではない —
これらはすべて descriptor 経由の SSBO 依存なので、
§3 の区分により<b>相変わらず検出できない</b> [規約 22 で後述]。
<b>もし壊れていたら、それは Voxy 側の未発見のバリア漏れだったはずである。</b>
壊れなかったことは「今回発見できる区分では見つからなかった」以上を意味しない。

### 6.2 Lavapipe — 追加のノイズが出る (出荷対象外なので追わない)

```
273 tests completed, 7 failed, 2 skipped
```

内訳:

| テスト | 原因 | 追うか |
|---|---|---|
| `subgroupSizeMatchesPhase0` | Lavapipe の subgroupSize=4 (Apple GPU は 32)。ハードコードされた実機値との比較 | ❌ 環境差分。出荷対象は Apple GPU のみ |
| `maxPushConstantsSizeIsAtLeastWhatWeNeed` | 同上系 (§8) | ❌ |
| `VkTranslucentTest` 5 件 (`bucketsAreOrderedFarToNear` 等) | 半透明バケットの prefix sum がズレる (`"bucket ends must not run backwards at 64"`) | ❌ **[未検証・追っていない]**。subgroup 幅 4 vs 32 に依存した実装がある可能性はあるが、Lavapipe は出荷対象ではないので今回は追わない |

**Lavapipe を恒常的な検証環境として採用しない理由はこれ** — ICD 依存の実装差分が
ノイズとして混ざり、Apple 専用フォークにとって価値の無い切り分け作業が増える。
今回の目的 (「ICD に依存するか」の 1 回の確認) はすでに果たした。

### 6.3 新設テスト

`VkBarriersTest.plainBufferHazardIsNowDetected` — descriptor を介さない
最小ハザードが実際に検出されることを常設で見張る。

```
[vk] plain buffer hazards = 1
plainBufferHazardIsNowDetected() PASSED
```

`missingBarrierIsDetected` は引き続き `Assumptions.abort` で中断するが、
理由の文面を「レイヤが死んでいる」から
「descriptor 経由のバッファ書き込みだけが追跡されない (GPU-AV でも変わらず、
ICD を替えても変わらない)」に書き換えた。

---

## 7. ⚠ 直せなかったこと — descriptor 経由の SSBO 追跡

GPU-Assisted Validation を同時に有効化しても
(`VK_LAYER_ENABLES=VK_VALIDATION_FEATURE_ENABLE_GPU_ASSISTED_EXT,...`)、
`missingBarrierIsDetected` のケースは変わらなかった
(GPU-AV が実際に起動していることはデバイス機能の強制ログで確認済み)。

Khronos 公式ドキュメントもこれを既知の制約として述べている:
SyncVal は「shader が実際にどの descriptor へアクセスするかを
精密に追跡できない (GPU-AV との統合が要る)」。今回 GPU-AV を足しても
このケースの結果は変わらなかったので、**この環境・このバージョンでは
未解決のまま**として記録する [確認済 — 直せると期待して良い理由が無い]。

**Voxy 自身の compute バリア (`VkBarriers.computeToCompute` 等) の大半は
まさにこの区分 (descriptor 経由の SSBO) にある。** したがって
「D 区分を削って様子を見る」方針がこの区分について成立しないという
Phase 3 の結論 (`docs/vulkan-validation-setup.md` §7.4) は<b>そのまま有効</b>。

**一方で `VkTexture` のレイアウト遷移バリアは非 descriptor 区分に入るので、
今回の修正で信頼度が上がった。**

---

## 8. ⚠ 副産物として見つけた、無関係の既存差分 (未修正・記録のみ)

`VkContextTest.maxPushConstantsSizeIsAtLeastWhatWeNeed` が
**バリデーション有効時 (= ローダー経由) にだけ**失敗する。

```
同梱 MoltenVK (既定):        maxPushConstants=4096, metalObjects=true,  unifiedMemType=1
ローダー経由 MoltenVK (brew): maxPushConstants=256,  metalObjects=false, unifiedMemType=0
```

**バリデーションを使うには常にローダー経由が要る**ので、
バリデーション有効での全件確認は今後も毎回この 1 件だけ失敗する。
今回の調査 (メッセージ分類) とは無関係なので直していない。

`ssao.comp` の 6 個の mat4 (384B) を要求している側なので、
Phase 6 で SSAO の Vulkan 移行に着手するときに
「どちらの MoltenVK ビルドの limit を信じるべきか」を先に決める必要がある
[未検証 — 256 が実際のハードウェア上限で 4096 が過大申告なのか、逆なのか]。
