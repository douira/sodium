package me.jellysquid.mods.sodium.client.render.chunk.translucent_sorting.data;

import me.jellysquid.mods.sodium.client.gl.util.VertexRange;
import me.jellysquid.mods.sodium.client.model.quad.properties.ModelQuadFacing;
import me.jellysquid.mods.sodium.client.util.NativeBuffer;
import net.minecraft.core.SectionPos;

public abstract class MixedDirectionData extends PresentTranslucentData {
    private final VertexRange[] ranges = new VertexRange[ModelQuadFacing.COUNT];

    MixedDirectionData(SectionPos sectionPos, NativeBuffer buffer, VertexRange range) {
        super(sectionPos, buffer);
        this.ranges[ModelQuadFacing.UNASSIGNED.ordinal()] = range;
    }

    @Override
    public VertexRange[] getVertexRanges() {
        return ranges;
    }
}