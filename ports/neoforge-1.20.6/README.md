# Ultra Fast Pregen for NeoForge 1.20.5 and 1.20.6, and Forge 1.20.6

The NeoForge build of Ultra Fast Pregen for Minecraft 1.20.5 and 1.20.6 (built against NeoForge 20.6), and Forge's jar
for 1.20.6 (there is no Forge for 1.20.5). See the [main README](../../README.md) for what the mod does.

- **NeoForge:** `./gradlew -p ports/neoforge-1.20.6 jar` gives `build/libs/ultrafastpregen-neoforge-1.20.5-1.20.6-16.jar`
- **Forge 50:** `./gradlew -p ports/neoforge-1.20.6 forgeJar` gives `build/libs/ultrafastpregen-forge-1.20.6-16.jar`
  (NeoForge 20.x asks for a version listing at every build, so neither builds with `--offline`)
- **Requires:** Java 21; on the server only, players do not need it

The source is the Fabric build's, two folders up, which is written against Minecraft 1.20.1 and reaches what 1.20.5
changed by name. Compiled against 1.20.6 it needs three things, all in this tree:

- **A copy of the source with one name rewritten.** `ChunkStatus` moved into the package `status` in 1.20.5; the
  build copies the source and rewrites that name (`prepareSharedSource` in `build.gradle`).
- **Its own `LegacyCalls`.** The family's class of that name holds the three calls only 1.20 to 1.20.4 have; a build
  against 1.20.6 cannot hold them, and this one never asks for them.
- **`OfficialNames`.** The source asks for 1.20.5's classes and methods by Fabric's intermediary names; here they have
  Mojang's.

Forge's jar holds the same compiled classes with this tree's Forge entry point (`src/forge`) and Forge's metadata
([shared/forge](../../shared/forge)).
