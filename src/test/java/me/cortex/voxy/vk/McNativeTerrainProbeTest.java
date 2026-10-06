package me.cortex.voxy.vk;

import me.cortex.voxy.client.core.vk.mcnative.McNativeTerrainProbe;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * {@link McNativeTerrainProbe} のうち、Minecraft のデバイスを要らない部分を固定する。
 *
 * <p>実際に MC のフレームへ地形が描けたかは {@code scripts/verify.py --only native} が測る。
 * ここで確かめるのは<b>フラグが無い限り何もしない</b>ことである — この probe は
 * 有効だと MC の colour/depth をクリアするので、既定で黙っていることは
 * 「プレイヤーに影響しない」の中身そのものである。
 */
public class McNativeTerrainProbeTest {

    @Test
    void theProbeIsOffUnlessItsFlagIsSet() {
        assertFalse(Boolean.getBoolean(McNativeTerrainProbe.FLAG),
            "the terrain probe must not be enabled by default in a test JVM");
        var before = McNativeTerrainProbe.status();
        assertFalse(before.enabled());
        assertFalse(before.attempted());
        assertFalse(before.built());
        assertEquals(0, before.drawsRecorded());
        assertNull(before.comparison());

        // Minecraft is not running here; every entry point must still be a silent no-op.
        assertDoesNotThrow(McNativeTerrainProbe::renderIfEnabled);
        assertDoesNotThrow(McNativeTerrainProbe::shutdown);
        assertDoesNotThrow(() -> McNativeTerrainProbe.shutdownImmediate(null));

        var after = McNativeTerrainProbe.status();
        assertFalse(after.attempted(), "a disabled probe must not record an attempt");
        assertEquals(0, after.drawsRecorded(), "a disabled probe must record nothing");
        assertEquals(before.notes(), after.notes(), "a disabled probe must not accumulate notes");
    }

    /**
     * <b>失敗の予算と間隔が、黙って無限に試み続けない値であること。</b>
     *
     * <p>round-4 の R4-L1 は、失敗した読み戻しを間隔ごとに出し続けていたことを指摘した。
     * 同じ構造をここにも持ち込んでいるので、同じ上限があることを固定する。
     */
    @Test
    void theFailureBudgetIsBounded() {
        var status = McNativeTerrainProbe.status();
        assertTrue(status.failureBudget() > 0 && status.failureBudget() <= 5,
            "the comparison failure budget must be small and positive: " + status.failureBudget());
        assertEquals(0, status.closeFailures());
    }
}
