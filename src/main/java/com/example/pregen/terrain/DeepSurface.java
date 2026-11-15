package com.example.pregen.terrain;

import com.example.pregen.PreGenMod;
import com.example.pregen.WorldgenCompat;
import com.example.pregen.mixin.SurfaceSystemAccessor;
import java.util.Collections;
import java.util.EnumSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.levelgen.Heightmap;
import net.minecraft.world.level.levelgen.SurfaceRules;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.VerticalAnchor;
import net.minecraft.world.level.levelgen.WorldGenerationContext;

/**
 * The surface step below the ground. Minecraft's surface step asks its rule list about every
 * default block (stone) of a column, down to the bottom of the world, and writes each block the
 * rules place. Far below the ground only the rules that look at nothing but the height can place
 * anything: the overworld's list is bedrock at the bottom, then everything about biomes behind one
 * test (the block is not below the preliminary surface), then deepslate below y 0 fading out to
 * y 8. So below that test's level in a column:
 * <ul>
 * <li>a default block gets the block of the first height rule that holds at its y, which is the
 * same for a whole run of heights where no rule draws a random number: such runs are written
 * straight into the column before Minecraft's loop reaches them (it then finds no default block
 * there and asks nothing);</li>
 * <li>elsewhere only the height rules are asked, Minecraft's own rule objects where they draw:
 * these heights too are answered as the loop starts the column, and the loop then finds nothing to
 * do below that level (its reads there are answered with air without looking, see
 * {@link Columns#floor}; the look-ahead for the next block that is not stone still reads the real
 * column).</li>
 * </ul>
 * This holds only for a rule list of exactly that shape (height rule -> block, or the
 * preliminary-surface test -> anything); any other list is left to Minecraft. The first chunks of
 * each list are not shortened: every answer is compared with the full list's, and one difference
 * turns the shortcut off for that list (the full list's answer is the one used, so no block is
 * ever taken from an unproven shortcut).
 * A subclass of SurfaceRules only to name its protected rule and context types.
 */
public final class DeepSurface extends SurfaceRules {
    /** Benchmark switch, on unless -Dfastpregen.deepSurface=false. */
    public static volatile boolean enabled = Boolean.parseBoolean(System.getProperty("fastpregen.deepSurface", "true"));
    /** Benchmark switch for the loop's floor (see Columns#floor), on unless -Dfastpregen.surfaceFloor=false. */
    public static volatile boolean floorEnabled = Boolean.parseBoolean(System.getProperty("fastpregen.surfaceFloor", "true"));
    /** Compares every answer with the full rule list in every chunk and writes nothing in bulk (the regression's mode). */
    public static volatile boolean verifyAll = Boolean.getBoolean("fastpregen.deepSurface.verifyAll");
    /** Chunks of a rule list compared with the full list before its shortcut is used. */
    private static final int VERIFY_CHUNKS = 16;
    /** Blocks written straight into their column. */
    public static final LongAdder BULK_BLOCKS = new LongAdder();
    /** Chunks whose surface step ran with the shortcut, and chunks that only compared. */
    public static final LongAdder SHORTENED_CHUNKS = new LongAdder();
    public static final LongAdder VERIFIED_CHUNKS = new LongAdder();
    /** Answers that differed from the full rule list's (the shortcut is then off for that list). */
    public static final LongAdder MISMATCHES = new LongAdder();

    private static final Object NONE = new Object();
    /** By identity: a rule source's own equals and hashCode walk its whole tree. */
    private static final Map<RuleSource, Object> PLANS = Collections.synchronizedMap(new IdentityHashMap<>());
    private static final ThreadLocal<Step> STEP = ThreadLocal.withInitial(Step::new);

    private DeepSurface() {}

    /** SurfaceRules.sequence (SequenceRuleSourceMixin). */
    public interface SequenceSource {
        List<RuleSource> pregen$sequence();
    }

    /** SurfaceRules.ifTrue (TestRuleSourceMixin). */
    public interface TestSource {
        ConditionSource pregen$ifTrue();

        RuleSource pregen$thenRun();
    }

    /** SurfaceRules.verticalGradient (VerticalGradientSourceMixin). */
    public interface GradientSource {
        VerticalAnchor pregen$trueAtAndBelow();

        VerticalAnchor pregen$falseAtAndAbove();
    }

    /** SurfaceRules.state (BlockRuleSourceMixin). */
    public interface BlockSource {
        BlockState pregen$resultState();
    }

    /** The surface step's column (SurfaceSystem$1, SurfaceBlockColumnMixin): below this height its loop has nothing to do. */
    public interface FloorColumn {
        int pregen$floor();
    }

    /** The known runs of the surface step running on this thread, or null: none, or a chunk that only compares. */
    public static Columns columnsOfThisStep() {
        Guarded guarded = STEP.get().guarded;
        return guarded == null ? null : guarded.columns;
    }

    /** SurfaceRules.Context (SurfaceRulesContextDeepMixin). */
    public interface RulesContext {
        /** The level the preliminary-surface test compares the block's y with, in the current column. */
        int pregen$minSurfaceLevel();

        int pregen$blockY();

        /** As updateY for a rule that looks at nothing but the height: the block's y, and conditions ask again. */
        void pregen$atHeight(int blockY);

        ChunkAccess pregen$chunk();

        WorldGenerationContext pregen$generationContext();

        SurfaceSystem pregen$system();

        void pregen$setDeepColumns(Columns columns);
    }

    /** The surface step running on a thread: its rule list, and the shortened rule built for it. */
    private static final class Step {
        RuleSource top;
        Guarded guarded;
    }

    /** What is known about one rule list. */
    private static final class Plan {
        /** The list's entries, to recognize it when Minecraft builds it. */
        final List<RuleSource> sequence;
        /** The height rules among them, in the list's order: where each is, its bounds and its block. */
        final int[] index;
        final GradientSource[] gradients;
        final BlockState[] states;
        final AtomicInteger verified = new AtomicInteger();
        volatile boolean broken;

        Plan(List<RuleSource> sequence, int[] index, GradientSource[] gradients, BlockState[] states) {
            this.sequence = sequence;
            this.index = index;
            this.gradients = gradients;
            this.states = states;
        }
    }

    /** At the start of Minecraft's surface step with this rule list, on this mod's terrain workers. */
    public static void begin(RuleSource top) {
        Step step = STEP.get();
        step.top = enabled ? top : null;
        step.guarded = null;
    }

    /** At the end of that step. */
    public static void end() {
        Step step = STEP.get();
        Guarded guarded = step.guarded;
        step.top = null;
        step.guarded = null;
        if (guarded != null) guarded.finished();
    }

    private static Object plan(RuleSource top) {
        Object plan = PLANS.get(top);
        if (plan == null) {
            plan = read(top);
            if (PLANS.size() > 64) PLANS.clear();
            PLANS.put(top, plan);
        }
        return plan;
    }

    /** The plan of a list of height rules and preliminary-surface tests only, else NONE. */
    private static Object read(RuleSource top) {
        if (!(top instanceof SequenceSource list)) return NONE;
        List<RuleSource> sequence = list.pregen$sequence();
        ConditionSource belowGround = abovePreliminarySurface();
        int[] index = new int[sequence.size()];
        GradientSource[] gradients = new GradientSource[sequence.size()];
        BlockState[] states = new BlockState[sequence.size()];
        int count = 0;
        boolean guarded = false;
        for (int i = 0; i < sequence.size(); i++) {
            if (!(sequence.get(i) instanceof TestSource test)) return NONE;
            ConditionSource condition = test.pregen$ifTrue();
            if (condition == belowGround) {
                guarded = true;
            } else if (condition instanceof GradientSource gradient && test.pregen$thenRun() instanceof BlockSource block
                    && block.pregen$resultState() != null) {
                index[count] = i;
                gradients[count] = gradient;
                states[count++] = block.pregen$resultState();
            } else {
                return NONE;
            }
        }
        // Without such a test there is no "below the ground" to shorten.
        if (!guarded) return NONE;
        return new Plan(sequence, java.util.Arrays.copyOf(index, count), java.util.Arrays.copyOf(gradients, count),
                java.util.Arrays.copyOf(states, count));
    }

    /**
     * The rule Minecraft's surface step uses for the list {@code sources}: {@code full} (what
     * Minecraft, or SurfaceRulePruning, built for it; {@code built} holds the rule of each entry,
     * null where none was built), or that rule shortened below the ground when {@code sources} is
     * the step's own list and its shape allows.
     */
    static SurfaceRule guard(List<RuleSource> sources, SurfaceRule[] built, SurfaceRule full, Context context) {
        Step step = STEP.get();
        RuleSource top = step.top;
        if (top == null || step.guarded != null || !(top instanceof SequenceSource list) || list.pregen$sequence() != sources) {
            return full;
        }
        if (!((Object) context instanceof RulesContext rules) || !WorldgenCompat.deepSurfaceAllowed()) return full;
        if (!(plan(top) instanceof Plan plan) || plan.broken) return full;
        int count = plan.index.length;
        SurfaceRule[] heightRules = new SurfaceRule[count];
        int[] trueAt = new int[count];
        int[] falseAt = new int[count];
        WorldGenerationContext generation = rules.pregen$generationContext();
        for (int i = 0; i < count; i++) {
            heightRules[i] = built[plan.index[i]];
            if (heightRules[i] == null) return full;
            trueAt[i] = plan.gradients[i].pregen$trueAtAndBelow().resolveY(generation);
            falseAt[i] = plan.gradients[i].pregen$falseAtAndAbove().resolveY(generation);
        }
        boolean verify = verifyAll || plan.verified.get() < VERIFY_CHUNKS;
        // A chunk that compares keeps its default blocks, so that the full list is asked about each.
        Columns columns = verify ? null : Columns.of(rules, trueAt, falseAt, plan.states, heightRules);
        Guarded guarded = new Guarded(plan, rules, full, heightRules, trueAt, falseAt, verify, columns);
        step.guarded = guarded;
        rules.pregen$setDeepColumns(columns);
        return guarded;
    }

    /**
     * The block the height rules alone give a default block of the context's height {@code blockY},
     * with Minecraft's own rule where one draws.
     */
    private static BlockState below(int x, int y, int z, int blockY, int[] trueAt, int[] falseAt, BlockState[] states,
                                    SurfaceRule[] rules) {
        for (int i = 0; i < trueAt.length; i++) {
            // SurfaceRules.verticalGradient: true at and below the first height, false at and above the second.
            if (blockY <= trueAt[i]) return states[i];
            if (blockY < falseAt[i]) {
                BlockState state = rules[i].tryApply(x, y, z);
                if (state != null) return state;
            }
        }
        return null;
    }

    /** A rule list that asks only its height rules below the level of its preliminary-surface tests. */
    private static final class Guarded implements SurfaceRule {
        private final Plan plan;
        private final RulesContext context;
        private final SurfaceRule full;
        private final SurfaceRule[] heightRules;
        private final int[] trueAt;
        private final int[] falseAt;
        private final boolean verify;
        private final Columns columns;
        private boolean mismatch;

        Guarded(Plan plan, RulesContext context, SurfaceRule full, SurfaceRule[] heightRules, int[] trueAt, int[] falseAt,
                boolean verify, Columns columns) {
            this.plan = plan;
            this.context = context;
            this.full = full;
            this.heightRules = heightRules;
            this.trueAt = trueAt;
            this.falseAt = falseAt;
            this.verify = verify;
            this.columns = columns;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            RulesContext context = this.context;
            // The preliminary-surface test of Minecraft: blockY >= getMinSurfaceLevel(). Where it fails, the
            // entries behind it place nothing.
            int blockY = context.pregen$blockY();
            if (mismatch || blockY >= context.pregen$minSurfaceLevel()) return full.tryApply(x, y, z);
            BlockState fast = below(x, y, z, blockY, trueAt, falseAt, plan.states, heightRules);
            if (!verify) return fast;
            BlockState slow = full.tryApply(x, y, z);
            if (slow != fast) {
                mismatch = true;
                MISMATCHES.increment();
                if (!plan.broken) {
                    plan.broken = true;
                    PreGenMod.LOGGER.warn("[PreGen] Surface shortcut below the ground turned off: Minecraft's rules place {} at"
                            + " [{}, {}, {}] where the shortcut expected {}. The world is not affected (Minecraft's answer is"
                            + " the one used); please report this with your mod list.", slow, x, y, z, fast);
                }
            }
            return slow;
        }

        void finished() {
            if (verify) {
                if (!mismatch) {
                    plan.verified.incrementAndGet();
                    VERIFIED_CHUNKS.increment();
                }
            } else {
                SHORTENED_CHUNKS.increment();
                if (columns != null) BULK_BLOCKS.add(columns.written);
            }
        }
    }

    /**
     * The runs of heights where every default block below the ground gets one known block, written
     * into each column as Minecraft's loop reaches it (SurfaceRules.Context#updateXZ), before the
     * loop reads those blocks.
     */
    public static final class Columns {
        private final RulesContext context;
        private final ProtoChunk chunk;
        private final BlockState defaultBlock;
        private final int[] low;
        private final int[] high;
        private final BlockState[] states;
        /** The heights where a height rule draws (no known run), lowest first; null where the floor is not used. */
        private final int[] drawn;
        private final int[] trueAt;
        private final int[] falseAt;
        private final BlockState[] ruleStates;
        private final SurfaceRule[] heightRules;
        private long written;
        /**
         * In the current column: below this height every default block has its final block already (the
         * known runs and the drawn heights, both written as the column starts), and no other rule can hold
         * there. Minecraft's loop has nothing left to do below it. Integer.MIN_VALUE: no such height.
         */
        public int floor = Integer.MIN_VALUE;

        private Columns(RulesContext context, ProtoChunk chunk, BlockState defaultBlock, int[] low, int[] high,
                        BlockState[] states, int[] drawn, int[] trueAt, int[] falseAt, BlockState[] ruleStates,
                        SurfaceRule[] heightRules) {
            this.context = context;
            this.chunk = chunk;
            this.defaultBlock = defaultBlock;
            this.low = low;
            this.high = high;
            this.states = states;
            this.drawn = drawn;
            this.trueAt = trueAt;
            this.falseAt = falseAt;
            this.ruleStates = ruleStates;
            this.heightRules = heightRules;
        }

        /** Null where writing straight into the chunk is not known to equal Minecraft's write. */
        static Columns of(RulesContext context, int[] trueAt, int[] falseAt, BlockState[] ruleStates, SurfaceRule[] heightRules) {
            ChunkAccess access = context.pregen$chunk();
            if (access.getClass() != ProtoChunk.class) return null;
            ProtoChunk chunk = (ProtoChunk) access;
            ChunkStatus status = chunk.getStatus();
            // ProtoChunk#setBlockState before the light steps: the section write and the heightmaps.
            if (status.isOrAfter(ChunkStatus.INITIALIZE_LIGHT)) return null;
            EnumSet<Heightmap.Types> heightmaps = status.heightmapsAfter();
            for (Heightmap.Types type : heightmaps) {
                if (!chunk.hasPrimedHeightmap(type)) return null;
            }
            BlockState defaultBlock = ((SurfaceSystemAccessor) context.pregen$system()).pregen$getDefaultBlock();
            // The chunk's lowest and highest block (spelled so that every Minecraft version has the calls).
            int minY = net.minecraft.core.SectionPos.sectionToBlockCoord(chunk.getSectionYFromSectionIndex(0));
            int maxY = minY + chunk.getSectionsCount() * 16 - 1;
            int[] low = new int[8];
            int[] high = new int[8];
            BlockState[] states = new BlockState[8];
            int count = 0;
            BlockState run = null;
            int runStart = 0;
            // Above the highest second height no rule holds, so the runs end there at the latest.
            int top = minY - 1;
            for (int limit : falseAt) top = Math.max(top, limit);
            top = Math.min(top, maxY);
            for (int y = minY; y <= top + 1; y++) {
                BlockState state = y <= top ? certain(y, trueAt, falseAt, ruleStates) : null;
                if (state != run) {
                    if (run != null && count < low.length && sameWrite(defaultBlock, run, heightmaps)) {
                        low[count] = runStart;
                        high[count] = y - 1;
                        states[count++] = run;
                    }
                    run = state;
                    runStart = y;
                }
            }
            if (count == 0) return null;
            // The floor: every block a height rule can give must be one this class writes as Minecraft does.
            boolean floor = floorEnabled;
            for (BlockState state : ruleStates) floor &= sameWrite(defaultBlock, state, heightmaps);
            int[] drawn = null;
            if (floor) {
                drawn = new int[Math.max(0, top - minY + 1)];
                int heights = 0;
                for (int y = minY; y <= top; y++) {
                    if (certain(y, trueAt, falseAt, ruleStates) == null && mayHold(y, falseAt)) drawn[heights++] = y;
                }
                drawn = java.util.Arrays.copyOf(drawn, heights);
            }
            return new Columns(context, chunk, defaultBlock, java.util.Arrays.copyOf(low, count),
                    java.util.Arrays.copyOf(high, count), java.util.Arrays.copyOf(states, count), drawn, trueAt, falseAt,
                    ruleStates, heightRules);
        }

        /** Whether any height rule can still hold at y (each holds only below its second height). */
        private static boolean mayHold(int y, int[] falseAt) {
            for (int limit : falseAt) {
                if (y < limit) return true;
            }
            return false;
        }

        /** The block every default block at y gets from the height rules, or null: none, or a rule draws there. */
        private static BlockState certain(int y, int[] trueAt, int[] falseAt, BlockState[] states) {
            for (int i = 0; i < trueAt.length; i++) {
                if (y <= trueAt[i]) return states[i];
                if (y < falseAt[i]) return null;
            }
            return null;
        }

        /**
         * Whether replacing {@code from} by {@code to} through the section equals the surface step's
         * write: no fluid to mark, no block entity, and both count for every heightmap kept now, so
         * the heightmap update of Minecraft's write changes nothing (an opaque block replacing an
         * opaque one at the same place never moves a height).
         */
        private static boolean sameWrite(BlockState from, BlockState to, EnumSet<Heightmap.Types> heightmaps) {
            if (from == to || from.isAir() || to.isAir()) return false;
            if (!from.getFluidState().isEmpty() || !to.getFluidState().isEmpty() || to.hasBlockEntity()) return false;
            for (Heightmap.Types type : heightmaps) {
                if (!type.isOpaque().test(from) || !type.isOpaque().test(to)) return false;
            }
            return true;
        }

        /** The column Minecraft's loop starts on. */
        public void column(int blockX, int blockZ) {
            int belowGround = context.pregen$minSurfaceLevel() - 1;
            int x = blockX & 15;
            int z = blockZ & 15;
            for (int i = 0; i < low.length; i++) {
                int top = Math.min(high[i], belowGround);
                if (top >= low[i]) replace(x, z, low[i], top, states[i]);
            }
            int[] drawn = this.drawn;
            if (drawn == null) return;
            // The heights where a rule draws: asked here as Minecraft's loop would ask them (the same rule
            // objects, for the same default blocks), so that the loop finds nothing to do below the ground.
            ProtoChunk chunk = this.chunk;
            BlockState defaultBlock = this.defaultBlock;
            for (int y : drawn) {
                if (y > belowGround) break;
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                if (section.hasOnlyAir() || section.getBlockState(x, y & 15, z) != defaultBlock) continue;
                context.pregen$atHeight(y);
                BlockState state = below(blockX, y, blockZ, y, trueAt, falseAt, ruleStates, heightRules);
                if (state != null && state != defaultBlock) {
                    section.setBlockState(x, y & 15, z, state, false);
                    written++;
                }
            }
            floor = belowGround + 1;
        }

        private void replace(int x, int z, int minY, int maxY, BlockState state) {
            ProtoChunk chunk = this.chunk;
            BlockState defaultBlock = this.defaultBlock;
            int count = 0;
            int y = minY;
            while (y <= maxY) {
                // The top block of y's section, also for negative y.
                int sectionTop = Math.min(maxY, y | 15);
                LevelChunkSection section = chunk.getSection(chunk.getSectionIndex(y));
                if (!section.hasOnlyAir()) {
                    int atOnce = BlockBits.replaceColumn(section, x, z, y & 15, sectionTop & 15, defaultBlock, state);
                    if (atOnce >= 0) {
                        count += atOnce;
                    } else {
                        for (int blockY = y; blockY <= sectionTop; blockY++) {
                            if (section.getBlockState(x, blockY & 15, z) == defaultBlock) {
                                section.setBlockState(x, blockY & 15, z, state, false);
                                count++;
                            }
                        }
                    }
                }
                y = sectionTop + 1;
            }
            written += count;
        }
    }

    /** Counters so far: blocks written in bulk, chunks shortened, chunks compared, differences. */
    public static long[] counts() {
        return new long[]{BULK_BLOCKS.sum(), SHORTENED_CHUNKS.sum(), VERIFIED_CHUNKS.sum(), MISMATCHES.sum()};
    }
}
