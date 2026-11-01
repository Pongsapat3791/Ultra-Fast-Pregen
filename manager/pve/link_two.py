#!/usr/bin/env python3
"""Link over a real network: the host on another machine, a client here, through the same checks as link_matrix.py.

Run from the compat folder: link_two.py <folder of jars> <host address> <secret> <target> [other clients' names].
The host's page must be on the host's port 25581, with link_matrix.py's token. Afterwards a client with a wrong secret must
appear under Refused with this machine's address."""
import json
import os
import sys
import time

import link_matrix as lm

HOST = sys.argv[2]
SECRET = sys.argv[3]
TARGET = sys.argv[4]
OTHERS = sys.argv[5:]
lm.ADDRESSES[lm.HOST_WEB] = HOST


def start(secret):
    link = "role=client port=25599 host=%s secret=%s name=alpha tile_chunks=32 local_worker=false" % (HOST, secret)
    return lm.script("lm-c", "start", TARGET, lm.ufp_jar(TARGET), lm.JARS + "/ufplink-0.3.0.jar", lm.JARS + "/ufpmanager-0.2.0.jar", PORT="25573",
                     WEB=str(lm.CLIENT_WEB), SEED="42", JVM="-Xms1G -Xmx3G", LINK=link).splitlines()[-1:]


def all_connected(result):
    wanted = sorted(["alpha"] + OTHERS)
    connected = lambda: sorted(m["name"] for m in lm.api("/api/poll")["link"]["machines"]) == wanted
    if not lm.wait_for(connected, 90):
        result["refused"] = str(lm.api("/api/poll")["link"]["refusals"])[:400]
        return False
    return True


def wrong_secret(result):
    lm.script("lm-c", "stop")
    before = len(lm.api("/api/poll")["link"]["refusals"])
    result["wrong_start"] = start("0" * 64)
    lm.wait_for(lambda: len(lm.api("/api/poll")["link"]["refusals"]) > before, 90)
    refusals = lm.api("/api/poll")["link"]["refusals"]
    result["refusals"] = str(refusals)[:400]
    return len(refusals) > before


def main():
    result = {"ok": {}, "client": start(SECRET)}
    lm.step(result, "connected", all_connected)
    machines = lm.api("/api/poll")["link"]["machines"]
    result["machines"] = [{key: m.get(key) for key in ("name", "threads", "system", "address")} for m in machines]
    began = time.time()
    lm.step(result, "plan", lm.check_plan)
    result["plan_seconds"] = round(time.time() - began)
    lm.step(result, "same", lm.check_same)
    result["client_errors"] = lm.log_errors("lm-c")[:5]
    lm.step(result, "wrong_secret", wrong_secret)
    lm.script("lm-c", "stop")
    print(("PASS " if all(result["ok"].values()) and not result["client_errors"] else "FAIL ") + json.dumps(result))


if __name__ == "__main__":
    main()
