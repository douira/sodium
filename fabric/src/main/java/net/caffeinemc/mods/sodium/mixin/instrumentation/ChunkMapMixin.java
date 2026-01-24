package net.caffeinemc.mods.sodium.mixin.instrumentation;

import net.minecraft.network.protocol.game.ClientboundSetChunkCacheCenterPacket;
import net.minecraft.server.level.ChunkMap;
import net.minecraft.server.level.ChunkTrackingView;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.level.ChunkPos;
import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.Shadow;

import java.util.ArrayList;

@Mixin(ChunkMap.class)
public abstract class ChunkMapMixin {

    @Shadow
    @Final
    private ServerLevel level;

    @Shadow
    protected abstract void markChunkPendingToSend(ServerPlayer serverPlayer, ChunkPos chunkPos);

    @Shadow
    protected static void dropChunk(ServerPlayer serverPlayer, ChunkPos chunkPos) {
    }

    /**
     * @author ishland
     * @reason Send unload packets before center change
     */
    @Overwrite
    private void applyChunkTrackingView(ServerPlayer serverPlayer, ChunkTrackingView chunkTrackingView) {
        if (serverPlayer.level() == this.level) {
            ChunkTrackingView chunkTrackingView2 = serverPlayer.getChunkTrackingView();

            // this.markChunkPendingToSend() only queues them, so it is fine to run them here
            ChunkTrackingView.difference(
                    chunkTrackingView2, chunkTrackingView, chunkPos -> this.markChunkPendingToSend(serverPlayer, chunkPos), chunkPos -> dropChunk(serverPlayer, chunkPos)
            );

            if (chunkTrackingView instanceof ChunkTrackingView.Positioned positioned
                    && !(chunkTrackingView2 instanceof ChunkTrackingView.Positioned positioned2 && positioned2.center().equals(positioned.center()))) {
                serverPlayer.connection.send(new ClientboundSetChunkCacheCenterPacket(positioned.center().x, positioned.center().z));
            }

            serverPlayer.setChunkTrackingView(chunkTrackingView);
        }
    }

}
