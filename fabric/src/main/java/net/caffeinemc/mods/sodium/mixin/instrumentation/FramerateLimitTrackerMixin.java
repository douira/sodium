package net.caffeinemc.mods.sodium.mixin.instrumentation;

import com.mojang.blaze3d.platform.FramerateLimitTracker;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

@Mixin(FramerateLimitTracker.class)
public class FramerateLimitTrackerMixin {
    @Inject(method = "getThrottleReason", at = @At("HEAD"), cancellable = true)
    private void onGetThrottleReason(CallbackInfoReturnable<FramerateLimitTracker.FramerateThrottleReason> cir) {
        cir.setReturnValue(FramerateLimitTracker.FramerateThrottleReason.NONE);
        cir.cancel();
    }
}
