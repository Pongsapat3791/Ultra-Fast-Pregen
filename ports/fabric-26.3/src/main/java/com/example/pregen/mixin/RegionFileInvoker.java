package com.example.pregen.mixin;

import java.io.IOException;
import java.nio.ByteBuffer;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.chunk.storage.RegionFile;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

@Mixin(RegionFile.class)
public interface RegionFileInvoker {

    /** Stores an already framed [length][compression id][payload] buffer; synchronized in vanilla. */
    @Invoker("write")
    void pregen$write(ChunkPos pos, ByteBuffer framed) throws IOException;
}
