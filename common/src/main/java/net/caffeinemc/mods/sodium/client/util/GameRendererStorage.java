package net.caffeinemc.mods.sodium.client.util;

import com.mojang.blaze3d.pipeline.RenderTarget;
import org.joml.Matrix4fc;

public interface GameRendererStorage extends FogStorage {
    Matrix4fc sodium$getProjectionMatrix();

    RenderTarget sodium$getMainRenderTarget();
}
