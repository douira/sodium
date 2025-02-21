package net.caffeinemc.mods.sodium.client.render.chunk.lists;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;

/**

/**
 * The occlusion section collector is passed to the occlusion graph search culler to
 * collect the visible chunks.
 */
public class OcclusionSectionCollector extends SectionCollector implements OcclusionCuller.GraphOcclusionVisitor {
    public OcclusionSectionCollector(int frame) {
        super(frame);
    }

    @Override
    public void visit(RenderSection section) {
        this.visit(section.getRegion(), section.getSectionIndex());
    }

    @Override
    public boolean orderIsSorted() {
        return false;
    }
}
