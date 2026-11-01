# Ultra Fast Pregen Manager

A web page inside your server: pick an area on a map, press Start, and watch the run. It drives
[Ultra Fast Pregen](../README.md) and Ultra Fast Pregen Link through their APIs, and shows the machines of a Link
network and which of them generates what.

Install it next to Ultra Fast Pregen, and next to Link if you use it. Without Ultra Fast Pregen the page opens and says so.

- **One jar:** `ufpmanager-0.2.0.jar` is a Fabric mod, a Forge mod, a NeoForge mod, a Bukkit plugin and a Sponge plugin. It
  names nothing of Minecraft, so it needs no build per version.
- **Install:** the server's `mods` folder (Bukkit servers: `plugins`). Players do not need it.
- **With Link:** Link 0.3.0 and Ultra Fast Pregen 15.1 or later. Alone, it also runs with Ultra Fast Pregen 15.
- **Requires:** Java 17.

## Open the page

Run `/ufpmanager` in the server's console, or in a singleplayer world you own. It shows a link with a login code: open
it, and the browser stays logged in. A code works once, for 10 minutes, so the link is safe in a log. The page's login box
takes the code too. With Ultra Fast Pregen 15, which has no such command, use the token in the file below.

**On another machine:** the page is not encrypted. An SSH tunnel keeps it private:

```bash
ssh -L 25580:127.0.0.1:25580 you@your-server
```

Then open the same link on your own machine. On a LAN or a VPN you can set `bind=0.0.0.0` instead, and the link then shows
this machine's address. Do not open the port to the internet: the token and everything else travel in the clear.

The settings are in `config/ufpmanager.conf` (Bukkit servers: `plugins/UltraFastPregenManager/ufpmanager.conf`):

| Key | Default | What it does |
|---|---|---|
| `enabled` | `true` | `false` turns the page off |
| `bind` | `127.0.0.1` | The address the page listens on. `0.0.0.0` opens it to the network |
| `port` | `25580` | The page's port |
| `token` | a new random one | Whoever has it can start and stop pregens |

## Ultra Fast Pregen

- **The map** shows the chunks each world has on disk, read from the region files. Click to set the center, drag to move,
  use the wheel to zoom.
- **Terrain:** the button shows those chunks as they look from above: each block's color, biomes, the sea floor through
  the water, and the slopes. In the Nether the view starts below the bedrock roof. Only chunks on disk can be shown, so
  an area nobody has generated stays empty, and Minecraft's generator is not run for a preview.
  The colors come from a table in this mod; a modded block gets one from its name. Pictures are drawn by two
  low-priority threads and kept for as long as the region file does not change.
- **A new run:** a square or a circle, any size up to 100,000 blocks, in any world.
- **A run:** progress, speed, time left, stage, and the chunks appearing on the map as they are saved. It stops after the
  current batch, and a stopped run resumes.
- **Settings:** batch size, threads and the rest of `ultrafastpregen.conf`, as with `/pregen set`.

## Ultra Fast Pregen Link

**Set up:** a copy of the host's server folder makes the simplest client ([details](../link/core/README.md)). On the server
players play on, press Make this server the host and copy the invite. On each other server, paste it in the Link tab and press
Join that host, or run it as a command there.

On a host, the Link tab shows:

- **Machines:** every client connected: threads, heap, system, address, what it does now, tiles done and its speed. A
  machine the host refused is listed too, with the reason (the secret, or a world that generates differently).
- **The plan:** choose an area and press Make a plan. The host cuts it into tiles and shares them among the machines by
  their threads, one block of tiles each, so machines work apart: neighbouring tiles cannot be generated at the same time.
  Pick a machine and drag over the tiles to change who generates what. A machine takes its own tiles first; with the box
  ticked, one with nothing left also takes the others', and tiles of a machine that is not connected go to whoever is free.
  Without it, those tiles wait for their machine.
- **The run:** each tile lit in its machine's color as it is generated and done.

On a client the tab shows its connection to the host. Clients do not need the Manager.

## Tests

The jar with SHA-1 `50d394c592186d9608fd226d8a3d8c52a856237e`, with Ultra Fast Pregen 15.1 and Link 0.3.0
(`482b1bae655d3362e1e36a205355f5f2bbb6fd81`). The scripts are in `pve/`; they use the page's API and nothing else, apart from
the server console.

- **Every server of Ultra Fast Pregen 15.1, 27 in all** (Fabric 1.20 to 1.20.6, 1.21, 1.21.1 and 26.1 to 26.2, Forge 1.20
  to 1.20.4, NeoForge 1.21, 1.21.1, 26.1.2 and 26.2, CraftBukkit, Spigot, Paper, Folia and SpongeVanilla 26.2), with Link: the
  page showed both mods and the client among the machines, ran a plan of 9 tiles, and drew the same terrain for a region of it
  on the host and on the client ([Link's tests](../link/core/README.md#tests)). No error came from the Manager. NeoForge
  26.1.2 and 26.2 log three lines about Netty's kqueue on Linux, with or without it.
- **On Windows:** the same on 12 of those servers.
- **The login and set-up**, on 12 servers, and on 4 of them again on Windows: `/ufpmanager` gives a code; the page trades it
  for the token once; a wrong code and the same code again are refused; a host and its invite made on the page, a client
  joined with it, a wrong invite and a server's own invite refused, and leaving from the host's page.
- **On Ultra Fast Pregen 15, the released jars,** on 10 servers: page, 500 × 500 blocks started from the API (1,024 chunks),
  the chunks found in the region files, and the terrain.
- **On six of them** (Fabric 1.20.1, NeoForge 1.21.1, Fabric 26.2, Paper, Folia, Sponge), 30 checks each: settings saved
  and used by the next run, a value that cannot add a line to the file, a circle, the Nether, stop and resume, the map of
  the Nether, terrain pictures, a second start refused, a wrong size and an unknown world refused, a wrong token and no
  token refused, and paths in a dimension or an address refused.
- **Link, a host and two clients on one machine**, 21 checks on Fabric 1.21.1 and on Paper 26.2: another seed and a wrong
  secret listed under Refused with the reason; a client without the Manager; a plan edited on the page and followed tile by
  tile; a machine killed in the middle of a tile, whose tiles wait for it and which finishes them when it is back; with helping
  on, the other machine taking those tiles; Stop ending a run after the tiles in progress. Every tile was complete on disk.
- **In singleplayer**, with the mods in a real game client: Fabric 1.20.1 and 1.21.1, Forge 1.20.1, NeoForge 1.21.1, Fabric
  and NeoForge 26.2: the page, the three worlds, a run, the terrain and Link.
- **In a browser** (the Chromium of the Claude desktop app): Start, the live run, the map filling up, a plan painted by
  dragging, and the terrain in all three dimensions, on Manager 0.1.0; the login, the set-up and the invite on 0.2.0, one
  build before the invite began to carry several addresses.

After these runs Ultra Fast Pregen's jars changed once more, with the last fixes in its
[changelog](../CHANGELOG.md#151). On the released 15.1 jars, the first list ran again on 9 servers and the singleplayer runs on
all six, with the same result.

**Not tested yet:** other browsers, and worlds with modded blocks in the terrain view.

## Building

```powershell
.\gradlew.bat -p ports/fabric-1.21.1 build
.\gradlew.bat -p link/core build
.\gradlew.bat -p manager build
```

The jar compiles against a released Ultra Fast Pregen jar and Link's jar, and against small stand-ins for the loaders'
classes (`src/stubs`), which are not in the jar.
