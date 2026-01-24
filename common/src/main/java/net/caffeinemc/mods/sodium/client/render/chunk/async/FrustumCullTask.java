package net.caffeinemc.mods.sodium.client.render.chunk.async;

import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.RayOcclusionSectionTree;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.SectionTree;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.world.level.Level;

public class FrustumCullTask extends CullTask<FrustumCullResult> {
    private final Level level;

    public FrustumCullTask(Viewport viewport, float buildDistance, int frame, OcclusionCuller occlusionCuller, boolean useOcclusionCulling, Level level) {
        super(viewport, buildDistance, frame, occlusionCuller, useOcclusionCulling);
        this.level = level;
    }

    @Override
    public FrustumCullResult runTask() {
        var tree = new RayOcclusionSectionTree(this.viewport, this.buildDistance, this.frame, CullType.FRUSTUM, this.level);

        var start = System.nanoTime();

        this.occlusionCuller.findVisible(tree, this.viewport, this.buildDistance, this.useOcclusionCulling, this);
        tree.prepareForTraversal();

        var end = System.nanoTime();
        var time = end - start;
        CullTask.timings.add(time);

        var frustumTaskLists = tree.getPendingTaskLists();

        return new FrustumCullResult() {
            @Override
            public SectionTree getTree() {
                return tree;
            }

            @Override
            public DeferredTaskList getFrustumTaskLists() {
                return frustumTaskLists;
            }
        };
    }

    @Override
    public AsyncTaskType getTaskType() {
        return AsyncTaskType.FRUSTUM_CULL;
    }

    @Override
    public CullType getCullType() {
        return CullType.FRUSTUM;
    }
}
