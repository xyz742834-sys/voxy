package me.cortex.voxy.client.core.vk.mcnative;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * round-6 review の 2 つの寿命指摘を、Minecraft 無しで固定する。
 *
 * <ul>
 *   <li><b>R6-TERRAIN-DEVICE</b>: 所有判定が「自分の context の device」同士を比べていたので
 *       常に真になり、MC が device を差し替えた後も真だった。基準は<b>MC のいま</b>である</li>
 *   <li><b>R6-TERRAIN-WAIT</b>: 参照フレームを提出した後、フェンス待ちが失敗しても
 *       finally が<b>無条件に</b>資源を解放していた</li>
 * </ul>
 *
 * <p>どちらも device も GPU も要らない部分だけを見る。probe は既定で無効であり、
 * ここで確かめるのは「無効なら何もしない」ことと、公開している状態が
 * 寿命の判断に必要な事実を<b>実際に持っている</b>ことである。
 */
public class McNativeTerrainLifetimeTest {

    @Test
    void theProbePublishesWhatTheGateNeedsToJudgeLifetime() {
        var status = McNativeTerrainProbe.status();
        // 証跡の JSON はこれらから組まれる。欠けているとゲートは寿命を判断できない。
        assertEquals(0, status.drawsRecorded());
        assertEquals(0, status.closeFailures());
        assertTrue(status.failureBudget() > 0);
        assertNull(status.comparison());
        assertFalse(status.built());
    }

    @Test
    void everyEntryPointIsInertWithoutItsFlag() {
        assertFalse(Boolean.getBoolean(McNativeTerrainProbe.FLAG));
        var before = McNativeTerrainProbe.status().notes();
        assertDoesNotThrow(McNativeTerrainProbe::renderIfEnabled);
        assertDoesNotThrow(McNativeTerrainProbe::shutdown);
        // ⚠ null の device を渡しても、壊さずに黙って帰ること。round-6 の
        // R6-TERRAIN-DEVICE は「待った device と持ち主が違えば壊さない」を要求する。
        assertDoesNotThrow(() -> McNativeTerrainProbe.shutdownImmediate(null));
        assertEquals(before, McNativeTerrainProbe.status().notes(),
            "a disabled probe must not accumulate notes");
    }

    /**
     * <b>深度 probe も同じ規律に従うこと。</b> 読み戻すだけだが、
     * フラグが無ければ MC の device も触らない。
     */
    @Test
    void theDepthProbeIsInertWithoutItsFlag() {
        assertFalse(Boolean.getBoolean(McNativeDepthProbe.FLAG));
        var before = McNativeDepthProbe.status();
        assertFalse(before.attempted());
        assertFalse(before.completed());
        assertFalse(before.uniform());
        assertNull(before.reversedZ());
        assertDoesNotThrow(McNativeDepthProbe::probeOnce);
        var after = McNativeDepthProbe.status();
        assertFalse(after.attempted(), "a disabled depth probe must not record an attempt");
        assertEquals(before.notes(), after.notes());
    }

    /**
     * <b>深度 probe の JSON は、測れなかったことを測れたと言わないこと。</b>
     *
     * <p>実測 (2026-10-06): MC の深度のコピーは<b>完了する</b>が、1708x960 の全画素が 0.0
     * だった。一様な画像は深度規約について何も語らない。既定状態 (未測定) の JSON が
     * {@code completed:false} と {@code reversedZ:null} を出していることを固定する —
     * ここが逆になると、ゲートは観測していない規約を受け取る。
     */
    @Test
    void theDepthProbeJsonDoesNotClaimAnUnmeasuredConvention() {
        String json = McNativeDepthProbe.json();
        assertTrue(json.contains("\"completed\": false"), json);
        assertTrue(json.contains("\"uniform\": false"), json);
        assertTrue(json.contains("\"reversedZ\": null"), json);
        assertTrue(json.contains("\"attempted\": false"), json);
    }
}
