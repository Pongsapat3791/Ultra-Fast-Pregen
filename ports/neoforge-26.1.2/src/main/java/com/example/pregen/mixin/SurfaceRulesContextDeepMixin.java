package com.example.pregen.mixin;

import com.example.pregen.terrain.DeepSurface;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.levelgen.SurfaceSystem;
import net.minecraft.world.level.levelgen.WorldGenerationContext;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * What the surface shortcut below the ground reads of the rules' context, and where it writes a
 * column's known runs: as Minecraft's loop starts the column (see DeepSurface). The same on
 * Minecraft 26.1 to 26.2. Priority as SurfaceRulesContext261Mixin's, for the same reason.
 */
@Mixin(targets = "net.minecraft.world.level.levelgen.SurfaceRules$Context", priority = 1500)
public abstract class SurfaceRulesContextDeepMixin implements DeepSurface.RulesContext {
    @Shadow @Final private SurfaceSystem system;
    @Shadow @Final private ChunkAccess chunk;
    @Shadow @Final private WorldGenerationContext context;
    @Shadow private int blockY;
    @Shadow private long lastUpdateY;
    @Unique private DeepSurface.Columns pregen$deepColumns;

    @Shadow
    protected abstract int getMinSurfaceLevel();

    @Override
    public int pregen$minSurfaceLevel() {
        return getMinSurfaceLevel();
    }

    @Override
    public int pregen$blockY() {
        return blockY;
    }

    @Override
    public void pregen$atHeight(int blockY) {
        // What updateY does for a condition that reads only the height: conditions per height compute again.
        this.lastUpdateY++;
        this.blockY = blockY;
    }

    @Override
    public ChunkAccess pregen$chunk() {
        return chunk;
    }

    @Override
    public WorldGenerationContext pregen$generationContext() {
        return context;
    }

    @Override
    public SurfaceSystem pregen$system() {
        return system;
    }

    @Override
    public void pregen$setDeepColumns(DeepSurface.Columns columns) {
        pregen$deepColumns = columns;
    }

    // Only a shortcut: where this cannot be placed, the rules below the ground still answer block by block.
    @Inject(method = "updateXZ", require = 0, at = @At("TAIL"))
    private void pregen$writeKnownRuns(int blockX, int blockZ, CallbackInfo ci) {
        DeepSurface.Columns columns = pregen$deepColumns;
        if (columns != null) columns.column(blockX, blockZ);
    }
}
