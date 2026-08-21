package me.cortex.voxy.client.core.vk;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import me.cortex.voxy.client.core.model.IdNotYetComputedException;
import me.cortex.voxy.client.core.rendering.building.BuiltSection;
import me.cortex.voxy.client.core.rendering.building.RenderDataFactory;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.world.WorldEngine;
import me.cortex.voxy.common.world.WorldSection;
import me.cortex.voxy.common.world.other.Mapper;

import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5c-3b — <b>ワールドの実データをメッシュ化する</b>。
 *
 * <h2>⚠ {@code RenderGenerationService} を通さないこと</h2>
 * あちらは<b>コンストラクタが {@code ModelBakerySubsystem} を要求する</b>。
 * {@code ModelBakerySubsystem} は {@code ModelStore} を作り、
 * それは {@code GlTexture().store(...)} = <b>GL 4.5 DSA</b> なので
 * Apple の GL 4.1 では落ちる (地雷 5 つ目)。
 *
 * <p><b>5c-2b で {@code ModelFactory} を直に作ったのとまったく同じ形である。</b>
 * ここでは {@link RenderDataFactory} を直に作る。あちらは
 * {@code WorldEngine} と {@code ModelFactory} しか要らない [確認済 — コンストラクタ]。
 *
 * <h2>⚠ 規約 16 — 迂回した側が何をしていたか数える</h2>
 * {@code ModelBakerySubsystem} の public は 9 つ。迂回して<b>落ちるもの</b>:
 *
 * <table>
 *   <tr><th>member</th><th>中身</th><th>どうしたか</th></tr>
 *   <tr><td>{@code factory}</td><td>{@code ModelFactory}</td><td>自分で作る [5c-2b]</td></tr>
 *   <tr><td>コンストラクタ</td><td><b>{@code ModelStore} を作る</b> + 処理スレッド</td>
 *       <td>⚠ これが地雷。作らせない</td></tr>
 *   <tr><td>{@code tick}</td><td>{@code factory.processUploads()}</td><td>自分で呼ぶ</td></tr>
 *   <tr><td>{@code shutdown}</td><td>スレッド停止 + free</td><td>{@code VkRealModelBakery.free}</td></tr>
 *   <tr><td><b>{@code requestBlockBake}</b></td><td>出会ったブロックのモデルを焼かせる</td>
 *       <td>⚠ <b>これが要る。</b> {@code VkRealModelBakery.ensureModels}</td></tr>
 *   <tr><td><b>{@code addBiome}</b></td><td>バイオーム色</td>
 *       <td>⚠ 5c-2b で<b>落として草と葉が黒くなった</b>。{@code setBiomeCallback} で配線済み</td></tr>
 *   <tr><td>{@code addDebugData}</td><td>デバッグ HUD</td><td>要らない</td></tr>
 *   <tr><td>{@code getStore}</td><td>{@code ModelStore}</td><td>GL 専用。要らない</td></tr>
 *   <tr><td>{@code areQueuesEmpty} / {@code getProcessingCount}</td><td>進捗</td>
 *       <td>同期的に回しきるので要らない</td></tr>
 * </table>
 *
 * <p><b>9 つ中 2 つが「落ちると壊れる」ものだった。</b>
 * 数えなければ {@code requestBlockBake} を落として
 * {@code IdNotYetComputedException} で止まっていた。
 *
 * <h2>本番との違い</h2>
 * 本番は専用スレッドで非同期にメッシュ化し、モデルが無ければ<b>要求して後で再試行</b>する。
 * ここは<b>同期</b>で、モデルを先に焼いてからメッシュ化する。
 * <b>5c-3b で見たいのはメッシュの中身</b>であって、非同期の調停ではない。
 */
public final class VkRealMesher {
    /** セクション 1 つのメッシュ化を諦めるまでの再試行回数。 */
    private static final int MAX_ATTEMPTS = 8;

    private final WorldEngine world;
    private final VkRealModelBakery bakery;
    private final RenderDataFactory factory;
    private boolean freed;

    /** 診断: どこで詰まったか。 */
    private int meshed, empty, missing, gaveUp, modelsBaked;

    public VkRealMesher(WorldEngine world, VkRealModelBakery bakery) {
        this.world = world;
        this.bakery = bakery;
        // ⚠ RenderGenerationService を通さない (ModelBakerySubsystem = ModelStore = DSA)
        this.factory = new RenderDataFactory(world, bakery.factory(), false);
    }

    /**
     * カメラのセクションを中心に {@code radius} セクションぶん、<b>LoD 0 で</b>メッシュ化する。
     *
     * <p>⚠ <b>LoD 0 固定は意図的である。</b> 5c-3b で見たいのは
     * 「実ジオメトリが正しくメッシュ化されて描かれるか」であって LoD の切り替えではない。
     * <b>粗い LoD が初めて効くのは 5c-4 (HiZ + traversal)</b> である
     * [docs/phase5c3-plan.md]。
     *
     * @return 中身のあるセクションだけ。<b>呼び出し側が free すること</b>
     */
    public List<BuiltSection> meshAround(int cx, int cy, int cz, int radius) {
        return this.meshAround(cx, cy, cz, radius, 0);
    }

    /**
     * 指定した LoD レベルでメッシュ化する (Phase 5c-4c)。
     *
     * <p>⚠ <b>レベル n のセクション座標は、レベル 0 の座標を n だけ右シフトしたもの</b>である。
     * 呼び出し側が既にその座標系で渡すこと。
     *
     * <p>⚠ 世界がそのレベルのデータを持っているとは限らない。
     * Voxy は取り込み時に LoD の階層を作るが、<b>プレイヤーが読み込んだ範囲だけ</b>である。
     * 無ければ {@code missing} に数えて飛ばす。
     */
    public List<BuiltSection> meshAround(int cx, int cy, int cz, int radius, int level) {
        this.meshed = this.empty = this.missing = this.gaveUp = this.modelsBaked = 0;
        var out = new ArrayList<BuiltSection>();
        for (int dx = -radius; dx <= radius; dx++) {
            for (int dy = -radius; dy <= radius; dy++) {
                for (int dz = -radius; dz <= radius; dz++) {
                    var built = this.meshOne(level, cx + dx, cy + dy, cz + dz);
                    if (built == null) continue;
                    if (built.isEmpty()) { this.empty++; built.free(); continue; }
                    out.add(built);
                    this.meshed++;
                }
            }
        }
        Logger.info("[5c-3b] meshed " + this.meshed + " sections around [" + cx + "," + cy + ","
            + cz + "] r=" + radius + " (LoD " + level + "): " + this.empty + " empty, "
            + this.missing + " not in the world yet, " + this.gaveUp + " gave up, "
            + this.modelsBaked + " models baked on demand");
        if (this.meshed == 0) {
            Logger.warn("[5c-3b] ⚠ nothing was meshed. Voxy only has sections the player has"
                + " already loaded — fly around, or pre-generate, before expecting geometry");
        }
        return out;
    }

    /**
     * 1 セクションだけメッシュ化する。
     *
     * <p>⚠ ワールドがその位置を持っていなければ {@code null}。
     * トラバーサルの要求に答えるときは<b>「まだ無い」が普通に起きる</b>。
     */
    public BuiltSection meshOne(int level, int x, int y, int z) {
        WorldSection section = this.world.acquireIfExists(level, x, y, z);
        if (section == null) { this.missing++; return null; }
        try {
            // ⚠ **先にモデルを焼く。** 本番は例外を投げて非同期に焼かせるが、
            // ここは同期なので先回りするほうが単純である
            this.modelsBaked += this.bakery.ensureModels(blockIdsIn(section));

            for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
                try {
                    return this.factory.generateMesh(section);
                } catch (IdNotYetComputedException e) {
                    // ⚠ セクションの外 (隣の境界) を引いたときは、中身を見ても出てこない
                    var wanted = new IntOpenHashSet();
                    if (e.isIdBlockId) wanted.add(e.id);
                    if (e.auxData != null) {
                        for (long state : e.auxData) wanted.add(Mapper.getBlockId(state));
                    }
                    if (wanted.isEmpty()) break;
                    this.modelsBaked += this.bakery.ensureModels(wanted);
                }
            }
            this.gaveUp++;
            return null;
        } finally {
            section.release();
        }
    }

    /** セクションに現れるブロック状態 id [確認済 — {@code RenderGenerationService} と同じ取り方]。 */
    private static IntOpenHashSet blockIdsIn(WorldSection section) {
        var ids = new IntOpenHashSet(256);
        for (long state : section._unsafeGetRawDataArray()) {
            ids.add(Mapper.getBlockId(state));
        }
        return ids;
    }

    public int meshedCount() { return this.meshed; }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        this.factory.free();
    }
}
