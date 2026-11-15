package com.example.pregen.mixin;

import com.example.pregen.terrain.WorldgenLocks;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.Rotation;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.EndCityStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * EndCityPieces lays a city out through section generators kept in static fields, one of which
 * remembers whether the city has its ship yet. Two cities laid out at once on different workers
 * changed each other's layout (a ship missing or added, and the rest of the city built from a
 * different random sequence); one layout at a time, as Minecraft lays them out.
 */
@Mixin(EndCityStructure.class)
public abstract class EndCityStructureMixin {
    @Inject(method = "generatePieces", at = @At("HEAD"))
    private void pregen$lockLayout(StructurePiecesBuilder builder, BlockPos start, Rotation rotation,
                                   Structure.GenerationContext context, CallbackInfo ci) {
        WorldgenLocks.acquire(WorldgenLocks.END_CITY_LAYOUT, "another End City layout");
    }

    @Inject(method = "generatePieces", at = @At("RETURN"))
    private void pregen$unlockLayout(StructurePiecesBuilder builder, BlockPos start, Rotation rotation,
                                     Structure.GenerationContext context, CallbackInfo ci) {
        WorldgenLocks.release(WorldgenLocks.END_CITY_LAYOUT);
    }
}
