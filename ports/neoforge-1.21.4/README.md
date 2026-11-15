# Ultra Fast Pregen for NeoForge and Forge, Minecraft 1.21.2 to 1.21.4

The NeoForge build of Ultra Fast Pregen for Minecraft 1.21.2 to 1.21.4 (built against NeoForge 21.4.161), and Forge's jar for
the same versions. See the [main README](../../README.md) for what the mod does.

- **NeoForge:** `./gradlew -p ports/neoforge-1.21.4 jar` gives `build/libs/ultrafastpregen-neoforge-1.21.2-1.21.4-16.jar`
- **Forge** (Forge 53 and 54 (Minecraft 1.21.3 and 1.21.4; there is no Forge for 1.21.2)): `./gradlew -p ports/neoforge-1.21.4 forgeJar` gives `build/libs/ultrafastpregen-forge-1.21.3-1.21.4-16.jar`
- **Requires:** Java 21; on the server only, players do not need it

## No engine source of its own

This tree holds only what Minecraft 1.21.2 to 1.21.4 spell differently. The source is the 26.x family's,
[ports/neoforge-26.1.2](../neoforge-26.1.2), then [ports/neoforge-1.21.11](../neoforge-1.21.11), then [ports/neoforge-1.21.10](../neoforge-1.21.10), then [ports/neoforge-1.21.8](../neoforge-1.21.8), then [ports/neoforge-1.21.5](../neoforge-1.21.5), then this tree: a file here replaces the file of the same path in
the trees before it. This tree's layer is `McNbt` (reading saved data before 1.21.5's getters) and `McBlocks` (a block write's third argument).

- Inside terrain generation (the beardifier, the aquifer, palettes, the flat caches) these versions are still as 1.21.1 is: six files come from [ports/neoforge-1.21.1](../neoforge-1.21.1), named in `build.gradle`.
- Link and Manager, parts of the mod on every loader and version, are in [shared/](../../shared).
- **Forge's jar** holds the same compiled classes with Forge's entry point and metadata
  ([shared/forge](../../shared/forge)): from Minecraft 1.20.6 on Forge runs on Mojang's names, as NeoForge does. Each
  Forge jar is link-checked against the server of every Forge version it names and booted there before a release.
