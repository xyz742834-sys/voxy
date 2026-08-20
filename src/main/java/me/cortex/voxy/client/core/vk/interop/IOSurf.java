package me.cortex.voxy.client.core.vk.interop;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * IOSurface の生成と問い合わせ。Panama (FFM) 経由。JNI 不使用。
 *
 * <p>IOSurface は macOS で <b>Vulkan と GL の両方から同じメモリを触るための唯一の経路</b>である。
 * Phase 0 §8.2 で確立した:
 *
 * <pre>
 * IOSurface (共有メモリ、コピーなし)
 *    ├─→ VkImage              (VkImportMetalIOSurfaceInfoEXT)
 *    └─→ GL_TEXTURE_RECTANGLE (CGLTexImageIOSurface2D)
 * </pre>
 *
 * <p>参照元は {@code ~/dev/mdi-bench} の {@code IOSurf.java} (Phase 0 の B-3 実測コード)。
 * 内容はほぼそのまま。Voxy 側では {@link VkInteropImage} だけがこのクラスを使う。
 *
 * <h2>⚠ 使えるピクセルフォーマットは 2 種類だけ [確認済 — Phase 0 §8.2]</h2>
 * <table>
 *   <tr><th>用途</th><th>FourCC</th><th>Vulkan</th><th>GL</th></tr>
 *   <tr><td>色</td><td>{@code 'BGRA'}</td><td>{@code B8G8R8A8_UNORM}</td><td>{@code RGBA/BGRA/UNSIGNED_INT_8_8_8_8_REV}</td></tr>
 *   <tr><td>深度</td><td>{@code 'r00f'}</td><td>{@code R32_SFLOAT}</td><td>{@code R32F/RED/FLOAT}</td></tr>
 * </table>
 * <b>stencil aspect を持つ面は作れない</b>。ステンシルは interop で運べない
 * [docs/phase5-proposal.md §3.1]。
 */
public final class IOSurf {
    private static final Linker LINKER = Linker.nativeLinker();

    private static final SymbolLookup IO = SymbolLookup.libraryLookup(
        "/System/Library/Frameworks/IOSurface.framework/IOSurface", Arena.global());
    private static final SymbolLookup CF = SymbolLookup.libraryLookup(
        "/System/Library/Frameworks/CoreFoundation.framework/CoreFoundation", Arena.global());

    /** kCFNumberSInt32Type */
    private static final int CF_NUMBER_SINT32 = 3;

    /** 色 (8bit x 4)。{@code VK_FORMAT_B8G8R8A8_UNORM} と対応。 */
    public static final int FOURCC_BGRA = fourcc("BGRA");
    /** 深度 (float x 1)。{@code VK_FORMAT_R32_SFLOAT} と対応。 */
    public static final int FOURCC_R32F = fourcc("r00f");

    public static int fourcc(String s) {
        if (s.length() != 4) throw new IllegalArgumentException("fourcc must be 4 chars: " + s);
        return (s.charAt(0) << 24) | (s.charAt(1) << 16) | (s.charAt(2) << 8) | s.charAt(3);
    }

    private static final MethodHandle CFDictionaryCreateMutable = LINKER.downcallHandle(
        CF.find("CFDictionaryCreateMutable").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.JAVA_LONG,
            ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle CFDictionarySetValue = LINKER.downcallHandle(
        CF.find("CFDictionarySetValue").orElseThrow(),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS, ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle CFNumberCreate = LINKER.downcallHandle(
        CF.find("CFNumberCreate").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS,
            ValueLayout.JAVA_INT, ValueLayout.ADDRESS));

    private static final MethodHandle CFRelease = LINKER.downcallHandle(
        CF.find("CFRelease").orElseThrow(),
        FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));

    private static final MethodHandle IOSurfaceCreate = LINKER.downcallHandle(
        IO.find("IOSurfaceCreate").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.ADDRESS));

    private static final MethodHandle IOSurfaceGetWidth = LINKER.downcallHandle(
        IO.find("IOSurfaceGetWidth").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private static final MethodHandle IOSurfaceGetHeight = LINKER.downcallHandle(
        IO.find("IOSurfaceGetHeight").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private static final MethodHandle IOSurfaceGetBytesPerRow = LINKER.downcallHandle(
        IO.find("IOSurfaceGetBytesPerRow").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private static final MethodHandle IOSurfaceGetAllocSize = LINKER.downcallHandle(
        IO.find("IOSurfaceGetAllocSize").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.ADDRESS));

    private IOSurf() {}

    /** グローバル定数 (CFStringRef 変数) の値を読む。シンボルはポインタ変数なので 1 段剥がす。 */
    private static MemorySegment key(String name) {
        return MemorySegment.ofAddress(
            IO.find(name).orElseThrow().reinterpret(8).get(ValueLayout.JAVA_LONG, 0));
    }

    private static MemorySegment cfNumber(Arena arena, int value) throws Throwable {
        MemorySegment box = arena.allocate(ValueLayout.JAVA_INT);
        box.set(ValueLayout.JAVA_INT, 0, value);
        return (MemorySegment) CFNumberCreate.invokeExact(
            MemorySegment.NULL, CF_NUMBER_SINT32, box);
    }

    /**
     * IOSurface を 1 枚作る。参照カウントは 1。{@link #release} で減らす。
     *
     * @param pixelFormat {@link #FOURCC_BGRA} または {@link #FOURCC_R32F}
     * @param bytesPerElement 1 テクセルのバイト数 (どちらの形式も 4)
     */
    public static MemorySegment create(int width, int height, int pixelFormat, int bytesPerElement) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment keyCb = CF.find("kCFTypeDictionaryKeyCallBacks").orElseThrow();
            MemorySegment valCb = CF.find("kCFTypeDictionaryValueCallBacks").orElseThrow();

            MemorySegment dict = (MemorySegment) CFDictionaryCreateMutable.invokeExact(
                MemorySegment.NULL, 0L, keyCb, valCb);
            if (dict.address() == 0) throw new IllegalStateException("CFDictionaryCreateMutable failed");

            MemorySegment w = cfNumber(arena, width);
            MemorySegment h = cfNumber(arena, height);
            MemorySegment bpe = cfNumber(arena, bytesPerElement);
            MemorySegment pf = cfNumber(arena, pixelFormat);

            CFDictionarySetValue.invokeExact(dict, key("kIOSurfaceWidth"), w);
            CFDictionarySetValue.invokeExact(dict, key("kIOSurfaceHeight"), h);
            CFDictionarySetValue.invokeExact(dict, key("kIOSurfaceBytesPerElement"), bpe);
            CFDictionarySetValue.invokeExact(dict, key("kIOSurfacePixelFormat"), pf);

            MemorySegment surf = (MemorySegment) IOSurfaceCreate.invokeExact(dict);

            CFRelease.invokeExact(w);
            CFRelease.invokeExact(h);
            CFRelease.invokeExact(bpe);
            CFRelease.invokeExact(pf);
            CFRelease.invokeExact(dict);

            if (surf.address() == 0) throw new IllegalStateException("IOSurfaceCreate returned NULL");
            return surf;
        } catch (Throwable t) {
            throw new RuntimeException("IOSurf.create", t);
        }
    }

    public static long width(MemorySegment s) {
        try { return (long) IOSurfaceGetWidth.invokeExact(s); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static long height(MemorySegment s) {
        try { return (long) IOSurfaceGetHeight.invokeExact(s); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static long bytesPerRow(MemorySegment s) {
        try { return (long) IOSurfaceGetBytesPerRow.invokeExact(s); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    public static long allocSize(MemorySegment s) {
        try { return (long) IOSurfaceGetAllocSize.invokeExact(s); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }

    /** 参照カウントを 1 減らす。{@code VkImage} / GL テクスチャを破棄した後に呼ぶこと。 */
    public static void release(MemorySegment s) {
        try { CFRelease.invokeExact(s); }
        catch (Throwable t) { throw new RuntimeException(t); }
    }
}
