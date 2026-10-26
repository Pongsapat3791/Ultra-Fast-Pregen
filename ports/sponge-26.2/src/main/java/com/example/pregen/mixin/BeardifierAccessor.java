package com.example.pregen.mixin;

import net.minecraft.world.level.levelgen.Beardifier;
import net.minecraft.world.level.levelgen.structure.pools.JigsawJunction;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

@Mixin(Beardifier.class)
public interface BeardifierAccessor {
    @Accessor("pieces")
    java.util.List<Beardifier.Rigid> pregen$getPieces();

    @Accessor("junctions")
    java.util.List<JigsawJunction> pregen$getJunctions();
}
