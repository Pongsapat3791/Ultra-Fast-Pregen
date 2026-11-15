package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.CellBeards;
import com.example.pregen.terrain.TerrainGenerationContext;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.structure.BoundingBox;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * On this mod's terrain workers, a noise cell's structure terms from only the pieces and junctions
 * that reach the cell (see CellBeards).
 */
@Mixin(Beardifier.class)
public abstract class BeardifierMixin {
    @Shadow @Final protected List<Beardifier.Rigid> pieces;
    @Shadow @Final protected List<JigsawJunction> junctions;
    @Shadow @Final private BoundingBox affectedBox;
    @Unique private CellBeards pregen$cells;

    // Only a shortcut: a mod that replaces fillArray leaves every block asking every piece.
    @Inject(method = "fillArray", require = 0, at = @At("HEAD"), cancellable = true)
    private void pregen$fillFromNearPieces(double[] output, DensityFunction.ContextProvider provider, CallbackInfo ci) {
        // The NoiseChunk itself is the provider only when it fills a cell's cache (NoiseChunk#selectCellYZ).
        if (affectedBox == null || !CellBeards.enabled || !(provider instanceof NoiseChunk chunk)
                || !TerrainGenerationContext.isActive() || !WorldgenCompat.fastNoiseFillAllowed()) {
            return;
        }
        CellBeards cells = pregen$cells;
        if (cells == null) {
            cells = new CellBeards((Beardifier) (Object) this, pieces, junctions, affectedBox);
            pregen$cells = cells;
        }
        NoiseChunkAccessor access = (NoiseChunkAccessor) chunk;
        Beardifier near = cells.forCell(access.pregen$getCellStartBlockX(), access.pregen$getCellStartBlockY(),
                access.pregen$getCellStartBlockZ(), access.pregen$getCellWidth(), access.pregen$getCellHeight());
        if (near == null) {
            Arrays.fill(output, 0.0);
            ci.cancel();
        } else if ((Object) near != this) {
            provider.fillAllDirectly(output, near);
            ci.cancel();
        }
    }
}
