package io.github.pongsapat3791.ufplink;

import io.github.pongsapat3791.ufplink.tool.Json;
import io.github.pongsapat3791.ultrafastpregen.api.PregenWorlds;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.SortedMap;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * What a world's generation depends on, kept in ufplink-fingerprint.json in the world folder:
 * Minecraft's chunk format (its data version), the seed, the mods and datapacks, and for each
 * dimension its generator settings and a probe: the terrain height and biome its generator gives
 * at 64 places, which catches generation that differs even where the settings read the same.
 * Written when the server starts and by /ufplink fingerprint; /ufplink import compares the
 * source world's file with this server's own.
 */
final class Fingerprint {
    static final String FILE_NAME = "ufplink-fingerprint.json";
    static final int FORMAT = 1;

    record Dimension(String generator, String probe) {}

    /** Differences that stop an import, differences that need "force", and the dimensions to import. */
    record Comparison(List<String> blocking, List<String> differences, List<String> dimensions, List<String> notes) {
        boolean importable(boolean force) {
            return blocking.isEmpty() && (force || differences.isEmpty()) && !dimensions.isEmpty();
        }
    }

    final String minecraft;
    final int dataVersion;
    final long seed;
    final String loader;
    final String link;
    final SortedMap<String, String> mods;
    final List<String> datapacks;
    final SortedMap<String, Dimension> dimensions;

    private Fingerprint(String minecraft, int dataVersion, long seed, String loader, String link,
                        SortedMap<String, String> mods, List<String> datapacks, SortedMap<String, Dimension> dimensions) {
        this.minecraft = minecraft;
        this.dataVersion = dataVersion;
        this.seed = seed;
        this.loader = loader;
        this.link = link;
        this.mods = mods;
        this.datapacks = datapacks;
        this.dimensions = dimensions;
    }

    /**
     * Mods that cannot change what is generated: Minecraft and its loader, the parts of Fabric API,
     * this mod and the Manager. Ultra Fast Pregen is not one: it does the generating, so its version counts.
     */
    static boolean ignoredMod(String id) {
        return id.equals("minecraft") || id.equals("java") || id.equals("fabricloader") || id.equals("mixinextras")
                || id.equals("fabric-api") || id.startsWith("fabric-") || id.equals(UfpLink.MOD_ID) || id.equals("ufpmanager")
                || id.equals("UltraFastPregenManager") || id.equals("UltraFastPregenLink");
    }

    /** This server's fingerprint; any thread (generators and registries do not change while it runs). */
    static Fingerprint compute() {
        PregenWorlds.ServerFacts facts = UfpLink.worlds().facts();
        SortedMap<String, String> mods = new TreeMap<>();
        facts.mods().forEach((id, version) -> {
            if (!ignoredMod(id)) mods.put(id, version);
        });
        SortedMap<String, Dimension> dimensions = new TreeMap<>();
        facts.dimensions().forEach((id, dimension) -> dimensions.put(id, new Dimension(dimension.generator(), dimension.probe())));
        return new Fingerprint(facts.minecraft(), facts.dataVersion(), facts.seed(), facts.loader(), UfpLink.version(), mods,
                new ArrayList<>(facts.datapacks()), dimensions);
    }

    static Path file(Path worldFolder) {
        return worldFolder.resolve(FILE_NAME);
    }

    /** Computes and writes this server's fingerprint off the server thread. */
    static void writeInBackground() {
        Thread thread = new Thread(() -> {
            try {
                Path written = compute().write(UfpLink.worlds().worldFolder());
                UfpLink.LOGGER.info("[UFP Link] Fingerprint of this world written to {}", written);
            } catch (IOException | RuntimeException failure) {
                // A server that stops right after it started writes it at its next start.
                if (UfpLink.worlds().serverRunning()) UfpLink.LOGGER.warn("[UFP Link] Could not write this world's fingerprint", failure);
            }
        }, "UFP-Link-Fingerprint");
        thread.setDaemon(true);
        thread.start();
    }

    Path write(Path worldFolder) throws IOException {
        Path file = file(worldFolder);
        Path temp = file.resolveSibling(FILE_NAME + ".tmp");
        Files.writeString(temp, toJsonString(), StandardCharsets.UTF_8);
        Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        return file;
    }

    /** The fingerprint a world folder holds, or null when it has none. */
    static Fingerprint read(Path worldFolder) throws IOException {
        Path file = file(worldFolder);
        if (!Files.isRegularFile(file)) return null;
        return fromJson(Files.readString(file, StandardCharsets.UTF_8));
    }

    String toJsonString() {
        StringBuilder mods = new StringBuilder();
        this.mods.forEach((id, version) -> mods.append(mods.length() == 0 ? "" : ",\n").append("    ").append(quote(id)).append(": ").append(quote(version)));
        StringBuilder packs = new StringBuilder();
        datapacks.forEach(pack -> packs.append(packs.length() == 0 ? "" : ",\n").append("    ").append(quote(pack)));
        StringBuilder worlds = new StringBuilder();
        dimensions.forEach((id, dimension) -> worlds.append(worlds.length() == 0 ? "" : ",\n").append("    ").append(quote(id))
                .append(": {\"generator\": ").append(quote(dimension.generator())).append(", \"probe\": ").append(quote(dimension.probe())).append("}"));
        return "{\n  \"format\": " + FORMAT + ",\n  \"about\": " + quote("Written by " + UfpLink.NAME + "; /ufplink import on another server compares it with its own.")
                + ",\n  \"minecraft\": " + quote(minecraft) + ",\n  \"data_version\": " + dataVersion + ",\n  \"seed\": " + seed
                + ",\n  \"loader\": " + quote(loader) + ",\n  \"ufplink\": " + quote(link)
                + ",\n  \"mods\": {\n" + mods + "\n  },\n  \"datapacks\": [\n" + packs + "\n  ],\n  \"dimensions\": {\n" + worlds + "\n  }\n}\n";
    }

    private static String quote(String text) {
        return '"' + text.replace("\\", "\\\\").replace("\"", "\\\"") + '"';
    }

    @SuppressWarnings("unchecked")
    static Fingerprint fromJson(String text) throws IOException {
        Map<String, Object> json = (Map<String, Object>) Json.parse(text);
        if (((Number) json.get("format")).intValue() != FORMAT) {
            throw new IOException("fingerprint format " + json.get("format") + "; this version of " + UfpLink.NAME + " reads " + FORMAT);
        }
        SortedMap<String, String> mods = new TreeMap<>();
        ((Map<String, Object>) json.get("mods")).forEach((id, version) -> mods.put(id, (String) version));
        List<String> datapacks = new ArrayList<>();
        ((List<Object>) json.get("datapacks")).forEach(pack -> datapacks.add((String) pack));
        SortedMap<String, Dimension> dimensions = new TreeMap<>();
        ((Map<String, Object>) json.get("dimensions")).forEach((id, value) -> {
            Map<String, Object> dimension = (Map<String, Object>) value;
            dimensions.put(id, new Dimension((String) dimension.get("generator"), (String) dimension.get("probe")));
        });
        return new Fingerprint((String) json.get("minecraft"), ((Number) json.get("data_version")).intValue(),
                ((Number) json.get("seed")).longValue(), (String) json.get("loader"), (String) json.get("ufplink"),
                mods, datapacks, dimensions);
    }

    /**
     * Whether chunks of the source world fit this one. A different chunk format or seed stops the
     * import; different mods, datapacks or dimension generation need "force", since chunks made by
     * another generator would not line up with the ones this server makes next to them.
     */
    static Comparison compare(Fingerprint source, Fingerprint target) {
        List<String> blocking = new ArrayList<>();
        List<String> differences = new ArrayList<>();
        List<String> dimensions = new ArrayList<>();
        List<String> notes = new ArrayList<>();
        if (source.dataVersion != target.dataVersion) {
            blocking.add("Minecraft differs: the source world is " + source.minecraft + " (data version " + source.dataVersion
                    + "), this server " + target.minecraft + " (" + target.dataVersion + ").");
        }
        if (source.seed != target.seed) {
            blocking.add("The seed differs: the source world has " + source.seed + ", this world " + target.seed + ".");
        }
        TreeSet<String> modIds = new TreeSet<>(source.mods.keySet());
        modIds.addAll(target.mods.keySet());
        List<String> modChanges = new ArrayList<>();
        for (String id : modIds) {
            String there = source.mods.get(id);
            String here = target.mods.get(id);
            if (there == null) modChanges.add(id + " " + here + " only here");
            else if (here == null) modChanges.add(id + " " + there + " only in the source");
            else if (!there.equals(here)) modChanges.add(id + " " + there + " in the source, " + here + " here");
        }
        if (!modChanges.isEmpty()) differences.add("Mods differ: " + String.join("; ", modChanges) + ".");
        if (!source.datapacks.equals(target.datapacks)) {
            differences.add("Datapacks differ: the source has " + source.datapacks + ", this server " + target.datapacks + ".");
        }
        for (Map.Entry<String, Dimension> entry : source.dimensions.entrySet()) {
            Dimension here = target.dimensions.get(entry.getKey());
            if (here == null) {
                notes.add(entry.getKey() + " is not on this server; skipped.");
                continue;
            }
            dimensions.add(entry.getKey());
            if (!here.generator().equals(entry.getValue().generator())) {
                differences.add(entry.getKey() + ": the generator settings differ.");
            } else if (!here.probe().equals(entry.getValue().probe())) {
                differences.add(entry.getKey() + ": the generator gives other terrain or biomes at the probed places.");
            }
        }
        return new Comparison(blocking, differences, dimensions, notes);
    }

}
