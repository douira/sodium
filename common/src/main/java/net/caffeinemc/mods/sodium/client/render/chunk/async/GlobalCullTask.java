package net.caffeinemc.mods.sodium.client.render.chunk.async;

import it.unimi.dsi.fastutil.longs.Long2ReferenceMap;
import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.DeferredTaskList;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.FrustumTaskCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.TaskSectionTree;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.world.level.Level;

public class GlobalCullTask extends AsyncRenderTask<GlobalCullResult> {
    protected final OcclusionCuller occlusionCuller;
    protected final boolean useOcclusionCulling;
    private final Long2ReferenceMap<RenderSection> sectionByPosition;
    private final Level level;

    public GlobalCullTask(Viewport viewport, float buildDistance, int frame, OcclusionCuller occlusionCuller, boolean useOcclusionCulling, Long2ReferenceMap<RenderSection> sectionByPosition, Level level) {
        super(viewport, buildDistance, frame);
        this.occlusionCuller = occlusionCuller;
        this.useOcclusionCulling = useOcclusionCulling;
        this.sectionByPosition = sectionByPosition;
        this.level = level;
    }

    private static final LongArrayList timings = new LongArrayList();

    @Override
    public GlobalCullResult runTask() {
        var tree = new TaskSectionTree(this.viewport, this.buildDistance, false, this.frame, this.level);

        var start = System.nanoTime();

        this.occlusionCuller.findVisible(tree, this.viewport, this.buildDistance, this.useOcclusionCulling, this, this.frame);

        var end = System.nanoTime();
        var time = end - start;
        timings.add(time);
        if (timings.size() >= 500) {
            var average = timings.longStream().average().orElse(0);
            System.out.println("Global culling took " + (average) / 1000 + "µs over " + timings.size() + " samples");
            timings.clear();
        }

        var collector = new FrustumTaskCollector(this.viewport, this.buildDistance, this.sectionByPosition);
        tree.traverse(collector, this.viewport, this.buildDistance);

        var globalTaskLists = tree.getPendingTaskLists();
        var frustumTaskLists = collector.getPendingTaskLists();

        return new GlobalCullResult() {
            @Override
            public TaskSectionTree getTaskTree() {
                return tree;
            }

            @Override
            public DeferredTaskList getFrustumTaskLists() {
                return frustumTaskLists;
            }

            @Override
            public DeferredTaskList getGlobalTaskLists() {
                return globalTaskLists;
            }
        };
    }

    @Override
    public AsyncTaskType getTaskType() {
        return AsyncTaskType.GLOBAL_TASK_COLLECTION;
    }
}
