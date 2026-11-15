# Ultra Fast Pregen for Fabric, Minecraft 26.3

The port of Ultra Fast Pregen to Minecraft 26.3, with the commands (including `/pregen set` and the boss bar), the config
file, the save file and every change up to round 16: see the [main README](../../README.md).

- **Requires:** Minecraft 26.3, Fabric Loader 0.16 or newer and Java 25 (Fabric API is not needed)
- **File:** `build/libs/ultrafastpregen-fabric-26.3-16.jar`; `16` is the update round
- **Install:** on the server only; players do not need it

## What is different on 26.3

Minecraft 26.3 rewrote terrain generation, so this build differs from the 26.2 one more than any build before it:

- **One terrain step:** noise, surface and caves are one step in Minecraft (`ChunkStatus.TERRAIN`), so a run has five stages
  instead of six: structures, terrain with caves, features, lighting, mob spawning.
- **No fast terrain path of its own:** density functions now compile to samplers that work on whole volumes, and
  `MaterialSystem` replaces the surface rules. The code the 26.2 fast path stood in for is gone, and so are the cell-skipping
  fill and the aquifer and surface shortcuts. This build runs Minecraft's own biome and terrain steps, and two small mixins
  keep them on this mod's worker threads, where Minecraft would hand them to its background threads. That was as fast as
  15.1's 26.2 build with all its shortcuts. The terrain steps that make 16 faster on 26.2 and older are for the code 26.3
  no longer has, so this build is now the slower one: 266 chunks/s on NeoForge 26.3 where NeoForge 26.2 makes 323, in the
  same 4,000 × 4,000 run on the same server ([tests](../../COMPATIBILITY.md#speed-against-151)).
- **Other mods:** their changes to those steps apply, since the steps are Minecraft's own, so the mod no longer checks for them
  before it chooses a path. `terrain_pipeline=vanilla` leaves the steps on Minecraft's threads. Parallel features and
  lighting on the workers keep their checks. No other mod has been tested with this build yet.
- **Files:** 26.3 keeps a "no such file" entry in its region cache, and saves all heightmaps at every status. Neighbors of a
  finished area are stored at status `terrain`, as Minecraft leaves them.

## Building

```powershell
.\gradlew.bat -p ports/fabric-26.3 build
.\gradlew.bat -p ports/fabric-26.3 runPartialBatchCheck -PcheckHeap=8G
```

The checks no longer need Fabric API. The terrain benchmark of the other builds is not here: there is no second terrain path
to compare it with, and the block-for-block comparison of `terrain_pipeline=auto` and `vanilla` is in the regression checks.
