package me.cortex.voxy.commonImpl;

import com.example.pregen.LodStubs;
import me.cortex.voxy.common.world.service.VoxelIngestService;

/** Test stand-in for Voxy's instance. */
public class VoxyInstance {
    public boolean isIngestEnabled(WorldIdentifier world) {
        return LodStubs.voxyIngestEnabled;
    }

    public VoxelIngestService getIngestService() {
        return new VoxelIngestService();
    }
}
