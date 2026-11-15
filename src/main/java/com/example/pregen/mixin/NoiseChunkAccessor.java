package com.example.pregen.mixin;

import java.util.List;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.DensityFunction;
import net.minecraft.world.level.levelgen.DensityFunctions;
import net.minecraft.world.level.levelgen.NoiseChunk;
import net.minecraft.world.level.levelgen.blending.Blender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;
import org.spongepowered.asm.mixin.gen.Invoker;

/** NoiseChunk internals read by the cell-skipping terrain fill. */
@Mixin(NoiseChunk.class)
public interface NoiseChunkAccessor {
    @Accessor("interpolators")
    List<NoiseChunk.NoiseInterpolator> pregen$getInterpolators();

    @Accessor("cellCaches")
    List<?> pregen$getCellCaches();

    @Accessor("blockStateRule")
    NoiseChunk.BlockStateFiller pregen$getBlockStateRule();

    @Accessor("beardifier")
    DensityFunctions.BeardifierOrMarker pregen$getBeardifier();

    @Accessor("blender")
    Blender pregen$getBlender();

    @Accessor("cellWidth")
    int pregen$getCellWidth();

    @Accessor("cellHeight")
    int pregen$getCellHeight();

    @Accessor("cellCountXZ")
    int pregen$getCellCountXZ();

    /** The corner of the cell being filled (see CellBeards). */
    @Accessor("cellStartBlockX")
    int pregen$getCellStartBlockX();

    @Accessor("cellStartBlockY")
    int pregen$getCellStartBlockY();

    @Accessor("cellStartBlockZ")
    int pregen$getCellStartBlockZ();

    @Accessor("interpolationCounter")
    long pregen$getInterpolationCounter();

    @Accessor("interpolationCounter")
    void pregen$setInterpolationCounter(long value);

    @Accessor("arrayInterpolationCounter")
    long pregen$getArrayInterpolationCounter();

    @Accessor("arrayInterpolationCounter")
    void pregen$setArrayInterpolationCounter(long value);

    @Invoker("wrap")
    DensityFunction pregen$wrap(DensityFunction function);

    @Invoker("getInterpolatedState")
    BlockState pregen$getInterpolatedState();
}
