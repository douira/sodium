package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import net.minecraft.client.renderer.chunk.VisibilitySet;
import net.minecraft.core.BlockPos;

public interface VisibilityGraph {
    void setOpaque(int x, int y, int z);
    
    default void setOpaque(BlockPos pos) {
        setOpaque(pos.getX(), pos.getY(), pos.getZ());
    }
    
    VisibilitySet resolve();
}
