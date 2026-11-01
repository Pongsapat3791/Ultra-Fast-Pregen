package io.github.pongsapat3791.ufpmanager;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The colors of the terrain preview, by block and biome name. They are a table here, as Minecraft's own colors are in
 * classes this mod does not touch. A block not in the table, a modded one say, gets its color from its name.
 */
final class BlockColors {
    enum Tint { NONE, GRASS, FOLIAGE }

    /**
     * How a block shows from above.
     *
     * @param skip  the view passes through it: air, plants, torches
     * @param water water, which shows the floor below it
     */
    record Kind(int color, Tint tint, boolean skip, boolean water) {}

    /** A biome's grass, foliage and water colors. */
    record Biome(int grass, int foliage, int water) {}

    private static final Kind SKIP = new Kind(0, Tint.NONE, true, false);
    private static final Kind WATER = new Kind(0x3F76E4, Tint.NONE, false, true);
    private static final Biome PLAINS = new Biome(0x91BD59, 0x77AB2F, 0x3F76E4);
    private static final Map<String, Kind> BLOCKS = new ConcurrentHashMap<>();
    private static final Map<String, Biome> BIOMES = new ConcurrentHashMap<>();

    private static final Set<String> SEE_THROUGH = Set.of(
            "air", "cave_air", "void_air", "barrier", "light", "structure_void", "short_grass", "grass", "tall_grass", "fern", "large_fern",
            "dead_bush", "dandelion", "poppy", "blue_orchid", "allium", "azure_bluet", "red_tulip", "orange_tulip", "white_tulip",
            "pink_tulip", "oxeye_daisy", "cornflower", "lily_of_the_valley", "wither_rose", "torchflower", "sunflower", "lilac", "rose_bush",
            "peony", "pitcher_plant", "sweet_berry_bush", "torch", "wall_torch", "soul_torch", "soul_wall_torch", "vine", "glow_lichen",
            "sculk_vein", "hanging_roots", "spore_blossom", "small_dripleaf", "red_mushroom", "brown_mushroom", "crimson_roots",
            "warped_roots", "nether_sprouts", "crimson_fungus", "warped_fungus", "twisting_vines", "twisting_vines_plant", "weeping_vines",
            "weeping_vines_plant", "cave_vines", "cave_vines_plant", "pink_petals", "leaf_litter", "wildflowers", "bush", "firefly_bush",
            "short_dry_grass", "tall_dry_grass", "cactus_flower", "resin_clump", "pale_hanging_moss", "seagrass", "tall_seagrass", "kelp",
            "kelp_plant", "sea_pickle", "bubble_column", "sugar_cane", "bamboo_sapling", "glass", "glass_pane", "tinted_glass", "fire",
            "soul_fire", "nether_portal", "end_portal", "ladder", "rail", "cobweb", "tripwire", "redstone_wire");

    private static final String[] TABLE = {
        "stone 7D7D7D", "deepslate 5E5E63", "cobbled_deepslate 55555A", "tuff 6C6D66", "calcite DFE0DC", "andesite 888889", "diorite BCBCBC",
        "granite 95674F", "dirt 866043", "coarse_dirt 77553B", "rooted_dirt 90674C", "podzol 5B3F24", "mycelium 6F6369", "mud 3C393D",
        "muddy_mangrove_roots 443A32", "sand DBD3A0", "red_sand BE6621", "suspicious_sand D6CD9B", "gravel 837F7E", "suspicious_gravel 867E7C",
        "clay A0A6B3", "snow F9FFFE", "snow_block F9FFFE", "powder_snow F4FAFC", "ice 91B7FD", "packed_ice 8DB4FA", "blue_ice 74A7FD",
        "sandstone D8CB9B", "red_sandstone B05A1F", "terracotta 985E43", "white_terracotta D1B2A1", "orange_terracotta A15325",
        "yellow_terracotta BA8523", "light_gray_terracotta 876A61", "brown_terracotta 4D3324", "red_terracotta 8F3D2F", "gray_terracotta 392A24",
        "cactus 5A8C2C", "pumpkin C07615", "melon 6F9130", "hay_block A68B12", "moss_block 596E2D", "moss_carpet 596E2D", "pale_moss_block 6E7568",
        "pale_moss_carpet 6E7568", "dripstone_block 866B5C", "pointed_dripstone 866B5C", "amethyst_block 8562BF", "sculk 0D1E24",
        "sculk_catalyst 0F2329", "mushroom_stem CBC4B9", "red_mushroom_block C82E2D", "brown_mushroom_block 957051", "bamboo 5F9A28",
        "bamboo_block 7C8C23", "lily_pad 208030", "big_dripleaf 6FA235", "azalea 5F7C2C", "flowering_azalea 7A6E83", "mangrove_roots 4A3B28",
        "bedrock 555555", "obsidian 0F0B19", "crying_obsidian 21103B", "lava CF5B14", "magma_block 8E3F1F", "netherrack 6F3232",
        "nether_quartz_ore 7B4A47", "nether_gold_ore 7E4A2D", "soul_sand 513E33", "soul_soil 4B392E", "basalt 4B4B4F", "smooth_basalt 3F3F45",
        "blackstone 2A2429", "gilded_blackstone 3A2C24", "nether_wart_block 730000", "warped_wart_block 167E86", "crimson_nylium 831F1F",
        "warped_nylium 2B7265", "glowstone AC8355", "shroomlight F09A4F", "bone_block E5E1CF", "ancient_debris 5E4238", "end_stone DBDE9E",
        "end_stone_bricks DBDE9E", "purpur_block A67BA6", "purpur_pillar A67BA6", "purpur_stairs A67BA6", "purpur_slab A67BA6", "chorus_plant 5D3A5D",
        "chorus_flower 8E6D8E", "dirt_path 94793C", "farmland 5A3A1F", "cobblestone 7A7A7A", "mossy_cobblestone 6E7A5E", "spawner 2B3B47",
        "chest 9C6E2B", "barrel 7F5B2F", "bell C9A23A", "beacon 76DDD7", "prismarine 63A094", "dark_prismarine 335F4D", "sea_lantern ACC8BE",
        "dried_kelp_block 40452B", "sponge C3C350", "wet_sponge ABB54A", "copper_block C06C50", "oxidized_copper 52A084", "iron_block DCDCDC",
        "gold_block F5D13A", "diamond_block 62DDD8", "emerald_block 2FC463", "lapis_block 1F438C", "redstone_block AB1B09", "coal_block 101010",
        "tnt B23C2A",
    };

    private static final String[] DYES = {
        "white F0F0F0", "orange E17C20", "magenta C04CB7", "light_blue 3CA9D6", "yellow EDC92C", "lime 70B91A", "pink EA8FAE", "gray 3F4448",
        "light_gray 8E8E86", "cyan 158991", "purple 7A2AAC", "blue 2C2E90", "brown 734A29", "green 546D1B", "red A12823", "black 15151A",
    };

    private static final String[] BIOME_TABLE = {
        "plains 91BD59 77AB2F", "sunflower_plains 91BD59 77AB2F", "snowy_plains 80B497 60A17B", "ice_spikes 80B497 60A17B", "desert BFB755 AEA42A",
        "swamp 6A7039 6A7039 617B64", "mangrove_swamp 6A7039 8DB127 3A7A6A", "forest 79C05A 59AE30", "flower_forest 79C05A 59AE30",
        "birch_forest 88BB67 6BA941", "old_growth_birch_forest 88BB67 6BA941", "dark_forest 507A32 3F7D1E", "pale_garden 778272 878D76 76889D",
        "old_growth_pine_taiga 86B87F 68A55F", "old_growth_spruce_taiga 86B783 68A464", "taiga 86B783 68A464", "snowy_taiga 80B497 60A17B",
        "savanna BFB755 AEA42A", "savanna_plateau BFB755 AEA42A", "windswept_savanna BFB755 AEA42A", "windswept_hills 8AB689 6DA36B",
        "windswept_gravelly_hills 8AB689 6DA36B", "windswept_forest 8AB689 6DA36B", "jungle 59C93C 30BB0B", "sparse_jungle 64C73F 3EB80F",
        "bamboo_jungle 59C93C 30BB0B", "badlands 90814D 9E814D", "eroded_badlands 90814D 9E814D", "wooded_badlands 90814D 9E814D",
        "meadow 83BB6D 63A948", "cherry_grove B6DB61 B6DB61", "grove 80B497 60A17B", "snowy_slopes 80B497 60A17B", "frozen_peaks 80B497 60A17B",
        "jagged_peaks 80B497 60A17B", "stony_peaks 9ABE4B 82AC1E", "river 8EB971 71A74D", "frozen_river 80B497 60A17B 3938C9",
        "beach 91BD59 77AB2F", "snowy_beach 80B497 60A17B 3D57D6", "stony_shore 8AB689 6DA36B", "warm_ocean 8EB971 71A74D 43D5EE",
        "lukewarm_ocean 8EB971 71A74D 45ADF2", "deep_lukewarm_ocean 8EB971 71A74D 45ADF2", "ocean 8EB971 71A74D", "deep_ocean 8EB971 71A74D",
        "cold_ocean 8EB971 71A74D 3D57D6", "deep_cold_ocean 8EB971 71A74D 3D57D6", "frozen_ocean 80B497 60A17B 3938C9",
        "deep_frozen_ocean 80B497 60A17B 3938C9", "mushroom_fields 55C93F 2BBB0F", "lush_caves 91BD59 77AB2F",
    };

    static {
        for (String entry : TABLE) {
            String[] parts = entry.split(" ");
            BLOCKS.put(parts[0], new Kind(Integer.parseInt(parts[1], 16), Tint.NONE, false, false));
        }
        for (String name : SEE_THROUGH) BLOCKS.put(name, SKIP);
        BLOCKS.put("water", WATER);
        BLOCKS.put("grass_block", new Kind(PLAINS.grass(), Tint.GRASS, false, false));
        BLOCKS.put("birch_leaves", new Kind(0x80A755, Tint.NONE, false, false));
        BLOCKS.put("spruce_leaves", new Kind(0x619961, Tint.NONE, false, false));
        BLOCKS.put("cherry_leaves", new Kind(0xE8B6D2, Tint.NONE, false, false));
        BLOCKS.put("pale_oak_leaves", new Kind(0x8E9389, Tint.NONE, false, false));
        BLOCKS.put("azalea_leaves", new Kind(0x5F7C2C, Tint.NONE, false, false));
        BLOCKS.put("flowering_azalea_leaves", new Kind(0x7A6E83, Tint.NONE, false, false));
        for (String entry : BIOME_TABLE) {
            String[] parts = entry.split(" ");
            int water = parts.length > 3 ? Integer.parseInt(parts[3], 16) : PLAINS.water();
            BIOMES.put(parts[0], new Biome(Integer.parseInt(parts[1], 16), Integer.parseInt(parts[2], 16), water));
        }
    }

    private BlockColors() {}

    static Kind block(String id) {
        String name = id.substring(id.indexOf(':') + 1);
        return BLOCKS.computeIfAbsent(name, BlockColors::guess);
    }

    static Biome biome(String id) {
        return BIOMES.getOrDefault(id.substring(id.indexOf(':') + 1), PLAINS);
    }

    private static Kind solid(int color) {
        return new Kind(color, Tint.NONE, false, false);
    }

    private static Kind guess(String name) {
        for (String entry : DYES) {
            String[] parts = entry.split(" ");
            if (name.startsWith(parts[0] + "_")) return solid(Integer.parseInt(parts[1], 16));
        }
        if (name.endsWith("_leaves")) return new Kind(0x77AB2F, Tint.FOLIAGE, false, false);
        if (name.endsWith("_sapling") || name.endsWith("_flower") || name.endsWith("_button") || name.endsWith("_sign")
                || name.endsWith("_pressure_plate") || name.endsWith("_carpet") || name.endsWith("_grass")) return SKIP;
        if (name.contains("glass")) return SKIP;
        if (name.contains("_log") || name.contains("_wood") || name.contains("_stem") || name.contains("hyphae")) return solid(0x6B5030);
        if (name.contains("planks") || name.contains("_fence") || name.contains("_stairs") && name.contains("oak")) return solid(0x9C7F4E);
        if (name.contains("sandstone") || name.contains("sand")) return solid(0xD8CB9B);
        if (name.contains("deepslate")) return solid(0x5E5E63);
        if (name.contains("brick")) return solid(0x966A5A);
        if (name.contains("copper")) return solid(0xA87050);
        if (name.contains("coral")) return solid(0xC0587A);
        if (name.contains("ore") || name.contains("stone")) return solid(0x7D7D7D);
        if (name.contains("dirt") || name.contains("soil")) return solid(0x866043);
        if (name.contains("ice")) return solid(0x91B7FD);
        if (name.contains("snow")) return solid(0xF9FFFE);
        if (name.contains("water")) return WATER;
        if (name.contains("lava")) return solid(0xCF5B14);
        return solid(0x8A8A8A);
    }
}
