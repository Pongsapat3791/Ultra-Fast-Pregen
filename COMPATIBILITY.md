# Compatibility and test results

Ultra Fast Pregen 15, tested with the released jars, whose SHA-1 is at the end of this page: the automated test suite,
the runs with other mods, the modpacks, and the Bukkit and Sponge builds; and 15.1: its six mod jars on every Minecraft
version they run on, alone and with C2ME, and its two plugin jars.

- [Automated test suite](#automated-test-suite): every build, block for block against Minecraft's own generation
- [Together with other mods](#together-with-other-mods): performance, LoD and worldgen mods on dedicated servers
- [Modpacks](#modpacks): All the Mods 10, All the Mods 9 and Prominence II, against 14.1
- [Bukkit servers and Sponge](#bukkit-servers-and-sponge): the 26.2 plugin builds
- [Version 15.1](#version-151): the six mod jars on every Minecraft version they run on,
  [End cities and fortresses](#end-cities-and-nether-fortresses),
  [players who see a replaced chunk](#players-who-see-a-replaced-chunk),
  [chunks the server holds](#chunks-the-server-holds-during-a-run), [with C2ME](#with-c2me), and
  [the plugins](#bukkit-and-sponge-plugins)
- [Known limits](#known-limits)
- [Tested mod files](#tested-mod-files)
- [Tested jars](#tested-jars)

## Automated test suite

`runTerrainBenchmark` and `runPartialBatchCheck` (see [Building and testing](README.md#building-and-testing)) run on a real
server of each build. **Every check passes on all six builds** (Fabric 1.20.1, Forge 1.20.1, Fabric 1.21.1, NeoForge 1.21.1,
NeoForge 26.1.2, Fabric 26.2) with the jars at the end of this page.

| Check | What it shows |
|---|---|
| `FAST_FILL_PARITY_CHECK` | The fast terrain path, cell skipping included, gives Minecraft's terrain block for block around a village (76 structure chunks). |
| `FEATURE_ORDER_PARITY_CHECK`, `FEATURE_BIOMES_PARITY_CHECK`, `VANILLA_PIPELINE_PARITY_CHECK` | Structures, carvers and features, run on all cores in phases (with each biome cell looked up once per chunk), give the same blocks as Minecraft's own steps one chunk at a time. |
| `PRESET_PARITY_CHECK` | The same for the large biomes, amplified, nether, end, caves and floating islands presets (2 seeds each). |
| `STORED_BIOME_CHECK` | The biomes saved in the chunks are Minecraft's in every quart (98,304 quarts). |
| `LIGHT_PARITY_CHECK` | The sky and block light of 1,572,864 blocks is identical to what Minecraft's light engine computes. |
| `OTHER_DIMENSIONS_CHECK` | The Nether and the End generate completely (4,096 chunks each). |
| `BATCH_SEAM_CHECK` | Features that cross a batch edge are carried into the next batch: a run in batches gives the blocks of one run in the same order (0–2 blocks differ). |
| `RESUME_EDGE_CHECK` | Stopping and resuming gives the blocks of an uninterrupted run (0 blocks differ). |
| `EXISTING_NEIGHBORS_CHECK` | Joining an area generated earlier: 0 different blocks along the edge (about 17,500 without the fix). |
| `SKIP_EXISTING_CHECK`, `HELD_DURING_BATCH_CHECK` | Chunks on disk and chunks the server holds are kept, also when the server finishes one during a batch. |
| `HELD_RING_CHECK` | Chunks the server holds part-generated get the saved chunk's blocks, their mobs reach the world's entity storage, and features that cross a batch edge reach them. |
| `REGENERATE_CHECK` | With `skip_existing_chunks=false`, a new run over an area that the save file has generates all of it again, and a run that continues keeps what is saved. |
| `SPAWN_EDGE_REGENERATION_CHECK` | Chunks the server holds next to the area are handled without dark chunks, and the server stays responsive (worst latency 1–37 ms). |
| `SAVE_FILE_RESUME_CHECK` | `/pregen resume` continues from the save file. |
| `AUTO_RESUME_CHECK`, `SERVER_STOP_CHECK` | A run cut short by a server stop continues on the next start; one stopped with `/pregen stop`, or continued 3 times in a row without saving a batch, does not. Stopping the server keeps the batch being saved, and the run completes after the restart. |
| `CIRCLE_CHECK` | A circle generates exactly the chunks whose center lies within its radius (1,116), and its inner chunks have the blocks of the same chunks generated as part of the whole square (580 compared, 0 differ). |
| `API_CHECK` | A run started through the API with a radius of 300 blocks completes; its listener sees the start, each of the 1,444 saved chunks once and the end, and a second start while it runs is refused. |
| `SAFE_MODE_RETRY_CHECK` | A batch made to fail on the fast paths is generated again the way Minecraft does: 0 different blocks, and the server holds no chunks around the area afterwards. |
| `TAIL_FAILURE_RETRY_CHECK` | A batch whose lighting or saving fails is generated again from what is saved, beside the next batch and with batches one at a time: 0 different blocks either way, and the server holds no chunks around the area afterwards. |
| `CHUNK_SAVE_CHECK` | The region files are byte-identical to Minecraft's own encoding, and existing chunks are kept. |
| `C2ME_STORAGE_CHECK` | Saving through C2ME's storage thread (against stand-ins of C2ME's classes; the real C2ME is in the tables below). |
| `LOD_EXPORT_CHECK` (26.x) | Saved chunks are handed to Voxy's and Distant Horizons' APIs with their light, and DH's own generation is paused and restored (against stand-ins of their API classes). |
| `WORLDGEN_COMPAT_CHECK` | The check of other mods' mixins picks the right path. |
| `PHASE_SPACING_CHECK` | Chunks whose features run at the same time never write into each other. |
| `PREGEN_CONFIG_CHECK`, `PREGEN_BATCH_HEAP_CHECK`, `FLAT_CLIMATE_INDEX_CHECK`, `BLOCK_ENTITY_PROMOTION_CHECK` | The config file (defaults, older keys, missing keys added once), heap sizing of a batch, the climate index against Minecraft's lookup, and block entities placed during generation. |

## Together with other mods

Ultra Fast Pregen 15 (the jars at the end of this page), each build on its own dedicated server (Intel Xeon E5-2670 v2,
10 cores / 20 threads, 10 GB heap). For every set of mods: a fresh world (seed 42) and `/pregen start 1` (a
1,000 × 1,000 block square, 4,096 chunks) around x = z = 100,000 in the Overworld, then the Nether, then the End.
Afterwards every saved chunk was read back from the region files. Version 14 was tested the same way on a 16-thread
Intel i9-11900T, with the same results apart from the speeds.

- ✅ = the run completed, all 4,096 chunks are fully generated, lit and readable, bedrock is there (in the Overworld also
  grass and sky light, in the Nether block light), and the safety net never had to step in. The number is the speed in
  chunks per second: small areas on a 20-thread machine, so not comparable with the benchmark in the README.
- ⛔ = Ultra Fast Pregen refused to start, as intended (see [Known limits](#known-limits)).
- **Terrain path:** *fast* = Ultra Fast Pregen's fused terrain path. *Minecraft's per-status* = Minecraft's own terrain
  steps, because the named mod changes a step the fast path would skip; structures, caves, features, light and saving
  still run on every core.
- In every one of these runs, the 324 chunks in the middle of the area were also compared with the same chunks
  generated by Minecraft itself on the same server and mods: the biomes are the same in all of them.

**132 of 132 runs passed** (mod sets × dimensions over the six builds); Moonrise was refused as intended in all 12.

The **C2ME** rows were run again with the same jars during the tests of 15.1. The first runs of those rows asked the
test server for C2ME under a name it did not have, so they ran without C2ME and showed Ultra Fast Pregen alone; the
"Everything together" rows had C2ME. With C2ME, 15 lights each batch on the server's single light thread; 15.1 keeps
lighting on the worker threads and is faster ([with C2ME](#with-c2me)).

### Fabric 1.20.1

Fabric Loader 0.19.5, Java 21. Moonrise and Voxy have no 1.20.1 build.

| Mods installed with Ultra Fast Pregen | Overworld | Nether | End | Terrain path |
|---|---|---|---|---|
| **Ultra Fast Pregen alone** | ✅ 167.1 | ✅ 301.1 | ✅ 670.8 | fast |
| **C2ME** | ✅ 124.2 | ✅ 129.5 | ✅ 755.3 | fast |
| **Lithium** | ✅ 173.1 | ✅ 317.3 | ✅ 807.7 | fast |
| **Lithium + Noisium** | ✅ 108.4 | ✅ 240.9 | ✅ 703.5 | Minecraft's per-status (Noisium) |
| **Performance**: Lithium, Starlight, Noisium, FerriteCore, ModernFix | ✅ 119.8 | ✅ 225.7 | ✅ 740.3 | Minecraft's per-status (Noisium) |
| **LoD**: Distant Horizons, Fabric API | ✅ 163.4 | ✅ 303.1 | ✅ 660.6 | fast |
| **Worldgen**: Terralith, Biomes O' Plenty, YUNG's Better Caves | ✅ 65.3 | ✅ 213.2 | ✅ 490.5 | fast |
| **Everything together**: Lithium, Starlight, Noisium, FerriteCore, ModernFix, Distant Horizons, Terralith, Biomes O' Plenty, YUNG's Better Caves, C2ME | ✅ 41.7 | ✅ 138.8 | ✅ 472.3 | Minecraft's per-status (Noisium) |

### Forge 1.20.1

Forge 47.4.28, Java 21. Canary is Lithium's Forge port; C2ME, Moonrise and Voxy have no Forge 1.20.1 build.

| Mods installed with Ultra Fast Pregen | Overworld | Nether | End | Terrain path |
|---|---|---|---|---|
| **Ultra Fast Pregen alone** | ✅ 165.3 | ✅ 286.8 | ✅ 737.6 | fast |
| **Canary** | ✅ 168.1 | ✅ 295.7 | ✅ 803.8 | fast |
| **Performance**: Canary, Noisium, FerriteCore, ModernFix | ✅ 118.3 | ✅ 360.3 | ✅ 500.4 | Minecraft's per-status (Noisium) |
| **LoD**: Distant Horizons | ✅ 156.7 | ✅ 272.8 | ✅ 720.6 | fast |
| **Worldgen**: Terralith, Biomes O' Plenty, Alex's Caves | ✅ 61.7 | ✅ 167.0 | ✅ 602.4 | Minecraft's per-status (Alex's Caves) |
| **Everything together**: Canary, Noisium, FerriteCore, ModernFix, Distant Horizons, Terralith, Biomes O' Plenty, YUNG's Better Caves, Alex's Caves | ✅ 57.0 | ✅ 221.2 | ✅ 467.5 | Minecraft's per-status (Noisium, Alex's Caves) |

### Fabric 1.21.1

Fabric Loader 0.19.5, Java 21. Voxy has no Fabric 1.21.1 build.

| Mods installed with Ultra Fast Pregen | Overworld | Nether | End | Terrain path |
|---|---|---|---|---|
| **Ultra Fast Pregen alone** | ✅ 142.7 | ✅ 299.5 | ✅ 762.5 | fast |
| **C2ME** | ✅ 106.4 | ✅ 175.6 | ✅ 613.3 | fast |
| **Lithium** | ✅ 138.4 | ✅ 259.4 | ✅ 810.3 | fast |
| **Performance**: Lithium, ScalableLux, Noisium, FerriteCore, ModernFix | ✅ 115.5 | ✅ 297.6 | ✅ 776.6 | Minecraft's per-status (Noisium) |
| **LoD**: Distant Horizons, Fabric API | ✅ 141.7 | ✅ 290.6 | ✅ 694.8 | fast |
| **Worldgen**: Terralith, Biomes O' Plenty, YUNG's Better Caves | ✅ 59.9 | ✅ 207.1 | ✅ 290.2 | fast |
| **Everything together**: Lithium, ScalableLux, Noisium, FerriteCore, ModernFix, Distant Horizons, Terralith, Biomes O' Plenty, YUNG's Better Caves, C2ME | ✅ 79.9 | ✅ 255.2 | ✅ 300.2 | Minecraft's per-status (Noisium) |
| **Moonrise**: Moonrise, Lithium, Noisium, FerriteCore, ModernFix | ⛔ refused | ⛔ refused | ⛔ refused | – |

### NeoForge 1.21.1

NeoForge 21.1.259, Java 21. Voxy is Fabric only.

| Mods installed with Ultra Fast Pregen | Overworld | Nether | End | Terrain path |
|---|---|---|---|---|
| **Ultra Fast Pregen alone** | ✅ 134.0 | ✅ 290.8 | ✅ 782.4 | fast |
| **C2ME** | ✅ 105.7 | ✅ 168.1 | ✅ 589.8 | fast |
| **Lithium** | ✅ 138.3 | ✅ 244.6 | ✅ 849.4 | fast |
| **Lithium + Noisium** | ✅ 95.6 | ✅ 229.0 | ✅ 677.0 | Minecraft's per-status (Noisium) |
| **Performance**: Lithium, ScalableLux, Noisium, FerriteCore, ModernFix | ✅ 128.7 | ✅ 399.3 | ✅ 801.9 | Minecraft's per-status (Noisium) |
| **LoD**: Distant Horizons | ✅ 137.2 | ✅ 264.3 | ✅ 741.2 | fast |
| **Worldgen**: Terralith, Biomes O' Plenty, YUNG's Better Caves | ✅ 62.2 | ✅ 230.9 | ✅ 301.7 | fast |
| **Everything together**: Lithium, ScalableLux, Noisium, FerriteCore, ModernFix, Distant Horizons, Terralith, Biomes O' Plenty, YUNG's Better Caves, C2ME | ✅ 86.9 | ✅ 312.2 | ✅ 313.4 | Minecraft's per-status (Noisium) |
| **Moonrise**: Moonrise, Lithium, Noisium, FerriteCore, ModernFix | ⛔ refused | ⛔ refused | ⛔ refused | – |

### NeoForge 26.1.2

NeoForge 26.1.2.122, Java 25. Noisium has no 26.x build; Voxy is Fabric only.

| Mods installed with Ultra Fast Pregen | Overworld | Nether | End | Terrain path |
|---|---|---|---|---|
| **Ultra Fast Pregen alone** | ✅ 140.9 | ✅ 253.3 | ✅ 651.8 | fast |
| **C2ME** | ✅ 136.8 | ✅ 191.1 | ✅ 364.1 | fast |
| **Lithium** | ✅ 143.6 | ✅ 265.8 | ✅ 731.3 | fast |
| **Performance**: Lithium, ScalableLux, FerriteCore, ModernFix | ✅ 189.5 | ✅ 346.0 | ✅ 861.0 | fast |
| **LoD**: Distant Horizons | ✅ 91.3 | ✅ 131.5 | ✅ 313.7 | fast |
| **Worldgen**: Terralith, Biomes O' Plenty, TerraBlender, GlitchCore, YUNG's Better Caves | ✅ 72.7 | ✅ 228.4 | ✅ 268.6 | fast |
| **Everything together**: Lithium, ScalableLux, FerriteCore, ModernFix, Distant Horizons, Terralith, Biomes O' Plenty, TerraBlender, GlitchCore, YUNG's Better Caves, C2ME | ✅ 66.5 | ✅ 150.3 | ✅ 230.1 | fast |
| **Moonrise**: Moonrise, Lithium, FerriteCore, ModernFix | ⛔ refused | ⛔ refused | ⛔ refused | – |

### Fabric 26.2

Fabric Loader 0.19.5, Java 25. Noisium, ModernFix and YUNG's Better Caves have no 26.2 build.

| Mods installed with Ultra Fast Pregen | Overworld | Nether | End | Terrain path |
|---|---|---|---|---|
| **Ultra Fast Pregen alone** | ✅ 139.3 | ✅ 248.8 | ✅ 534.2 | fast |
| **C2ME** | ✅ 127.9 | ✅ 186.4 | ✅ 316.2 | fast |
| **Lithium** | ✅ 147.3 | ✅ 238.6 | ✅ 577.2 | fast |
| **Performance**: Lithium, ScalableLux, FerriteCore | ✅ 191.0 | ✅ 341.6 | ✅ 736.6 | fast |
| **LoD**: Distant Horizons, Fabric API | ✅ 94.6 | ✅ 147.4 | ✅ 304.3 | fast |
| **Voxy** | ✅ 146.9 | ✅ 248.3 | ✅ 584.7 | fast |
| **Worldgen**: Terralith, Biomes O' Plenty, TerraBlender, GlitchCore | ✅ 102.1 | ✅ 237.8 | ✅ 266.5 | fast |
| **Everything together**: Lithium, ScalableLux, FerriteCore, Distant Horizons, Terralith, Biomes O' Plenty, TerraBlender, GlitchCore, C2ME | ✅ 83.3 | ✅ 143.3 | ✅ 205.5 | fast |
| **Moonrise**: Moonrise, Lithium, FerriteCore, Fabric API | ⛔ refused | ⛔ refused | ⛔ refused | – |

## Modpacks

The modpacks' own servers on the same test server as above (Xeon E5-2670 v2, 20 threads), with the pack's Ultra Fast
Pregen jar replaced by the 15 jar, and for comparison by 14.1: `/pregen start 1` (4,096 chunks) at x = z = 100,000 in the
Overworld of a fresh world; every chunk of the area was read back. All the Mods 10 ran with its own JVM settings (10 GB
heap), All the Mods 9 and Prominence II with an 8 GB heap. spark's background profiler was turned off in all three: with
it on, starting All the Mods 9 froze this test server twice, before Ultra Fast Pregen ran. The packs log errors of their
own while starting (configs, recipes, other mods' mixins); none of those lines comes from Ultra Fast Pregen.

| Modpack | Build | Mods | 15 | 14.1 | Terrain path |
|---|---|---|---|---|---|
| All the Mods 10 8.3 | NeoForge 1.21.1 (21.1.259) | 469 | ✅ 4,096 chunks in 1m 08s (60.0 chunks/s) | 59.6 chunks/s | fast |
| All the Mods 9 1.1.1 | Forge 1.20.1 (47.4.0) | 418 | ✅ 4,096 chunks in 1m 08s (60.1 chunks/s) | 51.5 chunks/s | fast |
| Prominence II 4.1.4 | Fabric 1.20.1 | 365 | ✅ 4,096 chunks in 1m 19s (51.5 chunks/s) | 52.6 chunks/s | fast |

In these packs other mods change most of the code that the new fast steps of 15 stand in for, so those steps turn
themselves off (see [Faster](CHANGELOG.md#faster)); in All the Mods 9 the surface rule shortcuts stay on, and 15 is 17%
faster there. One run each; a run's speed varies by up to about 10%.

## Bukkit servers and Sponge

The 26.2 plugin builds on the same test server as above (Intel Xeon E5-2670 v2, 20 threads, 10 GB heap): a fresh world
with seed 42 and a 1,000 × 1,000 block square around x = z = 100,000 in each dimension. The details, and the tests of
stopping the server, chunks the server holds, a failing batch and the API, are in the builds' READMEs:
[Bukkit](ports/bukkit-26.2/README.md#tests-minecraft-262) and [Sponge](ports/sponge-26.2/README.md#tests-minecraft-262).

| Server | Overworld | Nether | End | Compared with the Fabric 26.2 build |
|---|---|---|---|---|
| CraftBukkit 26.2 (4648) | ✅ 119.5 | ✅ 244.9 | ✅ 435.4 | the same block types, biomes and light in every chunk |
| Spigot 26.2 (4648) | ✅ 119.1 | ✅ 243.7 | ✅ 485.5 | the same blocks and light; 10 shipwreck chests are stored without water and get it back when the server loads them ([details](ports/bukkit-26.2/README.md#tests-minecraft-262)) |
| Paper 26.2 (build 134) | ✅ 140.8 | ✅ 333.1 | ✅ 748.1 | Paper's own changes to generation ([details](ports/bukkit-26.2/README.md#paper-and-folia-compared-with-spigot)) |
| Folia 26.2 (build 7) | ✅ 144.6 | ✅ 334.4 | ✅ 744.3 | the same as Paper |
| SpongeVanilla 26.2 (RC2754) | ✅ 142.8 | ✅ 253.3 | ✅ 553.4 | the same blocks and light |
| *Fabric 26.2 build, for comparison* | *137.5* | *229.8* | *551.1* | |

✅ and the numbers as in the tables above: every chunk fully generated, lit and readable; chunks per second. The
Bukkit plugin has no fast terrain path (it cannot change Minecraft's code), so it is slower than the mods in the
Overworld; the Sponge build is the Fabric build's code and has every fast path.

## Version 15.1

Version 15.1's six mod jars on a dedicated server of every Minecraft version they run on, on the same test server as
above (Intel Xeon E5-2670 v2, 20 threads, 10 GB heap; Java 21, and Java 25 for 26.x), each time a fresh world with
seed 42 and no other mod:

- **Fresh area:** 1,000 × 1,000 blocks around x = z = 100,000 in each dimension;
- **Around spawn:** 1,000 × 1,000 blocks around 0, 0 in batches of 512 × 512 blocks (`batch_size = 512`, 4 batches),
  over the spawn chunks saved before; then the same area again, every batch skipped from the save file; then a restart,
  which reads the save file at start, and `/pregen resume`;
- **Restart:** the same area and batches; the server was stopped once the first batch was saved, and the run continued
  by itself after the restart and completed.

| Jar | Minecraft | Server | Overworld | Nether | End | Around spawn | Restart |
|---|---|---|---|---|---|---|---|
| `ultrafastpregen-forge-1.20-1.20.4-15.1.jar` | 1.20 | Forge 46.0.14 | ✅ 166.6 | ✅ 281.5 | ✅ 725.6 | ✅ 128.9 | ✅ |
|  | 1.20.1 | Forge 47.4.28 | ✅ 164.9 | ✅ 284.8 | ✅ 770.4 | ✅ 123.4 | ✅ |
|  | 1.20.2 | Forge 48.1.0 | ✅ 166.4 | ✅ 280.2 | ✅ 726.2 | ✅ 127.6 | ✅ |
|  | 1.20.3 | Forge 49.0.2 | ✅ 165.6 | ✅ 291.4 | ✅ 764.3 | ✅ 126.2 | ✅ |
|  | 1.20.4 | Forge 49.2.13 | ✅ 165.7 | ✅ 286.5 | ✅ 764.5 | ✅ 127.7 | ✅ |
| `ultrafastpregen-fabric-1.20.x-15.1.jar` | 1.20 | Fabric Loader 0.19.5 | ✅ 167.9 | ✅ 296.6 | ✅ 783.5 | ✅ 127.1 | ✅ |
|  | 1.20.1 | Fabric Loader 0.19.5 | ✅ 164.3 | ✅ 303.6 | ✅ 758.0 | ✅ 137.1 | ✅ |
|  | 1.20.2 | Fabric Loader 0.19.5 | ✅ 167.3 | ✅ 292.9 | ✅ 732.5 | ✅ 131.5 | ✅ |
|  | 1.20.3 | Fabric Loader 0.19.5 | ✅ 168.8 | ✅ 298.0 | ✅ 778.6 | ✅ 129.2 | ✅ |
|  | 1.20.4 | Fabric Loader 0.19.5 | ✅ 163.8 | ✅ 303.3 | ✅ 731.8 | ✅ 131.9 | ✅ |
|  | 1.20.5 | Fabric Loader 0.19.5 | ✅ 149.8 | ✅ 291.3 | ✅ 738.2 | ✅ 123.7 | ✅ |
|  | 1.20.6 | Fabric Loader 0.19.5 | ✅ 157.4 | ✅ 296.3 | ✅ 730.1 | ✅ 124.7 | ✅ |
| `ultrafastpregen-fabric-1.21-1.21.1-15.1.jar` | 1.21 | Fabric Loader 0.19.5 | ✅ 144.1 | ✅ 294.1 | ✅ 795.0 | ✅ 120.8 | ✅ |
|  | 1.21.1 | Fabric Loader 0.19.5 | ✅ 139.2 | ✅ 291.9 | ✅ 730.3 | ✅ 119.9 | ✅ |
| `ultrafastpregen-neoforge-1.21-1.21.1-15.1.jar` | 1.21 | NeoForge 21.0.167 | ✅ 134.8 | ✅ 244.1 | ✅ 745.4 | ✅ 119.2 | ✅ |
|  | 1.21.1 | NeoForge 21.1.259 | ✅ 134.5 | ✅ 257.1 | ✅ 744.7 | ✅ 121.6 | ✅ |
| `ultrafastpregen-neoforge-26.1.2-26.2-15.1.jar` | 26.1.2 | NeoForge 26.1.2.122 | ✅ 146.1 | ✅ 251.3 | ✅ 608.5 | ✅ 131.3 | ✅ |
|  | 26.2 | NeoForge 26.2.0.93 | ✅ 144.7 | ✅ 257.2 | ✅ 613.5 | ✅ 129.7 | ✅ |
| `ultrafastpregen-fabric-26.1-26.2-15.1.jar` | 26.1 | Fabric Loader 0.19.5 | ✅ 147.4 | ✅ 255.9 | ✅ 617.4 | ✅ 128.1 | ✅ |
|  | 26.1.1 | Fabric Loader 0.19.5 | ✅ 144.9 | ✅ 261.9 | ✅ 667.1 | ✅ 128.3 | ✅ |
|  | 26.1.2 | Fabric Loader 0.19.5 | ✅ 148.8 | ✅ 263.0 | ✅ 552.8 | ✅ 129.9 | ✅ |
|  | 26.2 | Fabric Loader 0.19.5 | ✅ 144.3 | ✅ 244.0 | ✅ 601.7 | ✅ 130.0 | ✅ |

✅ and the numbers (chunks per second): every chunk fully generated, lit and readable, on the fast terrain path, and
no error in the log; around spawn, also no cave stored with daylight. NeoForge 26.1.2 and 26.2 log one error at every
start, also without Ultra Fast Pregen: their debug log fails on netty's kqueue class, which is for macOS and BSD. What
the 15 jars did on these versions is in the [changelog](CHANGELOG.md#151).

**The fast terrain path on each version:** the same fresh 1,000 × 1,000 blocks generated twice, once on the fast terrain
path and once with Minecraft's own biome, noise and surface steps (`terrain_pipeline=vanilla`; features and the rest run
the same way in both), compared block for block over all 4,096 chunks of each dimension:

| Server | Overworld: blocks that differ | Nether | End |
|---|---|---|---|
| Forge 1.20.2 | 26: 7 bee nests one block apart (14), 9 mud for dirt, 3 sand or stone | 0 | 0 |
| Forge 1.20.4 | 30: 9 bee nests (18), 9 mud for dirt, 3 sand or stone | 0 | 0 |
| Fabric 1.20.1 | 25: 6 bee nests (12), 9 mud for dirt, 4 sand, clay or stone | 0 | 0 |
| Fabric 1.20.2 | 26: 7 bee nests (14), 9 mud for dirt, 3 sand or stone | 0 | 0 |
| Fabric 1.20.4 | 26: 7 bee nests (14), 9 mud for dirt, 3 sand or stone | 0 | 0 |
| Fabric 1.20.5 | 8: 4 bee nests | 0 | 0 |
| Fabric 1.20.6 | 14: 7 bee nests | 0 | 0 |
| Fabric 1.21 | 12: 6 bee nests | 0 | 0 |
| NeoForge 1.21 | 10: 5 bee nests | 0 | 0 |
| NeoForge 26.1.2, 26.2 | 0 | 0 | 0 |
| Fabric 26.1, 26.1.1, 26.1.2, 26.2 | 0 | 0 | 0 |

The light differs only in chunks with a different block or next to one. Bee nests also land one block apart between
two runs of the same jar (Minecraft places them with an unseeded shuffle), so their place is not fixed from run to run.
The 12 or 13 mud, sand and stone blocks on 1.20.1 to 1.20.4 are the ones the 15 jar gives on 1.20.1 too.

In an earlier run of 15.1 on Fabric 26.2, 21 Nether blocks differed: soul sand and soul soil in one world and
netherrack in the other, at the bottom of chunk (6266, 6225). They come from Minecraft's biome lookup, not from the
terrain path, and this run of the final jar did not show them. Minecraft finds a
biome by searching its biome tree from the last result of the same thread, and keeps that result when another biome is
exactly as close. Where two biomes are exactly as close, the first biome cell a chunk fills therefore depends on what
that thread looked up before. That is the case at this chunk's corner: the Nether's climate is the same at every
height, yet in each of the nine worlds kept from these tests the chunk's lowest corner cell is Nether Wastes and the
cells above it are Soul Sand Valley, and no other column of the 65,536 in the area changes with height. On Fabric
1.20.6 the same 21 blocks differed the other way round, in a run of an earlier 15.1 build. Ultra Fast Pregen's biome
lookups search the same tree from the same starting point as Minecraft's, so a world generated by Minecraft itself can
come out either way too.

### End cities and Nether fortresses

Minecraft lays out each End city and Nether fortress with code that keeps its state in static fields, and creates one
structure start at a time; 15 created a batch's structure starts on all worker threads, where two layouts at the same
moment could change each other ([changelog](CHANGELOG.md#151)). In these tests it showed at the End city at chunk
(6224, 6222), in the fresh area above. Minecraft itself (no Ultra Fast Pregen, the chunk force-loaded) gives this city
88 pieces, its ship among them; with the jars before the fix it came out without the ship and with 78 pieces
(3,314 blocks in 20 chunks differ). A fresh world each time, with the Overworld, the Nether and then the End generated
in one server, 1,000 × 1,000 blocks each:

| Server | Before the fix | 15.1 |
|---|---|---|
| Fabric 1.21 | 78 pieces in 3 of 3 runs | 88 pieces in 7 of 7 runs |
| NeoForge 1.21.1 with C2ME | 78 pieces in 1 of 2 runs ([the pair below](#with-c2me)) | 88 pieces in 3 of 3 runs |
| Fabric 26.2 | 88 pieces in 3 of 3 runs | 88 pieces in 3 of 3 runs |
| Spigot 26.2, the Bukkit plugin | 88 pieces in 3 of 3 runs | 88 pieces in 3 of 3 runs |

With 15.1 the runs compared had the same End, block for block. Generating the End alone never showed the race, with
any build, and neither did 26.2 here, although it keeps the same static fields: the 1.21 runs are the ones that show
the fix at work. The mods' fix is the same code on every version; the plugin cannot change Minecraft's code and locks
the chunks where these structures can start instead. Fortresses keep their state the same way and are now laid out
one at a time too; no fortress came out differently in these tests.

### Players who see a replaced chunk

With `skip_existing_chunks=false`, a run replaces chunks the server holds and sends them again to the players who see
them; Forge 1.20.2 gave that call another name at run time ([changelog](CHANGELOG.md#151)). A small test client that
speaks Minecraft's protocol (not the game itself) joined each server as a plain Minecraft client, stood at
x = z = 100,000 and got the chunks around it; a gold block went in above it at y = 300, and a run of 1,000 × 1,000
blocks went around it.

| Server | The 81 chunks around the client | The gold block | Saved chunks |
|---|---|---|---|
| Forge 1.20, 1.20.1, 1.20.2, 1.20.3, 1.20.4 | ✅ all sent again during the run | gone on the server and in the chunk the client got | ✅ 4,096 |
| Fabric 1.20.2, 1.20.4 | ✅ all sent again during the run | gone on the server and in the chunk the client got | ✅ 4,096 |
| Forge 1.20.2, 1.20.4, with the default `skip_existing_chunks=true` | none sent again, as they are kept | stays | ✅ 4,096 |

No run had an error in the log, and the client stayed connected.

### Chunks the server holds during a run

Around players and force-loaded chunks a server keeps chunks it has only part-generated. A run generates them and puts
the result into the server's copy ([changelog](CHANGELOG.md#151)). On each server below, the same area was generated
twice in a fresh world with seed 42: 1,000 × 1,000 blocks around x = z = 100,000 in 4 batches that meet at the center,
once with nothing held, and once with 256 chunks force-loaded at the center and a view distance of 8, which leaves the
1,408 chunks 11 to 21 chunks from the center held part-generated when the run reaches them. All 1,408 were full chunks
in both worlds. Compared over those chunks, from the files of the stopped server:

| Server | Chunks with the same blocks | Blocks that differ | Of those on the two lines where batches meet | Mobs, nothing held / held | Fluid ticks kept |
|---|---|---|---|---|---|
| Forge 1.20.1 | 1,374 | 491 | 83 | 117 / 117 | 9,524 of 9,524 |
| Forge 1.20.4 | 1,375 | 487 | 81 | 112 / 112 | 9,060 of 9,060 |
| Fabric 1.20 | 1,376 | 486 | 83 | 117 / 117 | 9,524 of 9,524 |
| Fabric 1.20.1 | 1,376 | 486 | 83 | 117 / 117 | 9,524 of 9,524 |
| Fabric 1.20.6 | 1,376 | 487 | 83 | 112 / 112 | 9,060 of 9,060 |
| Fabric 1.21 | 1,376 | 487 | 83 | 112 / 112 | 9,056 of 9,056 |
| Fabric 1.21.1 | 1,374 | 491 | 83 | 112 / 112 | 9,056 of 9,056 |
| NeoForge 1.21 | 1,377 | 485 | 83 | 112 / 112 | 9,056 of 9,056 |
| NeoForge 1.21.1 | 1,376 | 486 | 83 | 112 / 112 | 9,056 of 9,056 |
| NeoForge 26.1.2 | 1,385 | 316 | 59 | 122 / 122 | 9,840 of 9,840 |
| NeoForge 26.2 | 1,385 | 316 | 59 | 122 / 122 | 9,850 of 9,850 |
| Fabric 26.1 | 1,385 | 316 | 59 | 122 / 122 | 9,841 of 9,841 |
| Fabric 26.2 | 1,385 | 316 | 59 | 122 / 122 | 9,851 of 9,851 |
| SpongeVanilla 26.2 | 1,385 | 316 | 59 | 122 / 122 | 9,851 of 9,851 |

Before the fixes, on Fabric 1.21.1, 5 to 7 of the 112 mobs were left, and 84 chunks with 31,800 blocks differed along
the two lines. The Bukkit plugin leaves such chunks to the server and has none of this.

What still differs is mostly mushrooms: about two thirds of the 491 blocks on Fabric 1.21.1 and most of the 316 on Fabric
26.2 became mushrooms in the world with chunks held. The rest on Fabric 1.21.1 is the leaves, vines and logs of some trees, some seagrass, and bee nests, which
also land apart between two runs of the same jar. The trees and the seagrass are next to the chunks the server generated
itself, 10 chunks from the center and closer, whose features ran in an order of the server's. The mushrooms stand under
trees: a mushroom can only be placed where it is dark, batches with held chunks are lit in the server's light engine, and
a later batch's features read that light where a world with nothing held has none yet. That reason comes from reading the
code and was not tested on its own. In 44 of the 1,408 chunks the light is stored as not done, on purpose, and the server
lights them when it loads them.

`skip_existing_chunks=false` through the commands, on Forge and Fabric 1.20.1, Fabric and NeoForge 1.21.1, Fabric,
NeoForge, SpongeVanilla, Spigot, Paper and Folia 26.2: `/pregen start 1` saved 4,096 chunks, the same area with the
setting off generated and saved all 4,096 again, and `/pregen resume` then kept all 4,096.

What the game draws from such chunks was looked at with a real client of each version, on all 27 servers and, with the mod
in the game, in 22 singleplayer worlds. With the final jars, this was repeated on 11 servers and in 6 singleplayer worlds.
In the ring of chunks the server had only started around the player, caves were dark as Minecraft lights them (66 to 81%
dark pixels, where version 14 gave 0 to 6%), as they were under the player (97 to 99%), and a second run over the area,
with `skip_existing_chunks=false`, changed the view by less than 2%.

### With C2ME

C2ME changes Minecraft's light engine in two places that keep its results. 15 therefore lit every batch on the
server's single light thread when C2ME was installed; 15.1 recognizes both changes and keeps lighting on the worker
threads ([changelog](CHANGELOG.md#151)). The fresh area as above with C2ME, on each version that has a C2ME build here,
by a 15.1 jar and by the same jar without this change (both built before the End city and Nether fortress fix of
15.1); the two worlds' blocks, sky light and block light were compared over all 4,096 chunks of each dimension:

| Server | C2ME | Overworld | Nether | End | The two worlds |
|---|---|---|---|---|---|
| Fabric 1.20.1 | 0.2.0+alpha.11.18 | 122.7 → ✅ 131.5 | 131.8 → ✅ 174.7 | 788.0 → ✅ 786.8 | the same, apart from 6 bee nests in the Overworld |
| Fabric 1.21.1 | 0.4.0-alpha.0.30 | 103.9 → ✅ 117.0 | 173.9 → ✅ 280.9 | 616.4 → ✅ 638.2 | the same, apart from 5 bee nests in the Overworld |
| NeoForge 1.21.1 | 0.4.0-alpha.0.123 | 101.6 → ✅ 118.0 | 165.0 → ✅ 271.2 | 600.2 → ✅ 612.9 | 8 bee nests in the Overworld; in the End, the pieces of one End City (3,314 blocks in 20 chunks) |
| NeoForge 26.1.2 | 0.4.0-alpha.0.100 | 143.2 → ✅ 181.7 | 184.9 → ✅ 358.0 | 362.1 → ✅ 591.3 | the same |
| Fabric 26.2 | 0.4.1-beta.1.0 | 129.7 → ✅ 161.4 | 186.0 → ✅ 370.3 | 313.3 → ✅ 479.3 | the same |

Chunks per second without the change → with it; each run completed with every chunk fully generated, lit and readable,
and no error from Ultra Fast Pregen. The light differs only in chunks with a different block or next to one. Bee nests
land one block apart between two runs of the same jar too.

The End city in the NeoForge 1.21.1 pair is the layout race fixed in 15.1
([End cities and Nether fortresses](#end-cities-and-nether-fortresses)): it had 78 pieces in one of the two worlds. With
the final jar and C2ME it had its 88 pieces in 3 of 3 runs.

The final 15.1 jars with C2ME: the fresh area, the area around spawn and the restart, as in the first table:

| Server | Overworld | Nether | End | Around spawn | Restart |
|---|---|---|---|---|---|
| Fabric 1.20.1 | ✅ 130.1 | ✅ 184.9 | ✅ 792.7 | ✅ 126.7 | ✅ |
| Fabric 1.21.1 | ✅ 119.6 | ✅ 289.0 | ✅ 638.6 | ✅ 107.4 | ✅ |
| NeoForge 1.21.1 | ✅ 119.5 | ✅ 259.4 | ✅ 631.9 | ✅ 107.4 | ✅ |
| NeoForge 26.1.2 | ✅ 175.1 | ✅ 356.0 | ✅ 567.8 | ✅ 138.9 | ✅ |
| Fabric 26.2 | ✅ 163.4 | ✅ 353.5 | ✅ 468.4 | ✅ 127.6 | ✅ |

The lighting on the worker threads stayed on in all of them. C2ME logs warnings of its own at start (optional classes
of other mods it does not find), and on NeoForge 1.21.1 errors about client classes on a dedicated server; none of
them comes from Ultra Fast Pregen.

### Bukkit and Sponge plugins

The 15.1 plugin jars on the servers of the 15 tests above (CraftBukkit and Spigot 26.2 build 4648, Paper 26.2 build
134, Folia 26.2 build 7, SpongeVanilla 26.2): the fresh area as above in each dimension; on Spigot, Paper and Folia
also 3,000 × 3,000 blocks in the Overworld (9 batches) with the server stopped during the run, after which the run
continued by itself and completed; and the End over 3,000 × 3,000 blocks twice each with the 15 and the 15.1 Bukkit
plugin on Spigot and with the 15.1 Sponge build, the two worlds compared block for block and structure for structure.

| Server | Overworld | Nether | End | Stopped and continued |
|---|---|---|---|---|
| CraftBukkit 26.2 | ✅ 116.4 | ✅ 235.4 | ✅ 438.2 | – |
| Spigot 26.2 | ✅ 121.4 | ✅ 243.5 | ✅ 462.8 | ✅ |
| Paper 26.2 | ✅ 133.9 | ✅ 325.1 | ✅ 740.8 | ✅ |
| Folia 26.2 | ✅ 143.4 | ✅ 328.7 | ✅ 752.1 | ✅ |
| SpongeVanilla 26.2 | ✅ 147.0 | ✅ 258.2 | ✅ 562.3 | – |

✅ and the numbers as above: every chunk fully generated, lit and readable, and no error in the log. Over 3,000 × 3,000
blocks, the light of the 748 chunks along the lines where batches meet is stored as not done, on purpose, and the server
lights them when it loads them. In the End over 3,000 × 3,000 blocks, the two worlds of each pair had the same blocks,
light and structures, and the 10 End cities had the same pieces with the 15 plugin, the 15.1 plugin and the 15.1 Sponge
build. The layout race of 15 did not show on Spigot, neither in these runs of the End alone nor with all three dimensions
in one server ([End cities and Nether fortresses](#end-cities-and-nether-fortresses)). Those runs of the End were on an
earlier build of 15.1. Over 1,000 × 1,000 blocks in each dimension, the final jars on Spigot and SpongeVanilla gave that
build's blocks and light in all 4,096 chunks of each.

#### On other Minecraft versions

The plugin jars run on Minecraft 26.2 only. On each server below, with a fresh world and the jar installed, the server
started and went on running, and no chunk was written at the area asked for:

| Server | The jar | `/pregen` |
|---|---|---|
| Paper 26.1.2 build 74, Folia 26.1.2 build 8 | the server refuses it: `Unsupported API version 26.2` | unknown command |
| Paper 26.3 build 231, Folia 26.3 build 6 | logs that it is for Minecraft 26.2 and what this server lacks, and disables itself | "plugin is disabled" |
| SpongeVanilla 26.1.2 (RC2762), 26.3 (RC2764) | applies none of its mixins and logs that it is for Minecraft 26.2 | unknown command |

CraftBukkit and Spigot were not tried on these versions.

## Known limits

- **Moonrise is not supported yet by the mods** (the Bukkit plugin supports Paper and Folia, which have it built in).
  It replaces Minecraft's chunk system (chunk storage, chunk holders and the light engine),
  which Ultra Fast Pregen generates and saves through. `/pregen start` says so and starts nothing (⛔ in the tables above).
  Pre-generate without Moonrise, or with a pregenerator that asks the server for chunks.
- **C2ME's density function compiler** (on by default) turns Ultra Fast Pregen's cell-skipping fill off on 1.21.1 and 26.x;
  the fast terrain path stays on. On 1.20.1 both work together. Lithium and Canary no longer turn the fill off (15).
- **Noisium with Lithium or Canary, and Alex's Caves** change terrain steps that the fast path would skip, so those worlds use
  Minecraft's per-status terrain path (shown in the tables). The start-up log names the mod.
- **SuperChunk** (0.5.0, NeoForge 1.21.1 and Forge 1.20.1) runs with the fast terrain path and parallel features; the
  cell-skipping fill, the surface rule shortcuts and lighting on the worker threads step aside for SuperChunk's own
  versions of them. SuperChunk's own generation differs from Minecraft's in some features (trees, plants, ores in about
  4% of chunks); with Ultra Fast Pregen the blocks are the ones SuperChunk produces on Minecraft's path.
- **Voxy** runs only on the client: on a dedicated server it receives nothing; in singleplayer saved chunks go into its LoD database.
- **Distant Horizons:** on the 26.x builds Ultra Fast Pregen sends every saved chunk to its LoD database (all 4,096 in
  these runs), and DH builds the LoDs while the run goes on, which costs some speed. The 1.20.1 and 1.21.1 builds do not
  send chunks to DH (the LoD export is in the 26.x builds); DH runs alongside them without errors.
- **Fabric 26.2, everything together, the End:** the saved chunks hold fewer sky-light arrays than Minecraft's own chunks of the
  same seed. ScalableLux does the same without Ultra Fast Pregen, and with ScalableLux alone Ultra Fast Pregen matches it.

Found while testing, not caused by Ultra Fast Pregen:

- **YUNG's Better Caves 2.0.7 on Forge 1.20.1** uses MixinExtras without bundling it, and Forge 47 has none: the server crashes
  at start-up unless another mod bundles MixinExtras (ModernFix does). It is tested together with ModernFix ("Everything together").
- **Moonrise 0.1.0-beta.15 on Fabric 1.21.1** crashes at start-up together with the full Fabric API 0.116.17
  (Fabric API's `ChunkHolderMixin` cannot apply), so the Moonrise runs on Fabric 1.21.1 have no Fabric API.

## Tested mod files

Exactly these files were installed (with the libraries they require), from Modrinth:

- **Fabric 1.20.1:** `BiomesOPlenty-fabric-1.20.1-19.0.0.96.jar`, `c2me-fabric-mc1.20.1-0.2.0+alpha.11.18.jar`, `cloth-config-11.1.136-fabric.jar`, `DistantHorizons-3.3.3-1.20.1-fabric-forge.jar`, `fabric-api-0.92.12+1.20.1.jar`, `ferritecore-6.0.1-fabric.jar`, `GlitchCore-fabric-1.20.1-0.0.1.1.jar`, `lithium-fabric-mc1.20.1-0.11.4.jar`, `modernfix-fabric-5.25.2+mc1.20.1.jar`, `noisium-fabric-2.3.0+mc1.20-1.20.1.jar`, `starlight-1.1.2+fabric.dbc156f.jar`, `TerraBlender-fabric-1.20.1-3.0.1.11.jar`, `Terralith_1.20.x_v2.5.4.jar`, `YungsApi-1.20-Fabric-4.0.6.jar`, `YungsBetterCaves-1.20.1-Fabric-2.0.7.jar`
- **Forge 1.20.1:** `alexscaves-2.0.2.jar`, `BiomesOPlenty-1.20.1-18.0.0.592.jar`, `canary-mc1.20.1-0.3.3.jar`, `citadel-2.6.3-1.20.1.jar`, `DistantHorizons-3.3.3-1.20.1-fabric-forge.jar`, `ferritecore-6.0.1-forge.jar`, `modernfix-forge-5.27.88+mc1.20.1.jar`, `noisium-forge-2.3.0+mc1.20-1.20.1.jar`, `TerraBlender-forge-1.20.1-3.0.1.11.jar`, `Terralith_1.20.x_v2.5.4.jar`, `YungsApi-1.20-Forge-4.0.6.jar`, `YungsBetterCaves-1.20.1-Forge-2.0.7.jar`
- **Fabric 1.21.1:** `BiomesOPlenty-fabric-1.21.1-21.1.0.14.jar`, `c2me-fabric-mc1.21.1-0.4.0-alpha.0.30.jar`, `cloth-config-15.0.140-fabric.jar`, `DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar`, `fabric-api-0.116.17+1.21.1.jar`, `ferritecore-7.0.3-fabric.jar`, `GlitchCore-fabric-1.21.1-2.1.0.2.jar`, `lithium-fabric-0.15.4+mc1.21.1.jar`, `lithostitched-1.8.1-fabric-21.1.jar`, `modernfix-fabric-5.25.1+mc1.21.1.jar`, `Moonrise-Fabric-0.1.0-beta.15+2eae1b1.jar`, `noisium-fabric-2.3.0+mc1.21-1.21.1.jar`, `ScalableLux-0.1.0.1+fabric.d0d58ab-all.jar`, `TerraBlender-fabric-1.21.1-4.1.0.8.jar`, `Terralith_1.21.x_v2.6.2.jar`, `YungsApi-1.21.1-Fabric-5.1.9.jar`, `YungsBetterCaves-1.21.1-Fabric-3.1.6.jar`
- **NeoForge 1.21.1:** `BiomesOPlenty-neoforge-1.21.1-21.1.0.14.jar`, `c2me-neoforge-mc1.21.1-0.4.0-alpha.0.123.jar`, `DistantHorizons-3.3.3-1.21.1-fabric-neoforge.jar`, `ferritecore-7.0.3-neoforge.jar`, `GlitchCore-neoforge-1.21.1-2.1.0.2.jar`, `lithium-neoforge-0.15.4+mc1.21.1.jar`, `lithostitched-1.8.1-neoforge-21.1.jar`, `modernfix-neoforge-5.27.26+mc1.21.1.jar`, `Moonrise-NeoForge-0.1.0-beta.15+2eae1b1.jar`, `noisium-neoforge-2.3.0+mc1.21-1.21.1.jar`, `ScalableLux-0.1.0.1+neoforge.1cb1e91-all.jar`, `TerraBlender-neoforge-1.21.1-4.1.0.8.jar`, `Terralith_1.21.1_v2.6.2_Neoforge.jar`, `YungsApi-1.21.1-NeoForge-5.1.9.jar`, `YungsBetterCaves-1.21.1-NeoForge-3.1.6.jar`
- **NeoForge 26.1.2:** `BiomesOPlenty-neoforge-26.1.2-26.1.2.0.22.jar`, `c2me-neoforge-mc26.1.2-0.4.0-alpha.0.100.jar`, `DistantHorizons-3.3.3-26.1.2-fabric-neoforge.jar`, `ferritecore-9.0.0-neoforge.jar`, `GlitchCore-neoforge-26.1.2-26.1.2.0.2.jar`, `lithium-neoforge-0.24.7+mc26.1.2.jar`, `lithostitched-1.8.1-neoforge-26.1.jar`, `modernfix-neoforge-5.27.24+mc26.1.2.jar`, `Moonrise-NeoForge-1.0.1+2cd8eef.jar`, `ScalableLux-0.2.0+neoforge.cd0897f-all.jar`, `TerraBlender-neoforge-26.1.2-26.1.2.0.3.jar`, `Terralith_26.1_v2.6.2_Neoforge.jar`, `YungsApi-26.1.2-NeoForge-6.1.3.jar`, `YungsBetterCaves-26.1.2-NeoForge-4.1.2.jar`
- **Fabric 26.2:** `BiomesOPlenty-fabric-26.2-26.1.2.0.40.jar`, `c2me-fabric-mc26.2-0.4.1-beta.1.0.jar`, `DistantHorizons-3.3.3-26.2-fabric-neoforge.jar`, `fabric-api-0.161.0+26.2.jar`, `ferritecore-9.0.0-fabric.jar`, `GlitchCore-fabric-26.2-26.2.0.0.0.jar`, `lithium-fabric-0.25.3+mc26.2.jar`, `lithostitched-1.8.0-fabric-26.2.jar`, `Moonrise-Fabric-1.1.2+151ce65.jar`, `ScalableLux-0.2.1+fabric.2b08348-all.jar`, `TerraBlender-fabric-26.2-26.2.0.0.2.jar`, `Terralith_26.2_v2.6.4.jar`, `voxy-0.2.19-beta.jar`

## Tested jars

Version 15, the released jars: the automated test suite, the runs with other mods, the modpacks, and the Bukkit and
Sponge builds.

| File | SHA-1 |
|---|---|
| `ultrafastpregen-bukkit-26.2-15.jar` | `c13d8f059539aa04628db6f7282e9ffa624ba9d6` |
| `ultrafastpregen-fabric-1.20.1-15.jar` | `e8b2b430076d9dfdd5a23487b504aac914df0f9f` |
| `ultrafastpregen-fabric-1.21.1-15.jar` | `7576cd5ecc32f642cefdee4c04ea9f3d14271540` |
| `ultrafastpregen-fabric-26.2-15.jar` | `59de6b802e9325f1c26d8c0fdd75d9aa943c5c84` |
| `ultrafastpregen-forge-1.20.1-15.jar` | `848459db137c7bec4d36625bf3f577b393a6e6dd` |
| `ultrafastpregen-neoforge-1.21.1-15.jar` | `0a7316527436bcc2ecd50fcc5c8a4b77f2d14cd9` |
| `ultrafastpregen-neoforge-26.1.2-15.jar` | `3f53da8291d3cc988ba44550039be2cc1ca1521a` |
| `ultrafastpregen-sponge-26.2-15.jar` | `41d7bea835b25c8588df2b344892b05f5f7a24bd` |

Version 15.1, the six mod jars and the two plugin jars: [Version 15.1](#version-151).

| File | SHA-1 |
|---|---|
| `ultrafastpregen-bukkit-26.2-15.1.jar` | `8b445681dedd031ad4a5c5727a964006a1246d48` |
| `ultrafastpregen-fabric-1.20.x-15.1.jar` | `79785f1fe99c4b3c1e93f1ef8153d3d0d8e75dcc` |
| `ultrafastpregen-fabric-1.21-1.21.1-15.1.jar` | `2868575ed3809ff6e7ae565cd5cc01eded5d1023` |
| `ultrafastpregen-fabric-26.1-26.2-15.1.jar` | `33e56406a9177e9fb40ab5bdbb44b01f790383bf` |
| `ultrafastpregen-forge-1.20-1.20.4-15.1.jar` | `5949248751da357b61ea46df79d1ca0985e47630` |
| `ultrafastpregen-neoforge-1.21-1.21.1-15.1.jar` | `d1c15d7cf7e40f225f7db918148c597459ea93ab` |
| `ultrafastpregen-neoforge-26.1.2-26.2-15.1.jar` | `8b0210dcc8d5b58f57064e6251c364e67efa67d8` |
| `ultrafastpregen-sponge-26.2-15.1.jar` | `39b2706073b0de0d5fba2e782142256a31d25ecf` |

Version 14.1 (the released jars), for the modpacks' comparison.

| File | SHA-1 |
|---|---|
| `ultrafastpregen-fabric-1.20.1-14.1.jar` | `0516137f57c868553d39ff79d87c518f2daf7ba1` |
| `ultrafastpregen-forge-1.20.1-14.1.jar` | `4e445a797c2fe97c0dcf7bc018f5371ed799bf2e` |
| `ultrafastpregen-neoforge-1.21.1-14.1.jar` | `a2aa6f0baa11a51dc3b1c5aa2979bb682f29dc7a` |
