package net.caffeinemc.mods.sodium.client.render.chunk.lists;

import net.caffeinemc.mods.sodium.client.render.chunk.LocalSectionIndex;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;

/**
 * Collects sections from a tree traversal. It needs to turn coordinates into section objects because the section collector is not capable of handling raw section indexes yet.
 */
public class TreeSectionCollector extends SectionCollector implements CoordinateSectionVisitor {
    private final RenderRegionManager regions;

    public TreeSectionCollector(int frame, TaskQueueType importantRebuildQueueType, RenderRegionManager regions) {
        super(frame, importantRebuildQueueType);
        this.regions = regions;
    }

    @Override
    public void visit(int x, int y, int z) {
        var region = this.regions.getForChunk(x, y, z);

        if (region == null) {
            return;
        }

        int rX = x & (RenderRegion.REGION_WIDTH - 1);
        int rY = y & (RenderRegion.REGION_HEIGHT - 1);
        int rZ = z & (RenderRegion.REGION_LENGTH - 1);
        var sectionIndex = LocalSectionIndex.pack(rX, rY, rZ);

        this.visit(region, sectionIndex, x, y, z);
    }

    @Override
    public boolean orderIsSorted() {
        return true;
    }
}
