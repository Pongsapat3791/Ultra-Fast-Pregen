# Ultra Fast Pregen for NeoForge 1.21.1

The NeoForge 1.21.1 port of Ultra Fast Pregen: the same in-RAM batch generator, with the commands (including `/pregen set` and the boss bar),
the config file, the save file and every change of round 14. See the [main README](../../README.md) for the details.

- **Requires:** NeoForge 21.1.1 or newer (tested with 21.1.259) and Java 21
- **File:** `build/libs/ultrafastpregen-neoforge-1.21.1-14.1.jar`, into the server's `mods/` folder; `14.1` is the update round, the same for every build
- **Install:** on the server only; players do not need it, as the mod has no packets of its own
- **Commands:** `/pregen start|resume|reset|stop|status|world|center|set`, operator level 2
- **Config:** `config/ultrafastpregen.conf`, with the same keys as the other builds (`skip_existing_chunks=true` by default).
  Round 9's `inmemory-pregen.conf` and `fastpregen.dat` are taken over automatically.

## Speed

Development machine (12 threads), a 2000 × 2000 block area, batch 1000, 8 GB heap, with `-PbatchBenchmark=1000 -PbatchBenchmark.worldK=2`:

| | Time | Speed |
|---|---|---|
| Fabric 1.20.1, round 12 | 51.0 s | 311.3 chunks/s |
| NeoForge 1.21.1, round 12 | 51.0 s | 311.2 chunks/s |
| Fabric 1.20.1, round 9 | 54.9 s | 288.9 chunks/s |
| NeoForge 1.21.1, round 9 | 55.8 s | 284.4 chunks/s |

Round 12 is about 8–9% faster than round 9 on both loaders. Round 14's numbers are in the [main README](../../README.md#speed).

## Differences from the 1.20.1 builds

Minecraft 1.21 changed how chunks are generated (`ChunkStatus` → `ChunkStep` / `ChunkPyramid`), and the port follows it:

- **Minecraft's own steps:** every step without a fast path (structures, carvers, features, light, mob spawning, and `terrain_pipeline=vanilla`)
  calls Minecraft 1.21's own `ChunkStep` through chunk holders that point at the chunks in RAM. A holder reports the status its chunk
  has really reached, like the server's holders.
- **Biomes before surface:** 1.21 builds a chunk's noise and surface only after its 8 neighbors have biomes. Ultra Fast Pregen does the same
  by computing biomes for one more ring of chunks around the terrain area, so surface rules always read the neighbors' stored biomes, as in vanilla 1.21.
- **Steps the server has queued:** in 1.21 a step runs again even when the chunk has already reached it (1.20 skipped it). A chunk the server
  was still generating, and which Ultra Fast Pregen has replaced, could get its terrain or features generated twice; `ChunkStepMixin`
  prevents that. The light steps and the FULL step run as usual.
- **Lava in deep caves:** carvers placing lava below the lava level (y ≤ -56) read the "fluid needs an update" flag that the aquifer left from its
  previous call. The aquifer fast path set that flag differently from Minecraft at some positions, so some lava was scheduled to flow when the
  chunk was first loaded where vanilla's was not (the blocks themselves were identical). Fixed: after noise, the aquifer sets the flag the way
  Minecraft does (found by comparing the fast path with Minecraft 1.21's steps; fixed on Fabric since 1.20.1-10).
- **Each step's radius:** 1.21's steps get the neighboring chunks within 1 chunk, as the server gives them; chunks further out are read from
  the grid through `WorldGenRegionMixin` (the 1.20.1 builds give the range of `ChunkStatus#getRange` for Lithium, which 1.21 no longer has).
- **Command registration:** NeoForge's `RegisterCommandsEvent` instead of a mixin (`neoforge.NeoForgeEntrypoint`)
- **Config folder:** `FMLPaths.CONFIGDIR`

## NeoForge

- **Works as usual:** biome modifiers, structure modifiers and datapacks, as every step reads the world's registries and biomes directly.
- **Automatic mixin check:** works as on Fabric, checking `ChunkStatusTasks` instead of `ChunkStatus`. NeoForge changes worldgen classes
  with patches rather than mixins; the patch in this area is `Beardifier`'s `PieceBeardifierModifier`, which works with the fast path
  because it only adds pieces to the existing list.
- **Chunk events:** Ultra Fast Pregen writes the chunks to the region files itself, so some events do not fire for them:
  - **`ChunkDataEvent.Save`:** not fired, as for any chunk that was never loaded.
  - **`ChunkEvent.Load` when the server first loads the chunk:** fired, but `isNewChunk()` is `false`.
  - Mods that act only on new chunks through these events (retrogen, for example) do not see the chunks Ultra Fast Pregen generated.
- **Data attachments:** attachments a mod adds to a chunk during worldgen are saved with it, since NeoForge's `ChunkSerializer` is used.

## Correctness (round 14, NeoForge 21.1.259)

- **Terrain checksum:** `runTerrainBenchmark` compares vanilla and the fast path in the same JVM: the block, biome, heightmap and fluid
  checksums match for all 3 seeds, and vanilla's other presets (large biomes, amplified, nether, end, caves, floating islands) match
  Minecraft 1.21.1 block for block. The fast path is about 1.7× faster than vanilla at this stage
  (measured in round 9: 397.9 vs 234.2 chunks/s, 8 threads, 3 seeds × 256 chunks).
- **Light:** the sky and block light of every block match what Minecraft 1.21.1's light engine computes from the same blocks
  (1,572,864 blocks, 0 different). Chunks the server held and that were then overwritten are not dark
  (before round 13, 184 of 256 chunks were dark in the spawn-edge test).
- **On a real server (`runPartialBatchCheck`):** every check passes:
  - the same results around a village, with and without the cell-skipping fill;
  - the feature order gives the same blocks as the phased placement;
  - the fast path gives the same blocks, heightmaps and post-processing positions as Minecraft 1.21's own steps
    (`terrain_pipeline=vanilla`) after carvers and features;
  - the biomes stored in the chunks match the computed ones in every quart;
  - `skip_existing_chunks`, the save file and resuming, the spawn edge, and replacing chunks the server holds;
  - batch seams: about 598 different blocks per edge column with the seam fix against 6,299 without it, and batches give the blocks
    of one run in the same order (2 blocks differ);
  - stopping and resuming: 0–2 blocks differ from an uninterrupted run;
  - joining an existing area: 0 different edge blocks (17,470 with the fix off);
  - the Nether and the End generate completely;
  - overlapping batches (light and saving while the next batch generates) run in every test;
  - the safety net: a batch made to fail on the fast paths is generated again Minecraft's way with 0 different blocks.
- **With other mods and modpacks:** see [COMPATIBILITY.md](../../COMPATIBILITY.md).

## Building and testing

From the repository's main folder (it uses the same Gradle wrapper):

```powershell
.\gradlew.bat -p ports/neoforge-1.21.1 build
```

- **`runTerrainBenchmark`:** every unit check and the vanilla / fast path benchmark of stage 2:
  ```powershell
  .\gradlew.bat -p ports/neoforge-1.21.1 runTerrainBenchmark '-Pbenchmark.compare=true' '-Pbenchmark.threads=8'
  ```
- **`runPartialBatchCheck`:** the checks on a real server in `ports/neoforge-1.21.1/build/partial-batch-check`
  (it needs `eula.txt` and `server.properties` there; set a new `level-name` before every run).
  `-PpipelineBenchmark=true`, `-PbatchBenchmark=...` and `-PcheckHeap=8G` work as on Fabric.
- **`runServer`:** a development server in `ports/neoforge-1.21.1/run`.

During development the test code (`src/test`) is part of the same mod as the main code, because NeoForge loads each mod as a Java module
and the tests use package-level members of the main code. The built jar contains only the main code.
