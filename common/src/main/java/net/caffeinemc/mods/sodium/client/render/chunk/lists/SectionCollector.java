package net.caffeinemc.mods.sodium.client.render.chunk.lists;

import it.unimi.dsi.fastutil.longs.LongArrayFIFOQueue;
import it.unimi.dsi.fastutil.objects.ObjectArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.ChunkUpdateType;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.minecraft.core.SectionPos;

import java.util.ArrayDeque;
import java.util.EnumMap;
import java.util.Map;

public abstract class SectionCollector implements RenderListProvider {
    private final int frame;
    private final ObjectArrayList<ChunkRenderList> renderLists;
    private final EnumMap<ChunkUpdateType, LongArrayFIFOQueue> sortedTaskLists;

    private static int[] sortItems = new int[RenderRegion.REGION_SIZE];

    public SectionCollector(int frame) {
        this.frame = frame;

        this.renderLists = new ObjectArrayList<>();
        this.sortedTaskLists = new EnumMap<>(ChunkUpdateType.class);

        for (var type : ChunkUpdateType.values()) {
            this.sortedTaskLists.put(type, new LongArrayFIFOQueue());
        }
    }

    public void visit(RenderRegion region, int sectionIndex, int x, int y, int z) {
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

        // always add to rebuild lists though, because it might just not be built yet
        ChunkUpdateType type = region.getSectionPendingUpdate(sectionIndex);
        if (type != null && region.getSectionTaskCancellationToken(sectionIndex) == null) {
            var queue = this.sortedTaskLists.get(type);

            if (queue.size() < type.getMaximumQueueSize()) {
                queue.enqueue(SectionPos.asLong(x, y, z));
            }
        }
    }

    @Override
    public ObjectArrayList<ChunkRenderList> getUnsortedRenderLists() {
        return this.renderLists;
    }

    @Override
    public Map<ChunkUpdateType, LongArrayFIFOQueue> getTaskLists() {
        return this.sortedTaskLists;
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
