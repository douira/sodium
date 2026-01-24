package net.caffeinemc.mods.sodium.client.render.chunk.async;

import it.unimi.dsi.fastutil.longs.LongArrayList;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;

public abstract class CullTask<T> extends AsyncRenderTask<T> {
    public static final LongArrayList timings = new LongArrayList();

    protected final OcclusionCuller occlusionCuller;
    protected final boolean useOcclusionCulling;

    protected CullTask(Viewport viewport, float buildDistance, int frame, OcclusionCuller occlusionCuller, boolean useOcclusionCulling) {
        super(viewport, buildDistance, frame);
        this.occlusionCuller = occlusionCuller;
        this.useOcclusionCulling = useOcclusionCulling;
    }

    public abstract CullType getCullType();
}
