#!/usr/bin/env python3
"""Pregeneration throughput benchmark: vanilla Minecraft generation vs Ultra Fast Pregen.

Runs on Windows or Linux with Python 3.8+ and nothing else installed:
    python bench.py setup                 # Java 25, NeoForge 26.1.2 server, mods (downloads)
    python bench.py run                   # the 4 default configurations once, radius 1500
    python bench.py run --c2me            # all 8 (the 4 again with C2ME)
    python bench.py run --repeat 2 --only vanilla
    python bench.py run --only c2me --c2me-dfc off
    python bench.py report                # table from results-26.1.2.csv
    python bench.py setup --mc 1.21.1     # the same on Minecraft 1.21.1 (Java 21, NeoForge 21.1)
    python bench.py run --dimension the_nether --c2me   # the Nether (or the_end): vanilla worlds

Minecraft 26.1.2 (the version of Bye?Pregen's published chart) is the default. Each version has
its own Java, server, mods and logs under work/<version> and its results in results-<version>.csv;
runs in the Nether or the End go to results-<version>-<dimension>.csv. Tectonic and WWOO change
only the overworld, so other dimensions run the vanilla-world configurations unless --configs or
--only says otherwise.
Each run starts the same NeoForge server with a fresh world (seed 42), generates a
square of side 2 x radius blocks centered away from spawn, and records chunks per second from
the tool's own timing. "Vanilla" is Minecraft's own chunk generation, driven by Chunky (which
only asks the server for the chunks; the same method as Bye?Pregen's published chart), timed by
its "Task finished ... Total time"; Ultra Fast Pregen by its "completed successfully in ...".
Worlds: vanilla, and Tectonic + William Wythers' Overhauled Overworld (WWOO). Optional: the same
with C2ME; runs with --c2me-dfc off are stored as <config>-nodfc. A failed run is logged and
skipped (nothing is written to results.csv for it) and the exit status is 1.
"""
import argparse
import csv
import datetime
import json
import os
import platform
import queue
import re
import shutil
import ssl
import subprocess
import sys
import threading
import time
import urllib.error
import urllib.request
import zipfile

HERE = os.path.dirname(os.path.abspath(__file__))
WORK = os.path.join(HERE, "work")
MODRINTH = "https://cdn.modrinth.com/data/"

# Minecraft version -> its NeoForge, Java, Ultra Fast Pregen jar (copied next to bench.py), C2ME's
# full config with the density function compiler off (next to bench.py), and mods: key -> (jar, url).
VERSIONS = {
    "26.1.2": {
        "neoforge": "26.1.2.122",
        "java": 25,
        "ufp": "ultrafastpregen-neoforge-26.1.2-26.2-15.1.jar",
        "c2me_nodfc": "c2me-26.1.2-nodfc.toml",
        "mods": {
            "chunky": ("Chunky-NeoForge-1.5.4.jar", MODRINTH + "fALzjamp/versions/EyCqftOK/Chunky-NeoForge-1.5.4.jar"),
            "c2me": ("c2me-neoforge-mc26.1.2-0.4.0-alpha.0.100.jar",
                     MODRINTH + "COlSi5iR/versions/LEHKpyxE/c2me-neoforge-mc26.1.2-0.4.0-alpha.0.100.jar"),
            "tectonic": ("tectonic-3.0.28-neoforge-26.1.jar",
                         MODRINTH + "lWDHr9jE/versions/RNLd3nvP/tectonic-3.0.28-neoforge-26.1.jar"),
            "lithostitched": ("lithostitched-1.8.1-neoforge-26.1.jar",
                              MODRINTH + "XaDC71GB/versions/pLlJIqyp/lithostitched-1.8.1-neoforge-26.1.jar"),
            "wwoo": ("wwoo-neoforge-26.1.2-2.7.0.jar",
                     MODRINTH + "II7t6llZ/versions/BYClfkDd/wwoo-neoforge-26.1.2-2.7.0.jar"),
            "cristellib": ("cristellib-neoforge-26.1.2-3.1.13.jar",
                           MODRINTH + "cl223EMc/versions/WWbbl4Rn/cristellib-neoforge-26.1.2-3.1.13.jar"),
            "cloth": ("cloth-config-26.1.154.jar", MODRINTH + "9s6osm5g/versions/TimoYzse/cloth-config-26.1.154.jar"),
        },
    },
    "1.21.1": {
        "neoforge": "21.1.259",
        "java": 21,
        "ufp": "ultrafastpregen-neoforge-1.21-1.21.1-15.1.jar",
        "c2me_nodfc": "c2me-nodfc.toml",
        "mods": {
            "chunky": ("Chunky-NeoForge-1.4.23.jar", MODRINTH + "fALzjamp/versions/LuFhm4eU/Chunky-NeoForge-1.4.23.jar"),
            "c2me": ("c2me-neoforge-mc1.21.1-0.4.0-alpha.0.123.jar",
                     MODRINTH + "COlSi5iR/versions/avwWYPbG/c2me-neoforge-mc1.21.1-0.4.0-alpha.0.123.jar"),
            "tectonic": ("tectonic-3.0.28-neoforge-21.1.jar",
                         MODRINTH + "lWDHr9jE/versions/n4iyW7aB/tectonic-3.0.28-neoforge-21.1.jar"),
            "lithostitched": ("lithostitched-1.8.1-neoforge-21.1.jar",
                              MODRINTH + "XaDC71GB/versions/cZu4cHY3/lithostitched-1.8.1-neoforge-21.1.jar"),
            "wwoo": ("wwoo-neoforge-2.6.7.jar", MODRINTH + "II7t6llZ/versions/sU7oaRZv/wwoo-neoforge-2.6.7.jar"),
            "cristellib": ("cristellib-neoforge-1.21.1-3.1.7.jar",
                           MODRINTH + "cl223EMc/versions/Sduz0AWP/cristellib-neoforge-1.21.1-3.1.7.jar"),
            "cloth": ("cloth-config-15.0.140-neoforge.jar",
                      MODRINTH + "9s6osm5g/versions/izKINKFg/cloth-config-15.0.140-neoforge.jar"),
        },
    },
}
DEFAULT_MC = "26.1.2"

# The chosen version's settings and paths, set by use_version() from --mc.
MC = NEOFORGE = UFP_JAR = C2ME_NODFC = None
DIMENSIONS = ("overworld", "the_nether", "the_end")
JAVA = 0
MODS = {}
JDK_DIR = SERVER = JARS = LOGS = RESULTS = None


def use_version(mc):
    global MC, NEOFORGE, JAVA, UFP_JAR, C2ME_NODFC, MODS, JDK_DIR, SERVER, JARS, LOGS, RESULTS
    v = VERSIONS[mc]
    MC, NEOFORGE, JAVA, UFP_JAR, C2ME_NODFC, MODS = mc, v["neoforge"], v["java"], v["ufp"], v["c2me_nodfc"], v["mods"]
    base = os.path.join(WORK, mc)
    JDK_DIR = os.path.join(base, "jdk")
    SERVER = os.path.join(base, "server")
    JARS = os.path.join(base, "jars")
    LOGS = os.path.join(base, "logs")
    RESULTS = os.path.join(HERE, f"results-{mc}.csv")

TW = ["tectonic", "lithostitched", "wwoo", "cristellib", "cloth"]

# name -> (tool, mods)
CONFIGS = {
    "vanilla-chunky": ("chunky", ["chunky"]),
    "vanilla-ufp": ("ufp", ["ufp"]),
    "tw-chunky": ("chunky", ["chunky"] + TW),
    "tw-ufp": ("ufp", ["ufp"] + TW),
    "vanilla-chunky-c2me": ("chunky", ["chunky", "c2me"]),
    "vanilla-ufp-c2me": ("ufp", ["ufp", "c2me"]),
    "tw-chunky-c2me": ("chunky", ["chunky", "c2me"] + TW),
    "tw-ufp-c2me": ("ufp", ["ufp", "c2me"] + TW),
}
DEFAULT_CONFIGS = ["vanilla-chunky", "vanilla-ufp", "tw-chunky", "tw-ufp"]
NODFC = "-nodfc"  # suffix of runs with C2ME's density function compiler off
# report rows: (config suffix, label suffix)
C2ME_VARIANTS = (("", ""), ("-c2me", " + C2ME"), ("-c2me" + NODFC, " + C2ME (DFC off)"))


def log(message):
    print(f"[{datetime.datetime.now():%H:%M:%S}] {message}", flush=True)


def download(url, dest):
    if os.path.exists(dest):
        return
    log(f"download {url}")
    tmp = dest + ".part"
    req = urllib.request.Request(url, headers={"User-Agent": "ultrafastpregen-benchmark"})
    try:
        with urllib.request.urlopen(req) as response, open(tmp, "wb") as out:
            shutil.copyfileobj(response, out)
    except urllib.error.URLError as failure:
        curl = windows_curl()
        if not isinstance(failure.reason, ssl.SSLCertVerificationError) or not curl:
            raise
        # A fresh Windows holds only the root certificates something has asked it for, and Python never asks;
        # Windows' own curl goes through Windows, which fetches a missing root as a browser does.
        reason = getattr(failure.reason, "verify_message", None) or failure.reason
        log(f"Python could not verify the certificate ({reason}): downloading with {curl}")
        subprocess.check_call([curl, "-fsSL", "--retry", "3", "-A", "ultrafastpregen-benchmark", "-o", tmp, url])
    os.replace(tmp, dest)


def windows_curl():
    """Windows' own curl.exe (Windows 10 1803 and later), which uses Windows' certificate store; None elsewhere."""
    path = os.path.join(os.environ.get("SystemRoot", r"C:\Windows"), "System32", "curl.exe")
    return path if os.name == "nt" and os.path.isfile(path) else None


def java_exe():
    name = "java.exe" if os.name == "nt" else "java"
    for root, _, files in os.walk(JDK_DIR):
        if name in files and os.path.basename(root) == "bin":
            return os.path.join(root, name)
    raise SystemExit("Java not installed: run  python bench.py setup")


def setup(args):
    os.makedirs(JARS, exist_ok=True)
    # Java (Eclipse Temurin) from Adoptium.
    if not os.path.isdir(JDK_DIR):
        osname = {"Windows": "windows", "Linux": "linux", "Darwin": "mac"}[platform.system()]
        arch = "x64" if platform.machine().lower() in ("amd64", "x86_64") else "aarch64"
        ext = "zip" if osname == "windows" else "tar.gz"
        archive = os.path.join(os.path.dirname(JDK_DIR), "jdk." + ext)
        download(f"https://api.adoptium.net/v3/binary/latest/{JAVA}/ga/{osname}/{arch}/jdk/hotspot/normal/eclipse", archive)
        os.makedirs(JDK_DIR)
        if ext == "zip":
            zipfile.ZipFile(archive).extractall(JDK_DIR)
        else:
            subprocess.check_call(["tar", "-xzf", archive, "-C", JDK_DIR])
    java = java_exe()
    if os.name != "nt":
        os.chmod(java, 0o755)
    # NeoForge server.
    if not os.path.isdir(os.path.join(SERVER, "libraries", "net", "neoforged", "neoforge", NEOFORGE)):
        os.makedirs(SERVER, exist_ok=True)
        installer = os.path.join(os.path.dirname(SERVER), f"neoforge-{NEOFORGE}-installer.jar")
        download(f"https://maven.neoforged.net/releases/net/neoforged/neoforge/{NEOFORGE}/neoforge-{NEOFORGE}-installer.jar",
                 installer)
        log("installing the NeoForge server")
        subprocess.check_call([java, "-jar", installer, "--install-server", SERVER])
    for key, (name, url) in MODS.items():
        if args.mods and key not in args.mods:
            continue
        download(url, os.path.join(JARS, name))
    ufp = os.path.join(HERE, UFP_JAR)
    if not os.path.exists(ufp):
        raise SystemExit(f"Put {UFP_JAR} next to bench.py")
    shutil.copy(ufp, os.path.join(JARS, UFP_JAR))
    log(f"setup done (Minecraft {MC})")


def jar_for(key):
    return UFP_JAR if key == "ufp" else MODS[key][0]


def jvm_args(args):
    heap = args.heap
    return [f"-Xms{heap}", f"-Xmx{heap}", "-XX:+UseG1GC", "-XX:+ParallelRefProcEnabled", "-XX:MaxGCPauseMillis=200"]


class Server:
    def __init__(self, args, log_path):
        java = java_exe()
        argfile = os.path.join("libraries", "net", "neoforged", "neoforge", NEOFORGE,
                               "win_args.txt" if os.name == "nt" else "unix_args.txt")
        cmd = [java] + jvm_args(args) + ["@" + argfile, "nogui"]
        self.proc = subprocess.Popen(cmd, cwd=SERVER, stdin=subprocess.PIPE, stdout=subprocess.PIPE,
                                     stderr=subprocess.STDOUT, text=True, encoding="utf-8", errors="replace", bufsize=1)
        self.lines = queue.Queue()
        self.log = open(log_path, "w", encoding="utf-8")
        self.log_lock = threading.Lock()
        self.reader = threading.Thread(target=self._read, daemon=True)
        self.reader.start()

    def _read(self):
        for line in self.proc.stdout:
            with self.log_lock:
                if not self.log.closed:
                    self.log.write(line)
            self.lines.put(line.rstrip())
        self.lines.put(None)

    def send(self, command):
        self.proc.stdin.write(command + "\n")
        self.proc.stdin.flush()

    def wait_for(self, pattern, timeout):
        deadline = time.time() + timeout
        regex = re.compile(pattern)
        while time.time() < deadline:
            try:
                line = self.lines.get(timeout=5)
            except queue.Empty:
                continue
            if line is None:
                raise RuntimeError("server exited")
            if regex.search(line):
                return line
        raise RuntimeError(f"timed out waiting for {pattern}")

    def stop(self):
        try:
            self.send("stop")
            self.proc.wait(180)
        except Exception:
            self.proc.kill()
            try:
                self.proc.wait(60)
            except subprocess.TimeoutExpired:
                pass
        # Let the reader drain the last lines into the log before closing it.
        self.reader.join(30)
        with self.log_lock:
            self.log.close()


def parse_duration(text):
    """'0:05:23' (Chunky) or '5m 23s' / '1h 2m 3s' (Ultra Fast Pregen) to seconds."""
    if ":" in text:
        seconds = 0
        for part in text.split(":"):
            seconds = seconds * 60 + int(part)
        return seconds
    total = 0
    for value, unit in re.findall(r"(\d+)\s*([hms])", text):
        total += int(value) * {"h": 3600, "m": 60, "s": 1}[unit]
    return total


def parse_chunky(line):
    """(chunks, seconds) from Chunky's 'Task finished ...' line; a stopped or cancelled task is a failed run."""
    if "Task finished" not in line:
        raise RuntimeError(f"Chunky did not finish: {line}")
    chunks = re.search(r"Processed: (\d+)", line)
    total = re.search(r"Total time: ([\d:]+)", line)
    if not chunks or not total:
        raise RuntimeError(f"unexpected Chunky line: {line}")
    return int(chunks.group(1)), parse_duration(total.group(1))


def parse_ufp(line):
    """(chunks, seconds) from Ultra Fast Pregen's 'completed successfully' line; anything else is a failed run.
    Its "Average Speed" is timed to the millisecond, so seconds come from it when it is there."""
    m = re.search(r"\((\d+) Chunks\) completed successfully in ([\dhms ]+)!", line)
    if not m:
        raise RuntimeError(line)
    chunks, seconds = int(m.group(1)), parse_duration(m.group(2))
    speed = re.search(r"Average Speed: ([\d.]+) chunks/s", line)
    if speed and float(speed.group(1)) > 0:
        seconds = round(chunks / float(speed.group(1)), 1)
    return chunks, seconds


def radius_error(radius):
    """Ultra Fast Pregen's /pregen start takes the square's side in thousands of blocks."""
    if radius < 500 or radius % 500:
        return (f"--radius must be a multiple of 500 and at least 500 (got {radius}): "
                "Ultra Fast Pregen's /pregen start takes the side of the square in thousands of blocks")
    return None


def stored_name(args, name):
    """Configuration name written to results.csv: C2ME runs with --c2me-dfc off get -nodfc."""
    return name + NODFC if "c2me" in CONFIGS[name][1] and args.c2me_dfc == "off" else name


def select_configs(args):
    names = args.configs or (list(CONFIGS) if args.c2me else DEFAULT_CONFIGS)
    if args.dimension != "overworld" and not args.configs and not args.only:
        # Tectonic and WWOO change only the overworld.
        names = [n for n in names if n.startswith("vanilla")]
    if args.only:
        matched = [n for n in names if args.only in n]
        if not matched and not args.configs:
            # e.g. --only c2me without --c2me: the C2ME configurations are not in the default list.
            matched = [n for n in CONFIGS if args.only in n]
        names = matched
    return names


def remove_tree(path, attempts=5, delay=2):
    """Delete a directory, retrying while a server that just stopped may still hold its files (Windows)."""
    for attempt in range(attempts):
        shutil.rmtree(path, ignore_errors=True)
        if not os.path.exists(path):
            return True
        if attempt + 1 < attempts:
            time.sleep(delay)
    return False


def run_one(args, name):
    tool, mods = CONFIGS[name]
    stored = stored_name(args, name)
    # A world, config or mod left over from the previous run would change the result; never reuse one.
    for leftover in ("mods", "world", "config"):
        path = os.path.join(SERVER, leftover)
        if not remove_tree(path):
            raise RuntimeError(f"could not delete {path} (is another server still running?); not reusing it")
    mods_dir = os.path.join(SERVER, "mods")
    os.makedirs(mods_dir)
    for key in mods:
        shutil.copy(os.path.join(JARS, jar_for(key)), mods_dir)
    if "c2me" in mods and args.c2me_dfc == "off":
        # C2ME rewrites a partial config with its defaults, so its full generated file is used,
        # with useDensityFunctionCompiler = false.
        os.makedirs(os.path.join(SERVER, "config"))
        shutil.copy(os.path.join(HERE, C2ME_NODFC), os.path.join(SERVER, "config", "c2me.toml"))
    with open(os.path.join(SERVER, "eula.txt"), "w") as f:
        f.write("eula=true\n")
    with open(os.path.join(SERVER, "server.properties"), "w") as f:
        f.write("level-seed=42\nlevel-name=world\nonline-mode=false\nview-distance=4\n"
                "simulation-distance=4\nspawn-protection=0\nmax-tick-time=-1\n")
    os.makedirs(LOGS, exist_ok=True)
    stamp = datetime.datetime.now().strftime("%Y%m%d-%H%M%S")
    dim_tag = "" if args.dimension == "overworld" else f"-{args.dimension}"
    log_path = os.path.join(LOGS, f"{stored}{dim_tag}-{stamp}.log")
    log(f"{stored}: starting server ({', '.join(mods)}){'' if not dim_tag else ', ' + args.dimension}")
    server = Server(args, log_path)
    try:
        server.wait_for(r"Done \(", 900)
        time.sleep(5)
        c = args.center
        dim = "minecraft:" + args.dimension
        if tool == "chunky":
            for command in (f"chunky world {dim}", "chunky shape square", f"chunky center {c} {c}",
                            f"chunky radius {args.radius}", "chunky start"):
                server.send(command)
                time.sleep(1)
            line = server.wait_for(r"Task (finished|stopped|cancelled) for " + re.escape(dim), args.timeout)
            chunks, seconds = parse_chunky(line)
        else:
            server.send(f"pregen world {dim}")
            time.sleep(1)
            server.send(f"pregen center {c} {c}")
            time.sleep(1)
            server.send(f"pregen start {2 * args.radius // 1000}")
            line = server.wait_for(r"completed successfully|was cancelled|Error during pre-generation", args.timeout)
            chunks, seconds = parse_ufp(line)
        if chunks <= 0 or seconds <= 0:
            raise RuntimeError(f"no usable timing: {chunks} chunks in {seconds}s")
        time.sleep(5)
    finally:
        server.stop()
    if args.keep_worlds:
        kept = os.path.join(os.path.dirname(SERVER), "worlds", f"{stored}-{stamp}")
        try:
            os.makedirs(os.path.dirname(kept), exist_ok=True)
            shutil.move(os.path.join(SERVER, "world"), kept)
        except OSError as failure:
            log(f"{stored}: could not keep the world ({failure})")
    cps = chunks / seconds
    row = {"time": stamp, "mc": MC, "config": stored, "tool": tool, "mods": " ".join(mods), "radius": args.radius,
           "chunks": chunks, "seconds": seconds, "cps": f"{cps:.1f}", "log": os.path.relpath(log_path, HERE)}
    if args.dimension != "overworld":
        row["dimension"] = args.dimension
    new = not os.path.exists(RESULTS)
    with open(RESULTS, "a", newline="") as f:
        writer = csv.DictWriter(f, fieldnames=list(row))
        if new:
            writer.writeheader()
        writer.writerow(row)
    log(f"{stored}: {chunks} chunks in {seconds}s = {cps:.1f} chunks/s")


def run(args):
    names = select_configs(args)
    failed = []
    for i in range(args.repeat):
        # Alternate the order between repeats so warm-up and thermal drift do not favor one tool.
        for name in (names if i % 2 == 0 else list(reversed(names))):
            try:
                run_one(args, name)
            except Exception as failure:
                # One crash, timeout or unexpected line must not cost the rest of the benchmark.
                log(f"{stored_name(args, name)}: FAILED: {str(failure) or type(failure).__name__}")
                failed.append(stored_name(args, name))
    if os.path.exists(RESULTS):
        report(args)
    if failed:
        log(f"{len(failed)} run(s) failed and were not recorded: {', '.join(failed)}"
            f" (logs in {LOGS})")
        sys.exit(1)


def cpu_model():
    """The CPU's model name; platform.processor() on Windows only gives family/model numbers."""
    name = ""
    try:
        if os.name == "nt":
            import winreg
            with winreg.OpenKey(winreg.HKEY_LOCAL_MACHINE, r"HARDWARE\DESCRIPTION\System\CentralProcessor\0") as key:
                name = winreg.QueryValueEx(key, "ProcessorNameString")[0]
        elif os.path.exists("/proc/cpuinfo"):
            with open("/proc/cpuinfo", encoding="utf-8", errors="replace") as f:
                name = next((line.split(":", 1)[1] for line in f if line.lower().startswith("model name")), "")
    except OSError:
        name = ""
    return " ".join(name.split()) or platform.processor() or platform.machine()


def report(args):
    if not os.path.exists(RESULTS):
        raise SystemExit("no results.csv yet")
    with open(RESULTS, newline="") as f:
        rows = list(csv.DictReader(f))
    by = {}
    for r in rows:
        by.setdefault(r["config"], []).append(float(r["cps"]))
    radii = sorted({r["radius"] for r in rows}, key=lambda r: (len(r), r))
    print(f"\nCPU: {cpu_model()} | {os.cpu_count()} threads | {platform.platform()}")
    dimension = "" if args.dimension == "overworld" else f" | {args.dimension}"
    print(f"NeoForge {NEOFORGE} (Minecraft {MC}){dimension} | radius {', '.join(radii)} | chunks/s, average (runs)")
    if len(radii) > 1:
        print("warning: results.csv mixes radii and averages them together; move results.csv aside "
              "before running with another --radius")
    print(f"{'world':34} {'Vanilla':>14} {'Ultra Fast Pregen':>18} {'speedup':>8}")

    def average(values):
        return sum(values) / len(values) if values else None

    def cell(values):
        return f"{average(values):.1f} ({len(values)})" if values else "-"

    for suffix, extra in C2ME_VARIANTS:
        for key, label in (("vanilla", "Vanilla worldgen"), ("tw", "Tectonic + WWOO")):
            base, ours = by.get(f"{key}-chunky{suffix}"), by.get(f"{key}-ufp{suffix}")
            if not base and not ours:
                continue
            ratio = f"{average(ours) / average(base):.2f}x" if base and ours else ""
            print(f"{label + extra:34} {cell(base):>14} {cell(ours):>18} {ratio:>8}")
    print("Vanilla: Minecraft's own generation, driven by Chunky (the same method as Bye?Pregen's chart).")


def main():
    parser = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    parser.add_argument("command", choices=["setup", "run", "report"])
    parser.add_argument("--mc", choices=list(VERSIONS), default=DEFAULT_MC,
                        help=f"Minecraft version, default {DEFAULT_MC} (Bye?Pregen's chart); each has its own work/<version>")
    parser.add_argument("--dimension", choices=DIMENSIONS, default="overworld",
                        help="dimension to generate, default overworld; others get their own results file")
    parser.add_argument("--radius", type=int, default=1500,
                        help="blocks, a multiple of 500; 1500 = a 3000 x 3000 square (default)")
    parser.add_argument("--center", type=int, default=20000, help="block x and z of the square's center, away from spawn")
    parser.add_argument("--heap", default="16G", help="server heap (-Xms/-Xmx), default 16G")
    parser.add_argument("--repeat", type=int, default=1)
    parser.add_argument("--c2me", action="store_true", help="also run every configuration with C2ME")
    parser.add_argument("--only", help="only configurations whose name contains this (--only c2me works without --c2me)")
    parser.add_argument("--configs", nargs="*", help="explicit configuration names")
    parser.add_argument("--timeout", type=int, default=4 * 3600, help="seconds per run")
    parser.add_argument("--keep-worlds", action="store_true", help="keep each run's world under work/worlds")
    parser.add_argument("--mods", nargs="*", help="setup: download only these mods, e.g. --mods chunky (default: all)")
    parser.add_argument("--c2me-dfc", choices=["on", "off"], default="on",
                        help="C2ME's density function compiler (useDensityFunctionCompiler), default on;"
                             " off is recorded as <config>-nodfc")
    args = parser.parse_args()
    use_version(args.mc)
    if args.dimension != "overworld":
        global RESULTS
        RESULTS = os.path.join(HERE, f"results-{MC}-{args.dimension}.csv")
    if args.command == "run":
        # Check everything before the first server starts.
        unknown = [n for n in args.configs or [] if n not in CONFIGS]
        if unknown:
            parser.error(f"unknown configuration {', '.join(unknown)}; choose from {', '.join(CONFIGS)}")
        if radius_error(args.radius):
            parser.error(radius_error(args.radius))
        if not select_configs(args):
            parser.error(f"no configuration matches --only {args.only}")
        if args.c2me_dfc == "off" and not os.path.exists(os.path.join(HERE, C2ME_NODFC)):
            parser.error(f"--c2me-dfc off needs {C2ME_NODFC} next to bench.py: C2ME's full config for Minecraft {MC}"
                         " with useDensityFunctionCompiler = false")
    {"setup": setup, "run": run, "report": report}[args.command](args)


if __name__ == "__main__":
    main()
