# Ultra Fast Pregen API

Other mods and plugins can start, resume and stop pregeneration, follow it, receive every chunk it saves, and tell
it how their own world generation changes interact with its fast paths.

- [What is in it](#what-is-in-it)
- [Setup](#setup)
- [Setup for plugins](#setup-for-plugins): Bukkit (CraftBukkit, Spigot, Paper, Folia) and Sponge
- [Quick start](#quick-start)
- [Runs](#runs)
- [Events](#events)
- [Compatibility declarations](#compatibility-declarations)
- [Reference](#reference)
- [Recipes](#recipes)
- [Notes](#notes)

## What is in it

The API is the package `io.github.pongsapat3791.ultrafastpregen.api`, inside every Ultra Fast Pregen jar from
version 15 on: the mods (Fabric 1.20 to 1.20.6, Forge 1.20.1, Fabric 1.21.1, NeoForge 1.21.1, NeoForge 26.1.2, Fabric 26.2)
and the plugins (the Bukkit plugin for CraftBukkit, Spigot, Paper and Folia 26.2, and Sponge 26.2).
It uses plain Java types only: dimension ids as strings, block coordinates, and chunk positions packed into
longs. No Minecraft class appears in it, so the same code compiles against any of the jars and runs on every
Minecraft version, loader and server Ultra Fast Pregen is built for.

| Class | What it is |
|---|---|
| `UltraFastPregenApi` | The entry point: `UltraFastPregenApi.get()` and the API version |
| `PregenApi` | Every function: runs, status, listeners, compatibility declarations |
| `PregenRequest` | The area of a run: a square or a circle around a center, in one dimension |
| `PregenStatus` | A snapshot of a run: where, how far, how fast, and its state |
| `StartResult` | Whether a start or resume started a run, and why not |
| `PregenListener` | Events of runs: start, progress, chunks saved, finish |

## Setup

### 1. Compile against an Ultra Fast Pregen jar

Put any Ultra Fast Pregen jar of version 15 or later in your project (for example in `libs/`) and add it as a
compile-only dependency. The API has no Minecraft types, so the jar needs no remapping; one jar works for every
version you build for.

```gradle
dependencies {
    // Fabric (Loom), NeoForge (ModDevGradle) and Forge alike.
    compileOnly files("libs/ultrafastpregen-fabric-1.21.1-15.jar")
}
```

### 2. Declare the dependency as optional

Players who do not install Ultra Fast Pregen must still be able to run your mod.

```json
// fabric.mod.json
"suggests": {
  "ultrafastpregen": "*"
}
```

```toml
# META-INF/neoforge.mods.toml (Forge: META-INF/mods.toml)
[[dependencies.yourmod]]
    modId = "ultrafastpregen"
    type = "optional"        # Forge 1.20.1: mandatory = false
    versionRange = "*"
    ordering = "NONE"
    side = "SERVER"
```

### 3. Touch the API only when the mod is installed

Without Ultra Fast Pregen the API classes are missing. Keep every reference to them in a separate class and load
that class only after checking that the mod is present:

```java
// In your mod's initializer
if (FabricLoader.getInstance().isModLoaded("ultrafastpregen")) {   // NeoForge / Forge: ModList.get().isLoaded("ultrafastpregen")
    PregenIntegration.register();
}
```

```java
// PregenIntegration.java: the only class that mentions the API
final class PregenIntegration {
    static void register() {
        PregenApi pregen = UltraFastPregenApi.get();
        // ...
    }
}
```

`UltraFastPregenApi.get()` works from the moment Ultra Fast Pregen has initialized, which every loader does before
any server starts. Called earlier, it throws `IllegalStateException`.

## Setup for plugins

The API is the same for plugins; only how the dependency is declared, and how work gets back to the server, differ.

### Bukkit: CraftBukkit, Spigot, Paper and Folia

Compile against the plugin jar (`compileOnly files("libs/ultrafastpregen-bukkit-26.2-15.jar")`), and declare it in
your `plugin.yml`, so that Ultra Fast Pregen is enabled before your plugin:

```yaml
softdepend: [UltraFastPregen]     # or depend: [UltraFastPregen] when your plugin needs it
```

Then check that it is enabled before your plugin loads the class that mentions the API:

```java
@Override
public void onEnable() {
    if (getServer().getPluginManager().isPluginEnabled("UltraFastPregen")) {
        PregenIntegration.register(this);   // the only class that mentions the API, as above
    }
}
```

- **Dimensions** are the worlds' keys: `world.getKey().toString()`, for example `minecraft:overworld`,
  `minecraft:the_nether` and `minecraft:the_end` for the default worlds.
- **Back to the server:** listeners run on Ultra Fast Pregen's threads. On CraftBukkit, Spigot and Paper hand work to the
  server with `Bukkit.getScheduler().runTask(plugin, () -> ...)`. Folia has no main thread and refuses that scheduler:
  use its global region scheduler (`Bukkit.getGlobalRegionScheduler().execute(plugin, () -> ...)`) or, for a place in
  the world, its region scheduler.
- **Settings** come from `plugins/UltraFastPregen/ultrafastpregen.conf`.
- **Compatibility declarations** do not apply: the plugin has no fast terrain path and checks no mixins, so
  `declareCompatibleMixin` does nothing. `requireMinecraftTerrain` still makes runs use Minecraft's per-status steps.

### Sponge

The Sponge build is the mod with a Sponge entry point. Compile against its jar
(`compileOnly files("libs/ultrafastpregen-sponge-26.2-15.jar")`) and declare it in your `META-INF/sponge_plugins.json`:

```json
"dependencies": [
  { "id": "ultrafastpregen", "version": "*", "load-order": "after", "optional": true }
]
```

Check `Sponge.pluginManager().plugin("ultrafastpregen").isPresent()` before loading the class that mentions the API,
and call `UltraFastPregenApi.get()` once the plugins are constructed (for example on `StartedEngineEvent<Server>`).
Hand work back to the server with Sponge's scheduler (`Sponge.server().scheduler().submit(Task.builder()...)`).
Compatibility declarations work as on the mods, in code; the metadata form (`fabric.mod.json`, `mods.toml`) has no
Sponge equivalent.

## Quick start

```java
final class PregenIntegration {
    static void register() {
        PregenApi pregen = UltraFastPregenApi.get();
        pregen.addListener(new PregenListener() {
            @Override
            public void onProgress(PregenStatus status) {
                LOGGER.info("Pregen {}%: {} chunks/s", Math.round(status.progress() * 100), status.chunksPerSecond());
            }

            @Override
            public void onFinish(PregenStatus status) {
                LOGGER.info("Pregen {} after {} s", status.state(), status.elapsedMillis() / 1000);
            }
        });
    }

    /** Called while the server runs, for example from one of your commands. */
    static void pregenerateAroundSpawn() {
        StartResult result = UltraFastPregenApi.get().start(PregenRequest.circle("minecraft:overworld", 0, 0, 3000));
        if (!result.started()) LOGGER.warn("Pregen did not start: {}", result.message());
    }
}
```

## Runs

```java
PregenApi pregen = UltraFastPregenApi.get();

StartResult started = pregen.start(PregenRequest.square("minecraft:overworld", 0, 0, 2000));
StartResult circle  = pregen.start(PregenRequest.circle("minecraft:the_nether", 100, -250, 1500));
StartResult resumed = pregen.resume("minecraft:overworld");   // the dimension's last run, like /pregen resume

boolean stopping = pregen.stop();                              // after the current batch, like /pregen stop
boolean running  = pregen.isRunning();
pregen.status().ifPresent(status -> show(status));
```

- **Area:** `PregenRequest.square(dimension, centerX, centerZ, radius)` covers blocks `center - radius` to
  `center + radius - 1` on both axes, and every chunk they touch. `PregenRequest.circle(...)` covers every chunk
  whose center lies within the radius; the chunks just outside are left part-generated, as at any area's edge, so
  features along the circle are not cut off. The radius is 1 to 50,000 blocks (`PregenRequest.MAX_RADIUS`).
- **Dimension:** the id of a loaded dimension: `minecraft:overworld`, `minecraft:the_nether`, `minecraft:the_end`
  or a modded one.
- **Settings:** a run uses `config/ultrafastpregen.conf` (batch size, threads, keeping existing chunks; the Bukkit
  plugin: `plugins/UltraFastPregen/ultrafastpregen.conf`), exactly as `/pregen start` does.
- **One run at a time:** `start` and `resume` return at once; the run continues on Ultra Fast Pregen's threads.
  While a run is active, every other start is refused.
- **Refused starts:** `StartResult.started()` is `false` and `message()` says why: no server running, the
  dimension is not loaded, another run is active, the area reaches past the world border, the Moonrise mod is
  installed (not supported yet by the mods; Paper and Folia are, by the plugin), or there is no earlier run to resume.
- **Status:** `status()` describes the active run, or else the last run since the server started; it is empty
  before the first run. Each call returns a new snapshot.

## Events

```java
pregen.addListener(new PregenListener() {
    @Override
    public void onChunksSaved(String dimension, long[] chunks) {
        for (long chunk : chunks) {
            int chunkX = (int) chunk;            // the packing of Minecraft's ChunkPos.asLong (ChunkPos.pack in 26.x)
            int chunkZ = (int) (chunk >>> 32);
            myMap.markForRender(dimension, chunkX, chunkZ);
        }
    }
});
```

| Method | When |
|---|---|
| `onStart(PregenStatus)` | A run started: from `/pregen start`, `/pregen resume`, the API, or after a restart |
| `onProgress(PregenStatus)` | About every 3 seconds while a run is active |
| `onChunksSaved(String dimension, long[] chunks)` | A batch of chunks reached the region files, fully generated and lit. Chunks that already existed and were kept are not included. From here the server loads them from disk like any saved chunk |
| `onFinish(PregenStatus)` | A run ended: `status.state()` is `COMPLETED`, `STOPPED` or `FAILED` |

Every method has an empty default: implement only what you need. `addListener` with the same listener twice has
no effect; `removeListener` removes it.

**Threads:** listeners are called on Ultra Fast Pregen's own threads, never on the server thread. Keep them short,
and hand anything that touches the game to the server, for example `server.execute(() -> ...)`. A listener that
throws is logged and the run goes on; each listener gets its own copy of the `chunks` array.

## Compatibility declarations

Ultra Fast Pregen reads the mixins of every installed mod and turns a fast path off when a mixin changes
something that path replaces; the server log names the mod. A mod can make that decision itself.

**Its mixin does not affect the fast paths.** It only reads values, or changes them in a way that Ultra Fast
Pregen's fast terrain path, cell-skipping noise fill, aquifer shortcut and parallel features reproduce. The
mixin then no longer turns any of them off:

```java
UltraFastPregenApi.get().declareCompatibleMixin("com.example.yourmod.mixin.NoiseRouterMixin");
```

**Its terrain changes are invisible to the mixin check** (patched classes, generators replaced at runtime). Ask
for Minecraft's own terrain steps while your mod is loaded; structures, caves, features, light and saving still
run on every core, and the reason appears in the server log:

```java
UltraFastPregenApi.get().requireMinecraftTerrain("yourmod", "rewrites noise settings at runtime");
```

Call these while your mod initializes, before a run starts. Both can also be declared without any code (and
without depending on Ultra Fast Pregen at all) in your mod's metadata:

```json
// fabric.mod.json
"custom": {
  "ultrafastpregen": {
    "compatible_mixins": ["com.example.yourmod.mixin.NoiseRouterMixin"],
    "minecraft_terrain": "rewrites noise settings at runtime"
  }
}
```

```toml
# META-INF/neoforge.mods.toml (Forge: META-INF/mods.toml)
[modproperties.yourmod]
ultrafastpregen_compatible_mixins = ["com.example.yourmod.mixin.NoiseRouterMixin"]
ultrafastpregen_minecraft_terrain = "rewrites noise settings at runtime"
```

Declare a mixin compatible only after checking it: Ultra Fast Pregen then skips its own safety check for that
mixin, and if the mixin does change what a fast path replaces, the terrain comes out wrong. A server owner can
still force Minecraft's steps for everything with `terrain_pipeline=vanilla`.

## Reference

### `UltraFastPregenApi`

| Member | Description |
|---|---|
| `static final int VERSION` | The API version (now 1). Methods are only added within a version, never changed or removed |
| `static PregenApi get()` | The API; throws `IllegalStateException` before Ultra Fast Pregen has initialized |

### `PregenApi`

| Method | Description |
|---|---|
| `int apiVersion()` | `UltraFastPregenApi.VERSION` of the installed Ultra Fast Pregen |
| `String modVersion()` | The installed version, for example `1.21.1-15` |
| `StartResult start(PregenRequest)` | Starts a run of the area |
| `StartResult resume(String dimension)` | Continues the dimension's last run from Ultra Fast Pregen's save file |
| `boolean stop()` | Asks the active run to stop after its current batch; `false` when nothing runs |
| `boolean isRunning()` | Whether a run is active |
| `Optional<PregenStatus> status()` | The active run, or else the last one since the server started |
| `void addListener(PregenListener)` / `removeListener` | Events of every later run |
| `void declareCompatibleMixin(String mixinClassName)` | See [Compatibility declarations](#compatibility-declarations) |
| `void requireMinecraftTerrain(String modId, String reason)` | See [Compatibility declarations](#compatibility-declarations) |

Every method is thread-safe.

### `PregenRequest`

| Member | Description |
|---|---|
| `static PregenRequest square(String dimension, int centerX, int centerZ, int radius)` | Blocks `center - radius` to `center + radius - 1` on both axes |
| `static PregenRequest circle(String dimension, int centerX, int centerZ, int radius)` | Every chunk whose center lies within the radius |
| `dimension()`, `centerX()`, `centerZ()`, `radius()`, `shape()` | The request; `shape()` is `Shape.SQUARE` or `Shape.CIRCLE` |
| `static final int MAX_RADIUS` | 50,000 blocks: an area 100,000 blocks across, like `/pregen start 100` |

The factories throw `IllegalArgumentException` for a radius outside 1..`MAX_RADIUS`, and
`NullPointerException` for a null dimension.

### `PregenStatus`

A record; a snapshot that does not change after it is handed out.

| Component | Description |
|---|---|
| `request()` | The run's area |
| `state()` | `RUNNING`, `COMPLETED`, `STOPPED` (by `/pregen stop`, the API or the server stopping; resumable) or `FAILED` (by an error, see the server log; resumable). `state().ended()` is `true` for the last three |
| `totalChunks()` | Chunks in the area |
| `doneChunks()` | Chunks finished: generated and saved, or kept because they existed |
| `keptChunks()` | Of these, chunks kept because they already existed or an earlier run generated them |
| `progress()` | 0.0 to 1.0, including the share of the batch in progress |
| `chunksPerSecond()` | The run's average speed over the chunks it generated itself |
| `elapsedMillis()` | Time since the run started, frozen when it ends |
| `etaMillis()` | Estimated time left; `-1` while unknown (the first seconds) or once the run ended |
| `stage()` | What the run is doing, for display, for example `Stage 4/6: Features Decoration` |

### `StartResult`

A record: `started()`, `message()` (why nothing started, or a note about the run), and `status()` (the new run's
status when it started).

### `PregenListener`

`onStart(PregenStatus)`, `onProgress(PregenStatus)`, `onChunksSaved(String dimension, long[] chunks)` and
`onFinish(PregenStatus)`, all with empty defaults. See [Events](#events).

## Recipes

### A map or LoD mod renders every saved chunk

```java
UltraFastPregenApi.get().addListener(new PregenListener() {
    @Override
    public void onChunksSaved(String dimension, long[] chunks) {
        renderQueue.addAll(dimension, chunks);   // render on your own thread; the chunks are on disk now
    }
});
```

### A server management mod pauses pregeneration while players are online

```java
// On player join (server thread):
if (UltraFastPregenApi.get().stop()) pausedByUs = true;

// When the last player leaves:
if (pausedByUs) {
    UltraFastPregenApi.get().status().ifPresent(status ->
            UltraFastPregenApi.get().resume(status.request().dimension()));
    pausedByUs = false;
}
```

`stop()` finishes the current batch first; `resume` continues from Ultra Fast Pregen's save file, skipping every
batch that is already done.

### A Discord bot or web panel shows progress

```java
UltraFastPregenApi.get().addListener(new PregenListener() {
    @Override
    public void onProgress(PregenStatus status) {
        panel.update(status.request().dimension(), status.progress(), status.chunksPerSecond(), status.etaMillis());
    }

    @Override
    public void onFinish(PregenStatus status) {
        panel.finished(status.state() == PregenStatus.State.COMPLETED);
    }
});
```

### A worldgen mod whose mixin only reads terrain values

```json
"custom": { "ultrafastpregen": { "compatible_mixins": ["com.example.yourmod.mixin.HeightLoggerMixin"] } }
```

## Notes

- **Server side:** runs belong to the running server: the dedicated server, or the integrated server of a
  singleplayer world. Between servers, `start` and `resume` are refused with "No server is running.".
- **Server stop:** a run stops with its server: the batch being saved finishes first (the server waits up to 20 s for
  it), and listeners get `onFinish` with `STOPPED`. A run cut short by a server stop or crash continues by itself once
  the server has started again (`resume_after_restart` in the config); listeners get `onStart` for it like for any run.
- **Stability:** `UltraFastPregenApi.VERSION` only grows. Methods are added in later versions, never changed or
  removed; check `apiVersion()` before calling a method a newer version added.
- **Source:** the API is in `src/main/java/io/github/pongsapat3791/ultrafastpregen/api/` of every build in the
  repository, with Javadoc on every class and method.
