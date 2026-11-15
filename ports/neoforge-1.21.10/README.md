# Ultra Fast Pregen for NeoForge and Forge, Minecraft 1.21.9 and 1.21.10

The NeoForge build of Ultra Fast Pregen for Minecraft 1.21.9 and 1.21.10 (built against NeoForge 21.10.64), and Forge's jar for
the same versions. See the [main README](../../README.md) for what the mod does.

- **NeoForge:** `./gradlew -p ports/neoforge-1.21.10 jar` gives `build/libs/ultrafastpregen-neoforge-1.21.9-1.21.10-16.jar`
- **Forge** (Forge 59 and 60): `./gradlew -p ports/neoforge-1.21.10 forgeJar` gives `build/libs/ultrafastpregen-forge-1.21.9-1.21.10-16.jar`
- **Requires:** Java 21; on the server only, players do not need it

## No engine source of its own

This tree holds only what Minecraft 1.21.9 and 1.21.10 spell differently. The source is the 26.x family's,
[ports/neoforge-26.1.2](../neoforge-26.1.2), then [ports/neoforge-1.21.11](../neoforge-1.21.11), then this tree: a file here replaces the file of the same path in
the trees before it. This tree's layer is `Mc` again (identifiers are `ResourceLocation`, permissions are numbers, the moon's brightness).

- Link and Manager, parts of the mod on every loader and version, are in [shared/](../../shared).
- **Forge's jar** holds the same compiled classes with Forge's entry point and metadata
  ([shared/forge](../../shared/forge)): from Minecraft 1.20.6 on Forge runs on Mojang's names, as NeoForge does. Each
  Forge jar is link-checked against the server of every Forge version it names and booted there before a release.
