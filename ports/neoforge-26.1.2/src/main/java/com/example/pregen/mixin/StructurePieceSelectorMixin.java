package com.example.pregen.mixin;

import com.example.pregen.terrain.WorldgenLocks;
import net.minecraft.util.RandomSource;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.levelgen.structure.StructurePiece;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * A box of blocks picked by a block selector: the selector picks a block into a field of its own and the
 * piece reads it back. Strongholds and jungle temples share one selector among all their pieces, so two
 * pieces filled at once on two threads could read each other's block (a mossy or cracked brick swapped,
 * rarely). Here the pick and the read are one step per selector, and the piece gets the block its own pick made.
 */
@Mixin(StructurePiece.class)
public abstract class StructurePieceSelectorMixin {
    /**
     * The one generateBox that takes a selector, with its parameters: by its name alone the first
     * generateBox of the class is taken, which picks no block (so 16-dev16 and 16-dev17 guarded nothing).
     */
    private static final String BOX = "generateBox(Lnet/minecraft/world/level/WorldGenLevel;Lnet/minecraft/world/level/levelgen/structure/BoundingBox;IIIIIIZLnet/minecraft/util/RandomSource;Lnet/minecraft/world/level/levelgen/structure/StructurePiece$BlockSelector;)V";

    // Only a guard: without it boxes are filled as Minecraft fills them.
    @Redirect(method = BOX, require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/StructurePiece$BlockSelector;next(Lnet/minecraft/util/RandomSource;IIIZ)V"))
    private void pregen$pickAlone(StructurePiece.BlockSelector selector, RandomSource random, int x, int y, int z, boolean edge) {
        synchronized (selector) {
            selector.next(random, x, y, z, edge);
            WorldgenLocks.PICKED.set(selector.getNext());
        }
    }

    @Redirect(method = BOX, require = 0, at = @At(value = "INVOKE",
            target = "Lnet/minecraft/world/level/levelgen/structure/StructurePiece$BlockSelector;getNext()Lnet/minecraft/world/level/block/state/BlockState;"))
    private BlockState pregen$ownPick(StructurePiece.BlockSelector selector) {
        BlockState picked = WorldgenLocks.PICKED.get();
        if (picked == null) return selector.getNext();
        WorldgenLocks.PICKED.remove();
        return picked;
    }
}
