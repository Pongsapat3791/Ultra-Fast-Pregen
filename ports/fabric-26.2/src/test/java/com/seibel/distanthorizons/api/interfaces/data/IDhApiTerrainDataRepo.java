package com.seibel.distanthorizons.api.interfaces.data;

import com.seibel.distanthorizons.api.interfaces.world.IDhApiLevelWrapper;
import com.seibel.distanthorizons.api.objects.DhApiResult;

public interface IDhApiTerrainDataRepo {
    DhApiResult<Void> overwriteChunkDataAsync(IDhApiLevelWrapper level, Object[] chunkObjectArray);
}
