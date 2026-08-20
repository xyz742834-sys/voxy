package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.VkContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * バリデーション抑制が<b>狭いこと</b>の検査。
 *
 * <p>Phase 5a §6.3 の通り、IOSurface backed の画像には 3 つの VUID が出続ける。
 * これを黙らせるのは<b>この環境で唯一機能している防御線を削る</b>ことなので、
 * 「黙るのは意図した組み合わせだけ」を常設で見張る。
 *
 * <h2>この検査を空虚に満たす方法と、その塞ぎ方</h2>
 * <ol>
 *   <li><b>何も抑制しない実装でも「広すぎない」検査は通る</b> →
 *       {@link #suppressesTheKnownNoise} で<b>実際に抑制されること</b>も要求する</li>
 *   <li><b>ハンドルの前方一致で誤爆する</b> →
 *       {@link #doesNotSuppressAHandleThatMerelySharesAPrefix} で境界を確かめる</li>
 *   <li><b>破棄後もハンドルが残って別の資源の指摘を黙らせる</b> →
 *       {@link #stopsSuppressingAfterUnregister}</li>
 * </ol>
 *
 * <p>Vulkan は要らない (述語だけを直接呼ぶ)。素の JVM で走る。
 */
public class InteropValidationSuppressionTest {

    /** 実際に出たメッセージの形 [確認済 — Phase 5a の実行ログ]。 */
    private static String noMemoryBound(long handle) {
        return "vkCmdCopyImageToBuffer(): srcImage VkImage 0x" + Long.toHexString(handle)
            + " used with no memory bound. Memory should be bound by calling vkBindImageMemory()."
            + " The Vulkan spec states: ... (VUID-vkCmdCopyImageToBuffer-srcImage-07966)";
    }

    private static String someOtherComplaint(long handle) {
        return "vkCmdCopyImageToBuffer(): srcImage VkImage 0x" + Long.toHexString(handle)
            + " was created with usage ... (VUID-vkCmdCopyImageToBuffer-srcImage-06662)";
    }

    private static final long HANDLE = 0x30000000003L;
    private static final long OTHER = 0x60000000006L;

    @AfterEach
    void cleanup() {
        VkContext.unregisterInteropImage(HANDLE);
        VkContext.unregisterInteropImage(OTHER);
        VkContext.unregisterInteropImage(0x300000000031L);
    }

    @Test
    void suppressesTheKnownNoise() {
        VkContext.registerInteropImage(HANDLE);
        assertTrue(VkContext.isSuppressedInteropNoise(noMemoryBound(HANDLE)),
            "the three known VUIDs on a registered interop handle must be suppressed");
    }

    /** VUID が違えば同じハンドルでも通す。<b>新しい種類の指摘は見えなければならない。</b> */
    @Test
    void doesNotSuppressADifferentVuidOnTheSameHandle() {
        VkContext.registerInteropImage(HANDLE);
        assertFalse(VkContext.isSuppressedInteropNoise(someOtherComplaint(HANDLE)),
            "only the three enumerated VUIDs may be suppressed");
    }

    /** ハンドルが違えば同じ VUID でも通す。<b>interop 以外の本物のバグは見えなければならない。</b> */
    @Test
    void doesNotSuppressTheSameVuidOnAnUnregisteredHandle() {
        VkContext.registerInteropImage(HANDLE);
        assertFalse(VkContext.isSuppressedInteropNoise(noMemoryBound(OTHER)),
            "a non-interop image with the same VUID is a real finding");
    }

    /**
     * {@code 0x30000000003} を登録したときに {@code 0x300000000031} が巻き添えにならないこと。
     * 単純な {@code contains} だと通ってしまう。
     */
    @Test
    void doesNotSuppressAHandleThatMerelySharesAPrefix() {
        VkContext.registerInteropImage(HANDLE);
        assertFalse(VkContext.isSuppressedInteropNoise(noMemoryBound(0x300000000031L)),
            "0x300000000031 merely starts with 0x30000000003; it is a different image");
    }

    /** 破棄後は黙らせない。ハンドルは再利用されうる。 */
    @Test
    void stopsSuppressingAfterUnregister() {
        VkContext.registerInteropImage(HANDLE);
        assertTrue(VkContext.isSuppressedInteropNoise(noMemoryBound(HANDLE)));
        VkContext.unregisterInteropImage(HANDLE);
        assertFalse(VkContext.isSuppressedInteropNoise(noMemoryBound(HANDLE)),
            "a freed handle may be reused by an unrelated resource");
    }

    /** 何も登録していなければ何も抑制しない。 */
    @Test
    void suppressesNothingByDefault() {
        assertFalse(VkContext.isSuppressedInteropNoise(noMemoryBound(HANDLE)));
        assertFalse(VkContext.isSuppressedInteropNoise(someOtherComplaint(OTHER)));
    }
}
