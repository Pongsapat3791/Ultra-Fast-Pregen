package com.example.pregen.mixin;

import com.example.pregen.terrain.WorldgenLocks;
import net.minecraft.world.level.levelgen.structure.Structure;
import net.minecraft.world.level.levelgen.structure.pieces.StructurePiecesBuilder;
import net.minecraft.world.level.levelgen.structure.structures.NetherFortressStructure;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/**
 * NetherFortressPieces counts how often a layout placed each kind of room and bridge in piece
 * weights kept in static fields, which every fortress resets and fills. Two fortresses laid out at
 * once on different workers changed each other's layout; one layout at a time, as Minecraft lays
 * them out.
 */
@Mixin(NetherFortressStructure.class)
public abstract class NetherFortressStructureMixin {
    @Inject(method = "generatePieces", at = @At("HEAD"))
    private static void pregen$lockLayout(StructurePiecesBuilder builder, Structure.GenerationContext context,
                                          CallbackInfo ci) {
        WorldgenLocks.acquire(WorldgenLocks.NETHER_FORTRESS_LAYOUT, "another Nether fortress layout");
    }

    @Inject(method = "generatePieces", at = @At("RETURN"))
    private static void pregen$unlockLayout(StructurePiecesBuilder builder, Structure.GenerationContext context,
                                            CallbackInfo ci) {
        WorldgenLocks.release(WorldgenLocks.NETHER_FORTRESS_LAYOUT);
    }
}
