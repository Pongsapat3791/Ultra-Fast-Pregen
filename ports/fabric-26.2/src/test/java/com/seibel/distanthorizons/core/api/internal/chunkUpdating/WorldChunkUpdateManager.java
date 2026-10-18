package com.seibel.distanthorizons.core.api.internal.chunkUpdating;

import com.example.pregen.LodStubs;

/** Test stand-in for DH's per-level update queues (not part of its API; read for back-pressure). */
public class WorldChunkUpdateManager {
    public int getTotalQueuedCount() {
        return LodStubs.dhQueue.getAsInt();
    }
}
