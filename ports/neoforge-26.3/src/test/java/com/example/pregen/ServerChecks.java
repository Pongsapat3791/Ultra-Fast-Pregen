package com.example.pregen;

import io.github.pongsapat3791.ultrafastpregen.api.PregenListener;

/** Starts the checks that need a running server through the mod's own events, so the checks need no Fabric API. */
public final class ServerChecks implements PregenListener {
    public static void register() {
        PregenApiImpl.INSTANCE.addListener(new ServerChecks());
    }

    @Override
    public void onServerStarted() {
        PartialExistingBatchCheck.onServerStarted(PregenRuns.server);
        SingleplayerCheck.onServerStarted(PregenRuns.server);
    }

    @Override
    public void onServerStopping() {
        PartialExistingBatchCheck.onServerStopped(PregenRuns.server);
    }
}
