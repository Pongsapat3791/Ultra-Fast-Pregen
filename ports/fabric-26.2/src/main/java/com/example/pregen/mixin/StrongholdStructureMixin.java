package com.example.pregen.mixin;

import com.example.pregen.terrain.WorldgenLocks;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.StrongholdStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * StrongholdPieces builds a layout through static fields (current piece weights, the imposed
 * next piece). Two strongholds laid out at once, possible with large batches or a /locate on
 * the server thread during a run, would corrupt each other; one layout at a time.
 */
@Mixin(StrongholdStructure.class)
public abstract class StrongholdStructureMixin {
    @Inject(method = "generatePieces", at = @At("HEAD"))
    private static void pregen$lockLayout(StructurePiecesBuilder builder, Structure.GenerationContext context,
                                          CallbackInfo ci) {
        WorldgenLocks.acquire(WorldgenLocks.STRONGHOLD_LAYOUT, "another stronghold layout");
    }

    @Inject(method = "generatePieces", at = @At("RETURN"))
    private static void pregen$unlockLayout(StructurePiecesBuilder builder, Structure.GenerationContext context,
                                            CallbackInfo ci) {
        WorldgenLocks.release(WorldgenLocks.STRONGHOLD_LAYOUT);
    }
}
