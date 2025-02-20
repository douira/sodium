package net.caffeinemc.mods.sodium.client.render.chunk.lists;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.TaskQueueType;

/**

/**
 * The occlusion section collector is passed to the occlusion graph search culler to
 * collect the visible chunks.
 */
public class OcclusionSectionCollector extends SectionCollector implements RenderSectionVisitor {
    public OcclusionSectionCollector(int frame, TaskQueueType importantRebuildQueueType) {
        super(frame, importantRebuildQueueType);
    }

    @Override
    public void visit(RenderSection section) {
        this.visit(section.getRegion(), section.getSectionIndex(), section.getChunkX(), section.getChunkY(), section.getChunkZ());
    }

    @Override
    public boolean orderIsSorted() {
        return false;
    }
}
