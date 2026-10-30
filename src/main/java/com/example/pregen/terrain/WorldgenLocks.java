package com.example.pregen.terrain;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Locks around Minecraft generation code that assumes one chunk at a time (as Minecraft itself
 * generates structures and features) but that this mod runs on several threads. A waiter gives
 * up after a while and runs unlocked, so an exception thrown while the lock is held can slow
 * later calls but never hangs the run.
 */
public final class WorldgenLocks {
    private static final Logger LOGGER = LoggerFactory.getLogger("PreGenEngine");
    private static final long WAIT_SECONDS = 30;

    /** StrongholdPieces keeps the layout being built in static fields. */
    public static final ReentrantLock STRONGHOLD_LAYOUT = new ReentrantLock();

    /** EndCityPieces keeps its section generators, and whether the city has its ship, in static fields. */
    public static final ReentrantLock END_CITY_LAYOUT = new ReentrantLock();

    /** NetherFortressPieces counts the rooms and bridges a layout placed in piece weights kept in static fields. */
    public static final ReentrantLock NETHER_FORTRESS_LAYOUT = new ReentrantLock();

    private WorldgenLocks() {}

    public static void acquire(ReentrantLock lock, String what) {
        try {
            if (!lock.tryLock(WAIT_SECONDS, TimeUnit.SECONDS)) {
                LOGGER.warn("[PreGen] Waited {}s for {}; continuing without the lock", WAIT_SECONDS, what);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    public static void release(ReentrantLock lock) {
        if (lock.isHeldByCurrentThread()) lock.unlock();
    }
}
