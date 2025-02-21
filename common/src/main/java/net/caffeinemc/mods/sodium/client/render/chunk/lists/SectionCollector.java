package net.caffeinemc.mods.sodium.client.render.chunk.lists;

import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;

public abstract class SectionCollector implements RenderListProvider {
    private final int frame;

    private final ObjectArrayList<ChunkRenderList> renderLists;

    private static int[] sortItems = new int[RenderRegion.REGION_SIZE];

    public SectionCollector(int frame) {
        this.frame = frame;

        this.renderLists = new ObjectArrayList<>();
    }

    public void visit(RenderRegion region, int sectionIndex) {
        // only process section (and associated render list) if it has content that needs rendering
        // TODO: avoid checking flags when traversing section tree because it already only has sections that need rendering
        if (region.sectionNeedsRender(sectionIndex)) {
            ChunkRenderList renderList = region.getRenderList();

            if (renderList.getLastVisibleFrame() != this.frame) {
                renderList.reset(this.frame, this.orderIsSorted());

                this.renderLists.add(renderList);
            }

            renderList.add(sectionIndex);
        }
    }

    @Override
    public ObjectArrayList<ChunkRenderList> getUnsortedRenderLists() {
        return this.renderLists;
    }

    @Override
    public void setCachedSortItems(int[] sortItems) {
        SectionCollector.sortItems = sortItems;
    }

    @Override
    public int[] getCachedSortItems() {
        return SectionCollector.sortItems;
    }
}
