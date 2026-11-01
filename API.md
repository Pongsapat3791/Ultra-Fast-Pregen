# Ultra Fast Pregen API

Other mods and plugins can start, resume and stop pregeneration, follow it, receive every chunk it saves, and tell
it how their own world generation changes interact with its fast paths.

- [What is in it](#what-is-in-it)
- [Setup](#setup)
- [Setup for plugins](#setup-for-plugins): Bukkit (CraftBukkit, Spigot, Paper, Folia) and Sponge
- [Quick start](#quick-start)
- [Runs](#runs)
- [Events](#events)
- [Worlds](#worlds)
- [Compatibility declarations](#compatibility-declarations)
- [Reference](#reference)
- [Recipes](#recipes)
- [Notes](#notes)

## What is in it

The API is the package `io.github.pongsapat3791.ultrafastpregen.api`, inside every Ultra Fast Pregen jar from
version 15 on: the mods (Fabric 1.20 to 1.20.6, Forge 1.20 to 1.20.4, Fabric and NeoForge 1.21 and 1.21.1, NeoForge and
Fabric 26.1 to 26.2; before 15.1 each built for one of those versions) and the plugins (the Bukkit plugin for CraftBukkit, Spigot, Paper and Folia 26.2, and Sponge 26.2).
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
| `PregenListener` | Events of runs: start, progress, chunks saved, finish; and of the server: started, stopping |
| `PregenWorlds` | The running server's worlds, for mods that bring chunks into a world from elsewhere (API version 2) |

## Setup

### 1. Compile against an Ultra Fast Pregen jar

Put any Ultra Fast Pregen jar of version 15 or later in your project (for example in `libs/`) and add it as a
compile-only dependency. The API has no Minecraft types, so the jar needs no remapping; one jar works for every
version you build for.

```gradle
dependencies {
    // Fabric (Loom), NeoForge (ModDevGradle) and Forge alike.
    compileOnly files("libs/ultrafastpregen-fabric-1.21-1.21.1-15.1.jar")
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
| `onServerStarted()` | A server, dedicated or a singleplayer world's, ticks for the first time. On the server thread. API version 2 |
| `onServerStopping()` | The server starts stopping. On the server thread. API version 2 |

Every method has an empty default: implement only what you need. `addListener` with the same listener twice has
no effect; `removeListener` removes it.

**Threads:** listeners are called on Ultra Fast Pregen's own threads, never on the server thread. Keep them short,
and hand anything that touches the game to the server, for example `server.execute(() -> ...)`. A listener that
throws is logged and the run goes on; each listener gets its own copy of the `chunks` array.

## Worlds

`PregenApi.worlds()`, from 15.1 (API version 2), gives what a mod needs to bring chunks into a world from elsewhere,
without naming a Minecraft class: folders, what generation depends on, separate worlds, and chunks read and written
through the server's own storage. Everything that depends on Minecraft's version or the loader is behind it, so such a
mod can be one jar for every version. Ultra Fast Pregen Link is built this way.

```java
PregenApi pregen = UltraFastPregenApi.get();
if (pregen.apiVersion() < 2) return;                   // an Ultra Fast Pregen before 15.1
PregenWorlds worlds = pregen.worlds();

// A world of its own that generates like the overworld. Fill its folder first, if the run must join chunks you have.
Path folder = worlds.separateWorldFolder("minecraft:overworld", "mymod:scratch");
String separate = worlds.openSeparateWorld("minecraft:overworld", "mymod:scratch");   // Bukkit gives it another id
pregen.start(PregenRequest.square(separate, 5000, 5000, 512).withBatchSize(1024).keepingExistingChunks());

// When onFinish comes: close it, read its region files, and write what you want into the real dimension.
worlds.closeSeparateWorld(separate);
worlds.writeChunks("minecraft:overworld", PregenWorlds.Store.CHUNKS, chunks, PregenWorlds.WriteRule.ABSENT, false);
```

- **Separate worlds** open with the same generator and seed as the dimension they copy, and what their folder already
  holds is part of them. They are not saved in the server's list of worlds: a separate world still open when the server
  stops is closed with it and does not open again by itself. Folia cannot create a world while it runs, so
  `openSeparateWorld` throws `IOException` there.
- **Where a separate world is** depends on the server: use `separateWorldFolder`, which creates nothing, and never build
  the path yourself. On Bukkit `openSeparateWorld` returns another id than the one asked for.
- **Writes** go through the thread that owns the store, in short tasks, so the server's own loads and saves run in
  between. A chunk the server is saving always stays. Leave out `heldChunks` before writing: on Paper and Folia the check
  and the write of a chunk are two steps, so a chunk the server holds could be overwritten otherwise.
- **Frames:** a chunk's `frame` is its record in a region file, `[length][compression id][compressed NBT]`.

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
| `static final int VERSION` | The API version (now 2: 15 has 1). Methods are only added, never changed or removed. A mod that needs a later version compares `apiVersion()` |
| `static PregenApi get()` | The API; throws `IllegalStateException` before Ultra Fast Pregen has initialized |

### `PregenApi`

| Method | Description |
|---|---|
| `int apiVersion()` | `UltraFastPregenApi.VERSION` of the installed Ultra Fast Pregen |
| `String modVersion()` | The installed version, for example `1.21-1.21.1-15.1` |
| `StartResult start(PregenRequest)` | Starts a run of the area |
| `StartResult resume(String dimension)` | Continues the dimension's last run from Ultra Fast Pregen's save file |
| `boolean stop()` | Asks the active run to stop after its current batch; `false` when nothing runs |
| `boolean isRunning()` | Whether a run is active |
| `Optional<PregenStatus> status()` | The active run, or else the last one since the server started |
| `void addListener(PregenListener)` / `removeListener` | Events of every later run |
| `PregenWorlds worlds()` | See [Worlds](#worlds). API version 2 |
| `void declareCompatibleMixin(String mixinClassName)` | See [Compatibility declarations](#compatibility-declarations) |
| `void requireMinecraftTerrain(String modId, String reason)` | See [Compatibility declarations](#compatibility-declarations) |

Every method is thread-safe.

### `PregenRequest`

| Member | Description |
|---|---|
| `static PregenRequest square(String dimension, int centerX, int centerZ, int radius)` | Blocks `center - radius` to `center + radius - 1` on both axes |
| `static PregenRequest circle(String dimension, int centerX, int centerZ, int radius)` | Every chunk whose center lies within the radius |
| `PregenRequest withBatchSize(int blocks)` | This run's batch size instead of the config's, for a run that must be one batch. A batch too large for the free heap still shrinks. API version 2 |
| `PregenRequest keepingExistingChunks()` | Keeps chunks that exist, whatever `skip_existing_chunks` says, for a run that must join chunks written beforehand. API version 2 |
| `dimension()`, `centerX()`, `centerZ()`, `radius()`, `shape()` | The request; `shape()` is `Shape.SQUARE` or `Shape.CIRCLE` |
| `batchSize()`, `keepsExistingChunks()` | What the two above asked for; `batchSize()` is 0 for the config's |
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

`onStart(PregenStatus)`, `onProgress(PregenStatus)`, `onChunksSaved(String dimension, long[] chunks)`,
`onFinish(PregenStatus)`, `onServerStarted()` and `onServerStopping()`, all with empty defaults. See [Events](#events).

### `PregenWorlds`

Any thread, unless a method says otherwise; methods that wait for the server or the disk block. Methods that need a
server throw `IllegalStateException` when none is running.

| Method | Description |
|---|---|
| `boolean serverRunning()` | Whether a server is running |
| `Path worldFolder()` | The world's folder, the one with level.dat |
| `Path serverFolder()` | The folder relative paths in commands start from |
| `List<String> dimensions()` | The ids of the dimensions loaded now |
| `Path dimensionFolder(String dimension)` | The folder with a loaded dimension's region and entities folders |
| `ServerFacts facts()` | Minecraft's version, the chunk format, the seed, the loader, every mod or plugin, the datapacks, and a hash of each dimension's generator and of what it gives at 64 places. Can take a second or two |
| `int dataVersion()` | Minecraft's chunk format, at once |
| `int neighborChunks()` | How far around an area chunks shape what a run generates |
| `String openSeparateWorld(String like, String id)` | Opens a separate world and returns its id: the same, unless the platform names worlds itself |
| `Path separateWorldFolder(String like, String id)` | The folder `openSeparateWorld` will use. Nothing is created |
| `void closeSeparateWorld(String id)` | Saves and closes it. Its folder stays |
| `List<StoredChunk> readChunks(String dimension, long[] chunks)` | The chunks the store has of these, with a save the server has queued |
| `long[] heldChunks(String dimension, long[] chunks)` | Of these, those the server holds in memory in any state |
| `WriteResult writeChunks(String dimension, Store store, List<ChunkWrite> chunks, WriteRule rule, boolean dryRun)` | Writes chunks or a chunk's mobs (`Store.CHUNKS`, `Store.ENTITIES`). `WriteRule.ABSENT` only where the store has nothing; `ABSENT_OR_PART_GENERATED` also over a part-generated chunk when the new one is full; `ABSENT_OR_UNCHANGED` also over a chunk that still has the hash in `ChunkWrite.baseHash` |
| `void addCommand(String name, CommandHandler handler)` | Adds a command for operators and the owner of a singleplayer world. Call it while your mod initializes |

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
