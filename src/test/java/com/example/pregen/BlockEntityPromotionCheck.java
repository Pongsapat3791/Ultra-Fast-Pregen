package com.example.pregen;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Registry;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.ChunkPos;
import net.minecraft.world.level.LevelHeightAccessor;
import net.minecraft.world.level.biome.Biome;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.chunk.UpgradeData;

/** DUMMY placeholders become real block entities before saving, exactly as vanilla promotes them. */
public final class BlockEntityPromotionCheck {
    private BlockEntityPromotionCheck() {}

    public static void run(Registry<Biome> biomes) {
        ProtoChunk chunk = new ProtoChunk(new ChunkPos(-3, 7), UpgradeData.EMPTY,
                LevelHeightAccessor.create(-64, 384), biomes, null);
        BlockPos chest = new BlockPos(-40, 70, 115);
        BlockPos stone = new BlockPos(-39, 70, 115);
        BlockPos packed = new BlockPos(-38, 70, 115);
        chunk.setBlockState(chest, Blocks.CHEST.defaultBlockState(), false);
        chunk.setBlockState(stone, Blocks.STONE.defaultBlockState(), false);
        chunk.setBlockState(packed, Blocks.BARREL.defaultBlockState(), false);
        // What WorldGenRegion#setBlock stores for block entities placed into proto chunks.
        chunk.setBlockEntityNbt(placeholder(chest, "DUMMY"));
        chunk.setBlockEntityNbt(placeholder(stone, "DUMMY"));
        CompoundTag realData = placeholder(packed, "minecraft:barrel");
        realData.putString("LootTable", "minecraft:chests/village/village_fisher");
        chunk.setBlockEntityNbt(realData);

        int promoted = InMemoryChunkGrid.promoteDummyBlockEntities(chunk);
        require(promoted == 1, "Exactly the chest placeholder must be promoted, got " + promoted);
        require(chunk.getBlockEntity(chest) instanceof ChestBlockEntity, "The chest must get a real block entity");
        require("minecraft:chest".equals(chunk.getBlockEntityNbtForSaving(chest).getString("id")),
                "The chest must be saved with its real type, not DUMMY");
        require(chunk.getBlockEntityNbtForSaving(stone) == null && !chunk.getBlockEntitiesPos().contains(stone),
                "A placeholder on a block without a block entity must be dropped");
        require(realData.equals(chunk.getBlockEntityNbtForSaving(packed)), "Real pending data must be kept as is");
        for (BlockPos pos : chunk.getBlockEntitiesPos()) {
            require(!"DUMMY".equals(chunk.getBlockEntityNbtForSaving(pos).getString("id")), "No DUMMY may be saved");
        }
        System.out.println("BLOCK_ENTITY_PROMOTION_CHECK PASS dummy_promoted=true dummy_without_block_dropped=true real_data_kept=true");
    }

    private static CompoundTag placeholder(BlockPos pos, String id) {
        CompoundTag tag = new CompoundTag();
        tag.putInt("x", pos.getX());
        tag.putInt("y", pos.getY());
        tag.putInt("z", pos.getZ());
        tag.putString("id", id);
        return tag;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }
}
