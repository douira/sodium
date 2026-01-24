package net.caffeinemc.mods.sodium.mixin.instrumentation;

import com.llamalad7.mixinextras.expression.Definition;
import com.llamalad7.mixinextras.expression.Expression;
import com.llamalad7.mixinextras.injector.ModifyExpressionValue;
import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import com.llamalad7.mixinextras.sugar.Local;
import com.llamalad7.mixinextras.sugar.Share;
import com.llamalad7.mixinextras.sugar.ref.LocalIntRef;
import net.fabricmc.fabric.impl.client.gametest.context.TestClientWorldContextImpl;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.status.ChunkStatus;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;

@Mixin(TestClientWorldContextImpl.class)
public class TestClientWorldContextImplMixin {
    @WrapOperation(method = "areChunksLoaded", at = @At(value = "INVOKE", target = "Lnet/minecraft/client/multiplayer/ClientLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;"))
    private static ChunkAccess captureCoordinates(ClientLevel instance, int x, int z, ChunkStatus chunkStatus, boolean b, Operation<ChunkAccess> original, @Share("x") LocalIntRef xRef, @Share("z") LocalIntRef zRef) {
        xRef.set(x);
        zRef.set(z);
        return original.call(instance, x, z, chunkStatus, b);
    }

    @Definition(id = "getChunk", method = "Lnet/minecraft/client/multiplayer/ClientLevel;getChunk(IILnet/minecraft/world/level/chunk/status/ChunkStatus;Z)Lnet/minecraft/world/level/chunk/ChunkAccess;")
    @Expression("?.getChunk(?, ?, ?, ?) == null")
    @ModifyExpressionValue(method = "areChunksLoaded", at = @At("MIXINEXTRAS:EXPRESSION"))
    private static boolean modifyAreChunksLoaded(boolean original, @Share("x") LocalIntRef xRef, @Share("z") LocalIntRef zRef, @Local(name = "viewDistance") int viewDistance, @Local(name = "centerChunkX") int centerChunkX, @Local(name = "centerChunkZ") int centerChunkZ) {
        long dx = Math.max(0, Math.abs(xRef.get() - centerChunkX) - 2);
        long dz = Math.max(0, Math.abs(zRef.get() - centerChunkZ) - 2);
        return original && dx * dx + dz * dz < viewDistance * viewDistance;
    }
}
