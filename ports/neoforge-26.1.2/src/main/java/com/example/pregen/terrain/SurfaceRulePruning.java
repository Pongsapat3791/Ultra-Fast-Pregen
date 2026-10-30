package com.example.pregen.terrain;

import java.util.Arrays;
import java.util.List;
import net.minecraft.core.Holder;
import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * Builds a chunk's surface rules without the tests its biomes decide (see SurfaceBiomes): a
 * biome test that always passes there leaves just what it guards, one that never passes drops
 * out with what it guards, and so do tests and sequences left with nothing to place; a sequence
 * ends at a rule that always places a block. Each block comes out as with Minecraft's rules,
 * after fewer tests (most of the overworld's rules are about biomes absent from a given chunk).
 * Minecraft 26.2 itself turns such biome tests into constant conditions; the rules around them
 * still ran for every block.
 * A subclass of SurfaceRules only to name its protected rule and context types; the mixins pass
 * them as Object.
 */
public final class SurfaceRulePruning extends SurfaceRules {
    /** A rule that never places a block: what a test that never passes comes to. */
    private static final SurfaceRule NEVER = (x, y, z) -> null;
    private static volatile Class<?> stateRule;

    private SurfaceRulePruning() {}

    /** The rules' context of a surface step whose chunk biomes were read (see SurfaceRulesContextMixin). */
    public interface ContextBiomes {
        SurfaceBiomes pregen$getSurfaceBiomes();
    }

    /** Minecraft's biome condition (SurfaceRules.isBiome). */
    public interface BiomeTest {
        /** Whether the condition holds on a block of this biome, tested as the condition does. */
        boolean pregen$matches(Holder<Biome> biome);
    }

    /** Minecraft's negated condition (SurfaceRules.not). */
    public interface Negation {
        ConditionSource pregen$getNegated();
    }

    /** SurfaceRules.Context, for code that cannot name it. */
    public static Class<?> contextClass() {
        return Context.class;
    }

    private static SurfaceBiomes biomes(Object context) {
        return ((ContextBiomes) context).pregen$getSurfaceBiomes();
    }

    /** 1: the condition holds for every block of the chunk, -1: for none, 0: it depends on the block. */
    static int decide(ConditionSource condition, SurfaceBiomes biomes) {
        if (condition instanceof BiomeTest test) return biomes.decide(test);
        if (condition instanceof Negation negation) return -decide(negation.pregen$getNegated(), biomes);
        return 0;
    }

    /** SurfaceRules.ifTrue(ifTrue, thenRun) built for the context, or null for Minecraft's own. */
    public static Object pruneTest(ConditionSource ifTrue, RuleSource thenRun, Object context) {
        SurfaceBiomes biomes = biomes(context);
        if (biomes == null || thenRun instanceof Built) return null;
        int decision = decide(ifTrue, biomes);
        if (decision < 0) return NEVER;
        Context rulesContext = (Context) context;
        SurfaceRule followup = thenRun.apply(rulesContext);
        if (decision > 0 || followup == NEVER) return followup;
        // Minecraft's own test, around the rule already built.
        return ifTrue(ifTrue, new Built(followup)).apply(rulesContext);
    }

    /** SurfaceRules.sequence(sources) built for the context, or null for Minecraft's own. */
    public static Object pruneSequence(List<RuleSource> sources, Object context) {
        if (biomes(context) == null) return null;
        Context rulesContext = (Context) context;
        SurfaceRule[] rules = new SurfaceRule[sources.size()];
        int count = 0;
        boolean alwaysPlaces = false;
        for (RuleSource source : sources) {
            SurfaceRule rule = source.apply(rulesContext);
            if (rule == NEVER) continue;
            rules[count++] = rule;
            if (alwaysPlaces(rule, rulesContext)) {
                alwaysPlaces = true;
                break;  // the rest is never reached
            }
        }
        if (count == 0) return NEVER;
        if (count == 1) return rules[0];
        return new Sequence(Arrays.copyOf(rules, count), alwaysPlaces);
    }

    /** Whether the rule places a block wherever it is asked: Minecraft's state rule, or a sequence ending in one. */
    private static boolean alwaysPlaces(SurfaceRule rule, Context context) {
        if (rule instanceof Sequence sequence) return sequence.alwaysPlaces;
        Class<?> state = stateRule;
        if (state == null) {
            stateRule = state = state(Blocks.STONE.defaultBlockState()).apply(context).getClass();
        }
        return rule.getClass() == state;
    }

    /** Minecraft's sequence rule: the first block one of the rules places. */
    private static final class Sequence implements SurfaceRule {
        private final SurfaceRule[] rules;
        private final boolean alwaysPlaces;

        Sequence(SurfaceRule[] rules, boolean alwaysPlaces) {
            this.rules = rules;
            this.alwaysPlaces = alwaysPlaces;
        }

        @Override
        public BlockState tryApply(int x, int y, int z) {
            for (SurfaceRule rule : rules) {
                BlockState state = rule.tryApply(x, y, z);
                if (state != null) return state;
            }
            return null;
        }
    }

    /** A rule built for one chunk, as a source for Minecraft's test to wrap. */
    private record Built(SurfaceRule rule) implements RuleSource {
        @Override
        public SurfaceRule apply(Context context) {
            return rule;
        }

        /**
         * Never called. On 26.2, where codec() returns a MapCodec, this method is no override, and
         * such a call would fail with an AbstractMethodError instead.
         */
        @Override
        public KeyDispatchDataCodec<? extends RuleSource> codec() {
            throw new UnsupportedOperationException("A rule built for one chunk is never saved");
        }
    }
}
