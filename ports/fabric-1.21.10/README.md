# Ultra Fast Pregen for Fabric, Minecraft 1.21.9 and 1.21.10

The Fabric build of Ultra Fast Pregen for Minecraft 1.21.9 and 1.21.10. See the [main README](../../README.md) for what the
mod does.

- **Build:** `./gradlew -p ports/fabric-1.21.10 build` gives `build/libs/ultrafastpregen-fabric-1.21.9-1.21.10-16.jar`
- **Requires:** Fabric Loader, Java 21; no Fabric API; on the server only, players do not need it

This tree holds build files only. Its source is the 26.x family's ([ports/neoforge-26.1.2](../neoforge-26.1.2)), the
layers of what Minecraft 1.21.9 and 1.21.10 spell differently (the NeoForge trees hold them, both loaders share them; see
[ports/neoforge-1.21.10](../neoforge-1.21.10/README.md)), and Fabric's entry point from
[ports/fabric-1.21.11](../fabric-1.21.11). Loom remaps the jar to Fabric's intermediary names.
