#!/usr/bin/env python3
"""The Manager with Ultra Fast Pregen Link, on this box: a host and two clients, driven only through the page's API.

Run it through ufp-run, from ~/compat: link_test.py [target], Fabric 1.21.1 by default. It checks what the page shows and
that a run follows the plan:
  refused   a machine with another seed, and one without the secret, appear under Refused with the reason
  no page   a client without the Manager is a machine like the others
  strict    without helping, a machine that leaves keeps its tiles: they wait for it, and it finishes them when it is back
  help      with helping, the tiles of a machine that left go to the one still there
  stop      Stop ends a run after the tiles in progress
Each tile is one region here (tile_chunks=32), so every tile's region must end with all its 1,024 chunks on disk."""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TOKEN = "mgrtesttoken00000000"
SECRET = os.urandom(32).hex()
TARGET = sys.argv[1] if len(sys.argv) > 1 else "fabric-1.21.1"
JARS = "jars-151-final/"
LINK = JARS + "ufplink-0.3.0.jar"
MANAGER = JARS + "ufpmanager-0.2.0.jar"
WEB = 25581
DIMENSION = "minecraft:overworld"
failures = []


def check(what, ok, detail=""):
    print("%s %s %s" % ("PASS" if ok else "FAIL", what, detail), flush=True)
    if not ok:
        failures.append(what)


def ufp_jar():
    for prefix, name in (("fabric-1.20", "fabric-1.20.x"), ("forge-", "forge-1.20-1.20.4"), ("fabric-1.21", "fabric-1.21-1.21.1"),
                         ("neoforge-1.21", "neoforge-1.21-1.21.1"), ("fabric-26", "fabric-26.1-26.2"), ("neoforge-26", "neoforge-26.1.2-26.2"),
                         ("sponge-", "sponge-26.2")):
        if TARGET.startswith(prefix):
            return "%sultrafastpregen-%s-15.1.jar" % (JARS, name)
    return JARS + "ultrafastpregen-bukkit-26.2-15.1.jar"


def script(instance, *arguments, **env):
    done = subprocess.run(["./mgr_srv.sh", instance, *arguments], env={**os.environ, **env}, capture_output=True, text=True)
    return done.stdout.strip()


def start(instance, port, web, role, name, seed=42, secret=None, jars=(LINK, MANAGER)):
    link = "role=%s port=25599 host=127.0.0.1 secret=%s name=%s tile_chunks=32 local_worker=false" % (role, secret or SECRET, name)
    print("  ", script(instance, "start", TARGET, ufp_jar(), *jars, PORT=str(port), WEB=str(web), SEED=str(seed),
                       JVM="-Xms1G -Xmx2G", LINK=link), flush=True)


def stop(instance):
    script(instance, "stop")


def kill(instance):
    run = os.path.realpath("run/mgr-" + instance)
    for pid in os.listdir("/proc"):
        if pid.isdigit():
            try:
                if os.readlink("/proc/%s/cwd" % pid) == run and open("/proc/%s/comm" % pid).read().strip() == "java":
                    os.kill(int(pid), 9)
            except OSError:
                pass


def api(path, **fields):
    data = None
    if fields:
        pairs = []
        for key, value in fields.items():
            pairs.extend((key, item) for item in value) if isinstance(value, list) else pairs.append((key, value))
        data = urllib.parse.urlencode(pairs).encode()
    request = urllib.request.Request("http://127.0.0.1:%d%s" % (WEB, path), data, {"Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(request, timeout=40) as response:
        body = response.read()
    return json.loads(body) if response.headers.get_content_type() == "application/json" else body


def link():
    return api("/api/poll")["link"]


def machines():
    return sorted(machine["name"] for machine in link()["machines"])


def wait_for(what, condition, seconds):
    end = time.time() + seconds
    while time.time() < end:
        if condition():
            return True
        time.sleep(1)
    print("   timed out waiting for", what, flush=True)
    return False


def area(x, z, size):
    return {"dimension": DIMENSION, "x": x, "z": z, "sizeK": size}


def share(plan, first_half, second_half):
    """Half of the tiles for each machine, cut across x; the page sends the same fields."""
    tiles = sorted(plan["tiles"])
    middle = len(tiles) // 2
    names = [first_half, second_half]
    assign = ";".join("%d,%d,%d" % (t[0], t[1], 0 if i < middle else 1) for i, t in enumerate(tiles))
    return names, assign, {(t[0], t[1]): names[0 if i < middle else 1] for i, t in enumerate(tiles)}


def tile_full(x, z):
    bits = api("/api/map/region?dimension=%s&x=%d&z=%d" % (urllib.parse.quote(DIMENSION), x, z))
    return sum(bin(value).count("1") for value in bits) == 1024


def run_of():
    return link()["run"]


def done_tiles(run):
    return [tile for tile in run["tiles"] if tile[3] == 2]


def test_refused():
    print("== refused", flush=True)
    before = len(link()["refusals"])
    start("c3", 25575, 25584, "client", "other-seed", seed=43)
    wait_for("the refusal", lambda: len(link()["refusals"]) > before, 40)
    stop("c3")
    refused = link()["refusals"]
    check("another seed is refused", bool(refused) and refused[0]["name"] == "other-seed" and "seed" in refused[0]["reason"].lower(),
          refused[0]["reason"][:90] if refused else "")
    check("it is not among the machines", "other-seed" not in machines())
    before = len(link()["refusals"])
    start("c3", 25575, 25584, "client", "no-secret", secret="00" * 32)
    wait_for("the refusal", lambda: len(link()["refusals"]) > before, 40)
    stop("c3")
    refused = link()["refusals"]
    check("a wrong secret is refused", bool(refused) and "secret" in refused[0]["reason"].lower(), refused[0]["reason"] if refused else "")


def test_no_page():
    print("== a client without the Manager", flush=True)
    start("c3", 25575, 25584, "client", "no-page", jars=(LINK,))
    wait_for("the machine", lambda: "no-page" in machines(), 60)
    check("a client without the Manager joins", "no-page" in machines(), str(link()["refusals"][:1])[:200])
    stop("c3")
    wait_for("it to go", lambda: "no-page" not in machines(), 30)


def test_strict():
    print("== strict plan, a machine leaves and comes back", flush=True)
    place = area(-3000, -3000, 1)
    plan = api("/api/link/plan", **place)
    check("the plan names both machines", sorted(plan["names"]) == ["alpha", "beta"], str(plan["names"]))
    names, assign, wanted = share(plan, "alpha", "beta")
    answer = api("/api/link/start", **place, name=names, assign=assign, helpOthers="false")
    check("the plan starts", answer["started"], str(answer["message"]))
    wait_for("beta's first tile", lambda: any(t[3] == 1 and run_of()["names"][t[4]] == "beta" for t in run_of()["tiles"] if t[4] >= 0), 90)
    print("   killing beta in the middle of its tile", flush=True)
    kill("c2")
    wait_for("beta to go", lambda: "beta" not in machines(), 30)
    check("the machine is gone from the page", "beta" not in machines())
    # alpha finishes its own and then waits: its tiles and the ones planned for beta stay apart. A client that found
    # nothing to do asks again 5 s later, so the host must still hold beta's tiles after that.
    def only_beta_left():
        now = run_of()
        return not any(t[3] == 1 for t in now["tiles"]) and all(now["names"][t[2]] == "beta" for t in now["tiles"] if t[3] == 0)
    wait_for("alpha to finish its own tiles", only_beta_left, 400)
    time.sleep(12)
    run = run_of()
    names_now = run["names"]
    pending = [t for t in run["tiles"] if t[3] == 0]
    check("the run waits for beta", run["running"] and bool(pending) and all(names_now[t[2]] == "beta" for t in pending),
          "%d tiles wait: %s" % (len(pending), run["waitingFor"]))
    print("   beta comes back", flush=True)
    start("c2", 25574, 25583, "client", "beta")
    finished = wait_for("the end of the run", lambda: not run_of()["running"], 500)
    run = run_of()
    check("the run ends once beta is back", finished and len(done_tiles(run)) == len(run["tiles"]))
    wrong = [(t[0], t[1]) for t in run["tiles"] if run["names"][t[4]] != wanted[(t[0], t[1])]]
    check("every tile was made by its planned machine", not wrong, str(wrong))
    check("every tile is complete on disk", all(tile_full(t[0], t[1]) for t in run["tiles"]))


def test_help():
    print("== helping: the tiles of a machine that left go to the other", flush=True)
    place = area(6000, -6000, 1)
    plan = api("/api/link/plan", **place)
    names, assign, wanted = share(plan, "alpha", "beta")
    answer = api("/api/link/start", **place, name=names, assign=assign, helpOthers="true")
    check("the plan starts", answer["started"], str(answer["message"]))
    time.sleep(3)
    kill("c2")
    wait_for("beta to go", lambda: "beta" not in machines(), 30)
    finished = wait_for("the end of the run", lambda: not run_of()["running"], 600)
    run = run_of()
    check("the run ends without beta", finished and len(done_tiles(run)) == len(run["tiles"]))
    taken = [t for t in run["tiles"] if run["names"][t[4]] == "alpha" and wanted[(t[0], t[1])] == "beta"]
    check("alpha took beta's tiles", bool(taken), "%d tiles" % len(taken))
    check("every tile is complete on disk", all(tile_full(t[0], t[1]) for t in run["tiles"]))
    start("c2", 25574, 25583, "client", "beta")
    wait_for("beta to be back", lambda: "beta" in machines(), 60)


def test_stop():
    print("== stop", flush=True)
    place = area(-6000, 6000, 2)
    plan = api("/api/link/plan", **place)
    answer = api("/api/link/start", **place, name=plan["names"], assign=";".join("%d,%d,%d" % (t[0], t[1], t[2]) for t in plan["tiles"]),
                 helpOthers="true")
    check("the plan starts", answer["started"], str(answer["message"]))
    wait_for("the first tile", lambda: len(done_tiles(run_of())) >= 1, 120)
    check("stop is accepted", api("/api/link/stop", go="1")["stopping"])
    finished = wait_for("the run to end", lambda: not run_of()["running"], 180)
    run = run_of()
    check("the run ends early", finished and 0 < len(done_tiles(run)) < len(run["tiles"]), "%d of %d" % (len(done_tiles(run)), len(run["tiles"])))
    check("it says why", "API" in str(run["stopReason"]), str(run["stopReason"]))


def main():
    for instance in ("h", "c1", "c2", "c3"):
        stop(instance)
    start("h", 25572, WEB, "host", "host")
    start("c1", 25573, 25582, "client", "alpha")
    start("c2", 25574, 25583, "client", "beta")
    wait_for("both machines", lambda: machines() == ["alpha", "beta"], 60)
    check("both machines are on the page", machines() == ["alpha", "beta"], str(machines()))
    for test in (test_refused, test_no_page, test_strict, test_help, test_stop):
        try:
            test()
        except (OSError, KeyError, ValueError, urllib.error.URLError) as failure:
            check(test.__name__ + " ran", False, repr(failure))
    log = open("logs/mgr-h.log", errors="replace").read()
    noise = ("sculk_patch_deep_dark", "kqueue", "Appender DebugFile", "Only supported on OSX/BSD")
    errors = [line for line in log.splitlines() if (" ERROR" in line or "/ERROR]" in line or "Exception" in line)
              and not any(word in line for word in noise)]
    check("no error in the host's log", not errors, "\n".join(errors[:6])[:600])
    for instance in ("h", "c1", "c2", "c3"):
        stop(instance)
    print("LINK_TEST_DONE failures: %s" % (failures or "none"), flush=True)


if __name__ == "__main__":
    main()
