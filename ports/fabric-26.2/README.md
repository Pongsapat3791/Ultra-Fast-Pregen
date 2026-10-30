# Ultra Fast Pregen for Fabric, Minecraft 26.1 to 26.2

The port of Ultra Fast Pregen to Minecraft 26.1 to 26.2 (Fabric, built against 26.2), with the commands (including `/pregen set` and the boss bar),
the config file, the save file and every change up to round 15.1: see the [main README](../../README.md).

- **Requires:** Minecraft 26.1, 26.1.1, 26.1.2 or 26.2, Fabric Loader 0.16 or newer and Java 25 (Fabric API is not needed)
- **File:** `build/libs/ultrafastpregen-fabric-26.1-26.2-15.1.jar`; `15.1` is the update round
- **Install:** on the server only; players do not need it

## Differences from the 1.20.1 builds

The code started from the NeoForge 1.21.1 build (the `ChunkStep` chunk generation of Minecraft 1.21 and newer) and follows 26.x's API
(Minecraft 26.x is not obfuscated, so Loom runs without remapping).

- **26.1 to 26.2 in one jar:** where 26.2 changed what the fast terrain path hooks, the jar carries the mixins of 26.1.x and of 26.2 and
  `mixin.PregenMixinPlugin` applies the ones of the running version, as in the NeoForge build
  ([ports/neoforge-26.1.2](../neoforge-26.1.2/README.md#differences-from-the-other-builds)).

- **The aquifer fast path is off:** Minecraft 26.x rewrote the aquifer (4 reference points instead of 3, and no sampling above a height of
  its own); the 1.20 fast path would not match the new algorithm, so Minecraft's aquifer is used. The cell-skipping fill works on cells that
  are all stone and (since round 14) on air cells above the height where 26.x's aquifer stops sampling (`skipSamplingAboveY`), because
  Minecraft answers there with the global fluid only. Air cells below the surface are still filled block by block.
- **Biomes for surface rules:** 26.x already computes biomes lazily, so the earlier fast path for them is not needed.
- **Minecraft's own steps:** 26.x's steps get the neighboring chunks within 1 chunk, as the server gives them; chunks further out are read
  from the grid through `WorldGenRegionMixin` (as in the 1.21.1 builds).
- **Fabric API:** Fabric API's mixins into `ChunkStatusTasks` only touch the FULL step (chunk events), so they do not turn the terrain fast path off.

## With C2ME, Voxy and Distant Horizons

- **C2ME (tested with 0.4.2-alpha.0.56 and 0.4.1-beta.1.0):** C2ME replaces Minecraft's chunk file IO (`IOWorker`) with a storage thread
  of its own. Ultra Fast Pregen writes through that thread, reads chunks C2ME has not written to the file yet, and replaces queued chunks
  when it generates new ones over them. There is no need to set `replaceImpl = false`: the blocks are identical to a run with C2ME's file IO
  off (2k area, 23,905 chunks, 0 different blocks). A C2ME version that renames those internal fields makes the run stop with a message
  that says to set `ioSystem.replaceImpl = false` in `config/c2me.toml`.
- **Voxy:** saved chunks go straight into Voxy's LoD with the same function Voxy World Gen V2 uses, with the light the server computed.
  Voxy runs only on the client, so this works in singleplayer; on a dedicated server nothing happens.
- **Distant Horizons (tested with 3.3.3):** every saved chunk goes to DH through its `overwriteChunkDataAsync` API, and DH's own LoD
  generation is paused during the run (as DH does while Chunky runs). After a 2k run (15,625 chunks) DH's database holds every chunk,
  but DH builds its LoDs on the CPU at the same time, so the speed drops to 142.6 from about 265 chunks/s.
- **Waiting for their queues:** when Voxy's or DH's queue is long, the next batch waits for it to shrink, so that DH does not drop chunks
  (the log shows the wait per batch). If a queue does not move for 1 minute, or the mod refuses chunks, chunks stop going to that mod
  for the rest of the run and pregeneration carries on.
- The start-up log names the mods found, and the end of the run sums up the chunks sent: `Saved chunks sent to LoD mods: ...`
- Chunks the server holds are not sent: Voxy and DH already receive loaded chunks from the server.

## Correctness (Minecraft 26.2, Fabric Loader 0.19.5)

- **Quick tests (`runTerrainBenchmark`):** every check passes; the fast path gives Minecraft 26.2's terrain block for block, in the
  overworld and the other presets (large biomes, amplified, nether, end, caves, floating islands).
- **Full suite on a real server (`runPartialBatchCheck`):** every check passes:
  - the fast path gives the same results as Minecraft 26.2's own steps, around a village and after features;
  - batches give the blocks of one run in the same order, block for block, and stopping and resuming differs from an uninterrupted run by 0 blocks;
  - joining an existing area: 0 different edge blocks (17,618 with the fix off);
  - the light of every block matches Minecraft's light engine, and chunks the server held and that were then overwritten are not dark
    (26.x has no spawn chunks any more, so the test holds chunks with a forced ticket);
  - the Nether and the End generate completely, and the safety net generates a failed batch again with 0 different blocks.
- **The released jar on a Fabric 26.2 server (loader 0.19.5, Java 25, without Fabric API):** it loads, `/pregen set` works and the full
  fast terrain path is used. A 1k area (4,096 chunks) took 22 s (185.4 chunks/s on the 12-thread development machine), with no dark chunks:
  no sky light underground, and block light from geodes.
- **With other mods and modpacks:** see [COMPATIBILITY.md](../../COMPATIBILITY.md).

## Building and testing (from the main project folder)

```powershell
.\gradlew.bat -p ports/fabric-26.2 build
.\gradlew.bat -p ports/fabric-26.2 runTerrainBenchmark
.\gradlew.bat -p ports/fabric-26.2 runPartialBatchCheck -PcheckHeap=8G
```

Gradle downloads JDK 25 by itself the first time. `runPartialBatchCheck` uses the test world in `build/partial-batch-check`
(it needs `eula.txt` and `server.properties`; set a new `level-name` before every run).
