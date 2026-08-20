package me.cortex.voxy.vk.bench;

import me.cortex.voxy.client.core.vk.VkBuffer;
import me.cortex.voxy.client.core.vk.VkContext;
import me.cortex.voxy.client.core.vk.interop.Cgl;
import me.cortex.voxy.client.core.vk.interop.VkInteropImage;
import org.lwjgl.PointerBuffer;
import org.lwjgl.opengl.GL;
import org.lwjgl.system.MemoryStack;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkBufferImageCopy;
import org.lwjgl.vulkan.VkCommandBuffer;
import org.lwjgl.vulkan.VkCommandBufferAllocateInfo;
import org.lwjgl.vulkan.VkCommandBufferBeginInfo;
import org.lwjgl.vulkan.VkSubmitInfo;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;

import static org.lwjgl.glfw.GLFW.*;
import static org.lwjgl.opengl.GL11C.*;
import static org.lwjgl.opengl.GL15C.*;
import static org.lwjgl.opengl.GL20C.*;
import static org.lwjgl.opengl.GL30C.*;
import static org.lwjgl.opengl.GL32C.*;
import static org.lwjgl.opengl.GL33C.*;
import static org.lwjgl.system.MemoryStack.stackPush;
import static org.lwjgl.system.MemoryUtil.NULL;
import static org.lwjgl.vulkan.VK10.*;

/**
 * Phase 5a — <b>GL → Vulkan 方向の同期コストの実測</b>。
 *
 * <p>Phase 0 の B-3 (0.315 ms) は <b>Vulkan → GL の片方向しか測っていない</b>
 * [確認済 — {@code Step8} は GL 側の書き込みを含まない]。
 * しかし Vulkan 側の cull と {@code depthTex} は
 * 「MC が既に描いた地形の深度」を要求するので逆方向の同期も要る
 * [docs/phase5-proposal.md §2.2]。その未測定の数字を埋めるのがこのベンチである。
 *
 * <h2>模したフレーム</h2>
 * <pre>
 * [GL] 地形パス × N          MC の地形描画に相当 (D32F の深度に書く)
 * [GL] 深度コピーパス        d32 → interop の R32F IOSurface (initDepthStencil の役割 1)
 * [SYNC]                    ← 測定対象
 * [VK] interop 深度を読む    vkCmdCopyImageToBuffer → ホスト可視バッファ
 * [VK] vkQueueWaitIdle
 * [CPU] 値の検証
 * </pre>
 *
 * Vulkan → GL の合成は含めない。<b>片方向を分離して測るのが目的</b>で、
 * 逆方向は Phase 0 が既に測っている。
 *
 * <h2>測るもの</h2>
 * <ul>
 *   <li>{@code sync_ms} — 同期プリミティブに費やした wall clock</li>
 *   <li>{@code frame_ms} — GL 発行 〜 Vulkan の待ち完了まで</li>
 *   <li>{@code gl_gpu_ms} — GL 側 GPU 時間 ({@code GL_TIME_ELAPSED})</li>
 *   <li><b>{@code frame_ms(FINISH) − frame_ms(NONE)}</b> — 同期で失う実コスト</li>
 * </ul>
 *
 * <h2>⚠ 対照 (docs/phase4-stage1-completion.md §5.2 の運用)</h2>
 * 「同期無しでも値が合ってしまう」ことは <b>同期が不要である証拠にはならない</b>。
 * この検査を空虚に満たす経路を潰すため、以下を必ず一緒に走らせる:
 *
 * <ul>
 *   <li><b>C1 読み手の生存</b> — FINISH で毎回値が変わり、期待値とビット一致すること。
 *       読み戻しが定数を返しているなら実験全体が無効。</li>
 *   <li><b>C2 陳腐検出器の感度</b> — わざと GL 書き込みを飛ばした回に、
 *       検査が「前の回の値」を報告できること。
 *       これができないなら NONE で不一致が出ないことに意味がない。</li>
 *   <li><b>C3 失敗の構成可能性</b> — NONE + 高負荷で不一致が出るか。
 *       出ないなら「同期不要」ではなく<b>「失敗例を構成できなかった」</b>と記録する。</li>
 * </ul>
 */
public final class GlToVkSyncBench {

    // ---------------- パラメータ ----------------

    static int W = 1920, H = 1080;
    static int WARMUP = 10, ITERS = 60;
    /** 地形パスのフラグメント内ループ回数。上限は定数で切ってある (GPU ハング防止)。 */
    static int BURN = 64;
    /** 地形パスの枚数。1 以上 (深度を書く回が必ず要るため)。 */
    static int[] LOADS = {1, 4, 16, 64};

    enum Sync {
        /** 同期しない。<b>正しくない</b>。コストの下限を出すための対照。 */
        NONE,
        /** {@code glFlush} — 発行するが待たない。 */
        FLUSH,
        /** {@code glFenceSync} + {@code glClientWaitSync(FLUSH_COMMANDS)}。 */
        FENCE,
        /** {@code glFinish} — GL のパイプラインを完全に空にする。 */
        FINISH
    }

    // ---------------- GL シェーダ ----------------

    static final String VS_FULLSCREEN = """
        #version 410 core
        void main() {
            vec2 p = vec2((gl_VertexID << 1) & 2, gl_VertexID & 2);
            gl_Position = vec4(p * 2.0 - 1.0, 0.0, 1.0);
        }
        """;

    /** MC の地形描画に相当する負荷。深度に uDepth をそのまま書く (値の連鎖を保つため)。 */
    static final String FS_TERRAIN = """
        #version 410 core
        uniform float uDepth;
        uniform int uBurn;
        out vec4 fragColor;
        void main() {
            float acc = gl_FragCoord.x * 1e-4;
            for (int i = 0; i < 512; i++) {      // 上限は定数。GPU 上のループは必ず切る
                if (i >= uBurn) break;
                acc = fract(acc * 1.0001 + sin(float(i) + acc));
            }
            fragColor = vec4(acc, 0.0, 0.0, 1.0);
            gl_FragDepth = uDepth;
        }
        """;

    /** initDepthStencil の役割 1 (d32 → interop の R32F) に相当。 */
    static final String FS_DEPTH_COPY = """
        #version 410 core
        uniform sampler2D uSrcDepth;
        out vec4 fragColor;
        void main() {
            float d = texelFetch(uSrcDepth, ivec2(gl_FragCoord.xy), 0).r;
            fragColor = vec4(d, 0.0, 0.0, 1.0);
        }
        """;

    // ---------------- 読み戻し領域 ----------------

    /** 3 箇所 x 16x16 を読む。ラスタ順の末尾 (右下) を必ず含める — 競合が最も出やすい。 */
    static final int PROBE = 16;
    static final int PROBES = 3;
    static final int PROBE_TEXELS = PROBE * PROBE;

    // ---------------- 状態 ----------------

    static long window;
    static VkInteropImage depthInterop;
    /** interop ではない同サイズの画像。C4 の対照。 */
    static me.cortex.voxy.client.core.vk.VkTexture privateTex;
    static VkBuffer readback;
    static VkCommandBuffer cmd;
    static int fboTerrain, fboInterop;
    static int texMcDepth, texMcColor;
    static int progTerrain, progCopy, vao;
    static int locDepth, locBurn, locSrc;
    static int glQuery;

    public static void main(String[] args) {
        parseArgs(args);

        initGl();
        VkContext.init();
        if (!VkContext.get().hasMetalObjects) {
            System.out.println("note: VK_EXT_metal_objects is not advertised; "
                + "MoltenVK accepts the import struct anyway (Phase 0 8.5)");
        }

        try {
            setupResources();
            primeLayout();

            System.out.println();
            System.out.println("=== controls ===");
            boolean c1 = controlReaderLiveness();
            boolean c2 = controlStalenessDetector();
            if (!c1 || !c2) {
                System.out.println();
                System.out.println("!! control failed -> the measurement below is meaningless. stop.");
                return;
            }

            System.out.println();
            System.out.println("=== sweep ===");
            sweep();
        } finally {
            teardown();
        }
    }

    static void parseArgs(String[] args) {
        for (int i = 0; i < args.length; i++) {
            switch (args[i]) {
                case "-w" -> W = Integer.parseInt(args[++i]);
                case "-h" -> H = Integer.parseInt(args[++i]);
                case "--iters" -> ITERS = Integer.parseInt(args[++i]);
                case "--warmup" -> WARMUP = Integer.parseInt(args[++i]);
                case "--burn" -> BURN = Integer.parseInt(args[++i]);
                case "--loads" -> LOADS = Arrays.stream(args[++i].split(","))
                    .mapToInt(Integer::parseInt).toArray();
                default -> throw new IllegalArgumentException("unknown arg: " + args[i]);
            }
        }
    }

    // ---------------- setup ----------------

    static void initGl() {
        if (!glfwInit()) throw new IllegalStateException("glfwInit failed (need -XstartOnFirstThread)");
        glfwWindowHint(GLFW_VISIBLE, GLFW_FALSE);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MAJOR, 4);
        glfwWindowHint(GLFW_CONTEXT_VERSION_MINOR, 1);
        glfwWindowHint(GLFW_OPENGL_PROFILE, GLFW_OPENGL_CORE_PROFILE);
        glfwWindowHint(GLFW_OPENGL_FORWARD_COMPAT, GLFW_TRUE);
        window = glfwCreateWindow(W, H, "phase5a", NULL, NULL);
        if (window == NULL) throw new IllegalStateException("glfwCreateWindow failed");
        glfwMakeContextCurrent(window);
        GL.createCapabilities();

        System.out.println("GL: " + glGetString(GL_RENDERER) + " / " + glGetString(GL_VERSION));
        System.out.println("resolution: " + W + "x" + H + "  burn=" + BURN
            + "  warmup=" + WARMUP + " iters=" + ITERS);
    }

    static void setupResources() {
        depthInterop = new VkInteropImage(W, H, VkInteropImage.Kind.DEPTH_R32F,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_SAMPLED_BIT);
        System.out.printf("interop depth: vk memReq=%d  IOSurface allocSize=%d bytesPerRow=%d%n",
            depthInterop.memoryRequirementSize, depthInterop.allocSize,
            me.cortex.voxy.client.core.vk.interop.IOSurf.bytesPerRow(depthInterop.ioSurface()));

        // --- GL 側 ---
        vao = glGenVertexArrays();
        glQuery = glGenQueries();

        texMcColor = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texMcColor);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_RGBA8, W, H, 0, GL_RGBA, GL_UNSIGNED_BYTE, (java.nio.ByteBuffer) null);

        texMcDepth = glGenTextures();
        glBindTexture(GL_TEXTURE_2D, texMcDepth);
        glTexImage2D(GL_TEXTURE_2D, 0, GL_DEPTH_COMPONENT32F, W, H, 0,
            GL_DEPTH_COMPONENT, GL_FLOAT, (java.nio.ByteBuffer) null);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MIN_FILTER, GL_NEAREST);
        glTexParameteri(GL_TEXTURE_2D, GL_TEXTURE_MAG_FILTER, GL_NEAREST);

        fboTerrain = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboTerrain);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0, GL_TEXTURE_2D, texMcColor, 0);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_DEPTH_ATTACHMENT, GL_TEXTURE_2D, texMcDepth, 0);
        requireComplete("terrain FBO");

        int glTex = depthInterop.glTexture();
        fboInterop = glGenFramebuffers();
        glBindFramebuffer(GL_FRAMEBUFFER, fboInterop);
        glFramebufferTexture2D(GL_FRAMEBUFFER, GL_COLOR_ATTACHMENT0,
            Cgl.GL_TEXTURE_RECTANGLE, glTex, 0);
        requireComplete("interop FBO");

        progTerrain = link(VS_FULLSCREEN, FS_TERRAIN);
        locDepth = glGetUniformLocation(progTerrain, "uDepth");
        locBurn = glGetUniformLocation(progTerrain, "uBurn");
        progCopy = link(VS_FULLSCREEN, FS_DEPTH_COPY);
        locSrc = glGetUniformLocation(progCopy, "uSrcDepth");

        glViewport(0, 0, W, H);

        // --- Vulkan 側 ---
        // VkTexture は必ずビューを作るので、ビューを作れる usage を 1 つ以上含める必要がある
        // (VUID-VkImageViewCreateInfo-image-04441)。transfer だけでは足りない。
        privateTex = new me.cortex.voxy.client.core.vk.VkTexture(VK_FORMAT_R32_SFLOAT, 1, W, H,
            VK_IMAGE_USAGE_TRANSFER_SRC_BIT | VK_IMAGE_USAGE_TRANSFER_DST_BIT
                | VK_IMAGE_USAGE_SAMPLED_BIT);
        readback = new VkBuffer((long) PROBES * PROBE_TEXELS * 4, VK_BUFFER_USAGE_TRANSFER_DST_BIT, true);
        try (MemoryStack stack = stackPush()) {
            var cbai = VkCommandBufferAllocateInfo.calloc(stack).sType$Default()
                .commandPool(VkContext.get().commandPool)
                .level(VK_COMMAND_BUFFER_LEVEL_PRIMARY).commandBufferCount(1);
            PointerBuffer p = stack.mallocPointer(1);
            VkContext.check(vkAllocateCommandBuffers(VkContext.get().device, cbai, p), "alloc cmd");
            cmd = new VkCommandBuffer(p.get(0), VkContext.get().device);
        }
    }

    /**
     * {@code UNDEFINED} → {@code GENERAL} を <b>GL が書く前に一度だけ</b>済ませる。
     * これを毎フレームやると GL の書き込みが捨てられる
     * [{@link VkInteropImage} のクラス javadoc]。
     */
    static void primeLayout() {
        try (MemoryStack stack = stackPush()) {
            beginCmd(stack);
            depthInterop.toGeneral(cmd, VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            privateTex.barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TOP_OF_PIPE_BIT, 0,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
            VkContext.check(vkEndCommandBuffer(cmd), "end");
            submitAndWait(stack);
        }
    }

    static void requireComplete(String what) {
        int s = glCheckFramebufferStatus(GL_FRAMEBUFFER);
        if (s != GL_FRAMEBUFFER_COMPLETE) {
            throw new IllegalStateException(what + " incomplete: 0x" + Integer.toHexString(s));
        }
    }

    static int link(String vs, String fs) {
        int v = compile(GL_VERTEX_SHADER, vs);
        int f = compile(GL_FRAGMENT_SHADER, fs);
        int p = glCreateProgram();
        glAttachShader(p, v);
        glAttachShader(p, f);
        glLinkProgram(p);
        if (glGetProgrami(p, GL_LINK_STATUS) != GL_TRUE) {
            throw new IllegalStateException("link: " + glGetProgramInfoLog(p));
        }
        glDeleteShader(v);
        glDeleteShader(f);
        return p;
    }

    static int compile(int type, String src) {
        int s = glCreateShader(type);
        glShaderSource(s, src);
        glCompileShader(s);
        if (glGetShaderi(s, GL_COMPILE_STATUS) != GL_TRUE) {
            throw new IllegalStateException("compile: " + glGetShaderInfoLog(s));
        }
        return s;
    }

    // ---------------- フレームの各段 ----------------

    /** GL: 地形パス × load + 深度コピー。GPU 時間クエリで囲む (結果はここでは読まない)。 */
    static void recordGl(int load, float depthValue) {
        glBeginQuery(GL_TIME_ELAPSED, glQuery);

        glBindFramebuffer(GL_FRAMEBUFFER, fboTerrain);
        glUseProgram(progTerrain);
        glBindVertexArray(vao);
        glUniform1i(locBurn, BURN);
        glUniform1f(locDepth, depthValue);
        glEnable(GL_DEPTH_TEST);
        glDepthFunc(GL_ALWAYS);
        glDepthMask(true);
        for (int i = 0; i < load; i++) {
            glDrawArrays(GL_TRIANGLES, 0, 3);
        }
        glDisable(GL_DEPTH_TEST);

        glBindFramebuffer(GL_FRAMEBUFFER, fboInterop);
        glUseProgram(progCopy);
        glActiveTexture(GL_TEXTURE0);
        glBindTexture(GL_TEXTURE_2D, texMcDepth);
        glUniform1i(locSrc, 0);
        glDrawArrays(GL_TRIANGLES, 0, 3);

        glBindVertexArray(0);
        glUseProgram(0);
        glBindFramebuffer(GL_FRAMEBUFFER, 0);

        glEndQuery(GL_TIME_ELAPSED);
    }

    static void doSync(Sync sync) {
        switch (sync) {
            case NONE -> { }
            case FLUSH -> glFlush();
            case FINISH -> glFinish();
            case FENCE -> {
                long fence = glFenceSync(GL_SYNC_GPU_COMMANDS_COMPLETE, 0);
                int r = GL_TIMEOUT_EXPIRED;
                for (int attempt = 0; attempt < 1000 && r == GL_TIMEOUT_EXPIRED; attempt++) {
                    r = glClientWaitSync(fence, GL_SYNC_FLUSH_COMMANDS_BIT, 10_000_000L);
                }
                glDeleteSync(fence);
                if (r == GL_WAIT_FAILED || r == GL_TIMEOUT_EXPIRED) {
                    throw new IllegalStateException("glClientWaitSync -> 0x" + Integer.toHexString(r));
                }
            }
        }
    }

    /** Vulkan: interop 深度の 3 箇所を読み戻す。 */
    static void recordAndSubmitVk(MemoryStack stack) {
        recordAndSubmitVk(stack, true);
    }

    /**
     * @param interop false なら <b>interop ではない自前の画像</b>から読む。
     *                C4 (GPU の取り合いか、IOSurface の依存追跡か) の切り分けに使う。
     */
    static void recordAndSubmitVk(MemoryStack stack, boolean interop) {
        beginCmd(stack);
        // GENERAL 据え置き。GL の書き込みは Vulkan のバリアでは表現できないので
        // ここでの src は「直前の transfer read」に対するもの。
        if (interop) {
            depthInterop.toGeneral(cmd,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        } else {
            privateTex.barrier(cmd, 0, VK_IMAGE_LAYOUT_GENERAL,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT,
                VK_PIPELINE_STAGE_TRANSFER_BIT, VK_ACCESS_TRANSFER_READ_BIT);
        }

        var regions = VkBufferImageCopy.calloc(PROBES, stack);
        int[][] origins = {
            {0, 0},
            {(W - PROBE) / 2, (H - PROBE) / 2},
            {W - PROBE, H - PROBE},
        };
        for (int i = 0; i < PROBES; i++) {
            var r = regions.get(i);
            r.bufferOffset((long) i * PROBE_TEXELS * 4).bufferRowLength(0).bufferImageHeight(0);
            r.imageSubresource().aspectMask(VK_IMAGE_ASPECT_COLOR_BIT)
                .mipLevel(0).baseArrayLayer(0).layerCount(1);
            r.imageOffset().set(origins[i][0], origins[i][1], 0);
            r.imageExtent().set(PROBE, PROBE, 1);
        }
        vkCmdCopyImageToBuffer(cmd, interop ? depthInterop.imageHandle() : privateTex.image,
            VK_IMAGE_LAYOUT_GENERAL, readback.handle, regions);

        VkContext.check(vkEndCommandBuffer(cmd), "end");
        var si = VkSubmitInfo.calloc(stack).sType$Default()
            .pCommandBuffers(stack.pointers(cmd));
        VkContext.check(vkQueueSubmit(VkContext.get().queue, si, VK_NULL_HANDLE), "submit");
    }

    static void beginCmd(MemoryStack stack) {
        VkContext.check(vkResetCommandBuffer(cmd, 0), "reset");
        var bi = VkCommandBufferBeginInfo.calloc(stack).sType$Default()
            .flags(VK_COMMAND_BUFFER_USAGE_ONE_TIME_SUBMIT_BIT);
        VkContext.check(vkBeginCommandBuffer(cmd, bi), "begin");
    }

    static void submitAndWait(MemoryStack stack) {
        var si = VkSubmitInfo.calloc(stack).sType$Default()
            .pCommandBuffers(stack.pointers(cmd));
        VkContext.check(vkQueueSubmit(VkContext.get().queue, si, VK_NULL_HANDLE), "submit");
        VkContext.check(vkQueueWaitIdle(VkContext.get().queue), "waitIdle");
    }

    /** 読み戻した全テクセルが同じ値ならそれを返す。ばらついていれば NaN。 */
    static float readbackValue() {
        long addr = readback.addr();
        float first = MemoryUtil.memGetFloat(addr);
        for (int i = 1; i < PROBES * PROBE_TEXELS; i++) {
            if (MemoryUtil.memGetFloat(addr + (long) i * 4) != first) return Float.NaN;
        }
        return first;
    }

    /** GL のタイマークエリを読む。<b>ここで GL は実質的にドレインされる</b>ので同期点より後で呼ぶこと。 */
    static double readGlGpuMs() {
        int[] avail = new int[1];
        long deadline = System.nanoTime() + 2_000_000_000L;
        do {
            glGetQueryObjectiv(glQuery, GL_QUERY_RESULT_AVAILABLE, avail);
            if (System.nanoTime() > deadline) throw new IllegalStateException("GL query never became available");
        } while (avail[0] == 0);
        long[] ns = new long[1];
        glGetQueryObjecti64v(glQuery, GL_QUERY_RESULT, ns);
        return ns[0] / 1e6;
    }

    /** 反復 i で GL が書く深度値。dyadic なので float でビット厳密。 */
    static float depthOf(int i) {
        return 0.25f + (i % 512) / 2048.0f;
    }

    // ---------------- 対照 ----------------

    /** C1: FINISH なら毎回ビット一致し、値が実際に変化していること。 */
    static boolean controlReaderLiveness() {
        int n = 24;
        int mismatches = 0;
        java.util.Set<Float> seen = new java.util.HashSet<>();
        for (int i = 0; i < n; i++) {
            float want = depthOf(i + 1);
            recordGl(1, want);
            glFinish();
            try (MemoryStack stack = stackPush()) {
                recordAndSubmitVk(stack);
                VkContext.check(vkQueueWaitIdle(VkContext.get().queue), "waitIdle");
            }
            float got = readbackValue();
            seen.add(got);
            if (got != want) mismatches++;
            readGlGpuMs();
        }
        boolean ok = mismatches == 0 && seen.size() == n;
        System.out.printf("C1 reader liveness   : %s  (mismatches=%d, distinct=%d/%d)%n",
            ok ? "PASS" : "FAIL", mismatches, seen.size(), n);
        if (!ok) {
            System.out.println("   -> readback is not tracking GL writes; nothing below can be trusted");
        }
        return ok;
    }

    /**
     * C2: GL の書き込みを 1 回わざと飛ばすと、検査が「前の値」を報告すること。
     * <b>これが効かないなら NONE で不一致が出ないことに何の意味も無い。</b>
     */
    static boolean controlStalenessDetector() {
        float prev = depthOf(101);
        recordGl(1, prev);
        glFinish();
        try (MemoryStack stack = stackPush()) {
            recordAndSubmitVk(stack);
            VkContext.check(vkQueueWaitIdle(VkContext.get().queue), "waitIdle");
        }
        readGlGpuMs();
        float baseline = readbackValue();

        // GL を動かさずにもう一度読む。期待値は「次の値」だが、実際には前の値のはず。
        float want = depthOf(102);
        try (MemoryStack stack = stackPush()) {
            recordAndSubmitVk(stack);
            VkContext.check(vkQueueWaitIdle(VkContext.get().queue), "waitIdle");
        }
        float got = readbackValue();

        boolean ok = baseline == prev && got == prev && got != want;
        System.out.printf("C2 staleness detector: %s  (baseline=%.6f, stale read=%.6f, want=%.6f)%n",
            ok ? "PASS" : "FAIL", baseline, got, want);
        if (!ok) {
            System.out.println("   -> the check cannot tell a stale read from a fresh one");
        }
        return ok;
    }

    // ---------------- 計測 ----------------

    record Row(Sync sync, int load, double glGpuMs, double glCpuMs, double syncMs,
               double vkMs, double frameMs, int stale, int other) {}

    static void sweep() {
        List<Row> rows = new ArrayList<>();
        for (int load : LOADS) {
            for (Sync sync : Sync.values()) {
                rows.add(measure(sync, load));
            }
        }

        System.out.println();
        System.out.printf("%-8s %6s %10s %10s %10s %10s %10s %7s %7s%n",
            "sync", "load", "gl_gpu_ms", "gl_cpu_ms", "sync_ms", "vk_ms", "frame_ms", "stale", "other");
        System.out.println("-".repeat(88));
        for (Row r : rows) {
            System.out.printf("%-8s %6d %10.3f %10.3f %10.3f %10.3f %10.3f %7d %7d%n",
                r.sync, r.load, r.glGpuMs, r.glCpuMs, r.syncMs, r.vkMs, r.frameMs, r.stale, r.other);
        }

        System.out.println();
        System.out.printf("%-6s %10s %12s %12s %12s%n",
            "load", "gl_gpu_ms", "NONE", "FLUSH", "FINISH-NONE");
        System.out.println("-".repeat(56));
        for (int load : LOADS) {
            Row none = find(rows, Sync.NONE, load);
            Row flush = find(rows, Sync.FLUSH, load);
            Row finish = find(rows, Sync.FINISH, load);
            System.out.printf("%-6d %10.3f %12.3f %12.3f %12.3f%n",
                load, finish.glGpuMs, none.frameMs, flush.frameMs,
                finish.frameMs - none.frameMs);
        }

        controlContentionDiscriminator(rows);

        System.out.println();
        int staleNone = rows.stream().filter(r -> r.sync == Sync.NONE).mapToInt(Row::stale).sum();
        int staleOther = rows.stream().filter(r -> r.sync != Sync.NONE).mapToInt(Row::stale).sum();
        System.out.println("C3 constructible failure: NONE stale=" + staleNone
            + ", synchronised stale=" + staleOther);
        if (staleNone == 0) {
            System.out.println("   -> could NOT construct a failing case. This is NOT evidence that");
            System.out.println("      synchronisation is unnecessary; record it as unproven.");
        }
    }

    /**
     * C4: NONE のとき {@code vk_ms} が {@code gl_gpu_ms} に張り付く理由の切り分け。
     *
     * <p>同じ GL 負荷のもとで <b>interop ではない自前の画像</b>を読む。
     * <ul>
     *   <li>それでも {@code vk_ms} が大きい → <b>GPU の取り合い</b>。
     *       Vulkan が GL の後ろに並んでいるだけで、IOSurface の依存追跡の証拠にならない</li>
     *   <li>{@code vk_ms} が小さい → GL の作業中でも Vulkan は先に走れる。
     *       つまり interop 画像で待たされたのは<b>データ依存</b>である</li>
     * </ul>
     * どちらでも「同期が要らない」という結論にはならない。<b>解釈の幅を狭めるためだけの対照</b>。
     */
    static void controlContentionDiscriminator(List<Row> rows) {
        System.out.println();
        System.out.printf("%-6s %10s %14s %14s%n", "load", "gl_gpu_ms", "vk_ms(interop)", "vk_ms(private)");
        System.out.println("-".repeat(48));
        for (int load : LOADS) {
            double[] vkMs = new double[ITERS];
            for (int i = 0; i < WARMUP + ITERS; i++) {
                recordGl(load, depthOf(i + 1));
                long t0 = System.nanoTime();
                try (MemoryStack stack = stackPush()) {
                    recordAndSubmitVk(stack, false);
                    VkContext.check(vkQueueWaitIdle(VkContext.get().queue), "waitIdle");
                }
                long t1 = System.nanoTime();
                readGlGpuMs();
                if (i >= WARMUP) vkMs[i - WARMUP] = (t1 - t0) / 1e6;
            }
            Row none = find(rows, Sync.NONE, load);
            System.out.printf("%-6d %10.3f %14.3f %14.3f%n", load, none.glGpuMs, none.vkMs, median(vkMs));
        }
    }

    static Row find(List<Row> rows, Sync s, int load) {
        return rows.stream().filter(r -> r.sync == s && r.load == load).findFirst().orElseThrow();
    }

    static Row measure(Sync sync, int load) {
        double[] syncMs = new double[ITERS];
        double[] frameMs = new double[ITERS];
        double[] glMs = new double[ITERS];
        double[] glCpuMs = new double[ITERS];
        double[] vkMs = new double[ITERS];
        int stale = 0, other = 0;
        float prevWant = Float.NaN;

        for (int i = 0; i < WARMUP + ITERS; i++) {
            float want = depthOf(i + 1);

            long t0 = System.nanoTime();
            recordGl(load, want);
            long t1 = System.nanoTime();
            doSync(sync);
            long t2 = System.nanoTime();
            try (MemoryStack stack = stackPush()) {
                recordAndSubmitVk(stack);
                VkContext.check(vkQueueWaitIdle(VkContext.get().queue), "waitIdle");
            }
            long t3 = System.nanoTime();

            float got = readbackValue();
            double gl = readGlGpuMs();   // ここで GL はドレインされる (同期点より後なので影響しない)

            if (i >= WARMUP) {
                int k = i - WARMUP;
                glCpuMs[k] = (t1 - t0) / 1e6;
                syncMs[k] = (t2 - t1) / 1e6;
                vkMs[k] = (t3 - t2) / 1e6;
                frameMs[k] = (t3 - t0) / 1e6;
                glMs[k] = gl;
                if (got != want) {
                    if (got == prevWant) stale++; else other++;
                }
            }
            prevWant = want;
        }
        return new Row(sync, load, median(glMs), median(glCpuMs), median(syncMs),
            median(vkMs), median(frameMs), stale, other);
    }

    static double median(double[] a) {
        double[] c = a.clone();
        Arrays.sort(c);
        return c.length % 2 == 1 ? c[c.length / 2] : (c[c.length / 2 - 1] + c[c.length / 2]) / 2;
    }

    // ---------------- teardown ----------------

    static void teardown() {
        try {
            if (readback != null) readback.free();
            if (privateTex != null) privateTex.free();
            if (depthInterop != null) depthInterop.free();
        } catch (Throwable t) {
            System.out.println("teardown: " + t);
        }
        glDeleteFramebuffers(fboTerrain);
        glDeleteFramebuffers(fboInterop);
        glDeleteTextures(new int[]{texMcColor, texMcDepth});
        glDeleteQueries(glQuery);
        glDeleteVertexArrays(vao);
        glDeleteProgram(progTerrain);
        glDeleteProgram(progCopy);
        VkContext.shutdown();
        glfwDestroyWindow(window);
        glfwTerminate();
        System.out.println();
        System.out.println("done. " + String.format(Locale.ROOT, "%s", java.time.Instant.now()));
    }
}
