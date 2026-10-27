package com.example.pregen;

import com.mojang.datafixers.util.Either;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import net.fabricmc.loader.api.FabricLoader;
import net.fabricmc.loader.api.MappingResolver;
import net.minecraft.core.BlockPos;
import net.minecraft.core.HolderLookup;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.server.level.ChunkHolder;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ThreadedLevelLightEngine;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.ChunkGenerator;
import net.minecraft.world.level.chunk.ChunkStatus;
import net.minecraft.world.level.chunk.ProtoChunk;
import net.minecraft.world.level.levelgen.structure.templatesystem.StructureTemplateManager;

/**
 * Minecraft's status steps and block entity saving as InMemoryChunkGrid calls them. 1.20.5 changed
 * both: ChunkStatus.generate takes a WorldGenContext and a ToFullChunk and its future holds the chunk
 * instead of an Either, and ProtoChunk.getBlockEntityNbtForSaving takes the registries. This jar is
 * built against 1.20.1, so on 1.20.5 and 1.20.6 they are called through method handles, found by their
 * intermediary names; one jar then runs on 1.20 to 1.20.6.
 */
abstract class ChunkSteps {
    /** The generate of 1.20.5 and later, or null before; found once. */
    private static final Modern.Calls MODERN = Modern.Calls.find();

    static ChunkSteps of(ServerLevel level, ChunkGenerator generator, StructureTemplateManager templates,
                         ThreadedLevelLightEngine light) {
        return MODERN != null ? new Modern(MODERN, level, generator, templates, light)
                : new Legacy(level, generator, templates, light);
    }

    /** status.generate on the cache's chunks; the future's value is not used. */
    abstract CompletableFuture<Object> generate(ChunkStatus status, Executor executor, List<ChunkAccess> cache);

    abstract CompoundTag blockEntityNbt(ProtoChunk chunk, BlockPos pos);

    /**
     * Whether a chunk's noise and surface need the biomes of all eight neighbors stored first. From
     * 1.20.5 a WorldGenRegion throws for a chunk it lacks or that lacks the status asked for, where
     * 1.20 to 1.20.4 answer null and the surface step then samples the biome source.
     */
    abstract boolean needsNeighborBiomes();

    /** 1.20 to 1.20.4: the calls this jar is compiled with. */
    private static final class Legacy extends ChunkSteps {
        private final ServerLevel level;
        private final ChunkGenerator generator;
        private final StructureTemplateManager templates;
        private final ThreadedLevelLightEngine light;
        private final Function<ChunkAccess, CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>>> fullChunk =
                c -> CompletableFuture.completedFuture(Either.left(c));

        Legacy(ServerLevel level, ChunkGenerator generator, StructureTemplateManager templates, ThreadedLevelLightEngine light) {
            this.level = level;
            this.generator = generator;
            this.templates = templates;
            this.light = light;
        }

        @Override
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> generate(ChunkStatus status, Executor executor, List<ChunkAccess> cache) {
            return (CompletableFuture<Object>) (CompletableFuture<?>) status.generate(executor, level, generator, templates,
                    light, fullChunk, cache);
        }

        @Override
        CompoundTag blockEntityNbt(ProtoChunk chunk, BlockPos pos) {
            return chunk.getBlockEntityNbtForSaving(pos);
        }

        @Override
        boolean needsNeighborBiomes() {
            return false;
        }
    }

    /** 1.20.5 and 1.20.6, through method handles. */
    private static final class Modern extends ChunkSteps {
        private final Calls calls;
        private final Object context;
        private final HolderLookup.Provider registries;

        Modern(Calls calls, ServerLevel level, ChunkGenerator generator, StructureTemplateManager templates,
               ThreadedLevelLightEngine light) {
            this.calls = calls;
            this.registries = level.registryAccess();
            try {
                this.context = calls.context.invoke(level, generator, templates, light);
            } catch (Throwable failure) {
                throw new IllegalStateException("Could not create Minecraft's WorldGenContext", failure);
            }
        }

        @Override
        @SuppressWarnings("unchecked")
        CompletableFuture<Object> generate(ChunkStatus status, Executor executor, List<ChunkAccess> cache) {
            try {
                return (CompletableFuture<Object>) calls.generate.invoke(status, context, executor, calls.toFullChunk, cache);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        CompoundTag blockEntityNbt(ProtoChunk chunk, BlockPos pos) {
            try {
                return (CompoundTag) calls.blockEntityNbt.invoke(chunk, pos, registries);
            } catch (RuntimeException | Error failure) {
                throw failure;
            } catch (Throwable failure) {
                throw new IllegalStateException(failure);
            }
        }

        @Override
        boolean needsNeighborBiomes() {
            return true;
        }

        /** The handles, by intermediary name: WorldGenContext is class_9312, ToFullChunk class_9311. */
        record Calls(MethodHandle context, MethodHandle generate, Object toFullChunk, MethodHandle blockEntityNbt) {
            static Calls find() {
                MappingResolver names = FabricLoader.getInstance().getMappingResolver();
                Class<?> contextType;
                Class<?> toFullType;
                try {
                    contextType = Class.forName(names.mapClassName("intermediary", "net.minecraft.class_9312"),
                            false, ChunkSteps.class.getClassLoader());
                    toFullType = Class.forName(names.mapClassName("intermediary", "net.minecraft.class_9311"),
                            false, ChunkSteps.class.getClassLoader());
                } catch (ClassNotFoundException olderMinecraft) {
                    return null;
                }
                try {
                    MethodHandles.Lookup lookup = MethodHandles.publicLookup();
                    MethodHandle context = lookup.findConstructor(contextType, MethodType.methodType(void.class,
                            ServerLevel.class, ChunkGenerator.class, StructureTemplateManager.class, ThreadedLevelLightEngine.class));
                    String generateName = names.mapMethodName("intermediary", "net.minecraft.class_2806", "method_12154",
                            "(Lnet/minecraft/class_9312;Ljava/util/concurrent/Executor;Lnet/minecraft/class_9311;Ljava/util/List;)"
                                    + "Ljava/util/concurrent/CompletableFuture;");
                    MethodHandle generate = lookup.findVirtual(ChunkStatus.class, generateName, MethodType.methodType(
                            CompletableFuture.class, contextType, Executor.class, toFullType, List.class));
                    String nbtName = names.mapMethodName("intermediary", "net.minecraft.class_2839", "method_20598",
                            "(Lnet/minecraft/class_2338;Lnet/minecraft/class_7225$class_7874;)Lnet/minecraft/class_2487;");
                    MethodHandle blockEntityNbt = lookup.findVirtual(ProtoChunk.class, nbtName, MethodType.methodType(
                            CompoundTag.class, BlockPos.class, HolderLookup.Provider.class));
                    // Statuses up to SPAWN never convert to a full chunk; FULL would get the chunk back as it is.
                    Object toFullChunk = Proxy.newProxyInstance(toFullType.getClassLoader(), new Class<?>[]{toFullType},
                            (proxy, method, args) -> switch (method.getName()) {
                                case "equals" -> proxy == args[0];
                                case "hashCode" -> System.identityHashCode(proxy);
                                case "toString" -> "Ultra Fast Pregen ToFullChunk";
                                default -> CompletableFuture.completedFuture(args[0]);
                            });
                    return new Calls(context, generate, toFullChunk, blockEntityNbt);
                } catch (ReflectiveOperationException | RuntimeException failure) {
                    throw new IllegalStateException("This Minecraft has WorldGenContext (1.20.5 or later) but not the"
                            + " ChunkStatus.generate and ProtoChunk.getBlockEntityNbtForSaving of 1.20.5 and 1.20.6", failure);
                }
            }
        }
    }
}
