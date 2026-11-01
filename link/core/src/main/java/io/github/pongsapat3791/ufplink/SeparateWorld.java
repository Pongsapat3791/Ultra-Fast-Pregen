package io.github.pongsapat3791.ufplink;

import io.github.pongsapat3791.ultrafastpregen.api.PregenWorlds;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Stream;

/** The world a dimension's area is generated in, apart from the world players are in. */
final class SeparateWorld {
    private SeparateWorld() {}

    /** Its dimension id, e.g. ufplink:pregen_minecraft_overworld. */
    static String idFor(String dimension) {
        int colon = dimension.indexOf(':');
        return UfpLink.MOD_ID + ":pregen_" + dimension.substring(0, colon) + "_" + dimension.substring(colon + 1).replace('/', '_');
    }

    /** Bukkit gives the world it opens for idFor's id the id minecraft:ufplink_pregen_... */
    static boolean isOne(String dimension) {
        return dimension.startsWith(UfpLink.MOD_ID + ":") || dimension.contains(":" + UfpLink.MOD_ID + "_pregen_");
    }

    /**
     * The folder of a dimension in another world: where this server keeps it in its own world, or, between
     * the Bukkit servers, which keep theirs in different places, in the world itself.
     */
    static Path folderIn(Path world, String dimension) {
        PregenWorlds worlds = UfpLink.worlds();
        Path same = world.resolve(worlds.worldFolder().relativize(worlds.dimensionFolder(dimension))).normalize();
        if (Files.isDirectory(same)) return same;
        int colon = dimension.indexOf(':');
        return world.resolve("dimensions").resolve(dimension.substring(0, colon)).resolve(dimension.substring(colon + 1));
    }

    /** The folder to delete for a separate world: Spigot keeps its dimension folder in a world folder of its own. */
    private static Path whole(Path folder) throws IOException {
        Path start = folder.toAbsolutePath().normalize();
        Path worlds = UfpLink.worlds().worldFolder().getParent();
        for (Path path = start; path != null && path.startsWith(worlds) && !path.equals(worlds); path = path.getParent()) {
            if (path.getFileName().toString().contains("pregen_")) return path;
        }
        throw new IOException("not deleting " + start + ": it is not a separate world's folder");
    }

    /** Deletes a separate world, and only that. */
    static void delete(Path folder) throws IOException {
        Path target = whole(folder);
        if (!Files.exists(target)) return;
        try (Stream<Path> files = Files.walk(target)) {
            List<Path> all = files.sorted(Comparator.reverseOrder()).toList();
            for (Path file : all) Files.deleteIfExists(file);
        }
    }
}
