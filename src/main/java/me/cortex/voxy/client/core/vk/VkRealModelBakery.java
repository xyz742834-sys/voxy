package me.cortex.voxy.client.core.vk;

import it.unimi.dsi.fastutil.ints.Int2ObjectOpenHashMap;
import me.cortex.voxy.client.core.model.ModelAtlasLayout;
import me.cortex.voxy.client.core.model.ModelFactory;
import me.cortex.voxy.common.Logger;
import me.cortex.voxy.common.config.IMappingStorage;
import me.cortex.voxy.common.world.other.Mapper;
import net.minecraft.world.level.block.state.BlockState;
import org.lwjgl.system.MemoryUtil;
import org.lwjgl.vulkan.VkCommandBuffer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;

/**
 * Phase 5c-2b — <b>実際のブロックを焼いて Vulkan のアトラスへ入れる</b>。
 *
 * <h2>⚠ {@code ModelStore} を作らせないこと</h2>
 * 本番は {@code ModelBakerySubsystem} が {@code ModelFactory} を作るが、
 * <b>そのコンストラクタで {@code ModelStore} も作る</b>。
 * {@code ModelStore} はアトラスを {@code GlTexture().store(...)} =
 * <b>GL 4.5 DSA</b> で作るので、Apple の GL 4.1 では<b>JVM ごと abort する</b>
 * (地雷 5 つ目 — [docs/phase5c1a-completion.md §7])。
 *
 * <p>したがってここでは<b>{@code ModelFactory} を直に作る</b>。
 * {@code ModelFactory} は置き場所を {@code ModelUploadTarget} として受け取るので
 * (Phase 5c-2b の分岐点)、GL の資源は 1 つも要らない。
 *
 * <h2>ワールドは要る</h2>
 * {@code ModelFactory} は既定バイオームを {@code Minecraft.getInstance().level} から取る
 * [確認済 — インスタンス初期化子]。<b>ワールドに入った状態でしか作れない。</b>
 * マッピングの永続化のほうは要らないので、{@link InMemoryMappingStorage} で足りる。
 *
 * <h2>⚠ 規約 1 が実データでどうなるか</h2>
 * 合成データは「索引がずれたら絵に出る」ように<b>色を索引から作って</b>いた。
 * 実データの色は<b>ブロックが決める</b>ので、似た色のブロックを選ぶと
 * <b>ずれても絵に出ない</b>。{@link #assertTilesAreDistinguishable} が
 * 焼けたタイルどうしを突き合わせ、<b>見分けが付かない組があれば知らせる</b>。
 */
public final class VkRealModelBakery {
    /** 永続化しないマッピング置き場。ベイクに必要なのは id の採番だけである。 */
    public static final class InMemoryMappingStorage implements IMappingStorage {
        private final Int2ObjectOpenHashMap<byte[]> data = new Int2ObjectOpenHashMap<>();

        @Override
        public void putIdMapping(int id, ByteBuffer buf) {
            byte[] b = new byte[buf.remaining()];
            buf.duplicate().get(b);
            this.data.put(id, b);
        }

        @Override public Int2ObjectOpenHashMap<byte[]> getIdMappingsData() { return this.data; }
        @Override public void flush() {}
        @Override public void close() {}
    }

    private final Mapper mapper;
    private final ModelFactory factory;
    private final VkModelUploadTarget target;
    /** Mapper を自分で作ったか。ワールドのものを借りているなら閉じてはならない。 */
    private final boolean ownsMapper;
    private boolean freed;

    /** 5c-2b: 合成ジオメトリ用。<b>自前の Mapper</b> を作る。 */
    public VkRealModelBakery(VkModelUploadTarget target) {
        this(target, null);
    }

    /**
     * @param existing ワールドの {@code Mapper}。5c-3b の実ジオメトリでは<b>必ずこれを渡す</b>。
     *
     * <h2>⚠ 新しい Mapper を作ってはならない</h2>
     * {@code WorldSection} の中身は<b>ワールドの Mapper が採番した</b>
     * ブロック状態 id とバイオーム id である。別の Mapper で読むと
     * <b>全部が別のブロックになる</b> — 落ちないし絵も出るので気付きにくい。
     *
     * <p>⚠ 渡された Mapper は<b>所有しない</b>。{@link #free} で閉じない。
     */
    public VkRealModelBakery(VkModelUploadTarget target, Mapper existing) {
        this.target = target;
        this.ownsMapper = existing == null;
        this.mapper = existing != null ? existing : new Mapper(new InMemoryMappingStorage());
        // ⚠ ModelBakerySubsystem を通さない。通すと ModelStore (GL 4.5 DSA) が作られる
        this.factory = new ModelFactory(this.mapper, target);
        // ⚠ **本番はこの配線を ModelBakerySubsystem.addBiome が担っている。**
        // 直に作ると付いてこないので、ここで繋ぐ。繋がないと ModelFactory の
        // biomes が空のままで、バイオーム着色されるブロックが**真っ黒になる**
        this.mapper.setBiomeCallback(this.factory::addBiome);
    }

    /**
     * 指定したブロック状態を焼く。<b>ワールドに入った状態で、レンダースレッドから呼ぶこと。</b>
     *
     * <p>焼けたモデル id を返す。合成地形の stateId をこれに差し替えると、
     * <b>実際のブロックのテクスチャで</b>描かれる。
     */
    public int[] bake(List<BlockState> states, int biomeCount) {
        // ⚠ **ブロック状態 id とモデル id は別物である。**
        // mapper が返すのはブロック状態 id で、ジオメトリが指すべきなのは
        // ModelFactory が採番した**モデル id** (uploadModel の索引) のほう。
        // 取り違えるとシェーダが**別のモデルの属性**を引き、絵は出るが中身が違う —
        // バリデーションも規約 1 も捕まえない型の間違いである
        int[] blockIds = new int[states.size()];
        for (int i = 0; i < states.size(); i++) {
            blockIds[i] = this.mapper.getIdForBlockState(states.get(i));
            if (!this.factory.addEntry(blockIds[i])) {
                Logger.warn("[5c-2b] the bakery refused block id " + blockIds[i]
                    + " (" + states.get(i) + ") — it may already be queued");
            }
        }
        // ベイクを回しきる。本番は専用スレッドが回すが、ここは待ってよい
        int spins = 0;
        while (this.factory.processAllThings()) {
            if (++spins > 100_000) {
                throw new IllegalStateException("the bakery did not settle after "
                    + spins + " steps — refusing to spin forever");
            }
        }
        // ⚠ **バイオームはモデルより後に登録する。**
        // ModelFactory.addBiome0 は「色が要るモデル」が 1 つも無いと**何も作らずに返す**
        // [確認済 — modelsRequiringBiomeColours.isEmpty() で早期 return]。
        // 先に登録すると色表が空のまま残り、着色されるブロックが真っ黒になる
        this.registerBiomes(biomeCount);
        spins = 0;
        while (this.factory.processAllThings()) {
            if (++spins > 100_000) {
                throw new IllegalStateException("the biome pass did not settle after "
                    + spins + " steps — refusing to spin forever");
            }
        }

        // ここまで来れば idMappings は埋まっている
        // [確認済 — ModelFactory はベイクの最後に idMappings[blockId] = modelId を書く]
        int[] ids = new int[states.size()];
        for (int i = 0; i < states.size(); i++) {
            ids[i] = this.factory.getModelId(blockIds[i]);
        }
        // 置き場所 (VkModelUploadTarget) へ渡す。GPU への転送はまだ
        this.factory.processUploads();
        Logger.info("[5c-2b] baked " + states.size() + " block states "
            + java.util.Arrays.toString(blockIds) + " -> model ids "
            + java.util.Arrays.toString(ids) + "; " + this.target.pendingCount()
            + " textures queued for transfer");

        this.reportIndistinguishableTiles(ids);
        this.reportBiomeTints(ids);
        return ids;
    }

    /**
     * バイオームを {@code count} 個登録する。
     *
     * <h2>⚠ なぜ 1 個では足りないのか</h2>
     * シェーダは {@code colourData[colourTint + extractBiomeId(quad)]} を引く
     * [quad_util.glsl]。合成地形は<b>バイオーム id にセクション番号を書いている</b>
     * (規約 1 — 索引がずれたら絵に出るようにするため) ので、
     * <b>セクション数だけバイオームが要る</b>。足りないと色表の外を読む。
     *
     * <p>id は {@code Mapper} が登録順に 0 から振るので、
     * <b>ここで登録した順序がそのままバイオーム id になる</b>。
     */
    private void registerBiomes(int count) {
        var lookup = net.minecraft.client.Minecraft.getInstance().level.registryAccess()
            .lookupOrThrow(net.minecraft.core.registries.Registries.BIOME);
        int n = 0;
        for (var holder : lookup.listElements().toList()) {
            if (n >= count) break;
            int id = this.mapper.getIdForBiome(holder);
            if (id != n) {
                throw new IllegalStateException("biome ids must be dense from 0 — registered "
                    + holder.unwrapKey().map(Object::toString).orElse("?") + " and got id " + id + " where " + n + " was expected");
            }
            n++;
        }
        if (n < count) {
            throw new IllegalStateException("the world's biome registry only has " + n
                + " biomes but the synthetic terrain uses biome ids up to " + (count - 1));
        }
        Logger.info("[5c-2b] registered " + n + " biomes (ids 0.." + (n - 1) + ")");
    }

    /**
     * <b>バイオーム着色が使える値になっているか</b>を実際に見て言う [規約 11]。
     *
     * <p>色表が空のままだと着色されるブロックは<b>真っ黒</b>になる —
     * これは「落ちない」ので、見に行かなければ分からない
     * [実際に草と葉が黒くなった]。
     */
    private void reportBiomeTints(int[] modelIds) {
        int tinted = 0;
        var bad = new ArrayList<String>();
        for (int id : modelIds) {
            if ((this.target.modelFlags(id) & 2) == 0) continue;   // バイオーム LUT を使わない
            tinted++;
            int base = this.target.modelColourTint(id);
            int colour = this.target.biomeColour(base);
            if ((colour & 0xFF000000) == 0) {
                bad.add("model " + id + " uses the biome table at index " + base
                    + " but that entry is empty (0x" + Integer.toHexString(colour)
                    + ") — it would render black");
            }
        }
        if (tinted == 0) {
            Logger.warn("[5c-2b] ⚠ none of the baked models use biome tinting —"
                + " the biome path is NOT being exercised");
        } else if (bad.isEmpty()) {
            Logger.info("[5c-2b] biome tinting is usable for all " + tinted
                + " tinted models (e.g. 0x"
                + Integer.toHexString(this.target.biomeColour(
                    this.target.modelColourTint(firstTinted(modelIds)))) + ")");
        } else {
            for (String s : bad) Logger.error("[5c-2b] ⚠ " + s);
        }
    }

    private int firstTinted(int[] modelIds) {
        for (int id : modelIds) if ((this.target.modelFlags(id) & 2) != 0) return id;
        return modelIds[0];
    }

    /**
     * <b>規約 1 が実データで成り立っているかを、実際に見て言う</b>
     * (【規約 11】「落ちなかった」は証拠にならない — 成り立っていることも言わせる)。
     *
     * <p>⚠ {@code recordUploads} より<b>前に</b>呼ぶこと。溜めた分が空になると読めない。
     */
    private void reportIndistinguishableTiles(int[] modelIds) {
        long[] addrs = new long[modelIds.length];
        for (int i = 0; i < modelIds.length; i++) {
            addrs[i] = this.target.stagedTileAddress(modelIds[i]);
            if (addrs[i] == 0L) {
                Logger.warn("[5c-2b] ⚠ model " + modelIds[i] + " has no staged tile —"
                    + " 規約 1 could not be checked for it");
                return;
            }
        }
        var problems = assertTilesAreDistinguishable(modelIds, addrs, ModelAtlasLayout.FACE_TEXELS);
        if (problems.isEmpty()) {
            Logger.info("[5c-2b] 規約 1 holds for the real data: all " + modelIds.length
                + " baked tiles are pairwise distinguishable");
        } else {
            for (String s : problems) {
                Logger.warn("[5c-2b] ⚠ 規約 1 weakened: " + s);
            }
        }
    }

    /**
     * ブロック状態 id の集合について<b>モデルが焼けていることを保証する</b> (Phase 5c-3b)。
     *
     * <h2>⚠ なぜ要るのか — 規約 16 の数え上げで出た</h2>
     * 本番はメッシャが {@code ModelBakerySubsystem.requestBlockBake} を呼び、
     * 出会ったブロックのモデルを非同期に焼かせている。
     * <b>{@code ModelBakerySubsystem} を迂回すると、この配線が落ちる</b> —
     * そのまま {@code generateMesh} を呼ぶと {@code IdNotYetComputedException} になる。
     *
     * <p>5c-2b の {@code addBiome} と<b>同じ型の見落とし</b>である。
     * あちらは「草と葉が黒くなる」で出た。こちらは例外で出るぶん見つけやすい。
     *
     * @return 新しく焼いた数
     */
    public int ensureModels(it.unimi.dsi.fastutil.ints.IntCollection blockIds) {
        int added = 0;
        for (int id : blockIds) {
            if (id == 0) continue;                       // 空気
            if (this.factory.hasModelForBlockId(id)) continue;
            if (this.factory.addEntry(id)) added++;
        }
        if (added == 0) return 0;
        int spins = 0;
        while (this.factory.processAllThings()) {
            if (++spins > 1_000_000) {
                throw new IllegalStateException("the bakery did not settle after " + spins
                    + " steps while baking " + added + " block states");
            }
        }
        this.factory.processUploads();
        return added;
    }

    /**
     * <b>まだ流していないタイルが互いに区別できるか</b> (規約 1 の実データ版, Phase 5c-3b)。
     *
     * <p>⚠ {@code recordUploads} より<b>前に</b>呼ぶこと。溜めた分が空になると読めない。
     *
     * @return 見分けが付かない組の数。0 なら全部区別できる
     */
    public int reportIndistinguishableStagedTiles() {
        var ids = this.target.stagedModelIds();
        long bytes = (long) ModelAtlasLayout.tileWidth(ModelAtlasLayout.FACE_TEXELS, 0)
            * ModelAtlasLayout.tileHeight(ModelAtlasLayout.FACE_TEXELS, 0) * 4L;

        long[] addrs = new long[ids.length];
        boolean[] blank = new boolean[ids.length];
        int blankCount = 0;
        for (int i = 0; i < ids.length; i++) {
            addrs[i] = this.target.stagedTileAddress(ids[i]);
            blank[i] = addrs[i] != 0 && isBlank(addrs[i], bytes);
            if (blank[i]) blankCount++;
        }

        // --- 同じ中身どうしをまとめる ---
        int[] group = new int[ids.length];
        java.util.Arrays.fill(group, -1);
        var groups = new ArrayList<List<Integer>>();
        for (int i = 0; i < ids.length; i++) {
            if (group[i] != -1 || addrs[i] == 0) continue;
            List<Integer> g = null;
            for (int j = i + 1; j < ids.length; j++) {
                if (group[j] != -1 || addrs[j] == 0) continue;
                if (!sameBytes(addrs[i], addrs[j], bytes)) continue;
                if (g == null) { g = new ArrayList<>(); g.add(i); group[i] = groups.size(); }
                g.add(j);
                group[j] = groups.size();
            }
            if (g != null) groups.add(g);
        }
        if (groups.isEmpty()) return 0;

        int involved = 0, pairs = 0;
        for (var g : groups) { involved += g.size(); pairs += g.size() * (g.size() - 1) / 2; }

        var modelToBlocks = this.modelToBlockStates();

        // ⚠⚠ **「同じブロックの別状態」と「違うブロックどうし」は<b>意味が違う</b>。**
        //
        // 水の水位違いが同じタイルになるのは当然で、**取り違えても絵は正しい**。
        // 規約 1 が本当に弱まるのは<b>違うブロックが同じ絵になる</b>場合だけである
        // — そのときだけ「モデルを取り違えても気付けない」が成立する。
        //
        // 一緒に数えると「24% が弱まっている」と読めてしまうが、実態は違う
        var crossBlock = new ArrayList<List<Integer>>();
        int sameBlockGroups = 0, blankGroups = 0;
        for (var g : groups) {
            if (blank[g.get(0)]) { blankGroups++; continue; }
            var names = new java.util.HashSet<String>();
            for (int idx : g) names.add(modelToBlocks.getOrDefault(ids[idx], "?"));
            if (names.size() == 1) sameBlockGroups++; else crossBlock.add(g);
        }

        Logger.info("[5c-3b] 規約 1: " + involved + " of " + ids.length
            + " baked models share a tile with another (" + pairs + " pairs, "
            + groups.size() + " groups) — " + sameBlockGroups
            + " groups are states of the SAME block (harmless: swapping them still"
            + " draws that block), " + blankGroups + " are blank (air-like), "
            + crossBlock.size() + " span DIFFERENT blocks");

        if (blankCount > 0) {
            var sb = new StringBuilder("[5c-3b] blank tiles: ");
            for (int i = 0; i < ids.length; i++) {
                if (blank[i]) sb.append(modelToBlocks.getOrDefault(ids[i], "?")).append(' ');
            }
            Logger.info(sb + " [blank is correct for blocks with no rendered shape;"
                + " anything else here means the bake produced nothing]");
        }

        if (crossBlock.isEmpty()) {
            Logger.info("[5c-3b] ⚠ no two DIFFERENT blocks bake to the same tile —"
                + " 規約 1 still holds where it matters");
            return 0;
        }

        // ⚠ ここだけが本当の弱まりである
        Logger.warn("[5c-3b] ⚠ 規約 1 weakened: " + crossBlock.size()
            + " groups contain DIFFERENT blocks with identical tiles."
            + " Mixing those up would NOT change the picture");
        int shown = 0;
        for (var g : crossBlock) {
            if (shown++ >= 8) {
                Logger.warn("[5c-3b]   ... and " + (crossBlock.size() - 8) + " more");
                break;
            }
            var sb = new StringBuilder("[5c-3b]   ");
            for (int k = 0; k < g.size(); k++) {
                sb.append(ids[g.get(k)]).append('=')
                  .append(modelToBlocks.getOrDefault(ids[g.get(k)], "?"));
                if (k + 1 < g.size()) sb.append(", ");
            }
            Logger.warn(sb.toString());
        }
        return pairs;
    }

    /** モデル id -> そのモデルを使うブロック状態 (先頭 1 つの名前)。診断用。 */
    private java.util.Map<Integer, String> modelToBlockStates() {
        var out = new java.util.HashMap<Integer, String>();
        int[] idMappings = this.factory._unsafeRawAccess();
        for (int blockId = 0; blockId < idMappings.length; blockId++) {
            int modelId = idMappings[blockId];
            if (modelId < 0 || out.containsKey(modelId)) continue;
            try {
                var state = this.mapper.getBlockStateFromBlockId(blockId);
                out.put(modelId, state == null ? "?" : state.getBlock().toString());
            } catch (RuntimeException e) {
                out.put(modelId, "?");
            }
        }
        return out;
    }

    /** タイルが<b>全部 0</b> か (= 焼き損なって何も出ていない)。 */
    private static boolean isBlank(long addr, long n) {
        for (long i = 0; i < n; i += 8) {
            if (MemoryUtil.memGetLong(addr + i) != 0L) return false;
        }
        return true;
    }

    /**
     * ワールドの Mapper に<b>既に登録されているバイオーム</b>を factory へ流し込む
     * (Phase 5c-3b)。
     *
     * <h2>⚠ コールバックだけでは足りない</h2>
     * {@code setBiomeCallback} は<b>これから登録されるもの</b>しか拾わない。
     * ワールドは取り込み時に既にバイオームを登録しているので、
     * <b>そのぶんは永久に届かない</b> — 着色されるブロックが黒くなる
     * (5c-2b で踏んだのと同じ症状の、別の原因)。
     *
     * <h2>⚠ モデルより後に呼ぶこと</h2>
     * {@code addBiome0} は「色が要るモデル」が 1 つも無いと<b>何も作らずに返す</b>
     * [5c-2b §9.4]。
     *
     * @return 流し込んだバイオーム数
     */
    public int replayBiomes() {
        var entries = this.mapper.getBiomeEntries();
        int n = 0;
        for (var e : entries) {
            if (e == null) continue;
            this.factory.addBiome(e);
            n++;
        }
        int spins = 0;
        while (this.factory.processAllThings()) {
            if (++spins > 1_000_000) {
                throw new IllegalStateException("the biome replay did not settle");
            }
        }
        this.factory.processUploads();
        Logger.info("[5c-3b] replayed " + n + " biomes already registered by the world");
        return n;
    }

    /** ベイク済みモデルの置き場所 (メッシャに渡す)。 */
    public ModelFactory factory() { return this.factory; }

    /** 溜まったテクスチャをアトラスへ流す。<b>フレームの中で呼ぶこと。</b> */
    public int recordUploads(VkCommandBuffer cmd) {
        return this.target.recordUploads(cmd);
    }

    /**
     * <b>焼けたタイルが互いに見分けが付くこと</b> (規約 1 の実データ版)。
     *
     * <p>合成データは色を索引から作っていたので「索引がずれたら絵に出る」ことが
     * 構造で保証されていた。実データではブロックが色を決めるので、
     * <b>似たブロックを選ぶとその性質が失われる</b>。
     *
     * <p>ここでは<b>アトラスに入った実際のテクセル</b>を突き合わせる。
     * 一致する組があれば、<b>その 2 つを取り違えても絵は変わらない</b> —
     * つまり以降の検査はその 2 つを区別しない。
     *
     * @param tiles モデル id ごとの、ミップ 0 のタイルの内容 (呼び出し側が読み戻す)
     * @return 見分けが付かない組の説明。空なら全て区別できる
     */
    public static List<String> assertTilesAreDistinguishable(int[] modelIds, long[] tileAddresses,
                                                            int faceTexels) {
        var problems = new ArrayList<String>();
        long bytes = (long) ModelAtlasLayout.tileWidth(faceTexels, 0)
            * ModelAtlasLayout.tileHeight(faceTexels, 0) * 4L;
        for (int i = 0; i < modelIds.length; i++) {
            for (int j = i + 1; j < modelIds.length; j++) {
                if (sameBytes(tileAddresses[i], tileAddresses[j], bytes)) {
                    problems.add("models " + modelIds[i] + " and " + modelIds[j]
                        + " have identical tiles — swapping them would not change the picture");
                }
            }
        }
        return problems;
    }

    private static boolean sameBytes(long a, long b, long n) {
        for (long i = 0; i < n; i += 8) {
            if (MemoryUtil.memGetLong(a + i) != MemoryUtil.memGetLong(b + i)) return false;
        }
        return true;
    }

    public Mapper mapper() { return this.mapper; }

    public void free() {
        if (this.freed) return;
        this.freed = true;
        this.factory.free();
    }
}
