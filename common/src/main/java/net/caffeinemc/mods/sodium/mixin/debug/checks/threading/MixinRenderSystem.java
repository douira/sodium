package net.caffeinemc.mods.sodium.mixin.debug.checks.threading;

import com.mojang.blaze3d.pipeline.RenderCall;
import com.mojang.blaze3d.systems.RenderSystem;
import net.caffeinemc.mods.sodium.client.render.util.DeferredRenderTask;
import net.caffeinemc.mods.sodium.client.render.util.RenderAsserts;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Overwrite;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

@Mixin(RenderSystem.class)
public abstract class MixinRenderSystem {
    /**
     * @author JellySquid
     * @reason Disallow the use of RenderSystem.recordRenderCall entirely
     */
    @Overwrite(remap = false)
    public static void recordRenderCall(RenderCall call) {
        throw new UnsupportedOperationException("RenderSystem#recordRenderCall is not supported when using Sodium");
    }

    @Inject(method = "replayQueue", at = @At("HEAD"), remap = false)
    private static void onReplayQueue(CallbackInfo ci) {
        DeferredRenderTask.runAll();
    }

    @Redirect(method = {
            "setShaderColor",
            "lineWidth",
            "glGenBuffers",
            "glGenVertexArrays",
            "setShader",
            "setShaderTexture(ILnet/minecraft/resources/ResourceLocation;)V",
            "setShaderTexture(II)V",
            "setProjectionMatrix",
            "setTextureMatrix",
            "resetTextureMatrix",
            "applyModelViewMatrix",
            "backupProjectionMatrix",
            "restoreProjectionMatrix",
            "setShaderGameTime"
    }, at = @At(value = "INVOKE", target = "Lcom/mojang/blaze3d/systems/RenderSystem;isOnRenderThread()Z"), remap = false)
    private static boolean validateCurrentThread$glCommands() {
        return RenderAsserts.validateCurrentThread();
    }
}