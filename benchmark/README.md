# Ultra Fast Pregen benchmark

A chunk pregeneration speed benchmark on a NeoForge server, for Windows and Linux
(written for a rented AMD Ryzen 9 9950X3D, but it works on any machine).

The default is **Minecraft 26.1.2** (NeoForge 26.1.2.122), the version of Bye?Pregen's chart.
Add `--mc 1.21.1` to every command to measure on 1.21.1 (NeoForge 21.1.259). Each version has its own server, mods and logs
in `work/<version>` and its own results in `results-<version>.csv`.

## What it measures

- Every run starts a fresh server on a fresh world (seed 42) and generates a square of radius 1500 blocks
  (3000 x 3000 blocks, about 35,000 chunks) away from spawn, recorded as **chunks per second (CPS)**.
- Two tools:
  - **Vanilla** = Minecraft's own world generation, driven by the Chunky mod
  - **Ultra Fast Pregen**
- Two worlds: normal worldgen (`vanilla-*`) and Tectonic + WWOO, a heavy worldgen mod pair (`tw-*`).
- Optionally with C2ME (`*-c2me`), and in the Nether or the End (`--dimension`).

**Why "Vanilla" uses Chunky:** Minecraft has no pregeneration command of its own. Chunky only asks the server for chunks and lets
Minecraft generate them as usual, so it measures the speed of Minecraft's own world generation. Bye?Pregen's published chart was made
the same way, so the numbers are comparable.

## Requirements

- Python 3.8 or newer (no packages to install); on Linux use `python3` instead of `python`
- Internet access during `setup` (it downloads Java 25 and the NeoForge 26.1.2.122 server, or Java 21 and NeoForge 21.1.259 with
  `--mc 1.21.1`, and the mods from Modrinth)
- About 5 GB of free disk space per version
- RAM: `--heap 24G` is recommended on a 64 GB machine (the default is 16G)
- Port 25565 must be free (do not leave another Minecraft server running)

## Steps

1. Copy the `benchmark` folder (`bench.py`, `chart.py`, `summary.py`, `c2me-26.1.2-nodfc.toml`, `c2me-nodfc.toml`)
   to the machine and put `ultrafastpregen-neoforge-26.1.2-14.jar` next to `bench.py`
   (`--mc 1.21.1` uses `ultrafastpregen-neoforge-1.21.1-14.jar`).
2. Install everything (once):
   ```
   python bench.py setup
   ```
3. Run the 4 main configurations, 2 runs each (radius 1500 by default):
   ```
   python bench.py run --repeat 2 --heap 24G
   ```
   - `--radius` must be a multiple of 500 and at least 500 (Ultra Fast Pregen's `/pregen start` takes the size in thousands of blocks);
     otherwise `bench.py` says so and stops before running anything.
4. (Optional) Measure with C2ME too (`--only c2me` runs only the 4 configurations with C2ME):
   ```
   python bench.py run --repeat 2 --heap 24G --only c2me
   python bench.py run --repeat 2 --heap 24G --only c2me --c2me-dfc off
   ```
   - The second line turns C2ME's density function compiler off (with `c2me-26.1.2-nodfc.toml`, or `c2me-nodfc.toml` on 1.21.1).
     Those results are recorded as `<configuration>-nodfc`, for example `tw-ufp-c2me-nodfc`, so they stay apart from the DFC-on runs
     and show up as "+ C2ME (DFC off)" rows.
   - `--c2me` runs all 8 configurations (the 4 normal ones again too).
5. (Optional) The Nether or the End: `python bench.py run --repeat 2 --heap 24G --dimension the_nether --c2me`
   (Tectonic and WWOO change only the overworld, so other dimensions run the vanilla-world configurations).
   The results go to `results-<version>-<dimension>.csv`.
6. Show the summary table:
   ```
   python bench.py report
   ```
7. Draw the charts:
   ```
   python chart.py --cpu "AMD Ryzen 9 9950X3D (16C/32T)" --ram "64 GB" --os "Windows 11 Pro"
   ```
   - Reads `results-26.1.2.csv` by default; for 1.21.1 use `python chart.py results-1.21.1.csv ...`
   - Writes `chart.svg`, `chart-c2me.svg` (with C2ME results), `chart-c2me-nodfc.svg` (with DFC-off results) and `chart.html`
   - Without `--cpu/--ram/--os` it uses the machine that runs `chart.py`
   - If the results file mixes several radii, pick one with `--radius 1500`
   - Open `chart.html` in a browser to see the charts with a table of the numbers
8. (Optional) Every result on one image: a row without C2ME and a row with C2ME, one panel per world
   (Overworld, Tectonic + WWOO, Nether, End):
   ```
   python summary.py --cpu "AMD Ryzen 9 9950X3D (16C/32T)" --ram "64 GB" --os "Windows 11 Pro"
   ```
   - Reads `results-26.1.2.csv`, `results-26.1.2-the_nether.csv` and `results-26.1.2-the_end.csv` (those that exist) and writes `summary.svg`
   - `--mc 1.21.1` for 1.21.1; `--out` picks the output file

## How long it takes

It depends on the machine. The slowest part is Chunky on Tectonic + WWOO, and every run also starts and stops a server, which takes
several minutes. With `--repeat 2` expect hours (possibly two or three), more with C2ME. A run over 4 hours counts as a timeout
(`--timeout`, in seconds).

## Tips

- Close every other program and do not play or do heavy work while it runs.
- Keep the machine plugged in and set the power plan to High performance / Best performance
  (on Linux, the `performance` CPU governor if you can).
- `results-<version>.csv` keeps every run, and the report and charts average all rows; delete it to start over.
- Each run's log is in `work/<version>/logs` (file name = configuration + time).
- A failed run (server crash, timeout, Chunky stopped, old world not deletable) shows as `<configuration>: FAILED: <reason>`,
  is not recorded, and the other runs go on. At the end the report is shown and the exit status is 1.
  Look at the log, then run just the missing configurations, for example `python bench.py run --configs tw-chunky tw-ufp`.
- `--keep-worlds` keeps each run's world for a later look (it takes a lot of disk space).
- Repeats run the configurations in reverse order every other time, so no configuration gains from the machine warming up or JIT warm-up.

## Reading the results

`python bench.py report` prints a table like this (example numbers, not real results):

```
world                                     Vanilla  Ultra Fast Pregen  speedup
Vanilla worldgen                         35.2 (2)           78.8 (2)    2.24x
Tectonic + WWOO                          22.5 (2)           41.0 (2)    1.82x
Tectonic + WWOO + C2ME (DFC off)         25.0 (2)           80.0 (2)    3.20x
```

- The numbers are average CPS, higher is faster; the number in brackets is how many runs were averaged.
- `speedup` = Ultra Fast Pregen's CPS divided by Vanilla's (2.24x = 2.24 times as fast).
- "+ C2ME" rows have the DFC on (C2ME's default); "+ C2ME (DFC off)" rows ran with `--c2me-dfc off`.
- The report reads the CPU name from the system (Windows registry / `/proc/cpuinfo`); for charts, pass `--cpu` with the real specs.
- Ultra Fast Pregen runs on the CPU only, without OpenCL / GPU acceleration.
- Timing: Chunky's "Total time" probably does not include writing the chunks to disk (the server saves them later), while Ultra Fast
  Pregen's time includes saving. If anything, that slightly favors Chunky.
- In the charts each panel (Vanilla worldgen / Tectonic + WWOO) has its own y-axis: compare bar heights within one panel only, and read
  the CPS and speedup numbers.
- If the runs of one configuration differ a lot (more than about 5%), something disturbed the machine: run them again.
