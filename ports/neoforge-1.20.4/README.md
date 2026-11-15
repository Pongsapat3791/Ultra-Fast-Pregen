# Ultra Fast Pregen for NeoForge, Minecraft 1.20.2 to 1.20.4

The NeoForge build of Ultra Fast Pregen for Minecraft 1.20.2 to 1.20.4 (built against NeoForge 20.4). See the
[main README](../../README.md) for what the mod does.

- **Build:** `./gradlew -p ports/neoforge-1.20.4 jar` gives `build/libs/ultrafastpregen-neoforge-1.20.2-1.20.4-16.jar`
  (NeoForge 20.x asks for a version listing at every build, so it does not build with `--offline`)
- **Requires:** NeoForge 20.2 to 20.4, Java 17; on the server only, players do not need it
- **NeoForge for Minecraft 1.20.1** is the fork of Forge that takes Forge's jar:
  `ultrafastpregen-forge-1.20-1.20.4-16.jar` ([ports/forge-1.20.1](../forge-1.20.1/README.md))

The source is the Fabric build's, two folders up, as for the Forge jar of 1.20 to 1.20.4; only the entry point and the
metadata are this tree's. NeoForge for these versions runs on Mojang's names (Forge for the same versions runs on its
own), so the source compiles against it as it is. The entry point makes its few calls into the loader by name: NeoForge
moved a mod's information between 20.3 and 20.4, and one jar serves the three versions.
