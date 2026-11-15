# Ultra Fast Pregen for NeoForge, Minecraft 26.3

The port of Ultra Fast Pregen to Minecraft 26.3 on NeoForge, with the commands (including `/pregen set` and the boss bar), the
config file, the save file and every change up to round 16: see the [main README](../../README.md).

- **Requires:** Minecraft 26.3, NeoForge 26.3 and Java 25
- **File:** `build/libs/ultrafastpregen-neoforge-26.3-16.jar`; `16` is the update round
- **Install:** on the server only; players do not need it

The code is that of the Fabric build, [ports/fabric-26.3](../fabric-26.3/README.md), which says what is different on 26.3.
This folder has its own entry point (`neoforge/`), `neoforge.mods.toml`, and the entry of the regression checks,
`PregenChecksMod`.

## Building

```powershell
.\gradlew.bat -p ports/neoforge-26.3 build
.\gradlew.bat -p ports/neoforge-26.3 runPartialBatchCheck -PcheckHeap=8G
```
