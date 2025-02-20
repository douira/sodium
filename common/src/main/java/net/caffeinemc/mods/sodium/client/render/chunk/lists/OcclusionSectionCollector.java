package net.caffeinemc.mods.sodium.client.render.chunk.lists;

/**
 * The occlusion section collector is passed to the occlusion graph search culler to
 * collect the visible chunks.
 */
public class OcclusionSectionCollector extends SectionCollector {
    public OcclusionSectionCollector(int frame) {
        super(frame);
    }

    @Override
    public boolean orderIsSorted() {
        return false;
    }
}
