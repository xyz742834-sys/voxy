package me.cortex.voxy.common.world;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.common.config.section.SectionStorage;
import me.cortex.voxy.common.voxelization.VoxelizedSection;
import me.cortex.voxy.common.world.other.Mapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.LongConsumer;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The real {@link WorldUpdater#insertUpdate} on a real {@link WorldEngine} (in-memory storage, nothing
 * stubbed): the outcomes the native update observation must tell apart (R29-PRELAUNCH-REMOVAL).
 * Re-writing content Voxy already holds changes nothing and marks nothing dirty — the reviewer's
 * actual-updater no-op; removing the only block changes the leaf, empties it (no non-air block left)
 * and marks it dirty with a child-existence change, which the scene may answer by removing the leaf's
 * geometry instead of re-meshing it. The observer sees exactly these, before the dirty marks. CPU only.
 */
public class WorldUpdaterOutcomeTest {
    /** Nothing stored: every section loads as air (the tracker fills it with sky light 15). */
    public static final class MemoryStorage extends SectionStorage {
        public int saves;
        @Override public int loadSection(WorldSection into) { return 1; }
        @Override public void saveSection(WorldSection section) { this.saves++; }
        @Override public void putIdMapping(int id, ByteBuffer data) { }
        @Override public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { return new Int2ObjectOpenHashMap<>(); }
        @Override public void flush() { }
        @Override public void close() { }
        @Override public void iteratePositions(int level, LongConsumer callback) { }
    }

    record Dirty(long key, int flags) {}
    record Outcome(long key, boolean changed, int nonEmptyBlocks) {}

    /** What the tracker fills an unstored section with (ActiveSectionTracker: sky 15, block 0). */
    static final long AIR = Mapper.composeMappingId((byte) 15, 0, 0);
    static final long GLASS_LIKE = Mapper.composeMappingId((byte) 15, 5, 0);   // any non-air block id
    static final long LEAF = WorldEngine.getWorldSectionId(0, 1, 3, 2);         // the probe's leaf

    private WorldEngine engine;
    private final List<Dirty> dirty = new ArrayList<>();
    private final List<Outcome> outcomes = new ArrayList<>();

    @BeforeEach
    void world() {
        me.cortex.voxy.testsupport.MinecraftBootstrap.ensure();
        this.engine = new WorldEngine(new MemoryStorage());
        // the instance's saving service, synchronously: a dirty section handed over on unload is
        // saved and given back (without one, the tracker retries the unload without end)
        this.engine.setSaveCallback((into, section, nonBlocking, acquired) -> {
            if (section.exchangeIsInSaveQueue(true)) {
                section.setNotDirty();
                into.storage.saveSection(section);
                section.exchangeIsInSaveQueue(false);
                if (acquired) section.release();
            }
            return true;
        });
        this.engine.setDirtyCallback((section, flags, neighbors) -> this.dirty.add(new Dirty(section.key, flags)));
        WorldUpdater.setObserver((into, leaf, changed) -> {
            assertSame(this.engine, into);
            this.outcomes.add(new Outcome(leaf.key, changed, leaf.getNonEmptyBlockCount()));
        });
    }

    @AfterEach
    void free() {
        WorldUpdater.setObserver(null);
        try { this.engine.free(); } catch (RuntimeException ignored) { }
    }

    /** The probe's chunk section (2, 7, 4), all {@code AIR} at every level, with one block at (8, 6, 8) when asked. */
    private static VoxelizedSection section(boolean glass) {
        var vs = VoxelizedSection.createEmpty().setPosition(2, 7, 4);
        Arrays.fill(vs.section, AIR);
        if (glass) {
            vs.section[(6 << 8) | (8 << 4) | 8] = GLASS_LIKE;   // level 0: y<<8 | z<<4 | x
            vs.lvl0NonAirCount = 1;
        }
        return vs;
    }

    private List<Dirty> leafDirty() { return this.dirty.stream().filter(d -> d.key == LEAF).toList(); }

    @Test
    void reWritingContentVoxyAlreadyHoldsChangesNothingAndMarksNothingDirty() {
        // Voxy holds nothing for the leaf: it loads as air. Ingesting air is the no-op the reviewer
        // demonstrated (noop_probe: unchanged_air_dirty_callbacks=0)
        WorldUpdater.insertUpdate(this.engine, section(false));
        assertEquals(List.of(new Outcome(LEAF, false, 0)), this.outcomes);
        assertTrue(this.dirty.isEmpty(), "no dirty mark at any level: " + this.dirty);
        assertEquals(0, this.engine.getActiveSectionCount(), "the updater released what it acquired");
    }

    @Test
    void placingThenRemovingTheOnlyBlockChangesAndEmptiesTheLeaf() {
        // the placement reaching Voxy (the chunk-unload ingest's content)
        WorldUpdater.insertUpdate(this.engine, section(true));
        assertEquals(new Outcome(LEAF, true, 1), this.outcomes.get(0));
        assertEquals(1, this.leafDirty().size());
        assertEquals(WorldEngine.UPDATE_TYPE_BLOCK_BIT | WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT,
            this.leafDirty().get(0).flags, "empty -> something: block and child-existence change");
        assertTrue(this.dirty.size() > 1, "the parents learn of the new child: " + this.dirty);

        // re-writing the same content: the updater's real no-op, again
        this.dirty.clear();
        WorldUpdater.insertUpdate(this.engine, section(true));
        assertEquals(new Outcome(LEAF, false, 1), this.outcomes.get(1));
        assertTrue(this.dirty.isEmpty());

        // the removal: changed, and the leaf is empty now — its geometry may be removed rather than re-meshed
        WorldUpdater.insertUpdate(this.engine, section(false));
        assertEquals(new Outcome(LEAF, true, 0), this.outcomes.get(2));
        assertEquals(WorldEngine.UPDATE_TYPE_BLOCK_BIT | WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT,
            this.leafDirty().get(0).flags, "something -> empty");
        assertTrue(this.dirty.stream().anyMatch(d -> WorldEngine.getLevel(d.key) == 1
            && (d.flags & WorldEngine.UPDATE_TYPE_CHILD_EXISTENCE_BIT) != 0), "the parent sees the child vanish: " + this.dirty);

        // and removing what is already gone is a no-op
        this.dirty.clear();
        WorldUpdater.insertUpdate(this.engine, section(false));
        assertEquals(new Outcome(LEAF, false, 0), this.outcomes.get(3));
        assertTrue(this.dirty.isEmpty());
        assertEquals(4, this.outcomes.size(), "one level-0 outcome per update, no more");
    }

    @Test
    void theObserverSeesTheOutcomeBeforeTheDirtyMark() {
        var order = new ArrayList<String>();
        this.engine.setDirtyCallback((section, flags, neighbors) -> { if (section.key == LEAF) order.add("dirty"); });
        WorldUpdater.setObserver((into, leaf, changed) -> order.add("outcome:" + changed));
        WorldUpdater.insertUpdate(this.engine, section(true));
        assertEquals(List.of("outcome:true", "dirty"), order,
            "a mesher reading after the dirty mark reads content the observer has already dated");
    }
}
