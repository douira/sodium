package net.caffeinemc.mods.sodium.instrumentation;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.fabricmc.fabric.api.client.gametest.v1.FabricClientGameTest;
import net.fabricmc.fabric.api.client.gametest.v1.context.ClientGameTestContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestServerContext;
import net.fabricmc.fabric.api.client.gametest.v1.context.TestSingleplayerContext;
import net.fabricmc.fabric.mixin.client.gametest.ClientChunkCacheAccessor;
import net.fabricmc.fabric.mixin.client.gametest.ClientChunkCacheStorageAccessor;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.CrashReport;
import net.minecraft.SharedConstants;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.components.debug.DebugScreenEntries;
import net.minecraft.client.gui.components.debug.DebugScreenEntryStatus;
import net.minecraft.client.gui.screens.worldselection.WorldCreationUiState;
import net.minecraft.client.multiplayer.ClientChunkCache;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.core.registries.Registries;
import net.minecraft.world.entity.Relative;
import net.minecraft.world.level.GameType;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import net.minecraft.world.level.levelgen.presets.WorldPresets;
import org.jetbrains.annotations.NotNull;
import org.joml.Vector2f;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Set;
import java.util.function.Function;
import java.util.function.Supplier;

@SuppressWarnings("UnstableApiUsage")
public class InstrumentedMeasurementRunner implements FabricClientGameTest {
    private static final long PROGRESS_LOG_INTERVAL = 5_000_000_000L; // 5 seconds
    private static final int WORLD_LOAD_TIMEOUT = SharedConstants.TICKS_PER_MINUTE * 5;
    private static final int MEASUREMENT_TIMEOUT = SharedConstants.TICKS_PER_SECOND * 30;
    private static final int VM_WARMUP = SharedConstants.TICKS_PER_SECOND * 10;
    private static final int LOCATION_WARMUP = SharedConstants.TICKS_PER_SECOND / 2;

    private static final Logger LOGGER = LoggerFactory.getLogger("Sodium Instrumentation");

    TestSingleplayerContext singleplayer;

    @Override
    public void runTest(ClientGameTestContext context) {
        // ensure no other mods are installed
        var allowedModIds = new String[] {
                "java",
                "fabric-lifecycle-events-v1",
                "fabric-resource-loader-v1",
                "fabric-block-view-api-v2",
                "fabric-rendering-v1",
                "sodium",
                "fabric-resource-loader-v0",
                "fabricloader",
                "mixinextras",
                "minecraft",
                "fabric-client-gametest-api-v1",
                "fabric-rendering-fluids-v1",
                "fabric-renderer-api-v1",
                "fabric-transitive-access-wideners-v1",
                "fabric-api-base",
        };
        FabricLoader.getInstance().getAllMods().forEach(modContainer -> {
            var modId = modContainer.getMetadata().getId();
            var isAllowed = false;
            for (var id : allowedModIds) {
                if (modId.startsWith(id)) {
                    isAllowed = true;
                    break;
                }
            }
            if (!isAllowed) {
                throw new IllegalStateException("Unallowed mod detected: " + modId + ". Please run this test in a supported launcher with no other mods installed.");
            }
        });

        var measurement = AsyncCullingMeasurement.CONTEXT;
        measurement.generateScenes();

        context.getInput().resizeWindow(1000, 500);
        context.runOnClient(client -> {
            client.options.vignette().set(false);
            client.options.framerateLimit().set(200);
            client.options.enableVsync().set(false);
            client.debugEntries.setOverlayVisible(true);
            client.debugEntries.setStatus(DebugScreenEntries.CHUNK_SOURCE_STATS, DebugScreenEntryStatus.IN_OVERLAY);
            client.options.save();
        });

        AsyncCullingMeasurement.WORLD_SEED.setApplier((seed) -> {
            if (this.singleplayer != null) {
                this.singleplayer.close();
            }
            this.singleplayer = context.worldBuilder()
                    .adjustSettings(creator -> {
                        creator.setGameMode(WorldCreationUiState.SelectedGameMode.CREATIVE);
                        creator.setSeed(seed);
                        creator.setName("Test World - " + measurement.getName());
                        var normalWorldType = creator.getSettings().worldgenLoadContext().lookupOrThrow(Registries.WORLD_PRESET).getOrThrow(WorldPresets.NORMAL);
                        creator.setWorldType(new WorldCreationUiState.WorldTypeEntry(normalWorldType));
                    }).create();

            AsyncCullingMeasurement.RENDER_DISTANCE.setApplier((renderDistance) -> setRenderDistance(context, renderDistance)
            );

            var testServer = this.singleplayer.getServer();
            testServer.runOnServer((server) -> {
                        var player = server.getPlayerList().getPlayers().get(0);
                        player.setGameMode(GameType.SPECTATOR);
//                        player.level().getChunkSource().setViewDistance(32);
                    }
            );
//            AsyncCullingMeasurement.CAMERA_POSITION.setApplier((position) -> {
//                setPlayerPosition(position, testServer);
//
//                // warm up location
//                LOGGER.info("Warming up location...");
//                warmupLocationRendering(context, testServer);
//            });
//            AsyncCullingMeasurement.CAMERA_ROTATION.setApplier((rotation) -> setPlayerOrientation(rotation, testServer));

            AsyncCullingMeasurement.CAMERA_STATE.setApplier((state) -> {
                setPlayerState(state, testServer);

                // warm up location
                LOGGER.info("Warming up location...");
                warmupLocationRendering(context, testServer);

                setPlayerState(state, testServer);
            });
        });

        // let vm warm up
        LOGGER.info("Warming up VM...");
        context.waitTicks(VM_WARMUP);

        // TODO: implement fixed fog culling behavior, the current situation is that it uses fog animation in under water scenes. we should just either use the target fog or disable fog culling entirely
        Function<Supplier<Boolean>, Boolean> wrappedTest = (Supplier<Boolean> test) -> context.computeOnClient(client -> test.get());
        var lastProgress = System.nanoTime();
        while (measurement.prepareScene(wrappedTest)) {
            context.waitTick();

            context.waitFor(client -> {
                if (!SodiumWorldRenderer.instance().isTerrainRenderComplete()) {
                    return false;
                }

                return checkChunksLoaded(client);
            }, WORLD_LOAD_TIMEOUT);
//            this.singleplayer.getClientWorld().waitForChunksRender(WORLD_LOAD_TIMEOUT);

            context.runOnClient(client ->
                    measurement.startSceneMeasurement()
            );

            context.waitFor(client -> SodiumWorldRenderer.instance().isTerrainRenderComplete() && measurement.isMeasurementComplete(), MEASUREMENT_TIMEOUT);

            context.runOnClient(client ->
                    measurement.endSceneMeasurement()
            );

            var now = System.nanoTime();
            if (now - lastProgress >= PROGRESS_LOG_INTERVAL) {
                lastProgress = now;
                LOGGER.info(measurement.getCurrentProgress(now));
            }
        }

        if (this.singleplayer != null) {
            this.singleplayer.close();
        }

        // crash with report
        context.runOnClient(client -> {
            String report = measurement.generateReport();
            client.emergencySaveAndCrash(new CrashReport("Measurement complete! This crash is intentional.", new RuntimeException()) {
                @Override
                public void getDetails(@NotNull StringBuilder stringBuilder) {
                    super.getDetails(stringBuilder);

                    stringBuilder.append("\nBegin Measurement Results\n");
                    stringBuilder.append(report);
                    stringBuilder.append("End Measurement Results\n");
                }
            });
        });
    }

    private static boolean checkChunksLoaded(Minecraft client) {
        int viewDistance = client.options.getEffectiveRenderDistance();
        int viewDistanceSquared = viewDistance * viewDistance;
        ClientLevel world = Objects.requireNonNull(client.level);
        ClientChunkCache.Storage chunks = ((ClientChunkCacheAccessor) world.getChunkSource()).getChunks();
        ClientChunkCacheStorageAccessor chunksAccessor = (ClientChunkCacheStorageAccessor) (Object) chunks;
        int centerChunkX = chunksAccessor.getCenterChunkX();
        int centerChunkZ = chunksAccessor.getCenterChunkZ();

        for (int dz = -viewDistance; dz <= viewDistance; dz++) {
            for (int dx = -viewDistance; dx <= viewDistance; dx++) {
                var x = centerChunkX + dx;
                var z = centerChunkZ + dz;
                long distX = Math.max(0, Math.abs(dx) - 2);
                long distZ = Math.max(0, Math.abs(dz) - 2);
                if (distX * distX + distZ * distZ <= viewDistanceSquared &&
                        world.getChunk(x, z, ChunkStatus.FULL, false) == null) {
                    return false;
                }
            }
        }

        return true;
    }

    private static void setPlayerOrientation(Vector2f rotation, TestServerContext testServer) {
        testServer.runOnServer((server) -> {
                    var player = server.getPlayerList().getPlayers().get(0);
                    player.teleportTo(player.level(), 0, 0, 0, Set.of(Relative.X, Relative.Y, Relative.Z), rotation.x(), rotation.y(), true);
                }
        );
    }

    private static void setPlayerPosition(double x, double y, double z, TestServerContext testServer) {
        testServer.runOnServer((server) -> {
                    var player = server.getPlayerList().getPlayers().get(0);
                    player.teleportTo(player.level(), x, y, z, Relative.ROTATION, 0, 0, true);
                }
        );
    }

    private static void setPlayerState(AsyncCullingMeasurement.CameraState state, TestServerContext testServer) {
        testServer.runOnServer((server) -> {
                    var player = server.getPlayerList().getPlayers().get(0);
                    player.teleportTo(player.level(), state.x(), state.y(), state.z(), Set.of(), state.yaw(), state.pitch(), true);
                }
        );
    }

    private static void setRenderDistance(ClientGameTestContext context, Integer renderDistance) {
        context.runOnClient(client -> {
            client.options.renderDistance().set(renderDistance);
            client.options.save();
        });
    }

    private static void warmupLocationRendering(ClientGameTestContext context, TestServerContext testServer) {
        // randomly look around to warm up rendering
        var initVectors = new Vector2f[] {
                new Vector2f(0, 0),
                new Vector2f(-120, -45),
                new Vector2f(120, 45),
                new Vector2f(180, 80),
        };
        var durationPerPosition = LOCATION_WARMUP / initVectors.length;
        for (var vec : initVectors) {
            setPlayerOrientation(vec, testServer);
            context.waitTicks(durationPerPosition);
        }
    }
}
