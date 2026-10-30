# Ultra Fast Pregen for NeoForge, Minecraft 26.1.2 and 26.2

The port of Ultra Fast Pregen to Minecraft 26.1.2 and 26.2 (NeoForge, built against 26.1.2), with the commands (including `/pregen set` and the boss bar),
the config file, the save file and every change up to round 15.1: see the [main README](../../README.md).

- **Requires:** Minecraft 26.1.2 or 26.2 with its NeoForge (tested with 26.1.2.122 and 26.2.0.93) and Java 25. NeoForge for 26.1 and
  26.1.1 exists only as betas, which bundle MixinExtras 0.5.3: it fails on this jar's `@Redirect` mixins (0.5.5 reads them)
- **File:** `build/libs/ultrafastpregen-neoforge-26.1.2-26.2-15.1.jar`, into the server's `mods/` folder; `15.1` is the update round
- **Install:** on the server only; players do not need it, as the mod has no packets of its own
- **Commands:** `/pregen start|resume|reset|stop|status|world|center|radius|shape|worldborder|spawn|set`, operator level 2 (in singleplayer the world's owner, cheats or not)
- **Config:** `config/ultrafastpregen.conf`, with the same keys as the other builds

## Differences from the other builds

The code is the Fabric 26.x build's (Minecraft 26.x is not obfuscated); only the loader entrypoint differs. Where 26.2 changed what the
fast terrain path hooks (density functions mapped by one walk in `DensityFunction#mapAll`, the surface rules' biome lookups and tests, the
surface step's parameters), both builds carry the mixins of 26.1.x and of 26.2, and `mixin.PregenMixinPlugin` applies the ones of the
running version: 26.2 is the version with the class `DensityFunction$1RecursiveVisitor`. `MinecraftVersion` covers the one renamed method
the rest of the code calls (`markPosForPostProcessing`, spelled `markPosForPostprocessing` before 26.2).

- **Command registration:** NeoForge's `RegisterCommandsEvent` (`neoforge.NeoForgeEntrypoint`) instead of the Fabric build's mixin
- **Config folder:** `FMLPaths.CONFIGDIR`
- **Aquifer:** Minecraft 26.x rewrote the aquifer, so the 1.20 / 1.21 aquifer fast path is not used. Air cells above the height where
  26.x's aquifer stops sampling (`skipSamplingAboveY`) still skip per-block work, because Minecraft answers there with the global fluid only.
- **Minecraft's own steps:** a step gets the neighboring chunks within 1 chunk, as the server gives them; chunks further out are read from
  the grid through `WorldGenRegionMixin` (as in the 1.21.1 and 26.2 builds).
- **NeoForge events and data attachments:** as in the 1.21.1 build (see the [NeoForge 1.21.1 README](../neoforge-1.21.1/README.md#neoforge)).

## With C2ME

- **C2ME 0.4.0-alpha.0.100 (NeoForge 26.1.2):** the fast terrain path stays on (log: `Terrain pipeline: fused fast path`), and chunks
  are written through C2ME's storage thread as on 26.2, so there is no need to set `replaceImpl = false`.
- **C2ME's density function compiler (DFC):** on by default. That is a little faster than turning it off and using Ultra Fast Pregen's
  cell-skipping fill instead, so C2ME's config can stay as it is.

## Correctness (NeoForge 26.1.2.122)

- **Quick tests (`runTerrainBenchmark`):** every check passes; the fast path gives Minecraft 26.1.2's terrain block for block, in the
  overworld and the other presets (large biomes, amplified, nether, end, caves, floating islands).
- **Full suite on a real server (`runPartialBatchCheck`):** every check passes:
  - the fast path gives the same results as Minecraft 26.1.2's own steps, around a village and after features;
  - batches give the blocks of one run in the same order, and stopping and resuming differs from an uninterrupted run by 0 blocks;
  - joining an existing area: 0 different edge blocks (17,618 with the fix off);
  - the light of every block matches Minecraft's light engine (1,572,864 blocks, 0 different), and chunks the server held and that
    were then overwritten are not dark;
  - saved chunks reach Distant Horizons' LoD database (`LOD_EXPORT_CHECK`), as in the Fabric 26.2 build;
  - the Nether and the End generate completely, and the safety net generates a failed batch again with 0 different blocks.
- **With other mods and modpacks:** see [COMPATIBILITY.md](../../COMPATIBILITY.md).

## Building and testing (from the main project folder)

```powershell
.\gradlew.bat -p ports/neoforge-26.1.2 build
.\gradlew.bat -p ports/neoforge-26.1.2 runTerrainBenchmark
.\gradlew.bat -p ports/neoforge-26.1.2 runPartialBatchCheck -PcheckHeap=8G
```

Gradle downloads JDK 25 by itself the first time. `runPartialBatchCheck` uses the test world in `build/partial-batch-check`
(it needs `eula.txt` and `server.properties`; set a new `level-name` before every run).
