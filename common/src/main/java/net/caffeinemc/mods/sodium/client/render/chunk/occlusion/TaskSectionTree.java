package net.caffeinemc.mods.sodium.client.render.chunk.occlusion;

import net.caffeinemc.mods.sodium.client.render.chunk.RenderSection;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.PendingTaskCollector;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.TraversableForest;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.minecraft.world.level.Level;

public class TaskSectionTree extends PendingTaskCollector implements OcclusionCuller.GraphOcclusionVisitor {
    private final TraversableForest tree;
    private boolean treeFinalized = false;

    public final float buildDistance;
    protected final int frame;

    public interface VisibleSectionVisitor {
        void visit(int x, int y, int z);
    }

    public TaskSectionTree(Viewport viewport, float buildDistance, boolean isFrustumTested, int frame, Level level) {
        super(viewport, buildDistance, isFrustumTested);

        this.buildDistance = buildDistance;
        this.frame = frame;

        this.tree = TraversableForest.createTraversableForest(this.baseOffsetX, this.baseOffsetY, this.baseOffsetZ, buildDistance, level);
    }

    public int getFrame() {
        return this.frame;
    }

    public boolean isValidFor(Viewport viewport, float searchDistance) {
        var cameraPos = viewport.getChunkCoord();
        return Math.abs((this.cameraX >> 4) - cameraPos.getX()) == 0 &&
               Math.abs((this.cameraY >> 4) - cameraPos.getY()) == 0 &&
               Math.abs((this.cameraZ >> 4) - cameraPos.getZ()) == 0 &&
                this.buildDistance >= searchDistance;
    }

    @Override
    protected void addPendingSection(RenderSection section, int type) {
        super.addPendingSection(section, type);

        this.markSectionTask(section);
    }

    public void markSectionTask(RenderSection section) {
        this.tree.add(section);
        this.treeFinalized = false;
    }

    public void markSectionTask(int x, int y, int z) {
        this.tree.add(x, y, z);
        this.treeFinalized = false;
    }

    public void traverse(VisibleSectionVisitor visitor, Viewport viewport, float distanceLimit) {
        if (!this.treeFinalized) {
            this.tree.prepareForTraversal();
        }
        this.tree.traverse(visitor, viewport, distanceLimit);
    }
}
