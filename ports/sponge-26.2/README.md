# Ultra Fast Pregen for Sponge (Minecraft 26.2)

The Fabric 26.2 build of Ultra Fast Pregen as a Sponge plugin, with the commands (including `/pregen set` and the boss
bar), the config file and the save file: see the [main README](../../README.md).

- **Requires:** SpongeVanilla 26.2 (Sponge API 20) and Java 25
- **File:** `build/libs/ultrafastpregen-sponge-26.2-15.jar`; `15` is the update round, the same for every build
- **Install:** put the jar in the server's `mods` folder; players do not need anything
- **Commands:** operator level 2, as in the mod; **config file:** `config/ultrafastpregen.conf`

## How it works on Sponge

SpongeVanilla applies the mixins a plugin's jar names (the `MixinConfigs` entry of its manifest), so this jar is the mod
itself with a Sponge entry point: the same code as the Fabric 26.2 build ([ports/fabric-26.2](../fabric-26.2/README.md)),
including the fast terrain path, the cell-skipping fill and the surface shortcuts.

- Two of Sponge's own mixins change methods a fast path replaces, and give the same results: `ChunkStatusTasksMixin`
  (Minecraft's biome step inside a try/catch) and `NoiseBasedChunkGeneratorMixin_API` (adds Sponge's API methods only).
  The check of other mods' mixins knows them, so the fast paths stay on.
- The jar is compiled against Sponge's Mixin (0.8.7), not Fabric's version of it: Fabric's stores the target of a
  `@Redirect` in a form that the MixinExtras SpongeVanilla ships cannot read, and the server failed at start-up.
- **Chunks the server holds** get the generated blocks as in the mod. The scheduled ticks of the generated chunk go
  straight into the server's chunk: Sponge tracks every scheduled tick with the level of the chunk it is scheduled
  in, and failed on the temporary chunk the Fabric build copies them through. Found with a player in the area: that
  batch was not saved, which the safety net now handles too (see the changelog).

## Tests (Minecraft 26.2)

SpongeVanilla 26.2-20.0.0-RC2754 on a test server with an Intel Xeon E5-2670 v2 (10 cores / 20 threads) and a 10 GB
heap; a fresh world with seed 42, around x = z = 100,000; the jar with SHA-1 `41d7bea835b25c8588df2b344892b05f5f7a24bd`.

- **The same world as the Fabric build:** in the Overworld, the Nether and the End (1,000 × 1,000 blocks each), every
  chunk has the same blocks and light as the Fabric 26.2 build's, compared block for block (3,844 chunks per
  dimension), with every fast path on: the fused terrain path, the cell-skipping fill and the surface shortcuts.
- **Speed:** 142.8, 253.3 and 553.4 chunks/s in 1,000 × 1,000 blocks (Overworld, Nether, End) and 192.8 in
  2,000 × 2,000; the Fabric 26.2 build on the same machine: 137.5, 229.8, 551.1 and 201.0 (one run each; a run's speed
  varies by up to about 10%).
- **Stopping the server during a run** (3,000 × 3,000 blocks, stopped after 3 of 9 batches): the run continued after the
  restart, and all 35,344 chunks were generated (199.0 chunks/s).
- **Chunks the server holds** (a force-loaded square in the middle of the area): generated and replaced as in the mod,
  so all 4,096 chunks are fully generated. Again with a larger square that the server was still generating while the
  batch was lit and saved: all chunks fully generated, no errors.
- **A batch whose lighting and saving fails** (batch 2 of a 2,000 × 2,000 run made to fail once): it was generated
  again and the run completed, with the same block types, biomes and light in every chunk as an undisturbed run.
- **The API from another Sponge plugin:** a run started, a second start refused, status, a stop at a fifth of the run,
  a resume from the finish event, and the run completed with all 15,876 chunks; the events counted every saved chunk.
- **In the game** with a player in the area: a 2,000 × 2,000 run around the world spawn completed (161.8 chunks/s) with
  no errors, the 625 chunks the server had fully generated kept and the rest generated; the player found the area
  complete in the game.
- **Not tested yet:** together with other Sponge plugins; SpongeForge and SpongeNeo.

## Building (from the main project folder)

```powershell
.\gradlew.bat -p ports/sponge-26.2 build
```

Loom provides Minecraft's classes to compile against; the Sponge API and Mixin come from Sponge's Maven repository.
Gradle downloads JDK 25 by itself the first time.
