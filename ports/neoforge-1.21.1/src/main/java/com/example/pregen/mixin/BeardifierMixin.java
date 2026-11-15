package com.example.pregen.mixin;

import com.example.pregen.WorldgenCompat;
import com.example.pregen.terrain.CellBeards;
import com.example.pregen.terrain.TerrainGenerationContext;
import it.unimi.dsi.fastutil.objects.ObjectListIterator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.Unique;

/**
 * On this mod's terrain workers, a noise cell's structure terms from only the pieces and junctions
 * that reach the cell (see CellBeards). Minecraft's Beardifier fills an array through the
 * interface's method, every block asking every piece; this is that method for this class.
 */
@Mixin(Beardifier.class)
public abstract class BeardifierMixin implements DensityFunctions.BeardifierOrMarker {
    @Shadow @Final private ObjectListIterator<Beardifier.Rigid> pieceIterator;
    @Shadow @Final private ObjectListIterator<JigsawJunction> junctionIterator;
    @Unique private CellBeards pregen$cells;

    @Override
    public void fillArray(double[] output, DensityFunction.ContextProvider provider) {
        // The NoiseChunk itself is the provider only when it fills a cell's cache (NoiseChunk#selectCellYZ).
        if (CellBeards.enabled && provider instanceof NoiseChunk chunk && TerrainGenerationContext.isActive()
                && WorldgenCompat.fastNoiseFillAllowed()) {
            CellBeards cells = pregen$cells;
            if (cells == null) {
                cells = new CellBeards((Beardifier) (Object) this, pregen$all(pieceIterator), pregen$all(junctionIterator));
                pregen$cells = cells;
            }
            NoiseChunkAccessor access = (NoiseChunkAccessor) chunk;
            Beardifier near = cells.forCell(access.pregen$getCellStartBlockX(), access.pregen$getCellStartBlockY(),
                    access.pregen$getCellStartBlockZ(), access.pregen$getCellWidth(), access.pregen$getCellHeight());
            if (near == null) {
                Arrays.fill(output, 0.0);
                return;
            }
            if ((Object) near != this) {
                provider.fillAllDirectly(output, near);
                return;
            }
        }
        provider.fillAllDirectly(output, this);
    }

    /** What the iterator goes over, in its order; it is left at its start, where Minecraft's compute leaves it. */
    @Unique
    private static <T> List<T> pregen$all(ObjectListIterator<T> iterator) {
        iterator.back(Integer.MAX_VALUE);
        List<T> all = new ArrayList<>();
        while (iterator.hasNext()) all.add(iterator.next());
        iterator.back(Integer.MAX_VALUE);
        return all;
    }
}
