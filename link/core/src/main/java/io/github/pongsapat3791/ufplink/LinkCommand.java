package io.github.pongsapat3791.ufplink;

import io.github.pongsapat3791.ultrafastpregen.api.PregenWorlds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * /ufplink pregen <world_k> [<x> <z> [<dimension>]] | pregen resume | import "<world folder>" [force]
 * | check "<world folder>" | invite [address] | join <invite> | leave | host pregen ... | host status | client status
 * | fingerprint | status | cancel. What writes into the world or changes the network needs operator level 4; the rest level 2.
 */
final class LinkCommand {
    private static final String WORLD = "minecraft:overworld";

    private LinkCommand() {}

    /** Words, with "double quotes" around one that has spaces. */
    private static List<String> words(String arguments) {
        List<String> words = new ArrayList<>();
        StringBuilder word = new StringBuilder();
        boolean quoted = false;
        boolean any = false;
        for (char c : arguments.toCharArray()) {
            if (c == '"') {
                quoted = !quoted;
                any = true;
            } else if (c == ' ' && !quoted) {
                if (any) words.add(word.toString());
                word.setLength(0);
                any = false;
            } else {
                word.append(c);
                any = true;
            }
        }
        if (any) words.add(word.toString());
        return words;
    }

    /** On the server thread. */
    static boolean run(String arguments, PregenWorlds.CommandReply source) {
        List<String> words = words(arguments);
        String first = words.isEmpty() ? "" : words.get(0);
        return switch (first) {
            case "" -> help(source);
            case "pregen" -> words.size() >= 2 && words.get(1).equals("resume") ? resume(source) : area(source, words, 1, false);
            case "host" -> words.size() >= 2 && words.get(1).equals("pregen") ? area(source, words, 2, true)
                    : words.size() >= 2 && words.get(1).equals("status") ? hostStatus(source) : help(source);
            case "client" -> clientStatus(source);
            case "invite" -> invite(source, words.size() >= 2 ? words.get(1) : null);
            case "join" -> words.size() >= 2 ? join(source, words.get(1)) : help(source);
            case "leave" -> leave(source);
            case "fingerprint" -> fingerprint(source);
            case "check" -> words.size() >= 2 ? start(source, words.get(1), true, false) : help(source);
            case "import" -> words.size() >= 2 ? start(source, words.get(1), false, words.size() >= 3 && words.get(2).equals("force")) : help(source);
            case "status" -> status(source);
            case "cancel" -> cancel(source);
            default -> help(source);
        };
    }

    private static boolean help(PregenWorlds.CommandReply source) {
        reply(source, UfpLink.NAME + " " + UfpLink.version() + "\n"
                + "/ufplink pregen <size in thousands of blocks> [<x> <z> [<dimension>]]: generates the area in a separate world,"
                + " then adds the chunks this world does not have (never replaces any)\n"
                + "/ufplink pregen resume: continues a pregen a stop cut short\n"
                + "/ufplink import \"<world folder>\" [force]: adds the chunks another server generated\n"
                + "/ufplink check \"<world folder>\": compares that world with this one and counts the chunks it would add\n"
                + "/ufplink invite [address]: makes this server a host and shows the line other servers join it with\n"
                + "/ufplink join <invite>: this server generates tiles for that host\n"
                + "/ufplink leave: back to no network\n"
                + "/ufplink host pregen <size in thousands of blocks> [<x> <z> [<dimension>]]: the clients generate the area\n"
                + "/ufplink host status, /ufplink client status\n"
                + "/ufplink fingerprint, /ufplink status, /ufplink cancel");
        return true;
    }

    private static boolean invite(PregenWorlds.CommandReply source, String address) {
        if (!level4(source)) return false;
        Invite invite;
        try {
            invite = UfpLink.invite(address);
        } catch (IllegalStateException problem) {
            fail(source, problem.getMessage());
            return false;
        }
        reply(source, "This server is a host, at " + invite.address().replace(",", ", ") + ", port " + invite.port() + ". On each other server:\n"
                + "/ufplink join " + invite.code());
        // The facts probe the generator: not on the server thread.
        Thread thread = new Thread(() -> {
            PregenWorlds.ServerFacts facts = UfpLink.worlds().facts();
            reply(source, "They need Minecraft " + facts.minecraft() + ", seed " + facts.seed() + ", and the same mods and datapacks. Allow port "
                    + invite.port() + " through this machine's firewall, and keep the line private: anyone with it can send chunks here.");
        }, "UFP-Link-Invite");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static boolean join(PregenWorlds.CommandReply source, String code) {
        if (!level4(source)) return false;
        String problem = UfpLink.join(code);
        if (problem != null) {
            fail(source, problem);
            return false;
        }
        reply(source, "Connecting to the host; this server stays a client after a restart. /ufplink leave ends that.");
        Thread thread = new Thread(() -> joinResult(source), "UFP-Link-Join");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    /** The person who joined hears how it went, once. */
    private static void joinResult(PregenWorlds.CommandReply source) {
        for (int i = 0; i < 60; i++) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException interrupted) {
                return;
            }
            LinkClient client = LinkClient.running();
            if (client == null) return;
            if (client.connected()) {
                reply(source, "Connected. The host's /ufplink host pregen gives this server tiles.");
                return;
            }
            if (client.problem() != null) {
                fail(source, "Not connected: " + client.problem().replaceAll("\\.$", "") + ". It keeps trying every 10 s.");
                return;
            }
        }
    }

    private static boolean leave(PregenWorlds.CommandReply source) {
        if (!level4(source)) return false;
        String problem = UfpLink.leave();
        if (problem != null) {
            fail(source, problem);
            return false;
        }
        reply(source, "This server has left the network.");
        return true;
    }

    private static boolean level4(PregenWorlds.CommandReply source) {
        if (source.hasPermission(4)) return true;
        fail(source, "That needs operator level 4.");
        return false;
    }

    /** <world_k> [<x> <z> [<dimension>]] from word `at` on. */
    private static boolean area(PregenWorlds.CommandReply source, List<String> words, int at, boolean host) {
        if (!level4(source)) return false;
        int worldK;
        int x = 0;
        int z = 0;
        try {
            if (words.size() <= at) return help(source);
            worldK = Integer.parseInt(words.get(at));
            if (words.size() > at + 2) {
                x = Integer.parseInt(words.get(at + 1));
                z = Integer.parseInt(words.get(at + 2));
            }
        } catch (NumberFormatException wrong) {
            fail(source, "The size and the position are whole numbers.");
            return false;
        }
        if (worldK < 1 || worldK > 100) {
            fail(source, "The size is 1 to 100 thousand blocks.");
            return false;
        }
        String given = words.size() > at + 3 ? words.get(at + 3) : WORLD;
        String dimension = given.contains(":") ? given : "minecraft:" + given;
        if (!UfpLink.worlds().dimensions().contains(dimension)) {
            fail(source, "This server has no world " + dimension + ".");
            return false;
        }
        return host ? hostPregen(source, worldK, x, z, dimension) : pregen(source, worldK, x, z, dimension);
    }

    private static boolean pregen(PregenWorlds.CommandReply source, int worldK, int x, int z, String target) {
        String problem = PregenJob.start(target, worldK, x, z, source);
        if (problem != null) {
            fail(source, problem);
            return false;
        }
        return true;
    }

    private static boolean resume(PregenWorlds.CommandReply source) {
        if (!level4(source)) return false;
        String problem = PregenJob.resume(source);
        if (problem != null) {
            fail(source, problem);
            return false;
        }
        return true;
    }

    private static boolean fingerprint(PregenWorlds.CommandReply source) {
        Thread thread = new Thread(() -> {
            try {
                reply(source, "Fingerprint written to " + Fingerprint.compute().write(UfpLink.worlds().worldFolder()));
            } catch (IOException | RuntimeException failure) {
                UfpLink.LOGGER.warn("[UFP Link] Could not write the fingerprint", failure);
                fail(source, "Could not write the fingerprint: " + failure);
            }
        }, "UFP-Link-Fingerprint");
        thread.setDaemon(true);
        thread.start();
        return true;
    }

    private static boolean start(PregenWorlds.CommandReply source, String text, boolean check, boolean force) {
        if (!check && !level4(source)) return false;
        PregenWorlds worlds = UfpLink.worlds();
        Path world = worlds.serverFolder().resolve(text).toAbsolutePath().normalize();
        String problem = !Files.isDirectory(world) ? world + " is not a folder."
                : !Files.isRegularFile(world.resolve("level.dat")) ? world + " has no level.dat: give the folder of a world."
                : sameFile(world, worlds.worldFolder()) ? "That is this server's own world."
                : PregenJob.current() != null ? "A pregen is running: /ufplink status, /ufplink cancel."
                : null;
        if (problem != null) {
            fail(source, problem);
            return false;
        }
        if (ImportTask.current() != null || ImportTask.start(world, check, force, source) == null) {
            fail(source, "Another import is running: /ufplink status, /ufplink cancel.");
            return false;
        }
        return true;
    }

    private static boolean sameFile(Path a, Path b) {
        try {
            return Files.isSameFile(a, b);
        } catch (IOException failure) {
            return a.equals(b);
        }
    }

    private static boolean hostPregen(PregenWorlds.CommandReply source, int worldK, int x, int z, String target) {
        if (LinkHost.running() == null) {
            fail(source, "This server is not a host: /ufplink invite makes it one.");
            return false;
        }
        if (SeparateWorld.isOne(target)) {
            fail(source, "That is a pregen's separate world.");
            return false;
        }
        String problem = HostJob.start(target, worldK, x, z, UfpLink.network().tileChunks);
        if (problem != null) {
            fail(source, problem);
            return false;
        }
        reply(source, "Distributed pregen started: " + HostJob.current().describe() + ". Clients: "
                + LinkHost.running().sessions().size() + "; /ufplink host status shows them.");
        return true;
    }

    private static boolean hostStatus(PregenWorlds.CommandReply source) {
        LinkHost host = LinkHost.running();
        if (host == null) {
            reply(source, "This server is not a host: /ufplink invite makes it one.");
            return false;
        }
        HostJob job = HostJob.current();
        StringBuilder text = new StringBuilder(job != null ? job.describe() : "No distributed pregen is running.");
        text.append("\nClients (").append(host.sessions().size()).append("):");
        for (HostSession session : host.sessions()) text.append("\n  ").append(session.describe());
        reply(source, text.toString());
        return true;
    }

    private static boolean clientStatus(PregenWorlds.CommandReply source) {
        LinkClient client = LinkClient.running();
        reply(source, client != null ? client.describe() : "This server is not a client: /ufplink join <invite> makes it one.");
        return client != null;
    }

    private static boolean status(PregenWorlds.CommandReply source) {
        PregenJob job = PregenJob.current();
        ImportTask task = ImportTask.current();
        HostJob distributed = HostJob.current();
        reply(source, job != null ? job.describe() : task != null ? task.describe()
                : distributed != null ? distributed.describe() : "Nothing is running.");
        return true;
    }

    private static boolean cancel(PregenWorlds.CommandReply source) {
        PregenJob job = PregenJob.current();
        ImportTask task = ImportTask.current();
        HostJob distributed = HostJob.current();
        if (job == null && task == null && distributed == null) {
            reply(source, "Nothing is running.");
            return false;
        }
        String reason = "cancelled by " + source.name();
        if (distributed != null) distributed.cancel(reason);
        if (job != null) job.cancel(reason);
        else if (task != null) task.cancel(reason);
        reply(source, "It stops after the batch or region file it is on; /ufplink pregen resume (or the same import) continues it.");
        return true;
    }

    private static void reply(PregenWorlds.CommandReply source, String message) {
        source.tell("[UFP Link] " + message, false);
    }

    private static void fail(PregenWorlds.CommandReply source, String message) {
        source.fail("[UFP Link] " + message);
    }
}
