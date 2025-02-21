package net.caffeinemc.mods.sodium.client.render.chunk.lists;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;

public class FallbackVisibleChunkCollector extends FrustumTaskCollector {
    private final TreeSectionCollector renderListCollector;

    public FallbackVisibleChunkCollector(Viewport viewport, float buildDistance, Long2ReferenceMap<RenderSection> sectionByPosition, RenderRegionManager regions, int frame) {
        super(viewport, buildDistance, sectionByPosition);
        this.renderListCollector = new TreeSectionCollector(frame, regions);
    }

    public RenderListProvider getRenderListProvider() {
        return this.renderListCollector;
    }

    @Override
    public void visit(int x, int y, int z) {
        super.visit(x, y, z);
        this.renderListCollector.visit(x, y, z);
    }
}
