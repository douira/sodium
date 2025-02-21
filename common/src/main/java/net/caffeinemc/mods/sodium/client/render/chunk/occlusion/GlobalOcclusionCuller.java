package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.world.level.Level;

public class GlobalOcclusionCuller extends OcclusionCuller {
    public GlobalOcclusionCuller(Long2ReferenceMap<RenderSection> sections, Level level) {
        super(sections, level);
    }

    @Override
    boolean isWithinFrustum(Viewport viewport, RenderSection section) {
        return true;
    }

    @Override
    boolean sectionIsUnvisited(RenderSection section) {
        return section.getGlobalCullLastVisibleFrame() != this.frame;
    }

    @Override
    void setSectionVisitedInitial(RenderSection section) {
        section.setGlobalCullLastVisibleFrame(this.frame);
        section.setGlobalCullIncomingDirections(GraphDirectionSet.NONE);
    }

    @Override
    void addSectionIncomingDirections(RenderSection section, int incoming) {
        section.addGlobalCullIncomingDirections(incoming);
    }

    @Override
    int getSectionIncomingDirections(RenderSection section) {
        return section.getGlobalCullIncomingDirections();
    }
}
