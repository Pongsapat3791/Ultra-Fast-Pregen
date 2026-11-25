# Compatibility and test results

Ultra Fast Pregen 15, tested with the released jars, whose SHA-1 is at the end of this page: the automated test suite,
the runs with other mods, the modpacks, and the Bukkit and Sponge builds; 15.1: its six mod jars on every Minecraft
version they run on, alone and with C2ME, and its two plugin jars; and 16: its 29 mod jars on every Minecraft version
from 1.20 to 26.3 on Fabric, NeoForge and Forge.

- [Automated test suite](#automated-test-suite): every build, block for block against Minecraft's own generation
- [Together with other mods](#together-with-other-mods): performance, LoD and worldgen mods on dedicated servers
- [Modpacks](#modpacks): All the Mods 10, All the Mods 9 and Prominence II, against 14.1
- [Bukkit servers and Sponge](#bukkit-servers-and-sponge): the 26.2 plugin builds
- [Version 16](#version-16): 67 servers, [the same world](#the-same-world),
  [block entities](#block-entities-against-a-world-with-no-mod) and
  [blocks against a server with no mod, and the edge of the area](#blocks-against-a-server-with-no-mod-and-the-edge-of-the-area),
  [what 16 changes in the world](#what-16-changes-in-the-world),
  [mushrooms and what grows beside them](#mushrooms-and-what-grows-beside-them),
  [Link: where two tiles meet](#link-where-two-tiles-meet),
  [a NeoForge world from 1.21.6 on](#a-neoforge-world-from-minecraft-1216-on),
  [runs that are not a fresh square](#runs-that-are-not-a-fresh-square), [the mod's parts](#the-mods-parts),
  [with a player](#with-a-player), [speed against 15.1](#speed-against-151),
  [the automated test suite with 16](#the-automated-test-suite-with-16), [with other mods](#with-other-mods),
  [in the modpacks, with the pack's own game](#in-the-modpacks-with-the-packs-own-game),
  [what the tests found in the loaders](#what-the-tests-found-in-the-loaders)
  and [what was not tested with 16](#what-was-not-tested-with-16)
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
NeoForge 26.1.2, Fabric 26.2) with the jars at the end of this page, and again on the same six source trees of
version 16 ([the automated test suite with 16](#the-automated-test-suite-with-16)).

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
| `BLOCK_ENTITY_DATA_CHECK` (new in 16) | The saved chunks of a run hold what generation put into their block entities: every monster spawner its mob, the chests their loot tables. |

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
themselves off (see [Faster](CHANGELOG.md#faster-1)); in All the Mods 9 the surface rule shortcuts stay on, and 15 is 17%
faster there. One run each; a run's speed varies by up to about 10%.

**Prominence II in this table ran with `parallel_features=always`**, which the test server's folder carried from an
earlier test; the default is `auto`. In this pack the two settings give different worlds and different speeds: see
[in the modpacks, with the pack's own game](#in-the-modpacks-with-the-packs-own-game).

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

## Version 16

Version 16's 29 mod jars (the Bukkit and Sponge builds stay at 15.1), on the same test server as above (Intel Xeon
E5-2670 v2, 20 threads; Java 21, and Java 25 for 26.x), each time a fresh world with seed 42 and no other mod.

### Every loader on every Minecraft version

A dedicated server of each of the 67 loader builds below started with its jar and pregenerated a square of the
Overworld; where the table says so, also 1,000 × 1,000 blocks of the Nether and of the End.

| Minecraft | Fabric | NeoForge | Forge |
|---|---|---|---|
| 1.20 | ✅ Loader 0.19.5 |  | ✅ 1.20-46.0.14 |
| 1.20.1 | ✅ Loader 0.19.5, Nether ✅, End ✅ | ✅ 1.20.1-47.1.106 | ✅ 1.20.1-47.4.28 |
| 1.20.2 | ✅ Loader 0.19.5 | ✅ 20.2.93 | ✅ 1.20.2-48.1.0 |
| 1.20.3 | ✅ Loader 0.19.5 | ✅ 20.3.8-beta | ✅ 1.20.3-49.0.2 |
| 1.20.4 | ✅ Loader 0.19.5 | ✅ 20.4.253, Nether ✅, End ✅ | ✅ 1.20.4-49.2.13, Nether ✅, End ✅ |
| 1.20.5 | ✅ Loader 0.19.5 | ✅ 20.5.21-beta |  |
| 1.20.6 | ✅ Loader 0.19.5, Nether ✅, End ✅ | ✅ 20.6.144, Nether ✅, End ✅ | ✅ 1.20.6-50.2.14, Nether ✅, End ✅ |
| 1.21 | ✅ Loader 0.19.5 | ✅ 21.0.167 | ✅ 1.21-51.0.33, Nether ✅, End ✅ |
| 1.21.1 | ✅ Loader 0.19.5, Nether ✅, End ✅ | ✅ 21.1.259, Nether ✅, End ✅ | ✅ 1.21.1-52.1.20, Nether ✅, End ✅ |
| 1.21.2 | ✅ Loader 0.19.5 | ✅ 21.2.1-beta |  |
| 1.21.3 | ✅ Loader 0.19.5 | ✅ 21.3.100 | ✅ 1.21.3-53.1.16, Nether ✅, End ✅ |
| 1.21.4 | ✅ Loader 0.19.5, Nether ✅, End ✅ | ✅ 21.4.161, Nether ✅, End ✅ | ✅ 1.21.4-54.1.22, Nether ✅, End ✅ |
| 1.21.5 | ✅ Loader 0.19.5, Nether ✅, End ✅ | ✅ 21.5.101, Nether ✅, End ✅ | ✅ 1.21.5-55.1.18, Nether ✅, End ✅ |
| 1.21.6 | ✅ Loader 0.19.5 | ✅ 21.6.20-beta | ✅ 1.21.6-56.0.9, Nether ✅, End ✅ |
| 1.21.7 | ✅ Loader 0.19.5 | ✅ 21.7.25-beta | ✅ 1.21.7-57.0.3, Nether ✅, End ✅ |
| 1.21.8 | ✅ Loader 0.19.5, Nether ✅, End ✅ | ✅ 21.8.57, Nether ✅, End ✅ | ✅ 1.21.8-58.1.26, Nether ✅, End ✅ |
| 1.21.9 | ✅ Loader 0.19.5 | ✅ 21.9.16-beta | ✅ 1.21.9-59.0.5, Nether ✅, End ✅ |
| 1.21.10 | ✅ Loader 0.19.5, Nether ✅, End ✅ | ✅ 21.10.64, Nether ✅, End ✅ | ✅ 1.21.10-60.1.18, Nether ✅, End ✅ |
| 1.21.11 | ✅ Loader 0.19.5, Nether ✅, End ✅ | ✅ 21.11.48, Nether ✅, End ✅ | ✅ 1.21.11-61.2.4, Nether ✅, End ✅ |
| 26.1 | ✅ Loader 0.19.5 |  | ✅ 26.1-62.0.9, Nether ✅, End ✅ |
| 26.1.1 | ✅ Loader 0.19.5 |  | ✅ 26.1.1-63.0.2, Nether ✅, End ✅ |
| 26.1.2 | ✅ Loader 0.19.5 | ✅ 26.1.2.122, Nether ✅, End ✅ | ✅ 26.1.2-64.1.5, Nether ✅, End ✅ |
| 26.2 | ✅ Loader 0.19.5, Nether ✅, End ✅ | ✅ 26.2.0.93, Nether ✅, End ✅ | ✅ 26.2-65.1.5, Nether ✅, End ✅ |
| 26.3 | ✅ Loader 0.19.5, Nether ✅, End ✅ | ✅ 26.3.0.99, Nether ✅, End ✅ | ✅ 26.3-66.1.5, Nether ✅, End ✅ |

✅: the run completed, every batch on the fast paths (none had to be made again on Minecraft's own steps), no error from
the mod in the log, and every one of the mod's hooks into Minecraft found its place in that version and was called.
NeoForge for 1.20.1 is the fork of Forge 47 and takes the Forge jar; there is no Forge for 1.20.5 and 1.21.2, and
NeoForge's builds for 26.1 and 26.1.1 are betas the NeoForge jar does not name.

### The same world

*These comparisons are of 16's first files, before the corrections of the edge, of the seams and of the mushrooms.
The released files make the same worlds with the three corrections turned off, and differ from them where the
corrections apply: [What 16 changes in the world](#what-16-changes-in-the-world).*

A fresh 2,000 × 2,000 blocks (15,876 chunks) around x = z = 100,000, compared chunk by chunk with a reference world:
blocks, biomes, light, heightmaps, block entities, entities, scheduled ticks and the positions Minecraft updates when
a chunk is first loaded. The reference is the best one there is for the server:

- **the world the 15.1 jar makes**, where 15.1 has a jar for the server;
- for Minecraft 1.21.2 to 1.21.11, which are new in 16: **the world the same jar makes on Minecraft's own terrain
  steps with every shortcut of this mod off** (`terrain_pipeline=vanilla` and the benchmark switches), so that
  Minecraft's own code decides every terrain block;
- for a loader that is new in 16 on a version: **the world another loader makes** on the same Minecraft version.

| Server | Reference | Chunks that differ |
|---|---|---|
| NeoForge 26.1.2, 26.2, 26.3 | its 15.1 world | 0 |
| Fabric 26.1.2, 26.2, 26.3 | its 15.1 world | 0 |
| Forge 26.1.2, 26.2, 26.3 | Fabric's 15.1 world | 0 |
| NeoForge 1.21.9, 1.21.10, 1.21.11 | its own world on Minecraft's steps | 0 |
| Fabric 1.21.9, 1.21.10, 1.21.11 | its own world on Minecraft's steps | 0 |
| Forge 1.21.9, 1.21.10, 1.21.11 | Fabric's world on Minecraft's steps | 0 |
| NeoForge 1.21.2, 1.21.4, 1.21.5, 1.21.6, 1.21.8 | its own world on Minecraft's steps | 0 |
| Fabric 1.21.2, 1.21.4, 1.21.5 | NeoForge's world on Minecraft's steps | 0 |
| Fabric 1.21.6, 1.21.8 | its own world on Minecraft's steps | 0 |
| Forge 1.21.3 | its own world on Minecraft's steps | 0 |
| Forge 1.21.4, 1.21.5 | NeoForge's world on Minecraft's steps | 0 |
| Forge 1.21.6, 1.21.8 | Fabric's world on Minecraft's steps | 0 |
| NeoForge 1.21, 1.21.1; Fabric 1.21.1 | its 15.1 world | 0 ¹ ² |
| Forge 1.21, 1.21.1 | NeoForge's 15.1 world | 0 ¹ ² |
| Fabric 1.20.1, 1.20.4, 1.20.6; Forge 1.20.1, 1.20.4 | its 15.1 world | 0 ¹ ² |
| NeoForge 1.20.1, 1.20.4 | Forge's 15.1 world | 0 ¹ ² |
| NeoForge 1.20.6, Forge 1.20.6 | Fabric's 15.1 world | 0 ¹ ² |

¹ On Minecraft 1.21.1 and older, 11 to 20 chunks differ in the side a bee nest hangs on, and with it one or two
scheduled ticks of the leaves beside it: Minecraft shuffles the sides with a random of its own, so two runs of any one
jar differ the same way (see [Version 15.1](#version-151)).

² Apart from the block entities that 15.1 saved empty on these Minecraft versions
([below](#block-entities-against-a-world-with-no-mod)): with the released files, a chunk that holds a block entity
which generation fills differs from 15.1's world in that block entity and in nothing else. That is 822 to 829 of the
15,876 chunks on the servers of Minecraft 1.21 and 1.21.1, and 480 to 483 on those of 1.20.1 to 1.20.6, measured
again on every server of these rows (ten with the released files, four with the build whose classes they hold).

**The Nether and the End**, the same way: NeoForge 26.1.2, NeoForge 1.21.1, Fabric 1.20.1 and Forge 1.21.1 against
15.1 worlds, Forge 26.2 against Fabric's 15.1 worlds, NeoForge 1.21.4, 1.21.8 and 1.21.11 against their own worlds on
Minecraft's steps. The End: no chunk differs anywhere. The Nether: none on four of them; one or two chunks on Fabric
1.20.1, NeoForge 1.21.8, NeoForge 1.21.11 and Forge 26.2, each time a few blocks of soul sand, basalt or netherrack at
the edge between two biomes. That is Minecraft's own biome lookup, which can answer either way where two biomes are
exactly as close (also described under [Version 15.1](#version-151)).

**Loaded by a server with no mod:** the worlds of 37 of these servers, among them every one of Minecraft 1.21.2 to
1.21.11 above, were then opened by the same server without any mod, which loaded 400 chunks in the middle of the
area and saved. Every time the log has no complaint about a chunk, every chunk was full before and after, none was
generated anew, and the water and lava the pregenerated world holds began to flow once loaded, as they do in the
15.1 worlds of four of these servers under the same load.

### Block entities against a world with no mod

Every comparison above has a world of this mod as its reference, so it could not see what all of them had in common.
The modpack tests with a player (below) found it: **on Minecraft 1.20 to 1.21.1, every version of this mod up to 15.1
saved the block entities that generation fills in after placing them empty** (chests without loot table, spawners
without a mob; [changelog](CHANGELOG.md#fixed)). So for 16 a square of 16 × 16 chunks, chosen for its many such
block entities, was also generated by a server with **no mod at all**, and every block entity of it compared with the
same square of the pregenerated world, value for value:

| Server | In the no-mod world | 16 | 15.1 |
|---|---|---|---|
| NeoForge 1.21.1 | 33 chests (22 with a loot table), 6 barrels, 46 decorated pots, 56 dispensers, 39 suspicious sand and gravel, 7 monster spawners, 23 trial spawners, 19 vaults | **every chest, barrel, pot, dispenser and suspicious block the same**, loot table and seed; every spawner has its mob, every trial spawner its settings | 208 of 244 block entities differ: no loot table in any of them, no mob in any spawner, no settings in any trial spawner |
| Fabric 1.20.1 | 18 chests, 39 suspicious sand and gravel, 11 monster spawners | **all 18 chests and 39 suspicious blocks the same**; 10 spawners the same | 67 of 68 differ: no loot table, no mob |
| NeoForge 26.1.2 | 22 chests, 19 barrels, 34 decorated pots, 22 dispensers, 39 suspicious sand and gravel, 6 monster spawners, 18 trial spawners | all the same | all the same: Minecraft 26.x never left the placeholder that was saved in their place |

What still differs from the no-mod world is what a loaded chunk changes, and the no-mod server had loaded its square:
a trial spawner picks its mob when it first ticks, the bees of a nest have flown, and four suspicious blocks had
fallen. A monster spawner of a dungeon can hold another mob (3 of 7 on NeoForge 1.21.1): Minecraft draws it after a
number of other draws that depends on what the neighboring chunks hold at that moment, in any two runs of any server.

**The repair the changelog names** for a world that 15.1 or an older version pregenerated was run on two servers: 16
with `skip_existing_chunks=false` and the same `/pregen start` over 15.1's world of 15,876 chunks. Before it, 208 of
244 block entities of the square differed from the no-mod world's on NeoForge 1.21.1 and 67 of 68 on Fabric 1.20.1;
after it, every chest, barrel, pot, dispenser and suspicious block is the same there, as in a world 16 generates
fresh. The repaired world is the fresh one in all chunks but the area's outermost (497 of 15,876 differ, in ores
and light at the edge, where the old run's part-generated chunks outside are read back).

Against the 15.1 worlds of the table above, the released files differ on Minecraft 1.20 to 1.21.1 in block entities
only, in the chunks that hold such a block entity (825 of 15,876 chunks on NeoForge 1.21.1, 482 on Fabric 1.20.1), and
in nothing on 26.1.2.

### Blocks against a server with no mod, and the edge of the area

The comparisons of blocks above have this mod on both sides (15.1's world, or the same jar on Minecraft's own terrain
steps), and the test suite compares with Minecraft's own steps run over the chunks in the same order. Against the
world a server with no mod makes of the same seed, no chunk-by-chunk identity can be expected, because **Minecraft
does not make the same world twice**. Where features of two neighboring chunks overlap (a blob of granite and a blob
of andesite, an ore and a patch of gravel, two patches of seagrass, coral), the chunk decorated later wins, and the
order in which a server decorates its chunks is not fixed.

A box of 16 × 16 chunks on three servers (the boxes of the block entity test, seed 42), every world held by a
server with no mod for the same time before it was saved. Blocks that differ from the world of a server with no
mod, in the whole box of 256 chunks and in its inside of 100 chunks:

| Compared with the world of a server with no mod | Fabric 1.20.1 | NeoForge 1.21.1 | NeoForge 26.1.2 |
|---|---|---|---|
| the same server and command a second time | 9,179 / 3,258 | 7,476 / 5,337 | 3,554 / 1,384 |
| the same server, the rows asked for in another order | 23,264 / 8,307 | 26,824 / 12,238 | 21,382 / 10,642 |
| pregenerated with 16's first files (the edge as in 15.1), then loaded by the server with no mod | 23,091 / 6,658 | 24,341 / 13,263 | 20,467 / 9,412 |
| pregenerated with 15.1, then loaded the same way | 23,095 / 6,658 | 24,290 / 13,276 | 20,462 / 9,400 |
| **pregenerated with 16 as released** (the edge corrected, see below) | 22,636 / 6,658 | 23,782 / 13,255 | 19,810 / 9,392 |

The blocks are of the same kinds in every row: stone variants where blobs overlap, ores in one kind of stone or
the other, seagrass and kelp, a vine. This mod decorates the chunks of a batch in an order of its own (nine
interleaved groups), another one than a server's; the suite's `FEATURE_ORDER_PARITY_CHECK` is the check that each
chunk's features are Minecraft's own for the order they ran in.

**One difference, on Fabric 1.20.1's box only, is not explained by another order alone.** Between two worlds with no
mod, the swaps are even: where
one has andesite and the other granite, the reverse is as frequent (2,532 and 2,588 blocks in the 196 chunks
inside). Between the pregenerated world and the world with no mod they are not: 2,254 blocks are andesite where the
server has granite and 1,221 the other way round; andesite against diorite 1,800 and 966; diorite against granite
1,426 and 1,121. The stone kind whose blobs Minecraft places later comes out on top more often, and the
pregenerated world holds 1% more andesite (177,853 blocks against 175,922 to 175,954) and correspondingly less
granite and diorite; the three kinds together cover the same number of blocks. It is the same to the block with
15.1, with features run one chunk at a time (`parallel_steps=false`), and with every shortcut of the mod off on
Minecraft's own terrain steps (`terrain_pipeline=vanilla`): it is neither new in 16 nor a matter of threads or
shortcuts. What those runs still share, and a server does not, is the order in which the chunks are decorated. Why
an order should favor a stone kind was not found. On the boxes of NeoForge 1.21.1 and 26.1.2 the stone counts inside
are within the range of the worlds with no mod.

**The outermost chunks of a pregenerated area were a different matter, and are fixed in 16.** Minecraft finishes
a chunk only when its eight neighbors have placed their features, so the parts of a neighbor's features that reach
across the border are in it. Up to 15.1 (and in the first files of 16, which were tested above) a pregen finished the
chunks of its area while the chunks outside held terrain only. Features of the area that reach out were kept in the
chunks outside, which the pregen saves part-generated. But when the server later generated the chunks outside itself,
**the parts of their features that reach into the area were dropped**, since most of world generation does not write
into a finished chunk (ores and stone blobs do: they were never missing). The outer ring of 60 chunks of the boxes
above then had less of what such features bring than all three worlds with no mod, and roofed spaces at the edge were
lit too bright, up to 13 blocks in (All the Mods 9: 77 chunk sections in six edge chunks under a tall structure that
crosses the edge; an amethyst geode reaching in from outside lacked 822 blocks).

16 places the features of the one row of chunks around the area inside the run and saves those chunks as Minecraft
leaves the neighbors of finished chunks: status `initialize_light`, their features placed, the light that reached
them stored; the row beyond holds terrain, as before. While the area's edge is lit, the chunks outside give no light
of their own. On five servers (NeoForge 26.1.2, NeoForge 1.21.1, Fabric 1.20.1, Fabric 26.3, NeoForge 26.3) all 68
chunks around a 16 × 16 box are stored that way, a server with no mod loads the box without a complaint about a
chunk, goes on from those chunks, and afterwards the rings around the box are finished as around its own chunks.
The outer ring of 60 chunks, after the server with no mod has loaded the box:

| Outer ring of 60 chunks | up to 15.1 | **16** | three worlds with no mod |
|---|---|---|---|
| Fabric 1.20.1: tall seagrass | 1,304 | **1,416** | 1,446 to 1,478 |
| Fabric 1.20.1: seagrass | 754 | **803** | 801 to 809 |
| Fabric 1.20.1: clay | 221 | **246** | 246 to 248 |
| Fabric 1.20.1: sand | 9,588 | **9,730** | 9,723 to 9,746 |
| Fabric 1.20.1: bubble columns | 82 | **96** | 96 |
| Fabric 1.20.1: lily pads | 83 | **101** | 100 to 102 |
| NeoForge 1.21.1: tall seagrass | 1,300 | **1,452** | 1,454 to 1,478 |
| NeoForge 1.21.1: lily pads | 162 | **177** | 177 |
| NeoForge 26.1.2: seagrass | 606 | **702** | 715 to 722 |
| NeoForge 26.1.2: tall seagrass | 1,154 | **1,262** | 1,288 to 1,304 |
| NeoForge 26.1.2: lily pads | 145 | **155** | 157 |

What is left is small and not explained: tall seagrass stays 2% below the worlds with no mod on two of the three
servers, seagrass 2% on one. Blocks that differ from the world with no mod, in the whole box and its inside (the
table at the top of this section): Fabric 1.20.1 22,636 / 6,658, NeoForge 1.21.1 23,782 / 13,255, NeoForge 26.1.2
19,810 / 9,392; Fabric 26.3 20,104 / 9,390 and NeoForge 26.3 21,239 / 10,550 against one world with no mod each.
The inside is as before; the whole box differs in 450 to 660 blocks fewer.

In the test suite (`EDGE_LIGHT_CHECK`, a 32 × 32 chunk area in four batches against the same chunks lit with all
their neighbors): with the correction off, 60 of the edge chunks and 50 of the chunks where two batches meet hold
wrong light; with it on, none. `EXTEND_LATER_CHECK`: an area that a later run extends is, block for block, the area
made at once (with the correction off 26,092 blocks differ along the old edge). `EXISTING_NEIGHBORS_CHECK` and the
checks of stopped and resumed runs cover the chunks around the area too.

### What 16 changes in the world

Three corrections of 16 change the world a run makes: the chunks around the area get their features
(`-Dfastpregen.edgeRing`), chunks where two batches meet are lit again (`-Dfastpregen.seamRelight`), and features
read the light Minecraft's own chunk reads at that step (`-Dfastpregen.darkFeatures`). Each is on and can be turned
off with `=false` as a Java argument of the server.

**With all three off, 16 makes the world of its first files, and so 15.1's** (apart from the block entities 15.1
saved empty on Minecraft 1.20 to 1.21.1, [above](#block-entities-against-a-world-with-no-mod)). 2,000 × 2,000 blocks at 100000 100000,
seed 42, against the world of 16's first files (which were compared with 15.1's above): all 15,876 chunks the same
on NeoForge 26.1.2, NeoForge 1.21.1, Fabric 1.20.1, Forge 1.20.1, Fabric 1.21.8 and Fabric 26.3 (blocks, biomes,
light, heightmaps, block entities, entities, scheduled ticks).

**With them on, as released,** 13,450 to 13,545 of the 15,876 chunks are the same on those six servers. Where the
others differ, on NeoForge 26.1.2 (the area is 126 × 126 chunks in four batches):

| Chunks | of | blocks differ | what |
|---|---|---|---|
| the outermost row | 500 | 500 | 212,775 blocks: what the chunks outside place inward (tuff, andesite, diorite and granite blobs, gravel, dirt, leaves) |
| the second row | 492 | 48 | 5,114 blocks: leaves and litter of trees at the edge, mushrooms |
| the two rows on each side of a batch seam | 960 | 185 | 4,703 blocks, 2,693 of them a mushroom |
| everywhere else | 13,924 | 1,473 | 46,647 blocks: 17,557 new brown mushrooms, 510 more in place of grass, the rest leaves, vines, leaf litter and logs of trees beside them |

Light that differs although no block differs in the chunk or in a chunk next to it: sky light in 24 chunks and
block light in 89, all of them in the row at a batch seam (the chunks that are lit again), none anywhere else.

The same on all six servers. Chunks with other blocks by place, the blocks elsewhere that have a mushroom on one
side, and the chunks whose light alone differs (all of them in the row at a batch seam):

| Server | outermost row | second row | rows at a batch seam | elsewhere | mushrooms elsewhere | sky light alone | block light alone |
|---|---|---|---|---|---|---|---|
| NeoForge 26.1.2 | 500 | 48 | 185 | 1,473 | 18,672 | 24 | 89 |
| NeoForge 1.21.1 | 500 | 40 | 197 | 1,411 | 19,446 | 20 | 83 |
| Fabric 1.20.1 | 500 | 42 | 197 | 1,407 | 19,499 | 20 | 82 |
| Forge 1.20.1 | 500 | 41 | 197 | 1,405 | 19,503 | 20 | 82 |
| Fabric 1.21.8 | 500 | 47 | 185 | 1,477 | 18,666 | 24 | 88 |
| Fabric 26.3 | 500 | 57 | 200 | 1,433 | 19,276 | 15 | 83 |

On each of the 30 servers that the 29 files were started on (below), the handler that gives features their light is
in Minecraft's light engine and is called.

Two rules of this comparison were written before its results and one of them failed. "Inside the area only blocks
with a mushroom on one side differ, or blocks right beside one": 295 chunks inside hold other pairs, leaves, logs
and vines. The cause is in the next section (a mushroom keeps a tree of a chunk decorated later from starting
there, as it does in Minecraft's own world), and the counts there say which of the two worlds is nearer to a
server's own. "Light alone differs only in the two outer rows and the two rows at a seam" held.

### Mushrooms and what grows beside them

The window of 16 × 16 chunks inside that area in which most chunks differ between the two worlds (206 of 256; a
swamp), on NeoForge 26.1.2. A server with no mod generated the same window twice. Block counts:

| Never loaded by a server | 16's first files | **16** | no mod, twice |
|---|---|---|---|
| oak logs | 2,133 | **2,108** | 2,096 and 2,099 |
| oak leaves | 42,485 | **42,137** | 41,980 and 41,837 |
| vines | 18,748 | **18,588** | 18,479 and 18,493 |
| short grass | 4,571 | **4,490** | 4,446 and 4,464 |
| firefly bushes | 130 | **94** | 92 and 94 |
| pumpkins | 19 | **17** | 17 and 17 |
| sugar cane | 90 | **91** | 96 and 95 |
| wildflowers | 33 | **30** | 40 and 43 |
| brown mushrooms | 0 | **3,469** | 139 and 129 (after the server ticked the chunks) |
| red mushrooms | 16 | **42** | 16 and 16 (the same) |

In every kind the two pregenerated worlds differ in, 16 is nearer to the server's own world than its first files
were, wildflowers excepted (by 3). Birch trees (one more than the server's, 993 leaves against 911), leaf litter
and the stone kinds are the same in both pregenerated worlds: they belong to the order in which chunks are
decorated (above).

The same window pregenerated alone by each jar and then loaded by a server with no mod, as the worlds with no mod
were:

| Loaded by a server with no mod | 16's first files | **16** | no mod, twice |
|---|---|---|---|
| brown mushrooms | 0 | **139** | 139 and 129 |
| red mushrooms | 16 | **16** | 16 and 16 |
| firefly bushes | 130 | **94** | 92 and 94 |
| pumpkins | 19 | **17** | 17 and 17 |
| oak logs, without the window's two outer rows | 1,256 | **1,256** | 1,255 and 1,276 |
| oak leaves, the same | 25,437 | **25,369** | 25,495 and 25,701 |
| vines, the same | 11,393 | **11,348** | 11,328 and 11,434 |

The first tick of a chunk removes the mushrooms that stand in daylight, in the pregenerated world as in the
server's own: 3,469 before, 139 after. **A pregenerated chunk that no server has loaded holds them all**, with the
marks that make the server check them; a map or LoD mod that reads such chunks sees about 25 times the mushrooms
that will stay.

On Fabric 1.20.1 the same window (16's first files, 16, two worlds with no mod): brown mushrooms once a server has
loaded the chunks 0, **179**, 175 and 184 (3,734 before that); oak logs 2,183, **2,165**, 2,163 and 2,220; oak
leaves 42,845, **42,469**, 42,481 and 43,428; vines 18,586, **18,370**, 18,496 and 18,729; grass 4,602, **4,493**,
4,457 and 4,463; sugar cane 92, **88**, 81 and 81.

Big packs run features one chunk at a time (when another mod keeps state while it places them), which is another
path through the mod than the runs above took. With `parallel_steps=false`, which takes that path, the released
files place the same number of mushrooms in the window as on all threads, to the block: 3,734 on Fabric 1.20.1,
3,469 on NeoForge 26.1.2, 3,724 on NeoForge 1.21.1 (oak logs and vines equal too).

### Link: where two tiles meet

A host and a client of the same server, a plan of 1,000 × 1,000 blocks around 4000 4000: nine tiles of 32 × 32
chunks, generated by the client one after another, each next to the tiles the host already has. The host's world
against the world the same jar makes of the same 96 × 96 chunks alone in one batch, in the 8,464 chunks that are
not in the two outer rows:

| | NeoForge 26.1.2, 16's first files | NeoForge 26.1.2, **16** | Fabric 1.20.1, 16's first files | Fabric 1.20.1, **16** |
|---|---|---|---|---|
| chunks saved for the server to light again | 364 | **0** | 380 | **0** |
| chunks whose sky light differs although no block near differs | 6 | **0** | 4 | **0** |
| the same for block light | 58 | **0** | 62 | **0** |
| chunks with other blocks, in the row at a tile seam (of 720) | 358 | 218 | 344 | 220 |
| in the second row (of 688) | 63 | 191 | 60 | 193 |
| further in (of 7,056) | 13 | 60 | 2 | 56 |
| the kind whose count differs most | sculk, 1,249 more (3.9%) | fire coral blocks, 477 fewer (0.9%) | sculk, 1,242 more (3.8%) | brain coral blocks, 315 more (0.6%) |

The light where two tiles meet is right with 16 and was not before. The blocks that differ are those that depend on
the order in which neighboring chunks are decorated (stone kinds where blobs overlap, seagrass, coral), in both
versions; with 16 they lie one row further from the seam, because the row of chunks beside a tile now gets its
features together with that tile, and about a fifth more blocks differ (45,000 against 38,000, 5 blocks per chunk
of the area; two runs of a server with no mod differ in 14 to 36 per chunk). Nothing is lost or placed twice at a
seam: the counts of every kind of block agree within 1% (stone kinds within 0.003%). The rule written before the
run, "not more chunks with other blocks than the same Link run made twice differs in", failed and was the wrong
rule: the same Link run made twice gives the same world to the block (tried, 9,216 chunks), so any other tile
layout than the one batch it is compared with fails it, in both versions.

A chunk that takes a block with water in it from a chunk of an earlier batch or tile (seagrass, coral) is marked for
the server to check that block when it first ticks the chunk; inside one batch such a block is not marked. So the
second row from a tile seam holds more of these marks than the one-batch world (51 against 18 in one chunk
looked at). The blocks are the same; this is as it was in 15.1.

### A NeoForge world from Minecraft 1.21.6 on

From Minecraft 1.21.6 on, NeoForge itself generates a slightly different world than Fabric and Forge do from the same
seed: about a fifth of the chunks differ somewhere (ore blobs, single cave blocks, the biome of a few cells), **with
or without Ultra Fast Pregen**. Servers with no mod show it: NeoForge 26.1.2, Fabric 26.1.2 and Forge 26.1.2 each
generated the same 196 chunks; Fabric and Forge agree, NeoForge differs from both in the biomes of two chunks, and
each loader's own world has exactly the biomes of the world Ultra Fast Pregen makes on that loader. With the mod,
Forge's worlds are Fabric's chunk for chunk (the table above), and NeoForge's are not. The 15.1 jars
show the same difference between their NeoForge and Fabric worlds. On 1.21.1 and 1.20.1 the loaders make the same
world. So a pregenerated world is the world of the loader it was made on, as it should be; a world made on NeoForge
1.21.6 or later and later run on Fabric or Forge (or the other way round) has borders where newly generated chunks
meet the old ones, as it would without this mod.

### Runs that are not a fresh square

Each scenario's world compared with the reference world of the same scenario (15.1's, or on 1.21.10 and 1.21.11 the
jar's own on Minecraft's steps):

| Scenario | NeoForge 26.1.2 | NeoForge 1.21.1 | Fabric 1.20.1 | NeoForge 1.21.11 |
|---|---|---|---|---|
| A circle of radius 1,000 | same | same ¹ | same ¹ | same |
| A square of radius 500, then one of radius 1,000 around it | same | same ¹ | same ¹ | same |
| The server stopped during batch 2, started again, the run resumed | same | same ¹ | same ¹ | same |
| Around 64 chunks the server generated itself and unloaded | same | same ¹ | same ¹ | |
| Around 64 chunks the server holds during the run | 7 chunks | 4 chunks | 20 chunks | |
| `depth terrain`, then `depth full` over it | same | same ¹ | same ¹ | same |
| `depth features`, then `depth full` over it | same | same ¹ | same ¹ | same |
| Around a stronghold | 5 chunks | 8 chunks | 11 chunks | same |

Also against their worlds on Minecraft's steps: NeoForge 1.21.4 (an extended area, a restart), NeoForge 1.21.5 (a
circle), Fabric 1.21.10 and 1.21.8 (an extended area) and Forge 1.21.8 (a restart): same.

Chunks the server holds are ticked by it while the run goes on (water flows, vines grow), so they never compare
exactly; the differences are of that kind, in and beside the held chunks, as between two runs of 15.1. Around a
stronghold the differing blocks are the stronghold's own bricks (stone, mossy or cracked): Minecraft picks them with
one chooser all pieces share, in the order its worker threads reach them, on any server.

#### With the corrections of the edge

The table above is of 16's first files. With the files as released, each scenario was compared with the world the
same jar makes of the whole 2,000 × 2,000 square at once (15,876 chunks), which asks more: both sides are 16, and
what differs is the scenario's alone.

| | NeoForge 26.1.2 | Fabric 26.3 | NeoForge 26.3 | Fabric 1.21.11 | NeoForge 1.21.10 | Fabric 1.21.8 | NeoForge 1.21.5 | NeoForge 1.21.4 |
|---|---|---|---|---|---|---|---|---|
| The server stopped during batch 2, started again, the run resumed: chunks that differ | 0 | 0 | 0 | | | | | |
| A square of radius 500, then one of radius 1,000 around it: chunks whose light alone differs | 0 | 0 | 0 | 0 | 0 | 0 | 0 | 0 |
| the same: chunks with other blocks | 362 | 453 | 458 | 370 | 369 | 370 | 377 | 359 |
| of them in the row at the first square's edge or at a batch seam | 202 | 253 | 253 | 205 | 205 | 205 | 206 | 204 |
| in the second row | 137 | 156 | 157 | 142 | 141 | 142 | 142 | 138 |
| further in | 23 | 44 | 48 | 23 | 23 | 23 | 29 | 17 |

A run that was stopped and resumed gives the same world as a run at once, to the block and to the light value. In
an extended area the light is that of a run at once; the blocks that differ lie along the first square's edge and are
leaves, stone kinds where blobs overlap, kelp and seagrass, in both directions: the order in which what crosses the
old edge was placed. No kind is short (on NeoForge 26.1.2 and Fabric 26.3 the counts of every kind agree within
0.3%). The 26.3 servers have more such chunks because 26.3 has more features that depend on the order (sulfur against
cinnabar, sulfur spikes). NeoForge 26.1.2 is the control: its source tree has the suite's `EXTEND_LATER_CHECK`; the
other seven servers run trees or layers without that check. The rule written before the runs said "only in the two
rows on each side of the old edge" and "not more than on the control"; the first part fails on the control itself (23
chunks further in), so the second is what the others are held to. Around every one of these areas the row of chunks
outside is stored with its features placed (508 chunks) and the row beyond with terrain.

**The same `/pregen start` again over an area an older version made** (the world of 16's first files, NeoForge
26.1.2; `skip_existing_chunks` as it is by default): no chunk of the area is generated again ("Generated and saved 0
chunks; kept 15,876"), the four batches run for the chunks around the area only and take 32 seconds. Against the old
world, the blocks of the 500 outermost chunks differ (204,976 blocks: what the chunks outside place inward) and of
no chunk further in; 247 of the outermost chunks are saved for the server to light again when it loads them. A
server with no mod loads the result without a complaint. So an older area's edge is repaired by running the same
command again, as long as the server has not generated beyond it there. On Fabric 1.20.1 the same: 19 seconds, the 500
outermost chunks and no other, 247 of them left for the server to light, loaded without a complaint.

**Nether and End** (NeoForge 26.1.2 and Fabric 1.20.1, 2,000 × 2,000): with the three corrections off the world is
that of 16's first files in all 15,876 chunks, in both dimensions on both servers. With them on, in the Nether the
blocks of 597 chunks differ: 464 in the outermost row, 91 in the second, 41 in the third and one in the fourth, none
deeper (basalt, blackstone and netherrack where blobs overlap, wart blocks); block light alone differs in 199 to 201
chunks in the row at a batch seam. The rule written before said "only the two outer rows": 42 chunks in the third
and fourth row fail it; they are the same order of overlapping blobs, carried one chunk at a time from the row whose
neighbors now place theirs. In the End 18 chunks of the outermost row differ (end stone and chorus plants of islands
that reach in). The row around the area is stored as in the Overworld, and a server with no mod loads the middle of
each world without a complaint.

**A 2 GB heap** (Overworld, 2,000 × 2,000): the run completes without an error line on NeoForge 26.1.2 and Fabric
1.20.1. On NeoForge 26.1.2 it takes smaller batches than 16's first files took there (750 blocks where they still
fitted 1,000: 143.5 against 166.3 chunks/s); on Fabric 1.20.1 both take 750 (133.9 against 123.4).

**With another light engine** (Starlight on Fabric 1.20.1, ScalableLux on Fabric 26.2 and NeoForge 1.21.1): a run of
the 16 × 16 chunk window completes without a warning of the mod, and the features find the same light as without
the other mod (3,734, 3,469 and 3,724 brown mushrooms placed, the same number with and without). The edge of an
area and the seams between batches were not measured with these mods installed; with them the batch's light is
left to the server's light engine, as before.

### The mod's parts

- **The switches:** 41 checks at the server console (Link and Manager off at the first start and the console saying
  so, `/pregen ufp|ufplink|ufpmanager on|off` and the settings file, `/ufplink set host|client|none` and the older
  spellings, the web page up and down with its switch, a pregen with both parts on, a run refused while the mod is
  off, the switches kept over a restart). All passed on NeoForge 26.1.2, 1.21.4, 1.21.1, 1.20.6, 1.20.4; Fabric
  1.21.8, 1.20.1; Forge 26.3, 26.2, 1.21.10, 1.21.5, 1.21.1, 1.20.6, 1.20.1.
- **Link and the Manager,** a host and a client of the same server through the web page's API and the console: the
  page with both parts, a plan of 9 tiles generated by the client and complete on the host's disk, the host's own
  pregen in a separate world for each dimension with every chunk merged, a pregen stopped half way and continued,
  one cut short by a server stop and continued after the restart, the import of a stopped client's world, the merge
  tool, and nothing in either log. All of it passed on NeoForge 26.1.2, 1.21.11, 1.21.8, 1.21.5, 1.21.1, 1.20.6,
  1.20.4; Fabric 1.21.10, 1.21.4, 1.20.1; Forge 26.3, 26.1.2, 1.21.11, 1.21.8, 1.21.4, 1.21.1, 1.20.6, 1.20.1.

#### The Manager's Health tab

With the released files on Fabric 1.20.1, Forge 1.20.1, NeoForge 1.21.1, Fabric 1.21.8, NeoForge 26.1.2 and Fabric
26.3, through the page's API (`/api/health`), 26 checks on each, all passed:

- before a run, for an area of 1,000 × 1,000 blocks: a disk check that passes and says what it counted, a warning
  for a 3 GB heap with what to do, the chunks that exist counted (none, then all of them after the run), a circle
  counted as well as a square;
- `parallel_features=always` warned about and no longer once it is set back; `skip_existing_chunks=false` over
  chunks that exist warned about as replacing them;
- during a run of 2,000 × 2,000 blocks: the answer says a run is going on, the run is seen moving, the disk space
  for the rest is checked, and no check of an area is mixed in; after the run the live level is back to good;
- a world border of 1,000 blocks: an area wider than it is warned about, an area inside it passes (Minecraft up to
  1.21.x keeps the border in `level.dat`, 26.x in a file of each dimension; both are read);
- a Link host with no run has one line that passes; without the page's token the checks are refused; the page has
  the tab and its script; no error of the mod in the server's log.

Not tried: a run that really hangs or a disk that really fills (the thresholds are five and fifteen minutes without
any movement, and less free space than the chunks to go need), and the Link lines with several machines.

The parts' switches (above) were run again with the released files on NeoForge 26.1.2, Fabric 1.20.1 and NeoForge
1.21.1: 41 of 41 on each.

### With a player

The game itself, on two Windows PCs, played by a test program: it types in the chat box, walks and flies, and takes
the game's own pictures, which a second program measures. Every world has seed 42 and `batch_size=512`, so the
1,000 × 1,000 blocks around the player are four batches that meet under them. 18 cases per game:

- the game starts and gets into the world; commands, walking, and the world is drawn;
- `/pregen start 1` from the chat box completes around the player, with the boss bar on screen;
- gold blocks the player put into the chunks the server holds are still there afterwards, and the view is the same
  picture (under 1% of its pixels changed);
- the chunks only the run made are drawn when the player flies in, looking ahead, straight down, and over the edge
  between two batches;
- with `skip_existing_chunks=false`, a second run replaces the chunks under the player: the gold is gone and the view
  is the same terrain (under 3% changed);
- the Nether and the End: a run in each, then a flight into the area;
- light underground: caves in the ring of chunks the server had only started are dark, and the caves below the
  player are the same before and after the second run;
- the parts' switches from the chat box (Link shown off, `/pregen ufp off` refuses a run, `on` again);
- a `depth terrain` run 3,000 blocks away, then a flight there: the server finishes those chunks on loading;
- no error in the game's log or the server's, the player never disconnected; the game closes by itself and the
  server stops without being killed.

| Run | Games | Cases passed |
|---|---|---|
| **A player on a server:** the plain game, with no mod, joins a dedicated server that has the jar | all 67 servers of the table above | 1,204 of 1,206 |
| **The mod in the game:** a singleplayer world made in the game's own screens | 30: Minecraft 1.20.4, 1.20.6, 1.21.1, 1.21.4, 1.21.5, 1.21.8, 1.21.10, 1.21.11, 26.2 and 26.3, each on Fabric, NeoForge and Forge, so every one of the 29 jars was played | 540 of 540 |

The two cases that did not pass are both the last one of the list, on servers of Minecraft 1.21.9, and both are
described under [what the tests found in the loaders](#what-the-tests-found-in-the-loaders): NeoForge 1.21.9 writes
one error line of its own at every start, and the Forge 1.21.9 server did not exit after `stop`. Everything else
passed on both.

On three of the servers the plain game also logged that Mojang's account service refused it (HTTP 429, or an HTML page
where it expects an answer): the test games have no account and start many times a day. Those lines, like the ones
every game without an account writes, are not counted.

### Speed against 15.1

A whole run of a fresh world without other mods, with 15.1's jar and with 16's on the same server: seed 42, around
x = z = 100,000, a 16 GB heap, and nothing else running on the test server (a second program that looked for another
Java process every 15 seconds during the runs found none). Chunks per second over the whole run, as the mod reports
them at its end; where there are several numbers, the runs of the two jars took turns.

| Server | Area in blocks | 15.1 | 16 | |
|---|---|---|---|---|
| NeoForge 26.1.2, Overworld | 4,000 × 4,000 | 248.0, 247.8, 250.3 | 356.0, 357.7, 352.6 | **+43%** |
| the same runs after their first batch | | 260.4, 260.4, 263.9 | 388.0, 385.5, 380.5 | +47% |
| NeoForge 26.1.2, Nether | 2,000 × 2,000 | 358.6, 358.4 | 410.7, 385.2 | +11% |
| NeoForge 26.1.2, End | 2,000 × 2,000 | 782.7, 786.8 | 890.6, 882.8 | +13% |
| NeoForge 1.21.1, Overworld | 4,000 × 4,000 | 209.5 | 331.3 | +58% |
| Fabric 1.20.1, Overworld | 4,000 × 4,000 | 242.1 | 345.4 | +43% |
| Forge 1.20.1, Overworld | 4,000 × 4,000 | 231.7 | 329.8 | +42% |
| Fabric 26.2, Overworld | 4,000 × 4,000 | 258.5 | 328.3 | +27% |

The first batch of a run is mostly the Java compiler warming up, so a longer run gains more than a short one. On
NeoForge 26.1.2 the profiler counted 29% fewer samples of running code over the whole run (186,000 → 132,000): the
gain is work that is no longer done, not work moved to other cores.

16 on servers 15.1 was not measured on (Overworld, 4,000 × 4,000 blocks, one run each): NeoForge 1.21.11 339.7,
NeoForge 1.21.8 330.6, NeoForge 1.21.4 332.9, NeoForge 1.20.4 327.4, NeoForge 26.2 323.3, Forge 26.2 324.7,
NeoForge 26.3 266.0. Minecraft 26.3 rewrote terrain generation; its jars run Minecraft's own terrain step on this
mod's worker threads and have none of the terrain shortcuts of the other jars
([ports/fabric-26.3](ports/fabric-26.3/README.md)).

**The table above is of 16's first files, before the corrections of the edge, of the seams and of the mushrooms.**
The released files, the first files and 15.1 taking turns three times on the idle server (Overworld, 4,000 × 4,000
blocks, chunks per second):

| Server | 15.1 | 16's first files | **16 as released** | against 15.1 | against the first files |
|---|---|---|---|---|---|
| NeoForge 26.1.2 | 248.1, 248.2, 248.1 | 355.0, 364.1, 356.8 | **345.0, 345.8, 351.1** | **+40%** | -3.2% |
| NeoForge 1.21.1 | (209.5, above) | 325.2, 329.3, 324.9 | **315.3, 312.7, 316.0** | +50% | -3.6% |
| Fabric 1.20.1 | (242.1, above) | 348.8, 353.8, 349.5 | **330.4, 331.4, 326.6** | +36% | -6.1% |
| Prominence II, its server alone (4,096 chunks, twice) | (30.5 and 30.0, an earlier day) | 30.5, 30.7 | **29.3, 29.6** | | -3.8% |

The corrections cost 3% to 6%. On NeoForge 26.1.2 the profiler counts the same amount of running code with and
without them (135,217 samples against 134,755; features even fewer, light 6% more, saving 5% more): the time goes
into waiting, as each batch's end, which the next batch's features wait for, now also reads and lights the saved
chunks beside it again (about 2,060 of 62,500 in this run) and saves the 1,004 chunks around the area. The Nether,
the End and the other servers of the tables above were not measured again with the released files.

After a run the released files keep about 70 MB more of the heap in use than the first files (NeoForge 26.1.2 311
against 238 MB, NeoForge 1.21.1 377 against 303, Fabric 1.20.1 434 against 361); the gap is the same during the run
and for a run of 4 batches as of 16, so it does not grow with the area.

One change that takes back part of the cost was tried and left out of the release: unpacking those saved chunks on
all worker threads gave the same world chunk for chunk and 350.0, 348.8 and 353.8 chunks/s against 344.2, 341.1 and
347.4 in three alternating rounds on NeoForge 26.1.2 (+1.9%, about as much as one jar's rounds differ among
themselves). It was measured on that one server only and would have needed every world test again.

These are numbers of an old 20-thread server; the chart in the [README](README.md#speed) is from a desktop PC and
version 14, and has not been measured again. The runs above are of the last test build before the two late fixes;
the released files, measured after them with nothing else on the server: NeoForge 26.1.2 352.3 and 359.3, NeoForge
1.21.1 328.0, Fabric 1.20.1 349.2 chunks/s.

### The automated test suite with 16

`runPartialBatchCheck` and `runTerrainBenchmark` (the checks of the [automated test suite](#automated-test-suite))
on a real server of each source tree that has them, with the sources the released jars are built from: Fabric 1.20.1
(the tree of the Fabric 1.20.x jar), Forge 1.20.1, Fabric 1.21.1, NeoForge 1.21.1, NeoForge 26.1.2 and Fabric 26.2.
**Every check passes on all six:** 27 checks of `runPartialBatchCheck` (28 on the two 26.x trees, which also have
`LOD_EXPORT_CHECK`) and the 9 of `runTerrainBenchmark`. So do the 29 checks of the Fabric 26.3 tree, whose suite
is its own (26.3 has no fast terrain path to check, and two checks the others do not have yet: a storage task that
fails, and the heightmaps of a chunk a player visited; it has no `runTerrainBenchmark`); NeoForge 26.3 holds the
same code behind another entry point, and its suite was not run. The jars for Minecraft 1.21.2 to 1.21.11 are built
from the 26.x tree's sources with a layer for each group of versions, and NeoForge's for 1.20.2 to 1.20.6 from the
1.20.x tree's: they have no suite of their own and rest on the world comparisons above.

**Three checks came with the corrections of the edge,** on all seven trees:

| Check | What it requires | With the correction off | On |
|---|---|---|---|
| `EDGE_LIGHT_CHECK` | A 32 × 32 chunk area in four batches against the same chunks lit with all their neighbors: no chunk at the edge or where two batches meet holds wrong light. | 60 edge chunks and 50 seam chunks wrong (26.3: 54 and 67) | 0 and 0 |
| `EXTEND_LATER_CHECK` | An area that a later run extends is, block for block, the area made at once, the chunks around it included. | 26,092 blocks differ along the old edge and 45,280 in the chunks around (26.3: 26,804 and 47,251) | 0 and 0 |
| `FEATURE_LIGHT_CHECK` | Features find the light dim enough for brown mushrooms where Minecraft's own chunk does. | none placed in 1,024 chunks (26.3: 28) | 3,842 placed (26.3: 4,085) |

`EXISTING_NEIGHBORS_CHECK`, `RESUME_EDGE_CHECK` and `SAVE_FILE_RESUME_CHECK` now also cover the row of chunks
around the area (a resumed or repeated run must leave it as a run at once does).

**One check was new in 16's first files, `BLOCK_ENTITY_DATA_CHECK`,** written after the modpack tests found the empty block entities
([above](#block-entities-against-a-world-with-no-mod)): it reads the chunks a run saved and requires every monster
spawner to hold its mob (146 to 148 spawners in the 4,096 chunks, depending on the Minecraft version) and the chests
their loot tables (163 of 163 on 1.20.x, 191 of 191 on 26.x, 184 of 225 on 1.21.1, where the chests a trial chamber
keeps for its rewards have none in Minecraft's own world either). `BLOCK_ENTITY_PROMOTION_CHECK`, on the six trees
with `runTerrainBenchmark`, got the case of a block entity that generation made and then filled. With the fix taken
out of the NeoForge 1.21.1 tree again, both fail: "Every generated monster spawner must be saved with its mob: 0 of
147" and "Exactly the chest placeholder must be promoted, got 2".

The suite's first run on 16 failed, and found what no other test here could: **after a run ended, the mod kept that
run's last batch in memory**, about 1 GB with the default batch size where 15.1 keeps about 0.3 GB, until the next
run began or the server stopped. The suite runs dozens of pregens in one server and ran out of its 8 GB heap. The
steps that chain each chunk's terrain work, new in 16, were not let go with the batch. It was fixed before the
release (the 26.3 jars never had it). With the released files:

| After a 4,000 × 4,000 run | Heap left, 15.1 | Heap left, 16 before the fix | Heap left, 16's first files | Heap left, 16 as released |
|---|---|---|---|---|
| NeoForge 26.1.2 | 238 MB | 1,016 MB | 238 MB | 311 MB |
| NeoForge 1.21.1 | 304 MB | 1,117 MB | 303 MB | 377 MB |
| Fabric 1.20.1 | 362 MB | 1,183 MB | 361 MB | 434 MB |

The released files keep about 70 MB more than the first files, a fixed amount that came with the corrections of the
edge ([speed against 15.1](#speed-against-151)).

Each of the 29 files of 16's first set then started and pregenerated on a server it names (30 servers: the Forge
1.20 to 1.20.4 jar on Forge 1.20.1 and on NeoForge 1.20.1), on the fast paths and with every hook of the mod
called. The worlds of three servers were compared again with their reference worlds: on NeoForge 26.1.2 15,876 of
15,876 chunks are the same; on NeoForge 1.21.1 and Fabric 1.20.1 all but the chunks with a block entity that 15.1
saved empty (824 and 483).

The released files, which have the corrections of the edge and the Manager's Health tab, were started the same way:
each of the 29 on a server it names, the same 30 servers, a short pregen on the fast paths. All 30 ran; no hook of the
mod was left uncalled, the handler that gives features their light is in Minecraft's light engine on every one, and
no server logged a warning or an error of the mod. `ultrafastpregen-neoforge-1.20.1-16.jar`, added afterwards, is the Forge 1.20 to 1.20.4 jar with only the Minecraft range of its `mods.toml` changed to 1.20.1, every class the same; it was started the same way on NeoForge 1.20.1 (47.1.106) with the same result.

### With other mods

The sets of mods of [Together with other mods](#together-with-other-mods) again, with 16 on the same six servers and
the same mod files ([Tested mod files](#tested-mod-files)): a fresh world, `/pregen start 1` (4,096 chunks) around
x = z = 100,000 in the Overworld, the Nether and the End, every saved chunk read back, and the 324 chunks in the
middle compared with the same chunks Minecraft generated on that server with those mods. The numbers are chunks per
second, Overworld / Nether / End; ᵖ marks the sets that run Minecraft's per-status terrain path (the same sets as
with 15).

| Mods | Fabric 1.20.1 | Forge 1.20.1 | Fabric 1.21.1 | NeoForge 1.21.1 | Fabric 26.2 | NeoForge 26.1.2 |
|---|---|---|---|---|---|---|
| none | ✅ 215 / 338 / 985 | ✅ 206 / 326 / 890 | ✅ 200 / 289 / 991 | ✅ 205 / 284 / 934 | ✅ 190 / 357 / 672 | ✅ 191 / 302 / 707 |
| C2ME | ✅ 173 / 208 / 891 | | ✅ 151 / 349 / 715 | ✅ 153 / 323 / 682 | ✅ 178 / 384 / 506 | ✅ 207 / 387 / 656 |
| Lithium (Canary on Forge) | ✅ 228 / 358 / 1,009 | ✅ 208 / 330 / 979 | ✅ 203 / 356 / 982 | ✅ 196 / 352 / 1,025 | ✅ 177 / 310 / 604 | ✅ 194 / 282 / 752 |
| Lithium + Noisium | ✅ 123 / 269 / 725 ᵖ | | | ✅ 121 / 276 / 753 ᵖ | | |
| Performance | ✅ 121 / 232 / 762 ᵖ | ✅ 125 / 385 / 502 ᵖ | ✅ 118 / 309 / 810 ᵖ | ✅ 131 / 419 / 768 ᵖ | ✅ 197 / 317 / 762 | ✅ 199 / 299 / 884 |
| LoD (Distant Horizons) | ✅ 216 / 293 / 935 | ✅ 220 / 321 / 935 | ✅ 205 / 282 / 654 | ✅ 189 / 301 / 896 | ✅ 103 / 143 / 306 | ✅ 100 / 130 / 348 |
| Voxy | | | | | ✅ 188 / 296 / 525 | |
| Worldgen | ✅ 67 / 247 / 658 | ✅ 67 / 216 / 671 ᵖ | ✅ 70 / 275 / 307 | ✅ 69 / 292 / 313 | ✅ 119 / 309 / 259 | ✅ 84 / 262 / 276 |
| Everything together | ✅ 43 / 155 / 492 ᵖ | ✅ 59 / 253 / 487 ᵖ | ✅ 83 / 261 / 304 ᵖ | ✅ 93 / 321 / 324 ᵖ | ✅ 88 / 169 / 214 | ✅ 79 / 165 / 230 |
| Moonrise | | | ⛔ refused | ⛔ refused | ⛔ refused | ⛔ refused |

**132 of 132 runs passed** (44 sets on the six servers, three dimensions each): every chunk fully generated, lit and
readable, the safety net never used, and Moonrise refused as intended in all 12. Every set is on the terrain path it
had with 15, and the comparison with Minecraft's own chunks gives what it gave with 15 in all 132: the same biomes in
each of the 324 chunks. These runs were made with 16's first files.

**With the classes of the released files the matrix was run again:** 132 of 132 passed, every set on the terrain
path it had (99 runs on the fast path, 33 on Minecraft's per-status path), every chunk fully generated, lit and
readable, the safety net never used, and Moonrise refused in all 12. It ran beside other tests, so its speeds are not
listed, and the comparison of biomes with Minecraft's own chunks was not repeated.

One short run each, so a single number says little; against 15's runs of the same sets on the same server three
weeks earlier, the Overworld is faster in all 39 sets without C2ME, by 1% with a performance set on the per-status
path and by up to 53% alone. For the five C2ME sets those earlier runs had no C2ME (see the note above the tables),
so Fabric 1.20.1 with C2ME was run again with 16 and 15.1 taking turns, twice: Overworld 168 and 182 chunks/s
against 141 and 138, Nether 194 and 213 against 181 and 192, End 912 and 910 against 881 and 851.

### In the modpacks, with the pack's own game

All the Mods 10, All the Mods 9 and Prominence II on one PC (AMD Ryzen 5 7500F, 32 GB): the pack's own server with
the released 16 jar in place of the pack's Ultra Fast Pregen, and the pack's game as the player. The question is a
server owner's: **is a pregenerated world the world the pack would have made by itself, does a player see the same,
and does the server tick the same?**

The area is 24 × 24 chunks (576) around x = z = 3,072, seed 42. For each pack:

- **P**: `/pregen` generates the area and the server stops; nothing has loaded the chunks.
- **N** and **N2**: the pack's server generates the same area by itself (it is told to hold it), twice. What they
  differ in is what two runs of the pack differ in without any pregen.
- **N3**: the same with the rows asked for in the other order: what the pack differs in when the order of
  generation changes, as any pregenerator changes it.
- **P1**: P, loaded by the server the way N was and for as long.
- The pack's game then joins a copy of P, of N and of N2 in turn and stands in the middle of the area in spectator
  mode: the server is asked for its tick time six times, 10 s apart, and for each of the 121 chunks around the
  player whether it ticks; the game takes pictures of the same views in each world.

The packs do not generate the same world twice (see the rows "the pack against itself"), so every number of the
pregenerated world stands beside the pack's own.

| | All the Mods 10 | All the Mods 9 | Prominence II |
|---|---|---|---|
| Server | NeoForge 1.21.1, 470 mods | Forge 1.20.1 (47.4.0), 419 mods | Fabric 1.20.1, 365 mods |
| `/pregen`, 576 chunks | 10 s, fast terrain path | 11 s, fast terrain path | 12 s, fast terrain path, features one chunk at a time (`parallel_features=auto`, see below) |
| Blocks that differ, pregenerated against the pack's own | 603,992 | 804,431 | 186,858 |
| the pack against itself: a second run, the other order | 126,783 and 520,406 | 935,425 and 975,551 | 120,219 and 152,198 |
| Block entities that differ or are in one world only | 81 of 392 | 58 of 522 ¹ | 63 of 318 |
| the pack against itself | 69 and 74 | 60 and 62 | 57 and 57 |
| Tick time with the player in place, pregenerated / the pack's own | 3.55 ms / 3.33 and 3.37 ms | 9.44 ms / 9.27 and 9.67 ms | no game ²; the server alone, holding the area: 2.67 ms / 2.55 and 2.60 ms |
| Chunks around the player that tick | 121 of 121 in each world | 121 of 121 in each world | all 576 held chunks in each world |
| The game | joined in 147 to 167 s, never disconnected | joined in 106 to 118 s, never disconnected | ² |
| Warnings or errors naming this mod, server and game logs | none | none | the version text ³ |

¹ Without the id Lootr draws for each container, which is random in every world; with it 222, and the pack against
itself 225 and 223.
² Prominence II's game could not be put together from the pack's server files: it starts, joins, and drops the
connection on an error of its own ("Fluid create:tea had invalid rendering data"); the pack's launcher files were
not downloaded for this test. Its server was measured alone, holding the area. (Later they were: see [below](#prominence-ii-again-with-the-files-as-released-and-the-packs-own-game).)
³ At each start of a Fabric server, Fabric Loader warns that the version text of the 1.20.x jar, `1.20.x-16`, is not
a semantic version ("Could not parse version number component 'x'"), and one of the pack's mods logs that it cannot
parse it. Nothing depends on it; 15.1's `1.20.x-15.1` was the same.

**The rules, and what they gave.** The rules were written into the test script before the first run: for each kind
of thing a chunk stores, the pregenerated world may differ from the pack's own in at most twice the chunks that the
pack's two runs differ in, plus 2% of the area; a view in 3% of its pixels, or twice the pack's own; the tick time
at most 1.25 times the pack's plus 2 ms; no warning or error naming this mod. **They were changed after results,**
and the script keeps the dates and the reasons: the pack's world in the other order joined its second run as the
measure, and block entities got a rule of their own (after All the Mods 10's first run, with the files before the
fix); what a chunk's file says about the server's last write of it is judged where the player has been (after All
the Mods 10's leftover lists, and again for Blood Magic's tag once All the Mods 9's worlds were compared); five
views from lower down were added (All the Mods 10's one picture from high above shows mostly haze); and whether
chunks tick is asked from the server instead of read from lists left on disk (after All the Mods 9's first play).
The table has the verdicts under the rules as first written beside those under the rules as they stand.

| Verdict | All the Mods 10 | All the Mods 9 | Prominence II |
|---|---|---|---|
| chunks, as first written | fail: blocks, light and structures beyond the pack's two runs of one order; leftover lists and tags | fail: sky light, leftover lists, Blood Magic's tag | pass |
| chunks, as the rules stand | pass | **fail: sky light at the edge of the area** (117 chunk sections against 25) | pass |
| block entities | pass | pass | pass |
| what the player sees | pass (6 of 6 views) | **fail: 1 of 6 views**, a coral reef, 3.1% against a limit of 3.0% | not run ² |
| ticks, as first written | pass | fail: 5 chunks with a list left on disk | not run ² |
| ticks, as the rules stand | pass | pass | pass for the server alone |
| logs | pass | pass | **fail: the version text** ³ |

**What a player sees.** Pictures from the same six places in each world, compared pixel by pixel (the daylight
cycle stopped at noon). The rule set before the runs: a view of the pregenerated world may differ from the pack's
own in 3% of its pixels, or in twice what the pack's two worlds differ in.

- **All the Mods 10:** 0.06% from high above (the pack's own two worlds: 0.02%), and 0.27% to 0.64% in the five
  views from lower down (0.10% to 0.22%): a sea with tall iron structures standing in it.
- **All the Mods 9:** nothing from high above, where both worlds show only the sea's haze. From lower down 1.8% to
  9.1% (the pack's own: 0.9% to 5.7%; a reef's corals, sea plants and the water's surface are not the same twice).
  Four of the five views are within the rule; the view over a coral reef is 3.1% against 1.0%, over the limit by
  0.1. Corals are among the features whose outcome depends on the order of generation.
- **Prominence II:** no pictures ².

**Containers and spawners.** With the files before the block entity fix, All the Mods 10's pregenerated world had no
loot table in any container, and Lootr, which turns containers with a loot table into its own, turned none. With
the released files Lootr converted them when the server loaded the world: 120 chests, 61 barrels and 15 suspicious
sand blocks, each with its loot table, against 120, 64 and 10 in the pack's own world (119, 64 and 15 in the other
order). In All the Mods 9 all 83 barrels and 83 of 92 chests hold the same as the pack's own (the pack against
itself: 83 barrels, and 86 and 83 chests), and all 27 spawners the same mob.

**Where the pregenerated world is not the pack's own, in all three packs:**

- **Structures in All the Mods 10** differ in 76 chunks, and in 83 between the pack's own two orders (in none
  between its two runs of one order): the pack's Overlapless mod lets the structure generated first keep a place,
  so the set depends on the order of generation.
- **The outermost chunks of the area** lack what features of the chunks outside bring in: in All the Mods 9 part
  of an amethyst geode (2,363 amethyst blocks against 3,185 in each of the pack's three worlds, the difference in
  two edge chunks) and a clay disk; in Prominence II parts of geodes again (amethyst 7,471 against 8,145 to
  8,291). Every chunk further in has the pack's count. See
  [the edge of the area](#blocks-against-a-server-with-no-mod-and-the-edge-of-the-area).
- **Sky light in All the Mods 9** differs in 117 chunk sections against 25 between the pack's own worlds; 92 of them
  are in the outermost chunks, most under a tall structure that crosses the edge (the same section).
- **What a chunk's file says about the server rather than the world** differs after the server has loaded a
  pregenerated area: 13 chunks of All the Mods 10 and 20 of All the Mods 9 still held their list of blocks to
  update on first load, and some scheduled ticks and data other mods attach. The server had ticked them: All the
  Mods 10's answered for each of the 576 chunks that it ticks it, in the pregenerated world as in its own, and the
  same 13 chunks held the list after 2 minutes as after 10. A server writes a chunk again only when something in it
  changes, and those it had saved before it ticked them. Where the player had been, the files of both worlds agree.

**`parallel_features=always` gives another world in a pack with BCLib.** Prominence II's test folder carried that
setting from an earlier test, and the first run used it: features ran on all threads although BCLib and one more
mod keep state in feature placement, as the start-up log says. Against the pack's own world, 5.7 million blocks
differed (the pack against itself: about 120,000), and features of several mods came out far rarer or in other amounts:
Mythic Upgrades' aquamarine crystal 341 blocks against 5,032 in each of the pack's three worlds, Create's scoria
4,633 against about 40,200, amethyst 5,645 against about 8,200, and 42 beds against 55. With the default, `auto`,
the same pack gives the table's numbers. `always` is faster there (56.7 against 45.1 chunks/s on this PC, 56 against
30.5 on the 20-thread test server) and is not the world the pack's server generates.

**Not the mod's, seen in these tests:** Prominence II's server does not exit after `stop`: it saves, and a thread of
one of the pack's mods keeps the process (with this mod idle too; none of its threads is left). All the Mods 10's
worlds held about 12,800 region files after the game had joined, the pack's own worlds as much as the pregenerated
one (8 before), nearly all of them empty: something in the pack reads chunks far away once a player has joined.

On the test server (the Xeon of the other tests), the packs' own servers ran `/pregen start 1` (4,096 chunks) with
16 and with 15.1, two rounds, the jars taking turns; every chunk of the area was read back, and no batch fell back
from the fast paths:

| Modpack | 16, chunks per second | 15.1 |
|---|---|---|
| All the Mods 10 | 63.4 (and 52.1 in a run that shared the server with another test) | 61.2 and 62.9 |
| All the Mods 9 | 67.2 and 68.4 | 63.6 and 61.0 |
| Prominence II, `parallel_features=always` | 55.5 and 56.5 | 57.5 and 56.9 |
| Prominence II, the default `auto` | 30.5 and 30.6 | 30.5 and 30.0 |

In these packs other mods change most of the terrain code that 16's new steps stand in for, so those steps turn
themselves off, as 15's did: the gain of the plain servers does not carry over (about 8% in All the Mods 9, within
a run's variation in the others).

**Link and the Manager in the packs** (the released jar with both parts on, on copies of the packs' servers): in all
three packs the Manager's page works and a run started from it completes with every one of its 4,096 chunks full,
`/ufplink pregen` in the Overworld, the Nether and the End generates in a separate world, merges it and deletes it,
and no warning or error in the logs names the mod. **A host and a client of the same pack do not connect in any of
the three.** The host refuses the client: by its check the client's generator gives other terrain or biomes at the
places it probes (all three packs), and in All the Mods 10 one datapack's name also differs, because the pack's
OpenLoader names it by the server's folder, which no two installations share. Link 0.3.0 gave the same refusals in
these packs. Whether the probes differ because these packs' generators differ from start to start, as their worlds
do, or because of what the check reads, was not looked into; as it is, Link's host and client cannot be used
between two servers of such a pack, and `/ufplink pregen` on one server can.

#### Prominence II again, with the files as released and the pack's own game

After the corrections of the edge the test was run again for Prominence II, this time with the pack's game as
CurseForge ships it (the launcher pack's configuration and its 214 resource packs laid over the mods of the server
pack; the Essential mod left off, which opens with a terms screen). **All the Mods 10 and All the Mods 9 were not
run again: their columns above are of 16's first files,** which made the edge of an area as 15.1 does.

| Prominence II | 16's first files (above) | **16 as released** |
|---|---|---|
| `/pregen`, 576 chunks | 12 s, 45.1 chunks/s | 12 s, 44.9 chunks/s |
| Blocks that differ, pregenerated against the pack's own | 186,858 | 181,987 |
| the pack against itself: a second run, the other order | 120,219 and 152,198 | 110,502 and 160,036 |
| Block entities that differ or are in one world only | 63 of 318 | 67 of 317 |
| the pack against itself | 57 and 57 | 64 and 63 |
| Amethyst blocks in the area (geodes reach in from outside) | 7,471 | 8,196 |
| in the pack's own three worlds | 8,145 to 8,291 | 8,177 to 8,291 |
| Tick time with the player in place, pregenerated / the pack's own | no game | 3.70 ms / 3.43 and 3.75 ms |
| Chunks around the player that tick | | 121 of 121 in each world |
| The game | did not stay connected | joined in 59 to 64 s, never disconnected |
| Pixels that differ in the same view, pregenerated against the pack's own / the pack against itself | | from above 0.5% / 0.9%; north 3.2% / 2.4%; east 7.1% / 5.4%; south 3.4% / 3.9%; west 0.8% / 0.8%; down 4.8% / 3.6% |
| Warnings or errors naming this mod, server and game logs | the version text | none |

Every rule passes, as the rules stand now and as they were first written. The geodes that reach into the area from
outside are whole (amethyst, calcite and smooth basalt within the pack's own range; they were short with the first
files). Two counts stay outside the pack's own range exactly as they were with the first files, both of the kind
that depends on the order in which chunks are decorated: Create's veridium (17,375 blocks against 16,727 to 16,840)
and the sky stone of one Applied Energistics meteorite at the area's edge (1,092 against 778 to 845).

### What the tests found in the loaders

- **Forge 1.20.6 to 26.3 keeps a generator's feature list in a holder of its own** that answers null to every thread
  but one when several ask it for the first time at once. Minecraft's own generation rarely does; this mod's features
  run on every core, so the first Nether or End batch after a server start failed there and was made again on
  Minecraft's own steps (the world was right, the batch slow). Version 16 asks once itself before every run.
- **Forge 26.1 to 26.3 reads one mixin annotation in a form of its own;** the Forge jars are built in that form.
- **Forge's game opens with a warning screen for a mod jar that has no `pack.mcmeta`** ("failed to load a valid
  ResourcePackInfo"; a server says nothing). The Forge jars carry one.
- **NeoForge 1.21.9 (21.9.16-beta) writes one error line at every start,** before anything loads: `Unable to create
  custom ContextSelector. Falling back to default. java.lang.ClassNotFoundException:
  net.neoforged.fml.log.MLClassLoaderContextSelector`. A server with no mod at all writes it too; NeoForge 1.21.10
  does not.
- **Forge 1.21.9 (59.0.5) does not exit after `stop`** on this test server, with no mod at all: a fresh world, 16
  chunks held for a while, `stop`. The log ends with "All dimensions are saved" and the process is still there 300
  seconds later, both times of two, and the same with this mod installed. Forge 1.21.10 exits after 5 seconds (4
  with the mod), Fabric 1.21.9 after 3. A server run by a script that waits for the process needs a time limit there.

### What was not tested with 16

- **The tests with a player were not played again after the last two changes** (one line each, for the memory a
  finished run kept and for the block entities): the 67 servers and 30 games ran the build before them. The three
  modpack games ran the released files.
- **The regression suite runs on seven source trees.** NeoForge 26.3's was not run (the same code as Fabric 26.3
  behind another entry point); the jars for Minecraft 1.21.2 to 1.21.11 and NeoForge's for 1.20.2 to 1.20.6 have no
  suite of their own and rest on the world comparisons.
- **Other mods:** on the six servers of version 15's tests only, not on Minecraft 1.21.2 to 1.21.11 or 26.3, and not
  on Forge from 1.20.6 on.
- **Modpacks:** three packs, one area of 576 chunks each for the comparison with the pack's own world, one player
  standing in one place. The games were built from the packs' server files, so they lack what only the packs'
  launcher files hold (Prominence II's resource packs, among them its fonts and menus).
- **The edge of a pregenerated area** with the corrections of 16 was measured on five servers with no other mod,
  three of them against three worlds of a server with no mod, and in Prominence II. Not with another light engine
  installed (Starlight, ScalableLux), and not in All the Mods 10 or All the Mods 9: those two packs were tested
  with 16's first files only, which made the edge as 15.1 does.
- **The three corrections one by one:** they were tested all on and all off; no world was made with one of them
  alone.
- **Speed on a desktop PC:** every number of 16 is from one old 20-thread server. The chart in the README is version
  14 on a Ryzen 5 7500F and was not measured again.
- **The Bukkit and Sponge builds** have no version 16. Their sources got the block entity line too; the released
  15.1 plugins are for Minecraft 26.2, where it changes nothing, and were not built or tested again.
- **Link between machines:** host and client ran on one machine, connected through its own network address.
- **Long use:** no server ran for days, and no run was larger than 4,000 × 4,000 blocks.

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
- **The edge of an area made by 15.1 or older, or by 16's first files:** wherever the server has generated beyond
  such an area by itself, the area's outermost chunks lack the parts of features that belong to chunks outside and
  reach in (part of a geode, a disk of sand or clay, seagrass, the leaves of a tree that stands just outside), and
  roofed spaces within 13 blocks of the edge can be lit brighter than a server would light them. 16 generates the
  edge as Minecraft does, and the same `/pregen start` again with 16 adds what is missing where the server has not
  yet gone beyond the area ([measured](#with-the-corrections-of-the-edge)). Left with 16: tall seagrass in the
  outermost chunks stays 2% below a server's own on two of three servers
  ([there](#blocks-against-a-server-with-no-mod-and-the-edge-of-the-area)).
- **A pregenerated chunk that no server has loaded yet** holds what the first tick of a chunk removes or settles:
  the brown and red mushrooms that stand in daylight (about 25 times as many as stay), fluids that have not flowed.
  A chunk Minecraft generated and never ticked is the same. Map and LoD mods that read pregenerated chunks see that
  state ([measured](#mushrooms-and-what-grows-beside-them)).
- **Where an area is extended later, or two Link tiles meet,** the blocks that depend on the order in which
  neighboring chunks are decorated (stone kinds where blobs overlap, leaves, seagrass, coral) differ from those of
  one run over the whole area, in about 2% to 5% of the chunks; no kind is short, and the light is the same
  ([extended areas](#with-the-corrections-of-the-edge), [Link](#link-where-two-tiles-meet)).
- **Against a server's own world of the same seed, single blocks differ** where features of neighboring chunks
  overlap, as they do between two runs of a server with no mod. On one of three servers tested (Fabric 1.20.1), the
  stone kind placed later won such overlaps more often than in a server's world (1% more andesite); why was not found
  ([the same section](#blocks-against-a-server-with-no-mod-and-the-edge-of-the-area)).
- **Link's host and client between two servers of a large modpack:** refused by the host's own check in All the
  Mods 10, All the Mods 9 and Prominence II ([there](#in-the-modpacks-with-the-packs-own-game)). `/ufplink pregen`
  on one server works in all three.
- **What other mods add to a chunk when the server saves it** (on Forge and NeoForge through the loader's chunk save
  event) is not in a chunk the pregen wrote until the server has loaded that chunk and saved it itself: the pregen
  writes chunks with Minecraft's own serializer and does not post that event. In All the Mods 10 this is Mekanism's
  `mek_world_gen_version` tag, missing in all 576 pregenerated chunks of the [test](#in-the-modpacks-with-the-packs-own-game)
  and present in every one after the server had loaded the area. Mekanism uses the tag for its ore retrogen, an
  option in its config.
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

Version 16, the 29 mod jars: [Version 16](#version-16). The tests ran on builds of these files made while the
round went on. Most of them (the 67 servers, the player tests, the first other-mods matrix, All the Mods 10 and All
the Mods 9) ran on the builds `16-dev35` to `16-dev42` and on a first set of release files built from `16-dev42`,
which was not published. The corrections of the edge, of the seams and of the mushrooms came after that set (builds
`16-dev43` and `16-dev44`); what this document reports with them ran on `16-dev44`: the suite, the 30 servers the
files were started on, the worlds with the corrections off and on, the scenarios, the other-mods matrix again, Link's
seams and Prominence II. **The released files hold the classes of `16-dev44` that generate the world, entry for
entry.** They differ from that build in the file that carries the version text (`fabric.mod.json`, `mods.toml` or
`neoforge.mods.toml`) and in the Manager's Health tab, which came last (five classes of the Manager and the page's
three files) and was tested with the released files themselves, as were the parts' switches and the start of each
file on a server. Not done again with the released classes: the player tests of the 67 servers and 30 games, and
the two All the Mods packs.

| File | SHA-1 |
|---|---|
| `ultrafastpregen-fabric-1.20.x-16.jar` | `6312d6a775a3c2601e1e721e8c95d80763e1cb4b` |
| `ultrafastpregen-fabric-1.21-1.21.1-16.jar` | `d42478275162574b61d960c62122add0621f2c86` |
| `ultrafastpregen-fabric-1.21.11-16.jar` | `aa7abb866ee21520d82a62eaea422ebdbfe369af` |
| `ultrafastpregen-fabric-1.21.2-1.21.4-16.jar` | `2ecbf86043b88916a6cce6a99c51763d9dfbabca` |
| `ultrafastpregen-fabric-1.21.5-16.jar` | `e0ae55d8b36a7a9e5a272effea4e373e8a7b63e5` |
| `ultrafastpregen-fabric-1.21.6-1.21.8-16.jar` | `c19cf2d2758481019547acaba3983f868af446ea` |
| `ultrafastpregen-fabric-1.21.9-1.21.10-16.jar` | `4ba4dd391106e13e564c91d50a8371d6493fdadd` |
| `ultrafastpregen-fabric-26.1-26.2-16.jar` | `810136870be3e29630d74968e9d2b7e2e3ffbccf` |
| `ultrafastpregen-fabric-26.3-16.jar` | `5e44339e201cab91864fe13aeb43fdcd610058bc` |
| `ultrafastpregen-forge-1.20-1.20.4-16.jar` | `cd414f93d4b18dc3cad44cc69038743745e43aa9` |
| `ultrafastpregen-forge-1.20.6-16.jar` | `6be6a36a57784faa07ec7556b9bb2175c840fdfa` |
| `ultrafastpregen-forge-1.21-1.21.1-16.jar` | `40337e71f3b9c5764dcb344019c7e6af34214614` |
| `ultrafastpregen-forge-1.21.11-16.jar` | `d219a029cd05890cef9c1bb8edb14403afad2f0a` |
| `ultrafastpregen-forge-1.21.3-1.21.4-16.jar` | `b2ec7689dbc61d535a1fcfd159aeed2e84b53aa5` |
| `ultrafastpregen-forge-1.21.5-16.jar` | `c7a771fdd8d45edd0b2e31e325bd3d5a980404e9` |
| `ultrafastpregen-forge-1.21.6-1.21.8-16.jar` | `1923214dbcdc9c1bc414f2f33b0aaf721b9692d2` |
| `ultrafastpregen-forge-1.21.9-1.21.10-16.jar` | `1131f75e980d24f503dcb6f1af93974b5477824a` |
| `ultrafastpregen-forge-26.1-26.2-16.jar` | `6824e2c503ade8bb1dc70ceeda24f445e99be62b` |
| `ultrafastpregen-forge-26.3-16.jar` | `92d2f489eeb971c9cfc27ccd54510b2517990e51` |
| `ultrafastpregen-neoforge-1.20.1-16.jar` | `080bc85ed8487b828c48d53a0c1bfc89ed30f9fb` |
| `ultrafastpregen-neoforge-1.20.2-1.20.4-16.jar` | `e22d70ad712e2ba87bc26d810325f90e243d5988` |
| `ultrafastpregen-neoforge-1.20.5-1.20.6-16.jar` | `67c892749c29437981defed19490dfb1331a23c8` |
| `ultrafastpregen-neoforge-1.21-1.21.1-16.jar` | `40c8942af23340a33b7264e41f10e1ab676e153d` |
| `ultrafastpregen-neoforge-1.21.11-16.jar` | `db52cf71027dc52e8971bae95b409ddaeda7755e` |
| `ultrafastpregen-neoforge-1.21.2-1.21.4-16.jar` | `8ad28ea9a3af9b9da69810de3d7c2f85e5be00dd` |
| `ultrafastpregen-neoforge-1.21.5-16.jar` | `6b839d2cb4eb43b825ac6475a5346c35560bed93` |
| `ultrafastpregen-neoforge-1.21.6-1.21.8-16.jar` | `3a9046b0370f6b4872e64349477238d46a178144` |
| `ultrafastpregen-neoforge-1.21.9-1.21.10-16.jar` | `84eb3b858ff5dd8a594b878848e116cb4a53c675` |
| `ultrafastpregen-neoforge-26.1.2-26.2-16.jar` | `801f6fa2d2c88bf376e8740745cb74fdeacf7335` |
| `ultrafastpregen-neoforge-26.3-16.jar` | `466d8371566a91d4ec68b935a0ad9c92b9787de4` |

Version 14.1 (the released jars), for the modpacks' comparison.

| File | SHA-1 |
|---|---|
| `ultrafastpregen-fabric-1.20.1-14.1.jar` | `0516137f57c868553d39ff79d87c518f2daf7ba1` |
| `ultrafastpregen-forge-1.20.1-14.1.jar` | `4e445a797c2fe97c0dcf7bc018f5371ed799bf2e` |
| `ultrafastpregen-neoforge-1.21.1-14.1.jar` | `a2aa6f0baa11a51dc3b1c5aa2979bb682f29dc7a` |
