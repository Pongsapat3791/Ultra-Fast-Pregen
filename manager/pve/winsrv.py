"""mgr_srv.sh for a Windows test machine: the same instances, folders and answers, with each server a child of this script.

UFP_J21 and UFP_J25 name the two java.exe. Run from the compat folder, with skel/ next to run/ and logs/."""
import glob
import os
import re
import shutil
import subprocess
import time

J21 = os.environ.get("UFP_J21", "java")
J25 = os.environ.get("UFP_J25", "java")
DONE = re.compile(r"Done \([0-9.,]+s\)!")
PLUGIN_SERVERS = {"spigot": ("spigot.jar", ("bundler",)), "craftbukkit": ("craftbukkit.jar", ("bundler",)),
                  "paper": ("paper.jar", ("cache", "libraries", "versions")), "folia": ("folia.jar", ("cache", "libraries", "versions")),
                  "sponge": ("sponge.jar", ("libraries",))}
LOW = subprocess.BELOW_NORMAL_PRIORITY_CLASS | subprocess.CREATE_NO_WINDOW
servers = {}
launches = {}


def remove(path):
    for attempt in range(30):
        try:
            if os.path.isdir(path):
                shutil.rmtree(path)
            elif os.path.exists(path):
                os.remove(path)
            return
        except OSError:
            if attempt == 29:
                raise
            time.sleep(1)


def linked(source, target):
    shutil.copytree(source, target, copy_function=os.link)


def lines_to(path, lines):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with open(path, "w", encoding="utf-8", newline="\n") as out:
        out.write("".join(line + "\n" for line in lines))


def launch_args(target, jvm):
    skel = "skel/" + target
    kind = target.split("-")[0]
    if kind in PLUGIN_SERVERS:
        return [J25, *jvm, "-jar", PLUGIN_SERVERS[kind][0], "nogui"]
    java = J25 if "-26." in target else J21
    if os.path.exists(skel + "/fabric-server-launch.jar"):
        return [java, *jvm, "-jar", "fabric-server-launch.jar", "nogui"]
    arguments = glob.glob(skel + "/libraries/net/minecraftforge/forge/*/win_args.txt") + glob.glob(skel + "/libraries/net/neoforged/neoforge/*/win_args.txt")
    if arguments:
        return [java, *jvm, "@" + os.path.relpath(arguments[0], skel).replace("\\", "/"), "nogui"]
    shim = glob.glob(skel + "/forge-*-shim.jar")
    if not shim:
        raise RuntimeError("no launcher in " + skel)
    return [java, *jvm, "-jar", os.path.basename(shim[0]), "nogui"]


def alive(instance):
    server = servers.get(instance)
    return server is not None and server.poll() is None


def launch(instance, arguments, label):
    log = "logs/mgr-%s.log" % instance
    with open(log, "ab") as out:
        servers[instance] = subprocess.Popen(arguments, cwd="run/mgr-" + instance, stdin=subprocess.PIPE, stdout=out,
                                             stderr=subprocess.STDOUT, creationflags=LOW)
    for second in range(1, 361):
        with open(log, errors="replace") as text:
            if DONE.search(text.read()):
                return "ready after %d s: %s" % (second, label)
        if not alive(instance):
            return "server died"
        time.sleep(1)
    return "server did not start"


def start(instance, target, jars, env):
    if alive(instance):
        return "session already exists"
    run = "run/mgr-" + instance
    log = "logs/mgr-%s.log" % instance
    for path in (run, log, log + ".1"):
        remove(path)
    skel = "skel/" + target
    if not os.path.isdir(skel):
        return "missing skeleton " + target
    conf = run + "/config/ultrafastpregen.conf"
    manager = link = run + "/config"
    kind = target.split("-")[0]
    if kind in PLUGIN_SERVERS:
        jar, folders = PLUGIN_SERVERS[kind]
        folder = "plugins"
        if kind == "sponge":
            folder = "mods"
        else:
            conf = run + "/plugins/UltraFastPregen/ultrafastpregen.conf"
            manager = run + "/plugins/UltraFastPregenManager"
            link = run + "/plugins/UltraFastPregenLink"
        os.makedirs(run + "/" + folder)
        for name in folders:
            linked(skel + "/" + name, run + "/" + name)
        os.link(skel + "/" + jar, run + "/" + jar)
        for path in glob.glob(skel + "/*.yml") + glob.glob(skel + "/*.json"):
            shutil.copy(path, run)
        if os.path.isdir(skel + "/config"):
            shutil.copytree(skel + "/config", run + "/config")
    else:
        folder = "mods"
        linked(skel, run)
        for name in ("mods", "config", "world", "logs"):
            remove(run + "/" + name)
        os.makedirs(run + "/mods")
        os.makedirs(run + "/config")
    for jar in jars:
        shutil.copy(jar, run + "/" + folder)
    settings = env.get("CONF") or os.environ.get("CONF", "")
    if settings:
        lines_to(conf, settings.split())
    lines_to(manager + "/ufpmanager.conf", ["enabled=true", "bind=0.0.0.0", "port=%s" % env.get("WEB", "25580"),
                                            "token=%s" % env.get("TOKEN", "mgrtesttoken00000000")])
    if env.get("LINK"):
        lines_to(link + "/ufplink-network.conf", env["LINK"].split())
    lines_to(run + "/eula.txt", ["eula=true"])
    lines_to(run + "/server.properties", ["server-port=%s" % env.get("PORT", "25571"), "level-name=world", "level-seed=%s" % env.get("SEED", "42"),
                                          "online-mode=false", "view-distance=%s" % env.get("VIEW", "4"), "simulation-distance=4", "spawn-protection=0",
                                          "allow-flight=true", "max-players=4", "enable-rcon=false", "enable-query=false",
                                          "enforce-secure-profile=false", "motd=ufp manager test"])
    launches[instance] = launch_args(target, env.get("JVM", "-Xms1G -Xmx3G").split())
    return launch(instance, launches[instance], target)


def stop(instance, keep):
    answer = "stopped"
    server = servers.get(instance)
    if alive(instance):
        try:
            server.stdin.write(b"stop\n")
            server.stdin.flush()
        except OSError:
            pass
        try:
            server.wait(90)
        except subprocess.TimeoutExpired:
            server.kill()
            server.wait()
            answer = "server had to be killed\nstopped"
    if not keep:
        remove("run/mgr-" + instance)
    return answer


def script(instance, command, *arguments, **env):
    if command == "start":
        return start(instance, arguments[0], arguments[1:], env)
    if command == "again":
        if alive(instance):
            return "session already exists"
        log = "logs/mgr-%s.log" % instance
        os.replace(log, log + ".1")
        return launch(instance, launches[instance], "again")
    if command == "cmd":
        if not alive(instance):
            return "no server"
        servers[instance].stdin.write((arguments[0] + "\n").encode())
        servers[instance].stdin.flush()
        return ""
    if command == "stop":
        return stop(instance, env.get("KEEP") == "1")
    raise ValueError(command)
