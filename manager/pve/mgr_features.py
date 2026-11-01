#!/usr/bin/env python3
"""What the page does beyond a plain start, on one server each of a few kinds, driven only through its API.

Run it through ufp-run, from ~/compat: settings, a circle, another dimension, stop and resume, starts that must be
refused, and requests that must get nothing."""
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

TOKEN = "mgrtesttoken00000000"
WEB = 25580
MANAGER = "jars-151-final/ufpmanager-0.2.0.jar"
J = "jars-151-final/"
TARGETS = {
    "fabric-1.20.1": (J + "ultrafastpregen-fabric-1.20.x-15.1.jar", "config/ultrafastpregen.conf"),
    "neoforge-1.21.1": (J + "ultrafastpregen-neoforge-1.21-1.21.1-15.1.jar", "config/ultrafastpregen.conf"),
    "fabric-26.2": (J + "ultrafastpregen-fabric-26.1-26.2-15.1.jar", "config/ultrafastpregen.conf"),
    "paper-26.2": (J + "ultrafastpregen-bukkit-26.2-15.1.jar", "plugins/UltraFastPregen/ultrafastpregen.conf"),
    "folia-26.2": (J + "ultrafastpregen-bukkit-26.2-15.1.jar", "plugins/UltraFastPregen/ultrafastpregen.conf"),
    "sponge-26.2": (J + "ultrafastpregen-sponge-26.2-15.1.jar", "config/ultrafastpregen.conf"),
}
failures = []


def check(what, ok, detail=""):
    print("%s %s %s" % ("PASS" if ok else "FAIL", what, str(detail)[:170]), flush=True)
    if not ok:
        failures.append(what)


def call(path, token=TOKEN, **fields):
    data = urllib.parse.urlencode(fields).encode() if fields else None
    headers = {"Authorization": "Bearer " + token} if token else {}
    request = urllib.request.Request("http://127.0.0.1:%d%s" % (WEB, path), data, headers)
    try:
        with urllib.request.urlopen(request, timeout=30) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as failure:
        body = failure.read()
        try:
            return failure.code, json.loads(body)
        except ValueError:
            return failure.code, {}


def picture(dimension, x, z, extra=""):
    request = urllib.request.Request("http://127.0.0.1:%d/api/map/terrain?dimension=%s&x=%d&z=%d%s" % (WEB, dimension, x, z, extra),
                                     headers={"Authorization": "Bearer " + TOKEN})
    with urllib.request.urlopen(request, timeout=30) as response:
        return response.read(), response.headers.get_content_type()


def status():
    return call("/api/poll")[1]["ufp"]["status"]


def wait_end(seconds=240):
    end = time.time() + seconds
    while time.time() < end:
        now = status()
        if now and now["ended"]:
            return now
        time.sleep(0.5)
    return status()


def wait_running(seconds=20):
    end = time.time() + seconds
    while time.time() < end:
        if call("/api/poll")[1]["ufp"]["running"]:
            return True
        time.sleep(0.1)
    return False


def test(target, jar, conf):
    print("==", target, flush=True)
    done = subprocess.run(["./mgr_srv.sh", "f", "start", target, jar, MANAGER],
                          env={**os.environ, "PORT": "25571", "WEB": str(WEB), "JVM": "-Xms1G -Xmx3G"}, capture_output=True, text=True)
    print("  ", done.stdout.strip().splitlines()[-1], flush=True)
    run = "run/mgr-f/"
    for _ in range(20):
        if call("/api/info")[1].get("ufp", {}).get("api"):
            break
        time.sleep(1)

    code, values = call("/api/settings")
    check("settings are read", code == 200 and values.get("skip_existing_chunks") == "true", values)
    code, values = call("/api/settings", batch_size="512", threads="10", skip_existing_chunks="true")
    check("settings are saved", code == 200 and values["batch_size"] == "512" and values["threads"] == "10")
    text = open(run + conf).read()
    check("they reach the settings file", "batch_size=512" in text and "threads=10" in text)
    code, body = call("/api/settings", batch_size="1")
    check("a wrong value is refused", code == 400, body)
    code, body = call("/api/settings", threads="4\nterrain_pipeline=fused")
    check("a value cannot add a line", code == 400 and "terrain_pipeline=fused" not in open(run + conf).read(), body)

    code, body = call("/api/pregen/start", dimension="minecraft:overworld", shape="circle", x="100000", z="-100000", radius="300")
    check("a circle starts", code == 200 and body["started"], body)
    wait_running()
    code, body = call("/api/pregen/start", dimension="minecraft:overworld", shape="square", x="0", z="0", radius="100")
    check("a second start is refused", code == 200 and not body["started"], body.get("message"))
    end = wait_end()
    check("the circle completes", end["state"] == "COMPLETED" and end["shape"] == "circle", "%s %s/%s" % (end["state"], end["done"], end["total"]))
    log = open("logs/mgr-f.log", errors="replace").read()
    check("the run used the saved settings", "Batch: 512x512" in log and "Worker Threads: 10" in log)

    code, body = call("/api/pregen/start", dimension="minecraft:the_nether", shape="square", x="20000", z="20000", radius="512")
    check("a run in the Nether starts", code == 200 and body["started"], body)
    wait_running()
    code, body = call("/api/pregen/stop", go="1")
    check("stop is accepted", code == 200 and body["stopping"], body)
    end = wait_end()
    check("it stops early", end["state"] == "STOPPED" and end["done"] < end["total"], "%s %s/%s" % (end["state"], end["done"], end["total"]))
    code, body = call("/api/pregen/resume", dimension="minecraft:the_nether")
    check("it resumes", code == 200 and body["started"], body)
    wait_running()
    end = wait_end()
    check("it completes", end["state"] == "COMPLETED" and end["done"] == end["total"], "%s %s/%s kept %s" % (end["state"], end["done"], end["total"], end["kept"]))
    code, body = call("/api/map/regions?dimension=minecraft:the_nether")
    check("the map finds the Nether", code == 200 and sum(r[2] for r in body["regions"]) >= end["total"], "%d regions" % len(body["regions"]))
    code, body = call("/api/poll")
    check("the finished list has the runs", len(body["ufp"]["finished"]) >= 3, len(body["ufp"]["finished"]))

    for name, region_x, region_z in (("Overworld", 195, -196), ("Nether", 39, 39)):
        dimension = "minecraft:overworld" if name == "Overworld" else "minecraft:the_nether"
        data, kind = picture(dimension, region_x, region_z)
        check(name + " terrain is a picture", kind == "image/png" and data[:4] == b"\x89PNG" and len(data) > 8000, len(data))
        small, _ = picture(dimension, region_x, region_z, "&size=small")
        check(name + " has a small picture too", small[:4] == b"\x89PNG" and len(small) < len(data), len(small))
    code, body = call("/api/map/terrain?dimension=minecraft:overworld&x=900&z=900")
    check("no picture of a region that does not exist", code == 404, body)
    code, body = call("/api/map/terrain?dimension=minecraft:../../x&x=0&z=0")
    check("a path gets no picture", code == 400, body)

    code, body = call("/api/pregen/start", dimension="minecraft:overworld", shape="square", x="0", z="0", radius="60000")
    check("too large a radius is refused", code == 400, body)
    code, body = call("/api/pregen/start", dimension="ufp:nothing", shape="square", x="0", z="0", radius="100")
    check("a world that does not exist is refused", code == 200 and not body["started"], body.get("message"))
    code, body = call("/api/pregen/stop", token="wrong")
    check("a wrong token gets nothing", code == 401)
    code, body = call("/api/pregen/start", token=None, dimension="minecraft:overworld", shape="square", x="0", z="0", radius="100")
    check("no token gets nothing", code == 401)
    code, body = call("/api/map/regions?dimension=minecraft:../../../etc")
    check("a path in a dimension is refused", code == 400, body)
    code, body = call("/api/map/region?dimension=" + urllib.parse.quote("minecraft:a/../../..") + "&x=0&z=0")
    check("also with dots hidden in it", code == 400, body)
    code, body = call("/../../../../etc/passwd", token=None)
    check("nothing is served outside the page", code == 404)

    log = open("logs/mgr-f.log", errors="replace").read().splitlines()
    errors = [l for l in log if (" ERROR" in l or "/ERROR]" in l or "Exception" in l) and "sculk_patch_deep_dark" not in l
              and "kqueue" not in l and "Appender DebugFile" not in l and "OSX/BSD" not in l]
    check("no error in the log", not errors, errors[:3])
    subprocess.run(["./mgr_srv.sh", "f", "stop"], capture_output=True)


def main():
    for target in sys.argv[1:] or TARGETS:
        try:
            test(target, *TARGETS[target])
        except (OSError, KeyError, ValueError, TypeError, IndexError, urllib.error.URLError) as failure:
            check(target + " ran", False, repr(failure))
            subprocess.run(["./mgr_srv.sh", "f", "stop"], capture_output=True)
    print("FEATURES_DONE failures: %s" % (failures or "none"), flush=True)


if __name__ == "__main__":
    main()
