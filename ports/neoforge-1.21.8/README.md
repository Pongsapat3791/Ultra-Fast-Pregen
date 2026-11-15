# Ultra Fast Pregen for NeoForge and Forge, Minecraft 1.21.6 to 1.21.8

The NeoForge build of Ultra Fast Pregen for Minecraft 1.21.6 to 1.21.8 (built against NeoForge 21.8.57), and Forge's jar for
the same versions. See the [main README](../../README.md) for what the mod does.

- **NeoForge:** `./gradlew -p ports/neoforge-1.21.8 jar` gives `build/libs/ultrafastpregen-neoforge-1.21.6-1.21.8-16.jar`
- **Forge** (Forge 56 to 58): `./gradlew -p ports/neoforge-1.21.8 forgeJar` gives `build/libs/ultrafastpregen-forge-1.21.6-1.21.8-16.jar`
- **Requires:** Java 21; on the server only, players do not need it

## No engine source of its own

This tree holds only what Minecraft 1.21.6 to 1.21.8 spell differently. The source is the 26.x family's,
[ports/neoforge-26.1.2](../neoforge-26.1.2), then [ports/neoforge-1.21.11](../neoforge-1.21.11), then [ports/neoforge-1.21.10](../neoforge-1.21.10), then this tree: a file here replaces the file of the same path in
the trees before it. This tree's layer is `Mc` again (how an empty chunk and a section's block states are made, the world spawn, the singleplayer owner).

- Inside terrain generation (the beardifier, the aquifer, palettes, the flat caches) these versions are still as 1.21.1 is: six files come from [ports/neoforge-1.21.1](../neoforge-1.21.1), named in `build.gradle`.
- Link and Manager, parts of the mod on every loader and version, are in [shared/](../../shared).
- **Forge's jar** holds the same compiled classes with Forge's entry point and metadata
  ([shared/forge](../../shared/forge)): from Minecraft 1.20.6 on Forge runs on Mojang's names, as NeoForge does. Each
  Forge jar is link-checked against the server of every Forge version it names and booted there before a release.
