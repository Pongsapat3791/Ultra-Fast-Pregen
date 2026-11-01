# Ultra Fast Pregen Link 0.3.0

Pre-generates the world **outside the world players are in**, on this server or on others, then adds only the chunks the
world does not have yet.

**It needs [Ultra Fast Pregen](../../README.md) 15.1 or later**, which does the generating. Install both; players do not
need either.

- **One jar:** `ufplink-0.3.0.jar` is a Fabric mod, a Forge mod, a NeoForge mod, a Bukkit plugin and a Sponge plugin. It names
  nothing of Minecraft: everything that depends on the version comes through Ultra Fast Pregen's API, so it runs wherever
  Ultra Fast Pregen does.
- **Install:** the server's `mods` folder (Bukkit servers: `plugins`), next to Ultra Fast Pregen.
- **Requires:** Java 17. The merge tool at the end needs Java 21.
- **Settings:** batch size and threads come from Ultra Fast Pregen's own config. There is nothing else to set up.

**What differs by server:**

- **Folia** cannot create a world while it runs, so it can be a host but not a client, and it has no `/ufplink pregen`.
- **Paper and Folia** write through Paper's chunk system, where a chunk's check and its write are two steps; what the
  server holds is left out first, as everywhere.

## One server: `/ufplink pregen`

```
/ufplink pregen <size in thousands of blocks> [<x> <z> [<dimension>]]
/ufplink pregen resume
/ufplink status
/ufplink cancel
```

The area is generated in a separate world (same generator and seed, in a folder of its own: `dimensions/ufplink/pregen_<dimension>`
in the world on mod servers, and `ufplink_pregen_<dimension>` on Bukkit servers), then merged: chunks the world lacks are added, part-generated chunks the server is not holding are replaced by fully
generated ones, and nothing else is touched (players' builds, chunks the server holds). The separate world is
deleted afterwards. A run cut short (server stop, `/ufplink cancel`) continues with `/ufplink pregen resume`.
Settings (batch size, threads) are Ultra Fast Pregen's.

## Several servers: host and clients

The host is the server players play on; clients are other servers (other machines, or the same one) that generate
tiles and send the chunks back.

1. Stop the host and copy its whole server folder to each other machine, then start them all. A client then has the same
   Minecraft, mods, config, datapacks and seed without setting anything. A large world can stay behind: leave out its
   `region`, `entities` and `poi` folders, and keep the rest of the world folder, which holds the seed.
2. On the host: `/ufplink invite`. It is a host from then on, with no restart, and it shows one line to copy.
3. On each client: run that line, `/ufplink join <invite>`. It connects at once and says so, or says what is wrong.
4. On the host: `/ufplink host pregen <size in thousands of blocks> [<x> <z> [<dimension>]]`.

```
/ufplink invite [<address>]
/ufplink join <invite>
/ufplink leave
/ufplink host status
/ufplink client status
```

- **What a client needs:** the same Minecraft version, seed, mods and datapacks as the host, and the same version of
  Ultra Fast Pregen and of this mod, which a copy of the folder has. A server that differs is refused, and it says what
  differs. A server made by hand needs the host's seed in `level-seed` of `server.properties` before its first start.
- **Two servers on one machine:** a copy needs a `server-port` of its own, and a `port` in `ufpmanager.conf`.
- **The firewall:** clients connect to the host on port 25599, or the next free one, which the invite carries. Allow it
  through the host's firewall.
- **The address:** the invite holds all of this machine's addresses, and a client takes the first that answers. So machines
  on different subnets work as long as there is a route between them, and the host's firewall takes connections from that
  subnet: a rule limited to the local subnet lets none in. For an address the machine does not know it has, such as a
  public one or a port forward's, give it: `/ufplink invite <address>`.
- **A restart** changes nothing: a client connects again by itself. `/ufplink leave` ends a server's part in the network.
- **The invite** holds the secret: keep it private. Anyone with it can send chunks to the host.

All of this lives in `ufplink-network.conf`, in `config` (Bukkit servers: `plugins/UltraFastPregenLink`), which also has
`name`, `tile_chunks` and `local_worker`. Editing it by hand needs a restart.

The host cuts the area into tiles of `tile_chunks` chunks (64 by default) and gives each free client a tile none of
whose neighbors is being generated, together with the host's chunks around it. The client generates the tile so that
it joins those chunks, and sends back the chunks that are new or changed. The host adds the new ones and replaces
one of its own only if it is still exactly what it sent; chunks the server holds are never touched. A client that
disconnects loses its tile to the next one.

The connection is authenticated (both sides prove they know the secret) but **not encrypted**: use it on a LAN or a
VPN, and keep the secret private.

With `local_worker=true` the host also generates tiles itself, as one more client. That also lets a host work with
no other machine. Tiles the host already has fully generated are not sent to anyone.

**Who generates what:** [Ultra Fast Pregen Manager](../../manager/README.md), a web page on the host, shows the machines
connected and those refused, and lets you plan the tiles of each machine on a map before a run. `/ufplink host pregen`
without a plan gives every machine the next free tile.

**The host and its clients need the same version of the mod:** 0.3.0 speaks protocol 2.

## API

`io.github.pongsapat3791.ufplink.api` has plain Java types only, like Ultra Fast Pregen's API: `UfpLinkApi.get()` gives
the role, the machines, the connections refused, the plan of an area, starting and stopping a plan or a pregen on this
server alone, and a run's tiles. The Manager uses nothing else. The source has the details.

## From another world: `/ufplink import`

```
/ufplink check "<world folder>"
/ufplink import "<world folder>" [force]
```

Adds the chunks of another world (for example one generated on another machine) that this world does not have.
Both worlds need `ufplink-fingerprint.json` (written when the server starts): a different seed or Minecraft version
is refused; different mods, datapacks or generation need `force`.

## Paper and other servers: the merge tool

The jar is also a command-line tool (Java 21) that merges a generated world into the world of a **stopped** server,
without Minecraft or any mod on that server. It is meant for servers Ultra Fast Pregen does not run on, such as Purpur or
older Paper versions, and works on any world from Minecraft 1.20 on.

```
java -jar ufplink-0.3.0.jar check "<generated world>" "<target server folder>"
java -jar ufplink-0.3.0.jar merge "<generated world>" "<target server folder>"
```

1. Generate the area on a server with this mod (`/ufplink pregen`), with the same Minecraft version and seed
   as the target and without mods or datapacks that change generation. Stop that server.
2. Stop the target server.
3. `check` reports what `merge` would do without writing anything; then run `merge`.

Each argument can be a server folder (with `server.properties`) or a world folder (with `level.dat`). Give the
target's server folder so the tool also reads `spigot.yml`, `bukkit.yml` and Paper's world settings. Bukkit servers keep the
nether and the end in places of their own (`world_nether/DIM-1` before 26.1, `world_nether/dimensions/minecraft/the_nether`
on Spigot 26.2); the tool finds them.

The tool refuses when the target's server is running or the generated world is open in a server (both hold their
`session.lock`), when the seed differs, and when the generated world is from a newer Minecraft. Without `--force`
it also refuses when:
- the generator settings (world type, in `level.dat` or from 26.1 in `data/minecraft/world_gen_settings.dat`) or the
  datapacks differ;
- `spigot.yml` changes structure seeds (`seed-village` and the others), Paper's `feature-seeds` are random or set,
  or `bukkit.yml` gives the world a generator plugin;
- the free disk space looks too small.

It warns when the generated world was made with mods (listed in its `ufplink-fingerprint.json`).

The merge rule is the same as `/ufplink import`. Chunks the target lacks are added with their mobs, and
part-generated chunks are replaced by fully generated ones. Every other chunk stays as it is.

Each region file is written to a copy first and swapped in only when complete. A merge stopped half-way therefore
leaves each file either as it was or complete, and running it again continues. The target's points of interest
(beds, job sites) for merged chunks are removed, and the server rebuilds them from the blocks when the chunks load.
Paper relights the chunks at the same time. Options: `--dimension <id>` (repeatable), `--threads <n>`, `--force`.

## Tests

The jar with SHA-1 `482b1bae655d3362e1e36a205355f5f2bbb6fd81`, with Ultra Fast Pregen 15.1 and Ultra Fast Pregen Manager 0.2.0
(`50d394c592186d9608fd226d8a3d8c52a856237e`). The scripts are in [`manager/pve`](../../manager/pve); they use the Manager's
API and the server console.

- **Every server of Ultra Fast Pregen 15.1, 27 in all** (Fabric 1.20 to 1.20.6, 1.21, 1.21.1 and 26.1 to 26.2, Forge 1.20
  to 1.20.4, NeoForge 1.21, 1.21.1, 26.1.2 and 26.2, CraftBukkit, Spigot, Paper, Folia and SpongeVanilla 26.2), a host and a
  client on one machine:
  - a plan of 9 tiles over 1,000 × 1,000 blocks, every tile generated by the client and complete on the host's disk;
  - `/ufplink pregen` in all three dimensions, each merged and its separate world deleted;
  - a run stopped and one cut short by a server stop, both continued in the same separate world;
  - one region of that plan against the same region generated by the client in its own world, drawn by the Manager: 0 of
    200,704 pixels differ, leaving out the outer chunks, which wait for neighbors that run did not make;
  - `/ufplink import` of the client's world, with a mob, and the merge tool from the stopped host into the stopped client.

  Folia hosts a Paper client, as it cannot create a world. All 27 passed all of it, with no error or warning from Link.
- **On Windows:** the same on 12 of those servers: Fabric 1.20.1, 1.20.5, 1.21.1 and 26.2, Forge 1.20.1, NeoForge 1.21.1 and
  26.2, CraftBukkit, Spigot, Paper, Folia and SpongeVanilla 26.2. All passed.
- **Set up with no file written by hand**, on 12 servers, and on 4 of them again on Windows: `/ufplink invite` and
  `/ufplink join` from the console and from the Manager's page; a plan; both servers restarted, and connected again by
  themselves; `/ufplink leave`; an invite whose first address answers nothing, where the client took the next; a wrong invite,
  a server's own invite and a server of another seed, each refused with the reason; a second host on the machine, which took
  the next free port.
- **Machines on two subnets:** the host on a machine at 10.0.0.200, the client in WSL behind another machine's NAT at
  172.22.x, Fabric 1.21.1, set up with those two commands only: 4 of 4 tiles in 105 s.
- **A client made by copying the host's stopped server folder** to another machine, then `/ufplink join`: the whole folder on
  Fabric 1.21.1; without `region`, `entities` and `poi`, and with `level-seed` empty, on Fabric 1.21.1, Fabric 26.2 and
  Paper 26.2. 4 of 4 tiles each.
- **With C2ME:** all of the first list on Fabric 1.21.1, Fabric 26.2 and NeoForge 1.21.1. All passed; C2ME's own lines about
  classes of other mods are the only ones in the logs.

After these runs Ultra Fast Pregen's jars changed once more, with the last fixes in its
[changelog](../../CHANGELOG.md#151). The first list was run again on the released 15.1 jars on Forge and Fabric 1.20.1, Fabric
and NeoForge 1.21.1, Fabric and NeoForge 26.2, Paper, Folia and SpongeVanilla 26.2, with the same result.

**Not tested yet:** a public address or a port forward with `/ufplink invite <address>`.

## Building

```powershell
.\gradlew.bat -p ports/fabric-1.21.1 build
.\gradlew.bat -p link/core build
```

The jar compiles against a jar of Ultra Fast Pregen with API version 2, and against small stand-ins for the loaders' classes
and slf4j (`src/stubs`), which are not in the jar.
