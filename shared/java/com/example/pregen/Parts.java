package com.example.pregen;

import io.github.pongsapat3791.ultrafastpregen.api.PregenWorlds;
import io.github.pongsapat3791.ultrafastpregen.link.UfpLink;
import io.github.pongsapat3791.ultrafastpregen.manager.Manager;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;

/**
 * The three parts of the mod and their switches: Ultra Fast Pregen itself, its Link (pregen on other servers,
 * /ufplink) and its Manager (a web page, /ufpmanager). Each is turned on and off by
 * /pregen ufp|ufplink|ufpmanager on|off; the state is kept in the settings file and holds after a restart.
 * Link and Manager start off, and the console says so at every start with the command that turns them on.
 * With Ultra Fast Pregen itself off nothing of the mod runs: no run starts or continues, and Link and Manager
 * wait for it, their own switches kept. An older Link or Manager installed as a mod of its own keeps the
 * built-in one off.
 */
public final class Parts {
    public enum Part {
        UFP("ufp", PreGenMod.NAME, null),
        LINK("ufplink", "Ultra Fast Pregen Link", "ufplink"),
        MANAGER("ufpmanager", "Ultra Fast Pregen Manager", "ufpmanager");

        /** Its word in the command and its key in the settings file. */
        public final String key;
        public final String title;
        /** The mod id it had as a mod of its own, or null. */
        final String oldModId;

        Part(String key, String title, String oldModId) {
            this.key = key;
            this.title = title;
            this.oldModId = oldModId;
        }
    }

    /** What the settings file says, as last read or set. */
    private static volatile boolean ufp = true;
    private static volatile boolean link;
    private static volatile boolean manager;
    private static volatile boolean started;

    private Parts() {}

    /** Whether Ultra Fast Pregen itself is on: runs may start. True before the mod has read its settings. */
    public static boolean ufpOn() {
        return ufp;
    }

    /** Whether the part's switch is on (for Link and Manager: not whether it runs, see its own running()). */
    public static boolean on(Part part) {
        return switch (part) {
            case UFP -> ufp;
            case LINK -> link;
            case MANAGER -> manager;
        };
    }

    /**
     * From the loader's entry point, once, after the API is there and the loaded mods are known: reads the
     * switches, tells the console, starts what is on, and takes the two commands.
     */
    static synchronized void start() {
        if (started) return;
        started = true;
        PreGenConfig config = PreGenConfig.load();
        ufp = config.ufpOn;
        link = config.linkOn;
        manager = config.managerOn;
        PregenWorldsImpl.INSTANCE.addCommand(Part.LINK.key, Parts::linkCommand);
        PregenWorldsImpl.INSTANCE.addCommand(Part.MANAGER.key, Parts::managerCommand);
        if (!ufp) {
            PreGenMod.LOGGER.info("[PreGen] {} is off (ufp=off in config/{}). Turn it on with /pregen ufp on.",
                    PreGenMod.NAME, PreGenConfig.FILE_NAME);
        }
        for (Part part : new Part[]{Part.LINK, Part.MANAGER}) {
            String old = oldMod(part);
            if (old != null) {
                PreGenMod.LOGGER.info("[PreGen] {} is built into {} now, but the older mod \"{}\" ({}) is installed too:"
                        + " the built-in one stays off. Remove that jar to use the built-in one.", part.title, PreGenMod.NAME,
                        part.oldModId, old);
            } else if (!on(part)) {
                PreGenMod.LOGGER.info("[PreGen] {} is off. Turn it on with /pregen {} on ({}).", part.title, part.key, about(part));
            } else if (!ufp) {
                PreGenMod.LOGGER.info("[PreGen] {} is on, and starts when {} is turned on.", part.title, PreGenMod.NAME);
            }
        }
        apply();
    }

    private static String about(Part part) {
        return part == Part.LINK ? "pregen on other servers, /ufplink" : "a web page to run pregens, /ufpmanager";
    }

    /** The version of an older mod of this part that is installed, or null. */
    private static String oldMod(Part part) {
        return part.oldModId == null ? null : PreGenMod.mods.get(part.oldModId);
    }

    /** Starts and stops Link and Manager to match the switches. */
    private static void apply() {
        boolean wantLink = ufp && link && oldMod(Part.LINK) == null;
        boolean wantManager = ufp && manager && oldMod(Part.MANAGER) == null;
        try {
            if (wantLink && !UfpLink.running()) {
                UfpLink.start(PreGenMod.configDirectory, PregenWorldsImpl.INSTANCE.serverRunning());
            } else if (!wantLink && UfpLink.running()) {
                UfpLink.stop();
            }
        } catch (RuntimeException | LinkageError failure) {
            PreGenMod.LOGGER.warn("[PreGen] {} did not start", Part.LINK.title, failure);
        }
        try {
            if (wantManager && !Manager.running()) {
                Path config = PreGenMod.configDirectory.toAbsolutePath();
                Manager.start(new Manager.Layout(Path.of("").toAbsolutePath(), config, config.resolve(PreGenConfig.FILE_NAME),
                        message -> PreGenMod.LOGGER.info("[UFP Manager] {}", message)));
            } else if (!wantManager && Manager.running()) {
                Manager.stop();
            }
        } catch (RuntimeException | LinkageError failure) {
            PreGenMod.LOGGER.warn("[PreGen] {} did not start", Part.MANAGER.title, failure);
        }
    }

    /** What /pregen ufp|ufplink|ufpmanager on|off did, for the one who typed it. */
    public record Outcome(boolean done, String message) {}

    /** Turns a part on or off, at once, and writes the switch into the settings file. On the server thread. */
    public static synchronized Outcome set(Part part, boolean on) {
        String old = oldMod(part);
        if (on && old != null) {
            return new Outcome(false, "The older mod \"" + part.oldModId + "\" (" + old + ") is installed: remove that jar to use the "
                    + part.title + " built into " + PreGenMod.NAME + ".");
        }
        boolean before = on(part);
        try {
            PreGenConfig.set(PreGenMod.configDirectory, part.key, on ? "on" : "off");
        } catch (IOException failure) {
            return new Outcome(false, "Could not write config/" + PreGenConfig.FILE_NAME + ": " + failure.getMessage());
        }
        switch (part) {
            case UFP -> ufp = on;
            case LINK -> link = on;
            case MANAGER -> manager = on;
        }
        String note = "";
        if (part == Part.UFP && !on && PregenRuns.stop()) note = " The running task stops after its current batch.";
        apply();
        if (part != Part.UFP && on && !ufp) {
            note = " It starts when " + PreGenMod.NAME + " is turned on (/pregen ufp on).";
        } else if (part == Part.LINK && on && !UfpLink.running() || part == Part.MANAGER && on && !Manager.running()) {
            note = " It did not start: see the server log.";
        } else if (part == Part.MANAGER && on) {
            note = " /ufpmanager gives a login link.";
        } else if (part == Part.LINK && on) {
            note = " /ufplink shows its commands.";
        }
        String state = on ? "on" : "off";
        PreGenMod.LOGGER.info("[PreGen] {} is {} ({}={} in config/{}).", part.title, state, part.key, state, PreGenConfig.FILE_NAME);
        return new Outcome(true, part.title + (before == on ? " is already " : " is now ") + state + "." + note);
    }

    /** One line with the three switches. */
    public static String describe() {
        StringBuilder text = new StringBuilder();
        for (Part part : Part.values()) {
            if (!text.isEmpty()) text.append(" | ");
            text.append(part.title).append(": ").append(on(part) ? "on" : "off");
            if (part != Part.UFP && on(part)) {
                boolean runs = part == Part.LINK ? UfpLink.running() : Manager.running();
                if (!runs) text.append(oldMod(part) != null ? " (an older mod of it is installed)" : !ufp ? " (waits for Ultra Fast Pregen)" : " (not running)");
            }
        }
        return text.append(". Change with /pregen ufp|ufplink|ufpmanager on|off.").toString();
    }

    private static boolean linkCommand(String arguments, PregenWorlds.CommandReply source) {
        if (UfpLink.running()) return UfpLink.command(arguments, source);
        source.fail("[UFP Link] " + off(Part.LINK));
        return false;
    }

    private static boolean managerCommand(String arguments, PregenWorlds.CommandReply source) {
        if (Manager.running()) return Manager.command(arguments, source);
        source.fail("[UFP Manager] " + off(Part.MANAGER));
        return false;
    }

    /** Why a part's command does nothing now, and what turns the part on. */
    private static String off(Part part) {
        String old = oldMod(part);
        if (old != null) return "The older mod \"" + part.oldModId + "\" is installed and did not take this command: remove one of the two.";
        if (!on(part)) return part.title + " is off. Turn it on with /pregen " + part.key + " on.";
        if (!ufp) return PreGenMod.NAME + " is off. Turn it on with /pregen ufp on.";
        return part.title + " did not start: see the server log.";
    }

    /** "on"/"off" (also true/false) as a switch; null when it is neither. */
    static Boolean parse(String value) {
        String text = value.trim().toLowerCase(Locale.ROOT);
        if (text.equals("on") || text.equals("true")) return Boolean.TRUE;
        if (text.equals("off") || text.equals("false")) return Boolean.FALSE;
        return null;
    }
}
