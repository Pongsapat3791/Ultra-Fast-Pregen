package com.seibel.distanthorizons.api;

import com.seibel.distanthorizons.api.interfaces.config.IDhApiConfig;
import com.seibel.distanthorizons.api.interfaces.data.IDhApiTerrainDataRepo;
import com.seibel.distanthorizons.api.interfaces.world.IDhApiWorldProxy;

/** Test stand-in for DH's API entry points; the fields are set by com.example.pregen.LodStubs. */
public class DhApi {
    public static class Delayed {
        public static IDhApiConfig configs;
        public static IDhApiTerrainDataRepo terrainRepo;
        public static IDhApiWorldProxy worldProxy;
    }
}
