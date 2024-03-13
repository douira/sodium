package net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data;

import net.caffeinemc.mods.sodium.client.gl.util.VertexRange;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionMeshParts;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortType;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.TQuad;
import net.caffeinemc.mods.sodium.client.util.NativeBuffer;
import net.minecraft.core.SectionPos;

/**
 * With this sort type the section's translucent quads can be rendered in any
 * order. However, they do need to be rendered with some index buffer, so that
 * vertices are assembled into quads. Since the sort order doesn't matter, all
 * sections with this sort type can share the same data in the index buffer.
 * 
 * NOTE: A possible optimization would be to share the buffer for unordered
 * translucent sections on the CPU and on the GPU. It would essentially be the
 * same as SharedQuadIndexBuffer, but it has to be compatible with sections in
 * the same region using custom index buffers which makes the management
 * complicated. The shared buffer would be a member amongst the other non-shared
 * buffer segments and would need to be resized when a larger section wants to
 * use it.
 */
public class AnyOrderData extends SplitDirectionData {
    private Sorter sorterOnce;

    AnyOrderData(SectionPos sectionPos, VertexRange[] ranges, int quadCount) {
        super(sectionPos, ranges, quadCount);
    }

    @Override
    public SortType getSortType() {
        return SortType.NONE;
    }

    @Override
    public Sorter getSorter() {
        var sorter = this.sorterOnce;
        if (sorter == null) {
            throw new IllegalStateException("Sorter already used!");
        }
        this.sorterOnce = null;
        return sorter;
    }

    /**
     * Important: The vertex indexes must start at zero for each facing.
     */
    public static AnyOrderData fromMesh(BuiltSectionMeshParts translucentMesh,
            TQuad[] quads, SectionPos sectionPos) {
        var ranges = translucentMesh.getVertexRanges();
        var anyOrderData = new AnyOrderData(sectionPos, ranges, quads.length);
        var sorter = new StaticSorter(quads.length);
        anyOrderData.sorterOnce = sorter;
        var indexBuffer = sorter.getIntBuffer();

        for (var range : ranges) {
            if (range == null) {
                continue;
            }

            int count = TranslucentData.vertexCountToQuadCount(range.vertexCount());
            for (int i = 0; i < count; i++) {
                TranslucentData.writeQuadVertexIndexes(indexBuffer, i);
            }
        }

        return anyOrderData;
    }
}
