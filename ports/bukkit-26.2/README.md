# Ultra Fast Pregen for Bukkit servers (Minecraft 26.2)

One plugin jar for **CraftBukkit, Spigot, Paper and Folia** on Minecraft 26.2, with the commands, the config file and the
save file of the mod: see the [main README](../../README.md).

- **Requires:** a CraftBukkit, Spigot, Paper or Folia 26.2 server and Java 25
- **File:** `build/libs/ultrafastpregen-bukkit-26.2-15.1.jar`; `15.1` is the update round, the same for every build
- **Install:** put the jar in the server's `plugins` folder; players do not need anything
- **Permission:** `ultrafastpregen.command` for `/pregen` and for seeing a run's boss bar (operators have it by default);
  the console can always use `/pregen`
- **Config file:** `plugins/UltraFastPregen/ultrafastpregen.conf`, with the mod's keys except `lod_export`;
  `terrain_pipeline` takes `auto` or `vanilla` (see `parallel_features` below)

## How it differs from the mod

A plugin cannot change Minecraft's code, which the mod does with mixins. The plugin calls Minecraft's own world generation
directly instead (Minecraft 26.x is not obfuscated, so the plugin finds it by name):

- **Terrain:** Minecraft's own biome and noise steps, run on the worker threads. The mod's fast terrain path (the fused
  pass, the cell-skipping fill and the surface shortcuts) replaces Minecraft code and is not in the plugin. Structures,
  caves, features, light and saving run on every core as in the mod. On the same machine the plugin generates the
  Overworld about 20 to 30% slower than the mod (see the tests below).
- **Chunks the server holds** when a batch reaches them (around players, force-loaded chunks): fully generated ones are
  kept, as in the mod; part-generated ones are left to the server, where the mod generates them too. The run's summary
  counts them, and `/pregen resume` generates them once the server has unloaded them. While a run goes on, the server does
  not pause when empty, so it unloads chunks it loaded meanwhile (mobs looking for a village or a swamp hut load chunks
  for a moment); a batch waits up to 10 s for those before it is lit and saved.
- **Paper and Folia** replace Minecraft's chunk system with their own (Moonrise): the plugin reads and writes chunks
  through Paper's region file IO. Paper keeps light in its own format and lights chunks again the first time it loads
  them, as it does for chunks written by other tools; the chunks also hold Minecraft's light, for other servers and tools.
- **Folia** has no main thread: a run's progress, boss bar and continuing after a restart go through its global region.
- **Worlds with a plugin's chunk generator** take Minecraft's per-status steps. **Block populators** of other plugins
  run inside the feature step; Minecraft runs that step one chunk at a time and populators may not expect more, so
  features then run one chunk at a time too (`parallel_features=auto`, the default; `always` runs them on all threads).
  Neither has been tested yet.
- **End cities, Nether fortresses and strongholds:** Minecraft lays them out with code that keeps its state in static
  fields. The plugin lays out the ones it generates one at a time (since 15.1 also End cities and fortresses); one that
  the server lays out for a player at the same moment is not covered, since the plugin cannot change that code. The
  mods lock that code itself, which covers both.
- **Not in the plugin:** sending saved chunks to Distant Horizons or Voxy (mods).
- **API:** other plugins can start, stop and follow runs ([API.md](../../API.md), "Setup for plugins").

## Paper and Folia compared with Spigot

The plugin generates with the server's own code, so its worlds follow the server's own changes and settings. Compared
block for block at x = z = 100,000 (3,844 chunks per dimension), the plugin's worlds on Paper and Folia are the same as
each other, and differ from its worlds on Spigot here:

- **The End:** 113 blocks of end stone in 110 chunks. Paper's default `misc.fix-far-end-terrain-generation: true`
  (Minecraft bug MC-159283) changes the shape of the End's outer islands far from the center.
- **The Overworld and the Nether:** single blocks of features in 813 Overworld chunks (18,512 blocks: leaves of mangrove
  and other trees, vines, ore and mineral blobs) and 418 Nether chunks (4,790 blocks: basalt, blackstone, wart blocks).
  This is not from placing features on many threads: one chunk at a time, the plugin gives the very same blocks on each
  server. Paper changes much of the server code that features run on; which of its changes does this has not been
  traced. Paper's and Spigot's own generation differ from each other too (in 127 of 196 chunks compared), as features
  that cross chunk borders depend on the order in which a server generates chunks.
- The light differs only next to these blocks.

## Tests (Minecraft 26.2)

CraftBukkit and Spigot 26.2 (BuildTools, 4648-96ae8cd), Paper 26.2 build 134 and Folia 26.2 build 7, on a test server
with an Intel Xeon E5-2670 v2 (10 cores / 20 threads) and a 10 GB heap; a fresh world with seed 42, around
x = z = 100,000.

**15.1** (the jar with SHA-1 `60314c701c76077eaf9fb42ae67079a96c5442a4`), on the same servers:

- **1,000 × 1,000 blocks in each dimension:** every chunk fully generated, lit and readable, no error in the log;
  Overworld, Nether and End in chunks/s: CraftBukkit 117.7, 237.5, 468.5; Spigot 116.8, 240.6, 494.6; Paper 134.2,
  325.4, 731.7; Folia 143.9, 327.4, 752.2.
- **Stopping the server during a run** (3,000 × 3,000 blocks, stopped after 3 of 9 batches): the run continued after the
  restart, and all 35,344 chunks were generated (Spigot 141.1, Paper 155.5, Folia 153.5 chunks/s).
- **The End over 3,000 × 3,000 blocks**, twice with the 15 jar and twice with 15.1 on Spigot: the two worlds of each
  pair were the same block for block, and the 10 End cities had the same pieces with both jars.
- **All three dimensions in one server**, three times with the 15 jar and three times with 15.1 on Spigot: the End city
  at chunk (6224, 6222) had its 88 pieces every time. The End city race fixed in 15.1
  ([changelog](../../CHANGELOG.md#151)) did not show with the 15 plugin in these tests, so they do not show the fix at
  work; it showed in the tests of the mods on Fabric 1.21.

**15** (the jar with SHA-1 `c13d8f059539aa04628db6f7282e9ffa624ba9d6`), the tests below:

- **Compared with the mod:** on Spigot, block for block against the Fabric 26.2 build in the Overworld, the Nether and
  the End (1,000 × 1,000 blocks each, 3,844 chunks per dimension), the Nether and the End are identical, and in the
  Overworld only 10 chests of 5 shipwrecks differ on disk: they are stored without water (`waterlogged=false`).
  CraftBukkit sets a structure's loot chests again from their block entity, which still holds the chest as it was
  before the water; the water flows back in when the server loads the chunk, as it does in Spigot's own generation,
  where the chunks are loaded before they are first saved. Loaded on Spigot, all 10 chests had their water again. The
  light is identical. On CraftBukkit every chunk has the same block types, biomes and light as on Spigot (not compared
  block for block). On Paper and Folia the worlds are the same as each other and differ from Spigot's as described
  above.
- **Speed** in chunks/s, the same areas, and the Overworld over 2,000 × 2,000 blocks (15,876 chunks in 4 batches); one
  run each, and a run's speed varies by up to about 10%:

  | Server | Overworld | Nether | End | Overworld, 2,000 × 2,000 |
  |---|---|---|---|---|
  | CraftBukkit | 119.5 | 244.9 | 435.4 | 139.4 |
  | Spigot | 119.1 | 243.7 | 485.5 | 149.3 |
  | Paper | 140.8 | 333.1 | 748.1 | 157.4 |
  | Folia | 144.6 | 334.4 | 744.3 | 164.5 |
  | *The Fabric 26.2 mod* | *137.5* | *229.8* | *551.1* | *201.0* |

  In the Overworld over 2,000 × 2,000 blocks the plugin is 18% (Folia) to 31% (CraftBukkit) slower than the mod, whose
  fast terrain path it does not have. Paper and Folia generate the Nether and the End faster than the mod; which of
  their changes does this has not been traced.
- **Stopping the server during a run** (3,000 × 3,000 blocks, stopped after 3 of 9 batches): the run continued after the
  restart, and all 35,344 chunks were generated (Spigot 137.2, Paper 152.7, Folia 151.3 chunks/s).
- **Chunks the server holds** (a force-loaded square of 256 chunks in the middle of a 1,000 × 1,000 run): the 400 it held
  fully generated were kept, 2,332 chunks generated, and the 1,364 it held part-generated were left to it. After the
  square was released (`forceload remove all`), `/pregen resume` generated those 1,364, so all 4,096 chunks are fully
  generated (Spigot, Paper, Folia).
- **Paper's light:** Paper lit all 64 chunks of a square loaded after a run again with its own light engine, as it does
  for chunks other tools write; they carry Paper's light format afterwards.
- **A batch whose lighting and saving fails** (batch 2 of a 2,000 × 2,000 run made to fail once): it was generated again
  and the run completed, with the same block types, biomes and light in every chunk as an undisturbed run (Spigot,
  Paper, Folia).
- **Minecraft's per-status terrain steps** (which worlds with a plugin's chunk generator take; here asked for through
  the API): the same block types, biomes and light in every chunk as the default path, at 151.0 (Spigot) and 167.3
  (Paper) chunks/s over 2,000 × 2,000 blocks.
- **Structures, caves and features one chunk at a time** (`parallel_steps=false`, the order Minecraft runs them in): the
  same blocks as on all threads, compared block for block on Spigot and Paper (Overworld and Nether).
- **The API from another plugin** (Spigot, Paper, Folia): a run started, a second start refused, status, a stop at a fifth
  of the run, a resume from the finish event, and the run completed with all 15,876 chunks; the events counted every
  saved chunk.
- **In the game** with a player on each of the four servers, on Folia flying through the area while it generated.
- **Not tested yet:** worlds with a plugin's chunk generator, other plugins' block populators, together with other
  plugins, and Paper's world settings other than the defaults.

## Building (from the main project folder)

```powershell
.\gradlew.bat -p ports/bukkit-26.2 build
```

The plugin compiles against Spigot's server, which only BuildTools produces: run `java -jar BuildTools.jar --rev 26.2
--compile craftbukkit,spigot`, which installs `org.spigotmc:spigot:26.2-R0.1-SNAPSHOT` into Maven's local repository,
where the build looks for it. If BuildTools installed it elsewhere, put `spigot_repo=<path of that repository>` in
`ports/bukkit-26.2/local.properties` (not committed) or pass `-Pspigot_repo=<path>`. Gradle downloads JDK 25 by itself
the first time.
