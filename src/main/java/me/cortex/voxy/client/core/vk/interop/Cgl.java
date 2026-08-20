package me.cortex.voxy.client.core.vk.interop;

import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;

/**
 * CGL の IOSurface 連携。Panama (FFM) 経由。
 *
 * <p>{@code CGLTexImageIOSurface2D} は <b>{@code GL_TEXTURE_RECTANGLE} にしか使えない</b>。
 * したがって GL 側から見た interop テクスチャは常に rectangle であり、
 * サンプリングは {@code sampler2DRect} + <b>正規化されていないテクセル座標</b>になる
 * [確認済 — Phase 0 §8.3 の 7f/7h]。
 *
 * <p>参照元は {@code ~/dev/mdi-bench} の {@code Cgl.java}。
 */
public final class Cgl {
    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup OGL = SymbolLookup.libraryLookup(
        "/System/Library/Frameworks/OpenGL.framework/OpenGL", Arena.global());

    public static final int GL_TEXTURE_RECTANGLE = 0x84F5;
    public static final int GL_RGBA = 0x1908;
    public static final int GL_BGRA = 0x80E1;
    public static final int GL_RED = 0x1903;
    public static final int GL_R32F = 0x822E;
    public static final int GL_FLOAT = 0x1406;
    public static final int GL_UNSIGNED_INT_8_8_8_8_REV = 0x8367;

    /** kCGLNoError */
    public static final int CGL_NO_ERROR = 0;

    private static final MethodHandle CGLGetCurrentContext = LINKER.downcallHandle(
        OGL.find("CGLGetCurrentContext").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.ADDRESS));

    /**
     * <pre>
     * CGLError CGLTexImageIOSurface2D(CGLContextObj ctx, GLenum target,
     *     GLenum internal_format, GLsizei width, GLsizei height,
     *     GLenum format, GLenum type, IOSurfaceRef ioSurface, GLuint plane);
     * </pre>
     */
    private static final MethodHandle CGLTexImageIOSurface2D = LINKER.downcallHandle(
        OGL.find("CGLTexImageIOSurface2D").orElseThrow(),
        FunctionDescriptor.of(ValueLayout.JAVA_INT,
            ValueLayout.ADDRESS,    // ctx
            ValueLayout.JAVA_INT,   // target
            ValueLayout.JAVA_INT,   // internal_format
            ValueLayout.JAVA_INT,   // width
            ValueLayout.JAVA_INT,   // height
            ValueLayout.JAVA_INT,   // format
            ValueLayout.JAVA_INT,   // type
            ValueLayout.ADDRESS,    // ioSurface
            ValueLayout.JAVA_INT)); // plane

    private Cgl() {}

    /** 現在の CGL コンテキスト。GL コンテキストが current でない場合は NULL が返る。 */
    public static MemorySegment currentContext() {
        try { return (MemorySegment) CGLGetCurrentContext.invokeExact(); }
        catch (Throwable t) { throw new RuntimeException("CGLGetCurrentContext", t); }
    }

    /**
     * 現在の GL コンテキストで、束縛済みの {@code GL_TEXTURE_RECTANGLE} に IOSurface を結びつける。
     *
     * @return CGLError。成功なら {@link #CGL_NO_ERROR}
     */
    public static int texImageIOSurface2D(MemorySegment ctx, int internalFormat,
                                          int width, int height, int format, int type,
                                          MemorySegment iosurface, int plane) {
        try {
            return (int) CGLTexImageIOSurface2D.invokeExact(
                ctx, GL_TEXTURE_RECTANGLE, internalFormat, width, height,
                format, type, iosurface, plane);
        } catch (Throwable t) { throw new RuntimeException("CGLTexImageIOSurface2D", t); }
    }
}
