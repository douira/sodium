package net.caffeinemc.mods.sodium.mixin.instrumentation;

import com.llamalad7.mixinextras.injector.wrapoperation.Operation;
import com.llamalad7.mixinextras.injector.wrapoperation.WrapOperation;
import it.unimi.dsi.fastutil.longs.LongSet;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.server.network.PlayerChunkSender;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

@Mixin(PlayerChunkSender.class)
public class PlayerChunkSenderMixin {
    @WrapOperation(method = "dropChunk", at = @At(value = "INVOKE", target = "Lit/unimi/dsi/fastutil/longs/LongSet;remove(J)Z"))
    private boolean alwaysDropChunk0(LongSet instance, long l, Operation<Boolean> original) {
        original.call(instance, l);
        return false;
    }

    @Redirect(method = "dropChunk", at = @At(value = "INVOKE", target = "Lnet/minecraft/server/level/ServerPlayer;isAlive()Z"))
    private boolean alwaysDropChunk1(ServerPlayer instance) {
        return true;
    }
}
