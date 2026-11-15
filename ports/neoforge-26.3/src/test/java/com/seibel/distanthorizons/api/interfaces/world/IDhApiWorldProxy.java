package com.seibel.distanthorizons.api.interfaces.world;

public interface IDhApiWorldProxy {
    boolean worldLoaded();

    Iterable<IDhApiLevelWrapper> getAllLoadedLevelWrappers();
}
