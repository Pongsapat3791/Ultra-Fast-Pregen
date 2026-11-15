package me.cortex.voxy.common.world.service;

import com.example.pregen.LodStubs;
import me.cortex.voxy.commonImpl.WorldIdentifier;
import net.minecraft.world.level.chunk.DataLayer;
import net.minecraft.world.level.chunk.LevelChunkSection;

/** Test stand-in for Voxy's ingest queue: records every section it is given. */
public class VoxelIngestService {
    public int getTaskCount() {
        return LodStubs.voxyQueue.getAsInt();
    }

    public static boolean rawIngest(WorldIdentifier world, LevelChunkSection section, int x, int y, int z,
                                    DataLayer blockLight, DataLayer skyLight) {
        LodStubs.voxySections.add(new LodStubs.Section(world.level, x, y, z, blockLight, skyLight));
        return true;
    }
}
