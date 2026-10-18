# Ultra Fast Pregen for Fabric 1.21.1

The Fabric build for Minecraft 1.21.1 shares its code with the NeoForge 1.21.1 build ([ports/neoforge-1.21.1](../neoforge-1.21.1/README.md));
only the loader entrypoint differs. It has the commands (including `/pregen set` and the boss bar), the config file, the save file and every
change of round 14: see the [main README](../../README.md).

- **Requires:** Fabric Loader 0.14 or newer and Java 21 (Fabric API is not needed)
- **File:** `build/libs/ultrafastpregen-fabric-1.21.1-14.1.jar`; `14.1` is the update round, the same for every build
- **Install:** on the server only; players do not need it (in singleplayer it runs in the integrated server)

## Differences from the NeoForge 1.21.1 build

- **Command registration:** a mixin into `Commands`, as in the Fabric 1.20.1 build (no Fabric API needed)
- **Config folder:** `FabricLoader.getConfigDir()`
- **Mixin names:** written with Mojang's names; Loom maps them to Fabric's intermediary names at build time.
  The check of other mods' mixins knows the methods by both names.
- Everything else (Minecraft 1.21's `ChunkStep` generation, the terrain and aquifer fast paths, the check of other mods' mixins)
  is the same as in the NeoForge 1.21.1 build.

## Building and testing (from the main project folder)

```powershell
.\gradlew.bat -p ports/fabric-1.21.1 build
.\gradlew.bat -p ports/fabric-1.21.1 runTerrainBenchmark
.\gradlew.bat -p ports/fabric-1.21.1 runPartialBatchCheck -PcheckHeap=8G
```

`runPartialBatchCheck` uses the test world in `build/partial-batch-check` (it needs `eula.txt` and `server.properties`;
set a new `level-name` before every run).

## Test results

- **Full test suite, round 14 (Fabric Loader 0.19.5):** every check passes, with the same block hashes as the NeoForge 1.21.1 build.
- **With other mods and modpacks:** see [COMPATIBILITY.md](../../COMPATIBILITY.md).
