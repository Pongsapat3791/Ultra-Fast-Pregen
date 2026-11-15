package me.cortex.voxy.commonImpl;

import com.example.pregen.LodStubs;

/** Test stand-in: Voxy has an instance only while the stubs say it runs (a client with Voxy on). */
public class VoxyCommon {
    private static final VoxyInstance INSTANCE = new VoxyInstance();

    public static VoxyInstance getInstance() {
        return LodStubs.voxyRunning ? INSTANCE : null;
    }
}
