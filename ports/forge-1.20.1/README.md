# Ultra Fast Pregen for Forge 1.20.1

The Forge build shares its code with the Fabric 1.20.1 build (`src/` of the main project); only the loader entrypoint differs
(`com.example.pregen.forge.ForgeEntrypoint` instead of `com.example.pregen.fabric.FabricEntrypoint`).
Every change to the Fabric build is in the Forge build too, with the same version number (`version` in the main project's `gradle.properties`).

Commands, the config file, the save file and how it works are the same as in the Fabric build: see the [main README](../../README.md).

- **Requires:** Forge 1.20.1 (47.x) and Java 17 or newer
- **File:** `ports/forge-1.20.1/build/libs/ultrafastpregen-forge-1.20.1-14.1.jar`
- **Install:** on the server only; players can join with or without it

## Building and testing (from the main project folder)

```powershell
.\gradlew.bat -p ports/forge-1.20.1 build
.\gradlew.bat -p ports/forge-1.20.1 runTerrainBenchmark
.\gradlew.bat -p ports/forge-1.20.1 runPartialBatchCheck -PcheckHeap=8G
```

- Built with ModDevGradle (legacyforge) against Forge 47.4.28; mixin names are mapped to Forge's names through `pregen.refmap.json` at build time.
- `runTerrainBenchmark` and `runPartialBatchCheck` run the Fabric build's tests (`src/test` of the main project) on a Forge server.
  `runPartialBatchCheck` uses the test world in `build/partial-batch-check`: set a new `level-name` before every run (the defaults come from `checks/`).

## Test results

- **Full test suite, round 14 (Forge 47.4.28):** every check passes, with the same block hashes as the Fabric build (vanilla parity around a village,
  feature order, Minecraft's own steps), including vanilla's other presets, batch seams, stopping and resuming, joining existing chunks, the Nether,
  the End, the light of every block (`LIGHT_PARITY_CHECK`) and the safety net (`SAFE_MODE_RETRY_CHECK`).
- **The released jar on a Forge server (round 13, i9-11900T ES, 16 threads, `-Xmx8G`), 3k area, seed 42, center 100000 100000:**
  5 min 22 s, 109.6 chunks/s on the full fast terrain path (the Fabric build on the same machine: 113.3), about 3% slower than Fabric (one run).
- **A whole world compared with the Fabric build (round 12, 28,900 chunks):** 50 blocks differ, all of them bee nests
  (Minecraft places bee nests with an unseeded shuffle).
- **With other mods and modpacks:** see [COMPATIBILITY.md](../../COMPATIBILITY.md).
