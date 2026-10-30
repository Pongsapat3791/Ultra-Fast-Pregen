# Changelog

## 15.1

Each mod jar now runs on every Minecraft version in its name, and End cities and Nether fortresses are laid out as
Minecraft lays them out, in every build including the Bukkit and Sponge plugins.

| Jar | Minecraft | The 15 jar |
|---|---|---|
| `ultrafastpregen-fabric-1.20.x-15.1.jar` | 1.20 to 1.20.6 | 1.20.1 |
| `ultrafastpregen-forge-1.20-1.20.4-15.1.jar` | 1.20 to 1.20.4 | 1.20.1 |
| `ultrafastpregen-fabric-1.21-1.21.1-15.1.jar` | 1.21 and 1.21.1 | 1.21.1 |
| `ultrafastpregen-neoforge-1.21-1.21.1-15.1.jar` | 1.21 and 1.21.1 | 1.21.1 |
| `ultrafastpregen-neoforge-26.1.2-26.2-15.1.jar` | 26.1.2 and 26.2 | 26.1.2 |
| `ultrafastpregen-fabric-26.1-26.2-15.1.jar` | 26.1 to 26.2 | 26.2 |
| `ultrafastpregen-bukkit-26.2-15.1.jar` | 26.2 (CraftBukkit, Spigot, Paper, Folia) | 26.2 |
| `ultrafastpregen-sponge-26.2-15.1.jar` | 26.2 (SpongeVanilla) | 26.2 |

Not supported yet: Minecraft 1.21.2 to 1.21.11, which changed chunk saving, chunk generation and more from release to
release, and 26.3, which rewrote the terrain generation itself. Each needs builds of its own. Forge 1.20.6 runs on
Mojang's names instead of the SRG names the Forge jar is built with, and there is no Forge for 1.20.5. NeoForge exists
for 26.1 and 26.1.1 only as betas, which bundle MixinExtras 0.5.3: it fails on this mod's `@Redirect` mixins (the 15
jar too), where the 0.5.5 of NeoForge 26.1.2 and 26.2 reads them.

- **Fabric, one jar for Minecraft 1.20 to 1.20.6:** `ultrafastpregen-fabric-1.20.x-15.1.jar` replaces
  `ultrafastpregen-fabric-1.20.1-15.jar`. It needs Java 17 or later, as before; Minecraft 1.20.5 and 1.20.6 themselves
  need Java 21.
- **Fixed: the 15 Fabric 1.20.1 jar on Minecraft 1.20.2 to 1.20.6.** It refused Minecraft 1.20, and Fabric loaded it
  on 1.20.2 to 1.20.6, where Minecraft had changed calls it makes:
  - 1.20.2 and later: every run over chunks that already exist (around spawn, a run continued after a restart) ended
    with "Timed out waiting for reading the area's region headers";
  - 1.20.3 and later: once `/pregen` had run, the server crashed at every start ("Exception in server tick loop"),
    because `resume_after_restart` reads the save file then;
  - 1.20.5 and 1.20.6: no chunk could be generated at all.

  The jar now makes each of these calls the way the running version takes them. A stored chunk's status and the save
  file are read without the NBT calls that 1.20.2 and 1.20.3 changed. On 1.20.5 and 1.20.6, Minecraft's chunk steps,
  block entities, region files and DataFixerUpper results are called as those versions take them. 1.20.5 also stopped
  letting a generation region answer for a neighbor that has not reached the step it asks for, so there the chunks just
  outside the terrain area get their biomes before the surface step reads them, and mob spawning gets the region of one
  chunk around it that 1.20.5 gives it. On 1.20 to 1.20.4 nothing else changes.
- **Forge, one jar for Minecraft 1.20 to 1.20.4:** the Forge jar shares the Fabric jar's code, so it gets the same
  fixes for 1.20.2 and 1.20.3 (a stored chunk's status and the save file). Forge 1.20.2 also gave the call that sends a
  regenerated chunk to the players who see it another name at run time; the jar now finds it by its parameters. It
  needs Forge 46 (Minecraft 1.20) or later.
- **Fixed: the 15 Fabric 1.21.1 jar on Minecraft 1.21.2 and later.** It declared `~1.21.1`, so Fabric loaded it on
  every 1.21.x, where it fails: 1.21.2 changed chunk saving and the calls it makes. The jar now asks for 1.21 or 1.21.1;
  the NeoForge jar, which asked for exactly 1.21.1, now takes 1.21 as well.
- **Fabric for Minecraft 26.1 to 26.2 and NeoForge for 26.1.2 and 26.2, one jar each:** 26.2 changed what the fast
  terrain path hooks:
  density functions are mapped by one walk in `DensityFunction#mapAll`, the surface rules keep the block's biome in a
  field and test biomes against a set, and the surface step takes other parameters. Each jar now carries the hooks of
  both versions, the ones each was tested with in 15, and applies those of the version it runs on.
- **Fixed: End cities and Nether fortresses could be laid out differently from Minecraft (every build, and the
  Bukkit and Sponge plugins).** Minecraft builds the layout of these two structures with code that keeps its state in
  static fields: for End cities whether the city has its ship yet, for fortresses how many of each kind of room and
  bridge the fortress has, which caps rooms such as the blaze spawners. Minecraft lays out one structure at a time,
  but Ultra Fast Pregen creates a batch's structures on all worker threads, so two End cities or two fortresses laid
  out at the same moment could change each other's layout: a city could lose its ship or get one it does not have in
  Minecraft, and the rest of it was then built from another random sequence. Both are now laid out one at a time, as
  strongholds already were. In the tests, an End city that has its ship and 88 pieces in Minecraft came out without
  the ship and with 78 pieces in 3 of 3 runs of the jar before the fix on Fabric 1.21, and whole in all 7 runs of
  15.1. On 26.2 it did not show before the fix either, although Minecraft keeps the same static fields there, and no
  fortress came out differently ([details](COMPATIBILITY.md#end-cities-and-nether-fortresses)).
- **Faster with C2ME: lighting stays on the worker threads.** 15 lights each batch on the worker threads unless another
  mod changes Minecraft's light engine, and C2ME changes it in two places that keep its results: each light storage's
  update runs under that storage's own lock, and the server's light engine schedules its next update when a task ends.
  With C2ME, batches were therefore lit on the server's single light thread, which made lighting the slowest step in
  the Nether and the End. These two changes are recognized now (all mod builds). On NeoForge 26.1.2 with C2ME, every
  chunk has the same blocks and light as before (Overworld, Nether and End, 4,096 chunks each), and runs went from 143
  to 182 chunks/s in the Overworld, 185 to 358 in the Nether and 362 to 591 in the End (Xeon E5-2670 v2, 20 threads,
  1,000 × 1,000 blocks).

### Tested
- **Every Minecraft version of every jar, 22 in all**, each on a dedicated server of its own (Forge, NeoForge, or Fabric
  Loader 0.19.5; Intel Xeon E5-2670 v2, 20 threads): a fresh 1,000 × 1,000 block area in the Overworld, the Nether and
  the End; 1,000 × 1,000 blocks around spawn in 4 batches over the spawn chunks saved before, the same area again from
  the save file, and a restart with `/pregen resume`; and a server stopped once the first batch was saved, whose run
  continued by itself after the restart. Every chunk was fully generated, lit and readable, on the fast terrain path,
  with no error from this mod in any log ([details](COMPATIBILITY.md#version-151)).
- **The fast terrain path against Minecraft's own biome, noise and surface steps** (`terrain_pipeline=vanilla`), the
  same 4,096 chunks block for block in each dimension, on 15 of the versions. In the Overworld only bee nests differ
  (none on 26.x), one block apart, as they also do between two runs of the same jar; on 1.20.1 to 1.20.4 also 12 or
  13 surface blocks (mud, sand), and the 15 jar gives the same 13 on 1.20.1. The Nether and the End are identical,
  apart from 21 blocks at the bottom of one Nether chunk on 26.2, which come from Minecraft's own biome lookup and can
  come out either way in Minecraft too ([details](COMPATIBILITY.md#version-151)).
- **With C2ME**, on each version that has a C2ME build (Fabric 1.20.1, 1.21.1 and 26.2, NeoForge 1.21.1 and 26.1.2):
  the 15.1 jar against the same jar without the C2ME change, block for block in the Overworld, the Nether and the End,
  and over existing chunks and across a restart ([details](COMPATIBILITY.md#with-c2me)). The two worlds had the same
  blocks and light on NeoForge 26.1.2 and Fabric 26.2; on the other three only bee nests differed, and on NeoForge
  1.21.1 also one End city, laid out by the race fixed above; with the final jar and C2ME, NeoForge 1.21.1 gave that
  city its 88 pieces in 3 of 3 runs. The final jars passed every run with C2ME, with lighting on the worker threads
  throughout.
- **Static checks against every version each jar declares:** every class, method, field and mixin target the jar refers
  to exists in that version's game and loader files, apart from 4 calls on the Fabric jar's path for 1.20 to 1.20.4,
  which it does not take on 1.20.5 and 1.20.6.
- **Corrected test results of 15:** the C2ME rows in
  [Together with other mods](COMPATIBILITY.md#together-with-other-mods) had run without C2ME, because the test asked
  for it under a name the test server did not have. They are now runs of the 15 jars with C2ME, and all passed.

## 15

### New builds
- **Bukkit plugin for Minecraft 26.2:** one jar for CraftBukkit, Spigot, Paper and Folia
  ([ports/bukkit-26.2](ports/bukkit-26.2/README.md)). A plugin cannot change Minecraft's code, so it calls Minecraft's
  own world generation directly: no fast terrain path, but structures, caves, features, light and saving on every core
  as in the mod. On Paper and Folia it saves through Paper's chunk storage (Moonrise), which the mods do not support.
  Chunks the server holds part-generated are left to it, and `/pregen resume` fills them in later.
- **Sponge 26.2** (SpongeVanilla, API 20): the Fabric 26.2 build as a Sponge plugin, with every fast path
  ([ports/sponge-26.2](ports/sponge-26.2/README.md)).

### New
- **Any radius, square or circle:** `/pregen radius <blocks>`, `/pregen shape square|circle` and `/pregen start` without a size;
  `/pregen worldborder` takes the center and size from the world border, `/pregen spawn` centers on the world spawn.
  A circle generates every chunk whose center lies within the radius; the chunks just outside it are left part-generated
  like any area's edge, so trees along the circle are not cut off.
- **Continues after a restart:** a run cut short by a server stop or crash (not by `/pregen stop`) continues by itself once
  the server has started again (`resume_after_restart`, on by default). After 3 restarts in a row without a saved batch it
  stops trying, so a crash cannot loop.
- **API for other mods** ([API.md](API.md)): start, resume and stop runs and read their status; listen to a run's start,
  progress, every batch of saved chunks and its end; declare their own mixins compatible with the fast paths, or ask for
  Minecraft's terrain steps, in code or in fabric.mod.json / mods.toml.
- **Singleplayer without cheats:** the world's owner can use `/pregen` without turning cheats on; before, the command
  looked missing in such worlds. Servers and LAN guests still need op level 2.

### Faster
- **Surface rules:** each chunk's surface step reads the biomes around it once. A block's biome then comes from those
  (Minecraft's own pick among the eight biome cells around the block), and the chunk's surface rules are built without
  the biome tests its biomes decide, so the overworld's many rules for biomes that are not in the chunk no longer run for
  every block. The surface step takes about 16% less CPU time and whole runs are 5% (26.1.2) to 7% (26.2) faster
  (i9-11900T, 16 threads, 2000 x 2000 blocks), with the same blocks as Minecraft's. It turns itself off when another mod changes surface rules or biome lookups; ModernFix's
  and C2ME's changes there give the same results and keep it on.
- **Terrain noise shared at chunk edges:** a chunk's terrain is interpolated between 5 x 5 columns of noise values,
  and the 16 columns on its edges belong to its neighbors too, which each computed them again. Now the first chunk to
  compute such a column leaves it for the others. This happens only where neither chunk adds anything of its own there
  (no blending with older terrain, no structure pieces nearby), so the values are the ones Minecraft computes. The
  terrain step takes about 10% less time.
- **Noise setup per chunk:** every chunk builds its own copy of the world's noise router, and Minecraft rebuilt each
  named function and spline wherever it is referenced. Each is now built once per chunk, and parts that need no cache of
  the chunk skip Minecraft's lookup map. This setup takes about half the time (it was a tenth of all generation time).
- **Surface, aquifers and feature biomes:** the surface step reads a column of blocks one section at a time; on 26.1.2
  and 26.2 the aquifer finds its twelve nearest centers once per aquifer cell instead of for every block; features look
  up each biome cell once per chunk.
- **Lighting on all threads:** each batch is lit on the worker threads, every tile of 8 x 8 chunks by its own copy of
  Minecraft's light engine, instead of on the server's single light thread. A batch's light takes 1 to 3 s instead of
  12 to 23 s while the next batch generates. Lighting already ran alongside the next batch, so whole runs gain little
  from this alone, but a run of one batch, and the last batch of every run, finish sooner. Batches with chunks the
  server holds, and servers where another mod changes the light engine, are lit by the server's light engine as before.
- **Lithium and Canary keep the cell-skipping fill:** their one change to the noise generator (the sea level read once
  and kept) turned the fill off. It stays on with them now: with Lithium on 26.2, 201 instead of about 185 chunks/s
  (Xeon E5-2670 v2, 20 threads, 2000 x 2000 blocks), and the full Essentially Optimized set of mods is no longer slower
  than Ultra Fast Pregen alone.
- **SuperChunk keeps the fast terrain path:** SuperChunk (NeoForge 1.21.1, Forge 1.20.1) bundles C2ME under its own
  package names, so its copy of C2ME's changes, and four of its own that give the same results, turned the fast
  terrain path off and ran features one chunk at a time. They are recognized now: overworld 104.5 -> 173.0 chunks/s on
  NeoForge 1.21.1 and 33.5 -> 184.1 on Forge 1.20.1 (Xeon E5-2670 v2, 20 threads, 1000 x 1000 blocks), with the
  same blocks as on Minecraft's path with SuperChunk (2 of 3,844 chunks differ on Forge).
- All together, whole runs are 15% faster on 26.1.2 and 9% faster on 26.2 than with the surface rules alone (same
  machine and area), with the same blocks and light as Minecraft's. Like the fast paths before them, these steps turn
  themselves off where another mod changes the code they stand in for; in large mod packs that is often the case.

### Safer
- **Stopping the server during a run:** the server waits for the batch being saved (up to 20 s) and drops the one being
  generated, so the run continues from its last saved batch. In singleplayer, leaving the world now stops the run; before,
  it kept running in the background after the world closed.
- **Light engine:** a batch dropped before its lighting (server stop) or generated again by the safety net no longer
  removes light data that the batch before it is still computing; that could break Minecraft's light engine and hang
  the server.
- **Fast paths no longer turned off at random:** before using a fast path, the mod reads other mods' mixins into the
  code it replaces. These checks ran on whichever worker thread needed them first, and two threads reading the same
  mixin at once could make one of them fail; on Fabric with Fabric API this sometimes turned the surface shortcuts off
  for a whole run. The checks now run one at a time.
- **A batch whose lighting or saving fails is generated again:** a batch is lit and saved while the next one
  generates, so such a failure showed only when the next batch waited for it. The safety net then generated the
  next batch again instead, the failed batch was never saved, and the run ended reporting "cancelled" with part of
  the area missing. Now the failed batch is generated again first, from what is saved, as `/pregen resume` does, and
  the run continues (test `TAIL_FAILURE_RETRY_CHECK`: the blocks of an undisturbed run, beside the next batch and one
  batch at a time). A run that ends incomplete without being stopped now says so, and the mods named in a failure
  are also read from Sponge's way of naming mixins. Found while testing the Sponge build with a player in the area.
- **Minecraft's way no longer makes the server generate chunks:** when a batch is generated the way Minecraft does
  (after a failure on the fast paths, or with `parallel_steps=false`), its structures and features run one chunk at a
  time on the run's own thread, and lookups that feature code makes through the world (a cat placed in a village
  checks for a witch hut) went to the server instead of the batch. The server then generated up to 17 x 17 chunks
  around that spot to structure starts and kept them: the next batches left those chunks to the server and lost the
  features that reach into them, and the server's saves replaced part-generated chunks at the area's edge. These
  lookups are answered from the batch now, as on the worker threads (all builds). Found by `TAIL_FAILURE_RETRY_CHECK`
  on 26.x, where a village lies next to a batch edge of its area; the check now also requires that the server holds
  no chunks around the area afterwards.
- Includes the fix of 14.1.

### Tested
- The automated test suite passes on all six mod builds with the released jars: blocks, biomes and light identical to
  Minecraft's own generation (Overworld, Nether, End and the other world presets), batch edges, stopping and resuming,
  joining existing areas, circles, continuing after a server stop, the API, and the safety net, also for a batch whose
  lighting or saving fails.
- With other mods, on dedicated servers of all six mod builds with the released jars: 132 of 132 runs passed (C2ME,
  Lithium / Canary, Noisium, FerriteCore, ModernFix, Starlight / ScalableLux, Distant Horizons, Voxy, Terralith,
  Biomes O' Plenty, YUNG's Better Caves and Alex's Caves, alone and together, in the Overworld, Nether and End, every
  saved chunk checked), and the biomes are Minecraft's own in every run. Moonrise was refused as intended.
- Modpacks against 14.1 on the same server: All the Mods 10 59.6 → 60.0 chunks/s, All the Mods 9 51.5 → 60.1 (the
  surface rule shortcuts stay on there), Prominence II 52.6 → 51.5; one run each, and a run's speed varies by up to
  about 10%. In these packs other mods change most of the code that the new fast steps stand in for, so those steps
  turn themselves off. Details in [COMPATIBILITY.md](COMPATIBILITY.md#modpacks).
- **The Bukkit and Sponge builds** on CraftBukkit, Spigot, Paper and Folia 26.2 and on SpongeVanilla 26.2
  ([COMPATIBILITY.md](COMPATIBILITY.md#bukkit-servers-and-sponge)): Sponge's worlds are the Fabric 26.2 build's block
  for block in all three dimensions, and the plugin's on Spigot too, apart from 10 shipwreck chests stored without
  water, which they get back when the server loads them; stopping the server during a run, chunks the server holds, a
  batch whose saving fails and the API work on every server; and a player tested every one of them in the game.

## 14.1

### Fixed
- **Bright caves next to loaded chunks:** chunks the server had only started generating when a batch reached them were
  lit as if they had no blocks: sky light 15 everywhere underground and no light from lava, so their caves showed
  bright. These are the ring of partly generated chunks, about 11 chunks wide, around chunks the server keeps loaded:
  the spawn chunks (1.20.1, 1.21.1), players and force-loaded chunks. A run centered on spawn, or near a player
  (always the case in singleplayer), crossed such a ring. Those chunks are now lit from their generated blocks like
  every other chunk. Runs away from loaded chunks were not affected.
- **Worlds generated with 14 around spawn or players:** Minecraft relights every chunk after "Erase cached data": in
  singleplayer, Edit World → Optimize World with "Erase cached data" ticked; on a server, start it once with
  `--forceUpgrade --eraseCache`. Deleting the affected chunks so they generate again works too.

### Tested
- A new check in the test suite looks for sky light inside solid blocks underground after a batch that covers chunks
  the server holds full and partly generated: with 14, 175 of its 256 chunks were lit as if empty; with 14.1, none.
  It passes on all six builds, and the light of every block still matches Minecraft's light engine.
- On dedicated servers pregenerating 2k around spawn, 14 left a ring of 1,584 (1.20.1) or 760 (1.21.1) chunks with
  bright caves around the spawn chunks; 14.1 left none (Fabric and Forge 1.20.1, Fabric and NeoForge 1.21.1).

## 14

### New builds
- **NeoForge 26.1.2** (Java 25) and **Fabric 1.21.1** (Java 21), next to Fabric/Forge 1.20.1, NeoForge 1.21.1 and Fabric 26.2.

### Faster
- **Worldgen-mod-heavy worlds:** block reads and writes during feature placement skip Minecraft's per-call dependency checks
  inside a batch, and `matching_blocks` predicates compare blocks directly. Tectonic + WWOO + C2ME: 127.8 → 156.8 chunks/s (Ryzen 5 7500F, NeoForge 26.1.2).
- **The next batch no longer waits for the previous one's lighting:** worker threads run below the light engine and the batch tail's
  tasks go first. With C2ME: 354.2 → 378.1 chunks/s.
- **26.x:** noise cells above the aquifer's sampling height are filled without per-block work when they are air
  (75% of noise cells decided at once, up from 18%): vanilla worldgen 306.4 → 332.8 chunks/s.
- **Modpacks keep the fast terrain path:** the check of other mods' mixins now looks at the methods they change. Mixins that only add
  accessors or change other methods no longer force Minecraft's slower per-status path (ATM9 35.0 → 39.1, ATM10 48.6 → 54.2,
  Prominence II 32.4 → 36.9 chunks/s on an i9-11900T). A mod that changes a step the fast path skips, also through a lambda or
  MixinExtras' sugar (Noisium with Lithium or Canary, for example), still gets the per-status path.
- **C2ME on 1.20.1:** the cell-skipping noise fill now works with C2ME (Fabric 1.20.1 with C2ME: 70.6 → 108.2 chunks/s on the i9).

### Safer
- **Safety net:** a batch that fails on the fast paths (most likely another mod's code that does not expect them) is generated again
  the way Minecraft does, and the rest of the run continues that way; the log names the mods involved. Before, the run stopped.
- **FastChunkGen (Forge 1.20.1, NeoForge 1.21.1):** fixed a server crash at start-up
  (it changes surface rules too; this mod's mixin now applies after it).
- **C2ME and FastChunkGen chunk storage on 1.20.1 / 1.21.1:** fixed "reading the area's region headers failed".
- **Lighting can no longer hang:** a batch whose light makes no progress for 3 minutes stops with a message.
- **Moonrise:** `/pregen start` now explains that Moonrise is not supported yet (it replaces Minecraft's chunk system)
  instead of failing with an error.
- **Light at batch edges:** the previous batch's light data is out of the light engine before the next batch's features run
  (snow and ice read light); before, only timing ensured it (26.x, 1.21.1).

### Tested
- The full test suite passes on all six builds: blocks, biomes and light identical to Minecraft, nether and end, batch seams,
  resuming, joining existing areas, and the new safety net (a batch made to fail is generated again with 0 different blocks).
- With other mods, on dedicated servers of all six builds with the released jars: 132 of 132 runs passed (C2ME,
  Lithium / Canary, Noisium, FerriteCore, ModernFix, Starlight / ScalableLux, Distant Horizons, Voxy, Terralith,
  Biomes O' Plenty, YUNG's Better Caves and Alex's Caves, alone and together, in the Overworld, Nether and End, every saved
  chunk checked). All the Mods 9, All the Mods 10 and Prominence II keep the fast terrain path. Details in
  [COMPATIBILITY.md](COMPATIBILITY.md).
- Singleplayer (Fabric 1.20.1, 3 GB heap): a 2k area in 1 min 11 s (221.1 chunks/s).
