package me.cortex.voxy.client.core.vk;

import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.VkSamplerCreateInfo;

import java.util.HashMap;
import java.util.Map;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;

/**
 * サンプラ。GL 側は {@code glCreateSamplers} で作った int を
 * テクスチャユニットに別途バインドしていた (画像とサンプラが独立)。
 *
 * <p>Vulkan の {@code COMBINED_IMAGE_SAMPLER} は両者を 1 つの descriptor にまとめるため、
 * バインド時に画像とサンプラの組を渡す形になる。
 *
 * <p>GL 側で作られているサンプラは 6 個で、いずれも
 * filter / wrap / compare の単純な組み合わせしかない [確認済 —
 * `HiZBuffer`, `ModelStore`, `LightMapHelper`, `SSAO`, `AbstractRenderPipeline`,
 * `HierarchicalOcclusionTraverser`]。同じ設定を何度も作らないようキャッシュする。
 */
public class VkSampler {
    public record Key(int minFilter, int magFilter, int mipmapMode, int addressMode,
                      boolean compareEnable, int compareOp) {}

    private static final Map<Key, VkSampler> CACHE = new HashMap<>();

    public final long handle;
    private final Key key;

    private VkSampler(Key key) {
        this.key = key;
        try (MemoryStack stack = stackPush()) {
            var ci = VkSamplerCreateInfo.calloc(stack).sType$Default()
                .minFilter(key.minFilter()).magFilter(key.magFilter())
                .mipmapMode(key.mipmapMode())
                .addressModeU(key.addressMode())
                .addressModeV(key.addressMode())
                .addressModeW(key.addressMode())
                .mipLodBias(0).anisotropyEnable(false).maxAnisotropy(1)
                .compareEnable(key.compareEnable()).compareOp(key.compareOp())
                .minLod(0).maxLod(VK_LOD_CLAMP_NONE)
                .borderColor(VK_BORDER_COLOR_FLOAT_OPAQUE_BLACK)
                .unnormalizedCoordinates(false);
            long[] p = new long[1];
            VkContext.check(vkCreateSampler(VkContext.get().device, ci, null, p), "vkCreateSampler");
            this.handle = p[0];
        }
    }

    public static VkSampler of(Key key) {
        return CACHE.computeIfAbsent(key, VkSampler::new);
    }

    /** NEAREST / CLAMP_TO_EDGE / mip なし。深度サンプリングなどの既定。 */
    public static VkSampler nearestClamp() {
        return of(new Key(VK_FILTER_NEAREST, VK_FILTER_NEAREST,
            VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            false, VK_COMPARE_OP_NEVER));
    }

    /** NEAREST_MIPMAP_NEAREST / CLAMP_TO_EDGE。HiZ 用 (GL 側の設定と同じ)。 */
    public static VkSampler nearestMipClamp() {
        return of(new Key(VK_FILTER_NEAREST, VK_FILTER_NEAREST,
            VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            false, VK_COMPARE_OP_NEVER));
    }

    /** LINEAR / CLAMP_TO_EDGE。ライトマップ用。 */
    public static VkSampler linearClamp() {
        return of(new Key(VK_FILTER_LINEAR, VK_FILTER_LINEAR,
            VK_SAMPLER_MIPMAP_MODE_NEAREST, VK_SAMPLER_ADDRESS_MODE_CLAMP_TO_EDGE,
            false, VK_COMPARE_OP_NEVER));
    }

    public Key key() { return this.key; }

    /** キャッシュごと破棄する。デバイス破棄前に呼ぶこと。 */
    public static void shutdown() {
        var dev = VkContext.get().device;
        CACHE.values().forEach(s -> vkDestroySampler(dev, s.handle, null));
        CACHE.clear();
    }

    public static int cachedCount() { return CACHE.size(); }
}
