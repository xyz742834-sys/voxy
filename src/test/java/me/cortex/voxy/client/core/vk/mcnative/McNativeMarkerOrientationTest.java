package me.cortex.voxy.client.core.vk.mcnative;

import org.junit.jupiter.api.Test;
import org.lwjgl.system.MemoryUtil;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * <b>向きの選択が「どちらか一方だけ」を要求すること</b>を固定する。
 *
 * <p>round-5 review B4 は、実機でも JUnit でもここを守っていないことを示した: レビュアが
 * 「上に正しい模様、下に箱いっぱいの黄色」の画像を分類器に食わせると、分類器は<b>上を選んで
 * 問題なしと報告</b>した ({@code badActualBottomGoodMirror chosenFlip=false note=null})。
 * 密度は疎な汚れを落とせるが、濃い模様が 2 つあるときにどちらが自分の draw かは言えない。
 *
 * <p>Minecraft もデバイスも要らない。{@link McNativeMarkerDraw#select} が
 * そのために切ってある継ぎ目である。
 */
public class McNativeMarkerOrientationTest {
    private static final int W = 240, H = 160;
    private static final int[] NEAR = {255, 0, 255};
    private static final int[] FAR = {0, 255, 255};
    private static final int[] REJECTED = {255, 255, 0};
    private static final int[] BACKGROUND = {40, 40, 40};

    /** RGBA8 の画像を作る。行 0 が Vulkan の行 0。 */
    private static ByteBuffer image() {
        ByteBuffer data = MemoryUtil.memCalloc(W * H * 4);
        for (int i = 0; i < W * H; i++) {
            data.put(i * 4, (byte) BACKGROUND[0]);
            data.put(i * 4 + 1, (byte) BACKGROUND[1]);
            data.put(i * 4 + 2, (byte) BACKGROUND[2]);
            data.put(i * 4 + 3, (byte) 255);
        }
        return data;
    }

    private static int px(float ndc, int size) {
        return (int) ((ndc + 1.0f) * 0.5f * size);
    }

    /** 最終画像の向き (flipped=false) での行。 */
    private static int row(float ndcY) {
        return (int) ((1.0f - ndcY) * 0.5f * H);
    }

    /** 反転した向き (flipped=true) での行。 */
    private static int mirrorRow(float ndcY) {
        return (int) ((1.0f + ndcY) * 0.5f * H);
    }

    private static void fill(ByteBuffer data, int x0, int y0, int x1, int y1, int[] rgb) {
        for (int y = Math.min(y0, y1); y < Math.max(y0, y1); y++) {
            for (int x = Math.min(x0, x1); x < Math.max(x0, x1); x++) {
                int at = (y * W + x) * 4;
                data.put(at, (byte) rgb[0]);
                data.put(at + 1, (byte) rgb[1]);
                data.put(at + 2, (byte) rgb[2]);
            }
        }
    }

    /** 期待される模様を、指定した向きで描き込む。 */
    private static void drawExpected(ByteBuffer data, boolean flipped) {
        int bx0 = px(McNativeMarkerDraw.BOX_X0, W), bx1 = px(McNativeMarkerDraw.BOX_X1, W);
        int sx = px(McNativeMarkerDraw.NEAR_X1, W);
        int by0 = flipped ? mirrorRow(McNativeMarkerDraw.BOX_Y0) : row(McNativeMarkerDraw.BOX_Y0);
        int by1 = flipped ? mirrorRow(McNativeMarkerDraw.BOX_Y1) : row(McNativeMarkerDraw.BOX_Y1);
        int cy0 = flipped ? mirrorRow(McNativeMarkerDraw.CELL_Y0) : row(McNativeMarkerDraw.CELL_Y0);
        int cy1 = flipped ? mirrorRow(McNativeMarkerDraw.CELL_Y1) : row(McNativeMarkerDraw.CELL_Y1);
        int ty0 = flipped ? mirrorRow(McNativeMarkerDraw.CONTROL_Y0)
                          : row(McNativeMarkerDraw.CONTROL_Y0);
        int ty1 = flipped ? mirrorRow(McNativeMarkerDraw.CONTROL_Y1)
                          : row(McNativeMarkerDraw.CONTROL_Y1);
        fill(data, bx0, by0, sx, by1, NEAR);
        fill(data, sx, by0, bx1, by1, FAR);
        fill(data, bx0, cy0, bx1, cy1, REJECTED);
        fill(data, bx0, ty0, bx1, ty1, REJECTED);
    }

    /** 箱を棄却色で塗りつぶす = 深度が効いていない状態。 */
    private static void drawYellowBox(ByteBuffer data, boolean flipped) {
        int bx0 = px(McNativeMarkerDraw.BOX_X0, W), bx1 = px(McNativeMarkerDraw.BOX_X1, W);
        int by0 = flipped ? mirrorRow(McNativeMarkerDraw.BOX_Y0) : row(McNativeMarkerDraw.BOX_Y0);
        int by1 = flipped ? mirrorRow(McNativeMarkerDraw.BOX_Y1) : row(McNativeMarkerDraw.BOX_Y1);
        fill(data, bx0, by0, bx1, by1, REJECTED);
    }

    @Test
    void oneCorrectOrientationIsAccepted() {
        ByteBuffer data = image();
        try {
            drawExpected(data, false);
            var result = McNativeMarkerDraw.select(data, W, H, 7);
            assertNull(result.note(), "a single correct pattern must be accepted");
            assertFalse(result.flipped());
            assertEquals(7, result.sampleAtDraw(), "the capture identity must be carried through");
            assertEquals(0, result.rejectedInBox());
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    @Test
    void theMirroredOrientationIsAlsoAccepted() {
        ByteBuffer data = image();
        try {
            drawExpected(data, true);
            var result = McNativeMarkerDraw.select(data, W, H, 11);
            assertNull(result.note(), "the composited frame is y-flipped, so this must pass too");
            assertTrue(result.flipped());
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    /**
     * <b>round-5 review B4 の反例そのもの。</b> 一方の向きに正しい模様、
     * もう一方の向きの箱に棄却色 — つまり「どちらかで深度が効いていない」。
     * 以前はこれを「問題なし」と報告した。
     */
    @Test
    void aGoodMirrorCannotExcuseAYellowBoxInTheOtherOrientation() {
        for (boolean goodSide : new boolean[] {false, true}) {
            ByteBuffer data = image();
            try {
                drawExpected(data, goodSide);
                drawYellowBox(data, !goodSide);
                var result = McNativeMarkerDraw.select(data, W, H, 3);
                assertNotNull(result.note(),
                    "a yellow box in the other orientation must not be excused (good side="
                        + goodSide + ")");
            } finally {
                MemoryUtil.memFree(data);
            }
        }
    }

    @Test
    void twoValidPatternsAreAmbiguousRatherThanSuccessful() {
        ByteBuffer data = image();
        try {
            drawExpected(data, false);
            drawExpected(data, true);
            var result = McNativeMarkerDraw.select(data, W, H, 5);
            assertNotNull(result.note(), "two dense valid patterns do not identify the draw");
            assertTrue(result.note().contains("both orientations"), result.note());
        } finally {
            MemoryUtil.memFree(data);
        }
    }

    @Test
    void anEmptyImageIsRejected() {
        ByteBuffer data = image();
        try {
            var result = McNativeMarkerDraw.select(data, W, H, 1);
            assertNotNull(result.note());
        } finally {
            MemoryUtil.memFree(data);
        }
    }
}
