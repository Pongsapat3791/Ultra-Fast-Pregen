# Changelog

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
