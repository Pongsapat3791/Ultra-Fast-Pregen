package com.seibel.distanthorizons.core.api.internal;

import com.seibel.distanthorizons.core.api.internal.chunkUpdating.WorldChunkUpdateManager;

public class SharedApi {
    public static final WorldChunkUpdateManager WORLD_CHUNK_UPDATE_MANAGER = new WorldChunkUpdateManager();
}
