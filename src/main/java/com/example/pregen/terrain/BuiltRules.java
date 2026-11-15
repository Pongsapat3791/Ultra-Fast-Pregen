package com.example.pregen.terrain;

import net.minecraft.util.KeyDispatchDataCodec;
import net.minecraft.world.level.levelgen.SurfaceRules;

/**
 * A rule built for one chunk, as a source for Minecraft's test to wrap (see SurfaceRulePruning). Its own file because
 * codec()'s return type is KeyDispatchDataCodec up to 26.1.2 and MapCodec from 26.2: each build has the one of the Minecraft it compiles against.
 * Inside a SurfaceRules, whose rule types are open to its subclasses only.
 */
final class BuiltRules extends SurfaceRules {
    private BuiltRules() {}

    record Built(SurfaceRule rule) implements RuleSource {
        @Override
        public SurfaceRule apply(Context context) {
            return rule;
        }

        /** Never called: on the other versions this method is no override, and such a call would fail with an AbstractMethodError instead. */
        @Override
        public KeyDispatchDataCodec<? extends RuleSource> codec() {
            throw new UnsupportedOperationException("A rule built for one chunk is never saved");
        }
    }
}
