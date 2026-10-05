package me.cortex.voxy.client.core.vk;

import me.cortex.voxy.common.Logger;
import org.lwjgl.PointerBuffer;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.vulkan.*;

import java.nio.IntBuffer;

import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.vulkan.VK10.*;
import static org.lwjgl.vulkan.VK11.vkGetPhysicalDeviceProperties2;

/**
 * Vulkan バックエンドのデバイスコンテキスト。
 * macOS/MoltenVK 前提。ウィンドウ・サーフェスは持たない
 * (描画結果は IOSurface 経由で GL 側に渡すため)。
 */
public class VkContext {
    private static VkContext INSTANCE;

    public final VkInstance instance;
    public final VkPhysicalDevice physical;
    public final VkDevice device;
    public final VkQueue queue;
    public final int queueFamily;
    public final long commandPool;
    public final VkPhysicalDeviceMemoryProperties memProps;

    public final float timestampPeriod;
    /** キューがタイムスタンプを取れるビット数。<b>0 なら取れない</b>。 */
    public final int timestampValidBits;
    public final int maxPushConstantsSize;
    public final int subgroupSize;
    public final boolean hasSubgroup;

    /**
     * SSBO を offset 付きで bind する際の最小アラインメント。
     * dynamic offset (`pDynamicOffsets`) にも同じ制約がかかるため、
     * UploadStream 相当のリングバッファはこの倍数で確保する必要がある。
     */
    public final long minStorageBufferOffsetAlignment;
    public final long minUniformBufferOffsetAlignment;

    /** ユニファイドメモリ用: DEVICE_LOCAL|HOST_VISIBLE|HOST_COHERENT を満たすメモリタイプ。 */
    public final int unifiedMemoryType;

    /**
     * {@code VK_EXT_metal_objects} が使えるか。IOSurface を {@code VkImage} にするのに要る
     * (Phase 5 の interop)。
     */
    public final boolean hasMetalObjects;

    /**
     * <b>この context が instance/device を所有しているか</b>。
     * 採用モード (Minecraft の device を借りている) では false で、
     * {@link #shutdown()} はそれらを破棄しない。
     */
    public final boolean adopted;

    public static VkContext get() {
        if (INSTANCE == null) throw new IllegalStateException("VkContext not initialised");
        return INSTANCE;
    }

    public static void init() {
        if (INSTANCE != null) return;
        INSTANCE = new VkContext();
    }

    /**
     * <b>外部 (Minecraft 自身の Vulkan バックエンド) の device を採用する</b>。
     *
     * <p>instance / physical device / device / queue は<b>借り物</b>で、ここでは作らず壊さない。
     * 自分で作るのはコマンドプールだけ。派生情報 (limits, memory properties, subgroup,
     * timestamp, unified memory type, device 拡張) は渡された physical device から
     * そのまま問い直すので、{@code VkBuffer} / {@code VkShader} など既存の資産は
     * どちらのモードでも同じように動く。
     *
     * <p>⚠ Voxy のシェーダが要る device 機能 ({@code shaderInt64} など) は
     * <b>device を作った側</b>が有効化していなければならない。MC の device では
     * {@code me.cortex.voxy.client.core.vk.mcnative.McNativeDeviceFeatures} が
     * その役を担う [docs/ai/vulkan-native-integration-survey.md]。
     *
     * @param queueFamily {@code queue} のファミリ。グラフィクス対応のファミリは
     *                    仕様上 compute も必ず対応するので、Voxy の compute も同じ queue に流せる。
     * @param enabledDeviceExtensions device を作った側が<b>有効にした</b>拡張名。
     *                    物理デバイスの対応可否ではなく<b>有効化の有無</b>で決まる機能
     *                    ({@code VK_EXT_metal_objects} による IOSurface interop) の判定に使う。
     */
    public static void initAdopted(VkInstance instance, VkPhysicalDevice physical, VkDevice device,
                                   VkQueue queue, int queueFamily,
                                   boolean validationEnabled, boolean syncValidationEnabled,
                                   java.util.Set<String> enabledDeviceExtensions) {
        if (INSTANCE != null) return;
        INSTANCE = new VkContext(instance, physical, device, queue, queueFamily,
            validationEnabled, syncValidationEnabled, enabledDeviceExtensions);
    }

    /** 採用モードか (instance/device を所有していない)。 */
    public static boolean isAdopted() { return INSTANCE != null && INSTANCE.adopted; }

    public static void shutdown() {
        if (INSTANCE == null) return;
        INSTANCE.destroy();
        INSTANCE = null;
    }

    /**
     * バリデーションレイヤを要求するか。{@code -Dvoxy.vk.validation=true}。
     * 利用できない環境では警告を出して続行する (落とさない)。
     */
    public static final boolean REQUEST_VALIDATION =
        Boolean.parseBoolean(System.getProperty("voxy.vk.validation", "false"));

    private static final String VALIDATION_LAYER = "VK_LAYER_KHRONOS_validation";

    /** バリデーションレイヤが実際に有効になったか。 */
    public final boolean validationEnabled;

    /**
     * 同期バリデーション (SYNC-HAZARD-*) が有効か。
     * バリア記述漏れを検出する唯一の実用的な手段で、
     * {@code glMemoryBarrier} 42 箇所の翻訳ではこれが防御線になる。
     */
    public final boolean syncValidationEnabled;

    private String driverIdentity = "(unknown)";

    /** 実際に要求できた instance API バージョン。1.4 未満なら一部機能がコアで使えない。 */
    public final int instanceApiVersion;

    /** 1.4 コア機能 (dynamic_rendering / synchronization2 / timeline_semaphore) が使えるか。 */
    public boolean hasVulkan14() {
        return this.instanceApiVersion >= VK_MAKE_VERSION(1, 4, 0);
    }

    /**
     * ローダー/ICD が対応する instance バージョン。
     * {@code vkEnumerateInstanceVersion} は Vulkan 1.1 で追加されたので、
     * 取得できない場合は 1.0 とみなす。
     */
    private static int queryInstanceVersion() {
        try (MemoryStack stack = stackPush()) {
            IntBuffer v = stack.mallocInt(1);
            if (VK11.vkEnumerateInstanceVersion(v) == VK_SUCCESS) return v.get(0);
        } catch (Throwable ignored) {
            // 1.0 のみのローダー
        }
        return VK_MAKE_VERSION(1, 0, 0);
    }

    /**
     * どの MoltenVK を使っているかの識別。
     * <b>ローダー経由と同梱直結でビルドが変わる</b>ため、
     * 「テストは通るのに本番で落ちる」を避けるべく毎回ログに出す。
     */
    public String driverIdentity() { return this.driverIdentity; }

    /** LWJGL がロードした Vulkan ライブラリのパス (未指定なら同梱 MoltenVK)。 */
    public static String vulkanLibraryPath() {
        String p = System.getProperty("org.lwjgl.vulkan.libname");
        return p == null ? "(LWJGL bundled MoltenVK)" : p;
    }

    private VkContext() {
        this.adopted = false;
        try (MemoryStack stack = stackPush()) {
            // Vulkan 1.4 を要求する。dynamic_rendering / synchronization2 / timeline_semaphore を
            // 拡張ではなくコア機能として使うため。
            //
            // ただし要求バージョンがローダー/ICD の対応を超えると vkCreateInstance が
            // VK_ERROR_INCOMPATIBLE_DRIVER になるため、実際に使える上限まで落とす。
            // LWJGL 同梱 MoltenVK とローダー経由 (brew) でビルドが違い、
            // 対応バージョンも揃っていない [確認済 — docs/vulkan-validation-setup.md 4]。
            int available = queryInstanceVersion();
            int requested = Math.min(available, VK_MAKE_VERSION(1, 4, 0));
            this.instanceApiVersion = requested;

            VkApplicationInfo app = VkApplicationInfo.calloc(stack)
                .sType$Default()
                .pApplicationName(stack.UTF8("voxy"))
                .apiVersion(requested);

            VkInstanceCreateInfo ici = VkInstanceCreateInfo.calloc(stack)
                .sType$Default().pApplicationInfo(app);

            // --- portability ---
            // Khronos ローダー経由で MoltenVK を使う場合、MoltenVK は "portability" ドライバ
            // として扱われるため、VK_KHR_portability_enumeration を有効にして
            // ENUMERATE_PORTABILITY ビットを立てないと VK_ERROR_INCOMPATIBLE_DRIVER (-9) になる。
            // 一方 LWJGL 同梱の MoltenVK を直接ロードする場合、この拡張は存在しないので
            // 指定してはいけない。したがって「あれば使う」形にする。
            var instanceExtensions = enumerateInstanceExtensions(stack);
            var wanted = new java.util.ArrayList<String>();
            if (instanceExtensions.contains("VK_KHR_portability_enumeration")) {
                ici.flags(ici.flags() | 0x00000001 /* VK_INSTANCE_CREATE_ENUMERATE_PORTABILITY_BIT_KHR */);
                wanted.add("VK_KHR_portability_enumeration");
            }

            // --- validation ---
            boolean haveValidation = REQUEST_VALIDATION
                && enumerateInstanceLayers(stack).contains(VALIDATION_LAYER);
            if (REQUEST_VALIDATION && !haveValidation) {
                Logger.warn("Vulkan validation requested but " + VALIDATION_LAYER + " is not available. "
                    + "LWJGL loads its bundled MoltenVK directly, which provides no explicit layers. "
                    + "Route through the Khronos loader "
                    + "(-Dorg.lwjgl.vulkan.libname=/opt/homebrew/lib/libvulkan.dylib) "
                    + "and install the layers (brew install vulkan-validationlayers).");
            }
            this.validationEnabled = haveValidation;
            boolean syncValidation = false;
            if (haveValidation) {
                ici.ppEnabledLayerNames(stack.pointers(stack.UTF8(VALIDATION_LAYER)));
                // メッセンジャが無いとレイヤの指摘がどこにも出ない
                if (instanceExtensions.contains("VK_EXT_debug_utils")) wanted.add("VK_EXT_debug_utils");

                // 同期バリデーションを有効にする。既定では OFF であり、
                // これを入れないとバリア記述漏れ (SYNC-HAZARD-*) は検出されない。
                //
                // ⚠ VK_EXT_validation_features は**レイヤが提供する**拡張なので、
                // vkEnumerateInstanceExtensionProperties(null, ...) には現れない。
                // レイヤ名を指定して列挙しないと見つからず、
                // 「有効にしたつもりで実は無効」という最悪の状態になる
                // (実際に一度そうなっており、ネガティブ対照テストで発覚した)。
                var layerExtensions = enumerateInstanceExtensions(stack, VALIDATION_LAYER);
                if (!layerExtensions.contains("VK_EXT_validation_features")) {
                    throw new IllegalStateException(
                        "validation layer is present but does not provide VK_EXT_validation_features; "
                      + "synchronization validation cannot be enabled. Layer extensions: " + layerExtensions);
                }
                wanted.add("VK_EXT_validation_features");
                var enables = stack.ints(
                    org.lwjgl.vulkan.EXTValidationFeatures.VK_VALIDATION_FEATURE_ENABLE_SYNCHRONIZATION_VALIDATION_EXT);
                var vf = org.lwjgl.vulkan.VkValidationFeaturesEXT.calloc(stack)
                    .sType$Default()
                    .pEnabledValidationFeatures(enables);
                vf.pNext(ici.pNext());
                ici.pNext(vf.address());
                syncValidation = true;
            }
            this.syncValidationEnabled = syncValidation;

            if (!wanted.isEmpty()) {
                PointerBuffer names = stack.mallocPointer(wanted.size());
                for (int i = 0; i < wanted.size(); i++) names.put(i, stack.UTF8(wanted.get(i)));
                ici.ppEnabledExtensionNames(names);
            }

            PointerBuffer pp = stack.mallocPointer(1);
            check(vkCreateInstance(ici, null, pp), "vkCreateInstance");
            this.instance = new VkInstance(pp.get(0), ici);
            if (haveValidation) this.createDebugMessenger();

            IntBuffer count = stack.mallocInt(1);
            check(vkEnumeratePhysicalDevices(instance, count, null), "enumerate");
            if (count.get(0) == 0) throw new IllegalStateException("no Vulkan device");
            PointerBuffer devs = stack.mallocPointer(count.get(0));
            check(vkEnumeratePhysicalDevices(instance, count, devs), "enumerate");
            this.physical = new VkPhysicalDevice(devs.get(0), instance);

            VkPhysicalDeviceSubgroupProperties subgroupProps =
                VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
                .sType$Default().pNext(subgroupProps.address());
            vkGetPhysicalDeviceProperties2(physical, props2);

            VkPhysicalDeviceProperties props = props2.properties();
            this.timestampPeriod = props.limits().timestampPeriod();
            this.maxPushConstantsSize = props.limits().maxPushConstantsSize();
            this.subgroupSize = subgroupProps.subgroupSize();
            this.hasSubgroup = this.subgroupSize > 1;
            this.minStorageBufferOffsetAlignment = props.limits().minStorageBufferOffsetAlignment();
            this.minUniformBufferOffsetAlignment = props.limits().minUniformBufferOffsetAlignment();

            this.memProps = VkPhysicalDeviceMemoryProperties.calloc();
            vkGetPhysicalDeviceMemoryProperties(physical, memProps);

            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            VkQueueFamilyProperties.Buffer qfp =
                VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, qfp);
            int family = -1;
            for (int i = 0; i < qfp.capacity(); i++) {
                if ((qfp.get(i).queueFlags() & VK_QUEUE_GRAPHICS_BIT) != 0) { family = i; break; }
            }
            if (family < 0) throw new IllegalStateException("no graphics queue family");
            this.queueFamily = family;
            // ⚠ タイムスタンプは**使えるとは限らない**。0 なら計測器は数字を出してはいけない
            // [Phase 5c-4c]。「対応していない」と「0 ms だった」は別物である [規約 18]
            this.timestampValidBits = qfp.get(family).timestampValidBits();

            VkDeviceQueueCreateInfo.Buffer qci = VkDeviceQueueCreateInfo.calloc(1, stack)
                .sType$Default().queueFamilyIndex(queueFamily)
                .pQueuePriorities(stack.floats(1.0f));

            VkPhysicalDeviceFeatures feats = VkPhysicalDeviceFeatures.calloc(stack)
                .multiDrawIndirect(true)
                .drawIndirectFirstInstance(true)
                .shaderInt64(true)
                .fragmentStoresAndAtomics(true)
                .vertexPipelineStoresAndAtomics(true);

            VkPhysicalDeviceVulkan11Features feats11 =
                VkPhysicalDeviceVulkan11Features.calloc(stack)
                    .sType$Default().shaderDrawParameters(true);

            // synchronization2 は 1.3 でコアになった機能だが、機能として明示的に
            // 有効化しないと vkCmdPipelineBarrier2 が使えない。
            // バリア翻訳ではこちらを使う (stage/access が 64bit になり、
            // TOP_OF_PIPE/BOTTOM_OF_PIPE の曖昧さが NONE で表現できる)。
            // dynamicRendering は VkRenderPass / VkFramebuffer の管理を不要にする。
            // GlFramebuffer (59行) + GlRenderBuffer (20行) の移植先が
            // 「描画開始時にアタッチメントを渡すだけ」に単純化される。
            VkPhysicalDeviceVulkan13Features feats13 =
                VkPhysicalDeviceVulkan13Features.calloc(stack)
                    .sType$Default()
                    .synchronization2(true)
                    .dynamicRendering(true);
            feats11.pNext(feats13.address());

            VkDeviceCreateInfo dci = VkDeviceCreateInfo.calloc(stack)
                .sType$Default().pNext(feats11.address())
                .pQueueCreateInfos(qci).pEnabledFeatures(feats);

            // MoltenVK は portability 実装なので、対応していれば
            // VK_KHR_portability_subset を必ず有効にしなければならない
            // (VUID-VkDeviceCreateInfo-pProperties-04451)。
            // ローダーを介さない場合はこの拡張自体が現れないため、条件付きで足す。
            var deviceExtensions = enumerateDeviceExtensions(stack, physical);
            var wantedDeviceExts = new java.util.ArrayList<String>();
            if (deviceExtensions.contains("VK_KHR_portability_subset")) {
                wantedDeviceExts.add("VK_KHR_portability_subset");
            }
            // interop (IOSurface → VkImage) に使う。
            // Phase 0 §8.5 で「明示的に有効化しなくても MoltenVK は受け付ける」と分かっているが、
            // ローダー + バリデーション経由では有効化していない拡張の構造体を渡すと
            // 指摘されるため、あれば必ず有効にする。
            this.hasMetalObjects = deviceExtensions.contains("VK_EXT_metal_objects");
            if (this.hasMetalObjects) {
                wantedDeviceExts.add("VK_EXT_metal_objects");
            }
            if (!wantedDeviceExts.isEmpty()) {
                PointerBuffer devExts = stack.mallocPointer(wantedDeviceExts.size());
                for (int i = 0; i < wantedDeviceExts.size(); i++) {
                    devExts.put(i, stack.UTF8(wantedDeviceExts.get(i)));
                }
                dci.ppEnabledExtensionNames(devExts);
            }

            check(vkCreateDevice(physical, dci, null, pp), "vkCreateDevice");
            this.device = new VkDevice(pp.get(0), physical, dci);

            vkGetDeviceQueue(device, queueFamily, 0, pp);
            this.queue = new VkQueue(pp.get(0), device);

            VkCommandPoolCreateInfo pci = VkCommandPoolCreateInfo.calloc(stack)
                .sType$Default()
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(queueFamily);
            long[] pPool = new long[1];
            check(vkCreateCommandPool(device, pci, null, pPool), "vkCreateCommandPool");
            this.commandPool = pPool[0];

            this.unifiedMemoryType = findMemoryType(~0,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
              | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
              | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            this.driverIdentity = props.deviceNameString()
                + " api=" + verStr(props.apiVersion())
                + " driver=" + verStr(props.driverVersion())
                + " vendorID=0x" + Integer.toHexString(props.vendorID())
                + " deviceID=0x" + Integer.toHexString(props.deviceID());
            Logger.info("Vulkan library: " + vulkanLibraryPath());
            Logger.info("Vulkan driver: " + this.driverIdentity);
            Logger.info("Vulkan instance API: requested=" + verStr(this.instanceApiVersion)
                + " available=" + verStr(available)
                + (this.hasVulkan14() ? "" : "  (below 1.4: dynamic_rendering / synchronization2 /"
                    + " timeline_semaphore are not core here)"));

            Logger.info("Vulkan: " + props.deviceNameString()
                + " api=" + VK_VERSION_MAJOR(props.apiVersion()) + "." + VK_VERSION_MINOR(props.apiVersion())
                + " subgroupSize=" + subgroupSize
                + " timestampBits=" + timestampValidBits + " timestampPeriod=" + timestampPeriod
                + " maxPushConstants=" + maxPushConstantsSize
                + " minSsboOffsetAlign=" + minStorageBufferOffsetAlignment
                + " unifiedMemType=" + unifiedMemoryType
                + " metalObjects=" + hasMetalObjects
                + " validation=" + validationEnabled
                + " syncValidation=" + syncValidationEnabled);
        }
    }

    /**
     * <b>採用モードの構築</b>。instance / physical / device / queue は外部のもので、
     * ここでは作らない。派生情報は渡された physical device から問い直し、
     * コマンドプールだけ自分で作る。
     *
     * @see #initAdopted
     */
    private VkContext(VkInstance instance, VkPhysicalDevice physical, VkDevice device,
                      VkQueue queue, int queueFamily,
                      boolean validationEnabled, boolean syncValidationEnabled,
                      java.util.Set<String> enabledDeviceExtensions) {
        this.adopted = true;
        this.instance = instance;
        this.physical = physical;
        this.device = device;
        this.queue = queue;
        this.queueFamily = queueFamily;
        this.validationEnabled = validationEnabled;
        this.syncValidationEnabled = syncValidationEnabled;
        this.instanceApiVersion = queryInstanceVersion();

        try (MemoryStack stack = stackPush()) {
            VkPhysicalDeviceSubgroupProperties subgroupProps =
                VkPhysicalDeviceSubgroupProperties.calloc(stack).sType$Default();
            VkPhysicalDeviceProperties2 props2 = VkPhysicalDeviceProperties2.calloc(stack)
                .sType$Default().pNext(subgroupProps.address());
            vkGetPhysicalDeviceProperties2(physical, props2);
            VkPhysicalDeviceProperties props = props2.properties();
            this.timestampPeriod = props.limits().timestampPeriod();
            this.maxPushConstantsSize = props.limits().maxPushConstantsSize();
            this.subgroupSize = subgroupProps.subgroupSize();
            this.hasSubgroup = this.subgroupSize > 1;
            this.minStorageBufferOffsetAlignment = props.limits().minStorageBufferOffsetAlignment();
            this.minUniformBufferOffsetAlignment = props.limits().minUniformBufferOffsetAlignment();

            this.memProps = VkPhysicalDeviceMemoryProperties.calloc();
            vkGetPhysicalDeviceMemoryProperties(physical, this.memProps);

            IntBuffer count = stack.mallocInt(1);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, null);
            VkQueueFamilyProperties.Buffer qfp = VkQueueFamilyProperties.calloc(count.get(0), stack);
            vkGetPhysicalDeviceQueueFamilyProperties(physical, count, qfp);
            if (queueFamily < 0 || queueFamily >= qfp.capacity()) {
                throw new IllegalArgumentException("adopted queue family " + queueFamily
                    + " is outside the device's " + qfp.capacity() + " families");
            }
            var family = qfp.get(queueFamily);
            if ((family.queueFlags() & VK_QUEUE_GRAPHICS_BIT) == 0) {
                throw new IllegalArgumentException("adopted queue family " + queueFamily
                    + " has no graphics support (flags 0x" + Integer.toHexString(family.queueFlags()) + ")");
            }
            // ⚠ グラフィクス対応ファミリは仕様上 compute も対応する。これを確かめておくのは、
            // Voxy が traversal/cull/table 生成を同じ queue に流すため。
            if ((family.queueFlags() & VK_QUEUE_COMPUTE_BIT) == 0) {
                throw new IllegalArgumentException("adopted queue family " + queueFamily
                    + " has no compute support, so Voxy's compute passes cannot share it");
            }
            this.timestampValidBits = family.timestampValidBits();

            // ⚠ IOSurface interop は device を作った側が拡張を<b>有効にしていないと</b>使えない。
            // round-1 review N4: ここで物理デバイスの対応可否を見ていたのは誤りで、
            // 「MC が有効にしたか」を見なければならない (MC は VK_EXT_metal_objects を
            // 有効にしないので、採用モードではこの経路が自動的に無効になる)。
            this.hasMetalObjects = enabledDeviceExtensions != null
                && enabledDeviceExtensions.contains("VK_EXT_metal_objects");

            VkCommandPoolCreateInfo pci = VkCommandPoolCreateInfo.calloc(stack)
                .sType$Default()
                .flags(VK_COMMAND_POOL_CREATE_RESET_COMMAND_BUFFER_BIT)
                .queueFamilyIndex(queueFamily);
            long[] pPool = new long[1];
            check(vkCreateCommandPool(device, pci, null, pPool), "vkCreateCommandPool(adopted)");
            this.commandPool = pPool[0];

            this.unifiedMemoryType = findMemoryType(~0,
                VK_MEMORY_PROPERTY_DEVICE_LOCAL_BIT
              | VK_MEMORY_PROPERTY_HOST_VISIBLE_BIT
              | VK_MEMORY_PROPERTY_HOST_COHERENT_BIT);

            this.driverIdentity = props.deviceNameString()
                + " api=" + verStr(props.apiVersion())
                + " driver=" + verStr(props.driverVersion())
                + " vendorID=0x" + Integer.toHexString(props.vendorID())
                + " deviceID=0x" + Integer.toHexString(props.deviceID())
                + " (adopted)";
            Logger.info("Vulkan (adopted from the host application): " + this.driverIdentity
                + " queueFamily=" + queueFamily
                + " subgroupSize=" + this.subgroupSize
                + " timestampBits=" + this.timestampValidBits
                + " maxPushConstants=" + this.maxPushConstantsSize
                + " minSsboOffsetAlign=" + this.minStorageBufferOffsetAlignment
                + " unifiedMemType=" + this.unifiedMemoryType
                + " metalObjects=" + this.hasMetalObjects
                + " validation=" + this.validationEnabled
                + " syncValidation=" + this.syncValidationEnabled);
        }
    }

    private static java.util.Set<String> enumerateInstanceLayers(MemoryStack stack) {
        var out = new java.util.HashSet<String>();
        IntBuffer count = stack.mallocInt(1);
        if (vkEnumerateInstanceLayerProperties(count, null) != VK_SUCCESS || count.get(0) == 0) return out;
        var props = VkLayerProperties.calloc(count.get(0), stack);
        vkEnumerateInstanceLayerProperties(count, props);
        for (int i = 0; i < props.capacity(); i++) out.add(props.get(i).layerNameString());
        return out;
    }

    private static java.util.Set<String> enumerateInstanceExtensions(MemoryStack stack) {
        return enumerateInstanceExtensions(stack, null);
    }

    /**
     * @param layer レイヤ名。null なら ICD / implicit のみ。
     *              <b>レイヤが提供する拡張はレイヤ名を渡さないと出てこない</b>。
     */
    private static java.util.Set<String> enumerateInstanceExtensions(MemoryStack stack, String layer) {
        var out = new java.util.HashSet<String>();
        IntBuffer count = stack.mallocInt(1);
        if (vkEnumerateInstanceExtensionProperties(layer, count, null) != VK_SUCCESS
            || count.get(0) == 0) return out;
        var props = VkExtensionProperties.calloc(count.get(0), stack);
        vkEnumerateInstanceExtensionProperties(layer, count, props);
        for (int i = 0; i < props.capacity(); i++) out.add(props.get(i).extensionNameString());
        return out;
    }

    private static java.util.Set<String> enumerateDeviceExtensions(MemoryStack stack, VkPhysicalDevice dev) {
        var out = new java.util.HashSet<String>();
        IntBuffer count = stack.mallocInt(1);
        if (vkEnumerateDeviceExtensionProperties(dev, (String) null, count, null) != VK_SUCCESS
            || count.get(0) == 0) return out;
        var props = VkExtensionProperties.calloc(count.get(0), stack);
        vkEnumerateDeviceExtensionProperties(dev, (String) null, count, props);
        for (int i = 0; i < props.capacity(); i++) out.add(props.get(i).extensionNameString());
        return out;
    }

    /**
     * バリデーションの指摘を Logger に流すメッセンジャ。
     * 有効時のみ生成される。ERROR は同期漏れの主要な検出手段なので確実に見えるようにする。
     */
    private long debugMessenger = VK_NULL_HANDLE;

    /**
     * バリデーションの指摘を貯める。テストが「バリアを外すと本当に検出されるか」を
     * 確かめるために使う。<b>バリデーションが有効なときだけ溜まる。</b>
     */
    private static final java.util.List<String> VALIDATION_MESSAGES =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    public static void clearValidationMessages() { VALIDATION_MESSAGES.clear(); }

    public static java.util.List<String> validationMessages() {
        synchronized (VALIDATION_MESSAGES) { return java.util.List.copyOf(VALIDATION_MESSAGES); }
    }

    /** 同期バリデーション由来の指摘 (SYNC-HAZARD-*) だけを取り出す。 */
    public static java.util.List<String> syncHazards() {
        return validationMessages().stream().filter(m -> m.contains("SYNC-HAZARD")).toList();
    }

    // ---------------- interop 画像に対する既知のノイズの抑制 ----------------
    //
    // docs/phase5b-composite.md §3 に何をなぜ抑制しているかを書いてある。
    // バリデーションが IOSurface backed の画像を理解するようになったら、
    // ここと INTEROP_NOISE_VUIDS ごと消せる。

    /**
     * IOSurface backed の画像に対してだけ黙らせる VUID。
     *
     * <p>{@code VkImportMetalIOSurfaceInfoEXT} で作った画像には
     * {@code vkBindImageMemory} を呼ばない (MoltenVK が内部で IOSurface を紐づける) が、
     * レイヤは自前でバインドを追跡しているため
     * <b>「メモリがバインドされていない」と言い続ける</b> [確認済 — Phase 5a §6.3]。
     *
     * <p><b>この 3 つだけ</b>を、<b>登録済みの interop ハンドルを含むメッセージに限って</b>落とす。
     * 同じ VUID でも別のハンドルなら通るし、同じハンドルでも別の VUID なら通る。
     */
    private static final String[] INTEROP_NOISE_VUIDS = {
        "VUID-VkImageViewCreateInfo-image-01020",
        "VUID-VkImageMemoryBarrier-image-01932",
        "VUID-vkCmdCopyImageToBuffer-srcImage-07966",
    };

    /** 登録済みの interop 画像ハンドル (メッセージ中の "0x..." 表記)。 */
    private static final java.util.Set<String> INTEROP_IMAGE_TOKENS =
        java.util.Collections.synchronizedSet(new java.util.HashSet<>());

    private static final java.util.List<String> SUPPRESSED_MESSAGES =
        java.util.Collections.synchronizedList(new java.util.ArrayList<>());

    /** {@link me.cortex.voxy.client.core.vk.interop.VkInteropImage} が生成時に呼ぶ。 */
    public static void registerInteropImage(long handle) {
        INTEROP_IMAGE_TOKENS.add("0x" + Long.toHexString(handle));
    }

    /** 破棄時に呼ぶ。<b>外し忘れると別の資源が同じハンドルを再利用したときに黙る</b>。 */
    public static void unregisterInteropImage(long handle) {
        INTEROP_IMAGE_TOKENS.remove("0x" + Long.toHexString(handle));
    }

    /**
     * 抑制対象か。<b>VUID とハンドルの両方が一致したときだけ true。</b>
     *
     * <p>public にしてあるのはテストが「抑制が広すぎないこと」を直接確かめるため。
     */
    public static boolean isSuppressedInteropNoise(String message) {
        boolean vuidMatches = false;
        for (String v : INTEROP_NOISE_VUIDS) {
            if (message.contains(v)) { vuidMatches = true; break; }
        }
        if (!vuidMatches) return false;
        synchronized (INTEROP_IMAGE_TOKENS) {
            for (String token : INTEROP_IMAGE_TOKENS) {
                // "VkImage 0x30000000003 used with no memory bound" のような形で出る。
                // 前方一致だけだと 0x3000... が 0x30001... に誤ヒットするので境界も見る
                int i = message.indexOf(token);
                while (i >= 0) {
                    int end = i + token.length();
                    char next = end < message.length() ? message.charAt(end) : ' ';
                    if (!isHexDigit(next)) return true;
                    i = message.indexOf(token, i + 1);
                }
            }
        }
        return false;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }

    /** 抑制した件数。<b>0 でないことを doc の主張と突き合わせるために公開している。</b> */
    public static int suppressedValidationCount() { return SUPPRESSED_MESSAGES.size(); }

    public static java.util.List<String> suppressedValidationMessages() {
        synchronized (SUPPRESSED_MESSAGES) { return java.util.List.copyOf(SUPPRESSED_MESSAGES); }
    }

    public static void clearSuppressedValidationMessages() { SUPPRESSED_MESSAGES.clear(); }

    private void createDebugMessenger() {
        try (MemoryStack stack = stackPush()) {
            var ci = org.lwjgl.vulkan.VkDebugUtilsMessengerCreateInfoEXT.calloc(stack)
                .sType$Default()
                .messageSeverity(
                      org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_WARNING_BIT_EXT
                    | org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT)
                .messageType(
                      org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_VALIDATION_BIT_EXT
                    | org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_TYPE_PERFORMANCE_BIT_EXT)
                .pfnUserCallback((severity, types, pCallbackData, pUserData) -> {
                    var data = org.lwjgl.vulkan.VkDebugUtilsMessengerCallbackDataEXT.create(pCallbackData);
                    String raw = data.pMessageString();
                    if (isSuppressedInteropNoise(raw)) {
                        // 既知のノイズ。数だけ残して黙らせる (docs/phase5b-composite.md §3)
                        SUPPRESSED_MESSAGES.add(raw);
                        return VK_FALSE;
                    }
                    // ⚠ 構造化 ID (pMessageIdName) を先頭に残す。
                    // 自由文の {@code pMessageString} は VVL のバージョンで言い回しが変わる
                    // ("SYNC-HAZARD-..." ではなく "WRITE_AFTER_WRITE hazard detected" 等) が、
                    // ID 名のほうは安定している [規約 9 — ホスト側の規約は確かめてから選ぶ。
                    // ここで確かめずに自由文へ部分一致していたのが後述のバグだった]
                    String idName = data.pMessageIdNameString();
                    String msg = "[vk-validation] "
                        + (idName != null && !idName.isEmpty() ? "[" + idName + "] " : "") + raw;
                    VALIDATION_MESSAGES.add(msg);
                    if ((severity & org.lwjgl.vulkan.EXTDebugUtils.VK_DEBUG_UTILS_MESSAGE_SEVERITY_ERROR_BIT_EXT) != 0) {
                        Logger.error(msg);
                    } else {
                        Logger.warn(msg);
                    }
                    return VK_FALSE;
                });
            long[] p = new long[1];
            int r = org.lwjgl.vulkan.EXTDebugUtils.vkCreateDebugUtilsMessengerEXT(this.instance, ci, null, p);
            if (r == VK_SUCCESS) {
                this.debugMessenger = p[0];
            } else {
                Logger.warn("vkCreateDebugUtilsMessengerEXT failed: " + r);
            }
        }
    }

    public int findMemoryType(int typeBits, int required) {
        for (int i = 0; i < memProps.memoryTypeCount(); i++) {
            if ((typeBits & (1 << i)) != 0
                && (memProps.memoryTypes(i).propertyFlags() & required) == required) {
                return i;
            }
        }
        throw new IllegalStateException("no memory type: bits=0x" + Integer.toHexString(typeBits)
            + " req=0x" + Integer.toHexString(required));
    }

    private void destroy() {
        // 作ったものだけ壊す。採用モードでは instance/device/queue は借り物なので触らない
        // (壊すと Minecraft 自身のレンダラを殺すことになる)。
        vkDestroyCommandPool(device, commandPool, null);
        if (this.debugMessenger != VK_NULL_HANDLE) {
            org.lwjgl.vulkan.EXTDebugUtils.vkDestroyDebugUtilsMessengerEXT(instance, this.debugMessenger, null);
            this.debugMessenger = VK_NULL_HANDLE;
        }
        if (!this.adopted) {
            vkDestroyDevice(device, null);
        }
        memProps.free();
        if (!this.adopted) {
            vkDestroyInstance(instance, null);
        }
    }

    private static String verStr(int v) {
        return VK_VERSION_MAJOR(v) + "." + VK_VERSION_MINOR(v) + "." + VK_VERSION_PATCH(v);
    }

    public static void check(int r, String what) {
        if (r != VK_SUCCESS) throw new IllegalStateException(what + " -> VkResult " + r);
    }
}
