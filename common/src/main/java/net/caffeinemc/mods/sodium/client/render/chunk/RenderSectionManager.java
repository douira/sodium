package net.caffeinemc.mods.sodium.client.render.chunk;

import com.mojang.blaze3d.systems.RenderSystem;
import it.unimi.dsi.fastutil.longs.*;
import it.unimi.dsi.fastutil.objects.*;
import net.caffeinemc.mods.sodium.client.SodiumClientMod;
import net.caffeinemc.mods.sodium.client.gl.device.CommandList;
import net.caffeinemc.mods.sodium.client.gl.device.RenderDevice;
import net.caffeinemc.mods.sodium.client.render.chunk.async.*;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.BuilderTaskOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkBuildOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.ChunkSortOutput;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.JobDurationEstimator;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.MeshResultSize;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.estimation.MeshTaskSizeEstimator;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.executor.*;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderMeshingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderSortingTask;
import net.caffeinemc.mods.sodium.client.render.chunk.compile.tasks.ChunkBuilderTask;
import net.caffeinemc.mods.sodium.client.render.chunk.data.BuiltSectionInfo;
import net.caffeinemc.mods.sodium.client.render.chunk.lists.*;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.*;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegion;
import net.caffeinemc.mods.sodium.client.render.chunk.region.RenderRegionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.terrain.TerrainRenderPass;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.SortBehavior.PriorityMode;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.DynamicTopoData;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.NoData;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.data.TranslucentData;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.trigger.CameraMovement;
import net.caffeinemc.mods.sodium.client.render.chunk.translucent_sorting.trigger.SortTriggering;
import net.caffeinemc.mods.sodium.client.render.chunk.tree.RemovableMultiForest;
import net.caffeinemc.mods.sodium.client.render.chunk.vertex.format.ChunkMeshFormats;
import net.caffeinemc.mods.sodium.client.render.texture.SpriteUtil;
import net.caffeinemc.mods.sodium.client.render.util.RenderAsserts;
import net.caffeinemc.mods.sodium.client.render.viewport.CameraTransform;
import net.caffeinemc.mods.sodium.client.render.viewport.Viewport;
import net.caffeinemc.mods.sodium.client.util.MathUtil;
import net.caffeinemc.mods.sodium.client.util.task.CancellationToken;
import net.caffeinemc.mods.sodium.client.world.LevelSlice;
import net.caffeinemc.mods.sodium.client.world.cloned.ChunkRenderContext;
import net.caffeinemc.mods.sodium.client.world.cloned.ClonedChunkSectionCache;
import net.minecraft.client.Camera;
import net.minecraft.client.Minecraft;
import net.minecraft.client.multiplayer.ClientLevel;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.BlockPos;
import net.minecraft.core.SectionPos;
import net.minecraft.util.Mth;
import net.minecraft.world.level.chunk.ChunkAccess;
import net.minecraft.world.level.chunk.LevelChunkSection;
import org.apache.commons.lang3.ArrayUtils;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3dc;

import java.util.*;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;

public class RenderSectionManager {
    private static final float NEARBY_REBUILD_DISTANCE = Mth.square(16.0f);
    private static final float NEARBY_SORT_DISTANCE = Mth.square(25.0f);

    private final ChunkBuilder builder;

    private final RenderRegionManager regions;
    private final ClonedChunkSectionCache sectionCache;

    private final Long2ReferenceMap<RenderSection> sectionByPosition = new Long2ReferenceOpenHashMap<>();

    private final ConcurrentLinkedDeque<ChunkJobResult<? extends BuilderTaskOutput>> buildResults = new ConcurrentLinkedDeque<>();
    private final JobDurationEstimator jobDurationEstimator = new JobDurationEstimator();
    private final MeshTaskSizeEstimator meshTaskSizeEstimator = new MeshTaskSizeEstimator();
    private ChunkJobCollector lastBlockingCollector;
    private int thisFrameBlockingTasks;
    private int nextFrameBlockingTasks;
    private int deferredTasks;

    private final ChunkRenderer chunkRenderer;

    private final ClientLevel level;

    private final ReferenceSet<RenderSection> sectionsWithGlobalEntities = new ReferenceOpenHashSet<>();

    private final OcclusionCuller occlusionCuller;

    private final int renderDistance;

    private final SortTriggering sortTriggering;

    @NotNull
    private SortedRenderLists renderLists;

    private DeferredTaskList frustumTaskLists;
    private DeferredTaskList globalTaskLists;
    private final EnumMap<DeferMode, ReferenceLinkedOpenHashSet<RenderSection>> importantTasks;

    private int frame;
    private int lastGraphDirtyFrame;
    private long lastFrameDuration = -1;
    private long averageFrameDuration = -1;
    private long lastFrameAtTime = System.nanoTime();
    private static final float AVERAGE_FRAME_DURATION_FACTOR = 0.05f;

    private boolean needsGraphUpdate = true;
    private boolean needsRenderListUpdate = true;
    private boolean cameraChanged = false;
    private boolean needsFrustumTaskListUpdate = true;

    private @Nullable BlockPos cameraBlockPos;
    private @Nullable Vector3dc cameraPosition;

    private final ExecutorService asyncCullExecutor = Executors.newSingleThreadExecutor(RenderSectionManager::makeAsyncCullThread);
    private final ObjectArrayList<AsyncRenderTask<?>> pendingTasks = new ObjectArrayList<>();
    private SectionTree renderTree = null;
    private TaskSectionTree globalTaskTree = null;
    private final Map<CullType, SectionTree> cullResults = new EnumMap<>(CullType.class);
    private final RemovableMultiForest renderableSectionTree;

    private final AsyncCameraTimingControl cameraTimingControl = new AsyncCameraTimingControl();

    public RenderSectionManager(ClientLevel level, int renderDistance, CommandList commandList) {
        this.chunkRenderer = new DefaultChunkRenderer(RenderDevice.INSTANCE, ChunkMeshFormats.COMPACT);

        this.level = level;
        this.builder = new ChunkBuilder(level, ChunkMeshFormats.COMPACT);

        this.renderDistance = renderDistance;

        this.sortTriggering = new SortTriggering();

        this.regions = new RenderRegionManager(commandList);
        this.sectionCache = new ClonedChunkSectionCache(this.level);

        this.renderLists = SortedRenderLists.empty();
        this.occlusionCuller = new OcclusionCuller(Long2ReferenceMaps.unmodifiable(this.sectionByPosition), this.level);

        this.renderableSectionTree = new RemovableMultiForest(renderDistance);

        this.importantTasks = new EnumMap<>(DeferMode.class);
        this.importantTasks.put(DeferMode.ZERO_FRAMES, new ReferenceLinkedOpenHashSet<>());
        this.importantTasks.put(DeferMode.ONE_FRAME, new ReferenceLinkedOpenHashSet<>());
    }

    public void updateCameraState(Vector3dc cameraPosition, Camera camera) {
        var now = System.nanoTime();
        this.lastFrameDuration = now - this.lastFrameAtTime;
        this.lastFrameAtTime = now;
        if (this.averageFrameDuration == -1) {
            this.averageFrameDuration = this.lastFrameDuration;
        } else {
            this.averageFrameDuration = MathUtil.exponentialMovingAverage(this.averageFrameDuration, this.lastFrameDuration, AVERAGE_FRAME_DURATION_FACTOR);
        }
        this.averageFrameDuration = Mth.clamp(this.averageFrameDuration, 1_000_100, 100_000_000);

        this.frame += 1;
        this.needsRenderListUpdate |= this.cameraChanged;
        this.needsFrustumTaskListUpdate |= this.needsRenderListUpdate;

        this.cameraBlockPos = camera.getBlockPosition();
        this.cameraPosition = cameraPosition;
    }

    public void updateRenderLists(Camera camera, Viewport viewport, boolean spectator, boolean updateImmediately) {
        // do sync bfs based on update immediately (flawless frames) or if the camera moved too much
        var shouldRenderSync = this.cameraTimingControl.getShouldRenderSync(camera);
        if ((updateImmediately || shouldRenderSync) && (this.needsGraphUpdate || this.needsRenderListUpdate)) {
            renderSync(camera, viewport, spectator);
            return;
        }

        if (this.needsGraphUpdate) {
            this.lastGraphDirtyFrame = this.frame;
        }

        // discard unusable present and pending frustum-tested trees
        if (this.cameraChanged) {
            this.cullResults.remove(CullType.FRUSTUM);

            this.pendingTasks.removeIf(task -> {
                if (task instanceof CullTask<?> cullTask && cullTask.getCullType() == CullType.FRUSTUM) {
                    cullTask.setCancelled();
                    return true;
                }
                return false;
            });
        }

        // remove all tasks that aren't in progress yet
        this.pendingTasks.removeIf(AsyncRenderTask::cancelIfNotStarted);

        this.unpackTaskResults(null);

        this.scheduleAsyncWork(camera, viewport, spectator);

        if (this.needsFrustumTaskListUpdate) {
            this.updateFrustumTaskList(viewport);
        }
        if (this.needsRenderListUpdate) {
            processRenderListUpdate(viewport);
        }

        this.needsRenderListUpdate = false;
        this.needsFrustumTaskListUpdate = false;
        this.needsGraphUpdate = false;
        this.cameraChanged = false;
    }

    private void renderSync(Camera camera, Viewport viewport, boolean spectator) {
        final var searchDistance = this.getSearchDistance();
        final var useOcclusionCulling = this.shouldUseOcclusionCulling(camera, spectator);

        // cancel running tasks to prevent two bfs running at the same time, which will cause race conditions
        for (var task : this.pendingTasks) {
            task.setCancelled();
            task.getResult();
        }
        this.pendingTasks.clear();

        var tree = new VisibleChunkCollectorSync(viewport, searchDistance, this.frame, CullType.FRUSTUM, this.level);
        this.occlusionCuller.findVisible(tree, viewport, searchDistance, useOcclusionCulling, CancellationToken.NEVER_CANCELLED);
        tree.prepareForTraversal();

        this.frustumTaskLists = tree.getPendingTaskLists();
        this.globalTaskLists = null;
        this.cullResults.put(CullType.FRUSTUM, tree);
        this.renderTree = tree;

        this.renderLists = tree.createRenderLists(viewport);

        // remove the other trees, they're very wrong by now
        this.cullResults.remove(CullType.WIDE);
        this.cullResults.remove(CullType.REGULAR);

        this.needsRenderListUpdate = false;
        this.needsGraphUpdate = false;
        this.cameraChanged = false;
    }

    private SectionTree unpackTaskResults(Viewport waitingViewport) {
        SectionTree latestTree = null;
        CullType latestTreeCullType = null;

        var it = this.pendingTasks.iterator();
        while (it.hasNext()) {
            var task = it.next();
            if (waitingViewport == null && !task.isDone()) {
                continue;
            }
            it.remove();

            // unpack the task and its result based on its type
            switch (task) {
                case FrustumCullTask frustumCullTask -> {
                    var result = frustumCullTask.getResult();
                    this.frustumTaskLists = result.getFrustumTaskLists();

                    // ensure no useless frustum tree is accepted
                    if (!this.cameraChanged) {
                        var tree = result.getTree();
                        this.cullResults.put(CullType.FRUSTUM, tree);
                        latestTree = tree;
                        latestTreeCullType = CullType.FRUSTUM;

                        this.needsRenderListUpdate = true;
                    }
                }
                case GlobalCullTask globalCullTask -> {
                    var result = globalCullTask.getResult();
                    var tree = result.getTaskTree();
                    this.globalTaskLists = result.getGlobalTaskLists();
                    this.frustumTaskLists = result.getFrustumTaskLists();
                    this.globalTaskTree = tree;
                    var cullType = globalCullTask.getCullType();
                    this.cullResults.put(cullType, tree);
                    latestTree = tree;
                    latestTreeCullType = cullType;

                    this.needsRenderListUpdate = true;
                }
                case FrustumTaskCollectionTask collectionTask ->
                        this.frustumTaskLists = collectionTask.getResult().getFrustumTaskLists();
                default -> {
                }
            }
        }

        if (waitingViewport != null && latestTree != null) {
            var searchDistance = this.getSearchDistanceForCullType(latestTreeCullType);
            if (latestTree.isValidFor(waitingViewport, searchDistance)) {
                return latestTree;
            }
        }
        return null;
    }

    private static Thread makeAsyncCullThread(Runnable runnable) {
        Thread thread = new Thread(runnable);
        thread.setName("Sodium Async Cull Thread");
        return thread;
    }

    private void scheduleAsyncWork(Camera camera, Viewport viewport, boolean spectator) {
        // if the origin section doesn't exist, cull tasks won't produce any useful results
        if (!this.occlusionCuller.graphOriginPresent(viewport)) {
            return;
        }

        // submit tasks of types that are applicable and not yet running
        AsyncRenderTask<?> currentRunningTask = null;
        if (!this.pendingTasks.isEmpty()) {
            currentRunningTask = this.pendingTasks.getFirst();
        }

        // pick a scheduling order based on if there's been a graph update and if the render list is dirty
        var scheduleOrder = getScheduleOrder();

        for (var type : scheduleOrder) {
            var tree = this.cullResults.get(type);

            // don't schedule frustum tasks if the camera just changed to prevent throwing them away constantly
            // since they're going to be invalid by the time they're completed in the next frame
            if (type == CullType.FRUSTUM && this.cameraChanged) {
                continue;
            }

            // schedule a task of this type if there's no valid and current result for it yet
            var searchDistance = this.getSearchDistanceForCullType(type);
            if ((tree == null || tree.getFrame() < this.lastGraphDirtyFrame || !tree.isValidFor(viewport, searchDistance)) &&
                    // and if there's no currently running task that will produce a valid and current result
                    (currentRunningTask == null ||
                            currentRunningTask instanceof CullTask<?> cullTask && cullTask.getCullType() != type ||
                            currentRunningTask.getFrame() < this.lastGraphDirtyFrame)) {
                var useOcclusionCulling = this.shouldUseOcclusionCulling(camera, spectator);

                // use the last dirty frame as the frame timestamp to avoid wrongly marking task results as more recent if they're simply scheduled later but did work on the same state of the graph if there's been no graph invalidation since
                var task = switch (type) {
                    case WIDE, REGULAR ->
                            new GlobalCullTask(viewport, searchDistance, this.lastGraphDirtyFrame, this.occlusionCuller, useOcclusionCulling, this.sectionByPosition, type, this.level);
                    case FRUSTUM ->
                        // note that there is some danger with only giving the frustum tasks the last graph dirty frame and not the real current frame, but these are mitigated by deleting the frustum result when the camera changes.
                            new FrustumCullTask(viewport, searchDistance, this.lastGraphDirtyFrame, this.occlusionCuller, useOcclusionCulling, this.level);
                };
                task.submitTo(this.asyncCullExecutor);
                this.pendingTasks.add(task);
            }
        }
    }

    private static final CullType[] WIDE_TO_NARROW = { CullType.WIDE, CullType.REGULAR, CullType.FRUSTUM };
    private static final CullType[] NARROW_TO_WIDE = { CullType.FRUSTUM, CullType.REGULAR, CullType.WIDE };
    private static final CullType[] COMPROMISE = { CullType.REGULAR, CullType.FRUSTUM, CullType.WIDE };

    private CullType[] getScheduleOrder() {
        // if the camera is stationary, do the FRUSTUM update first to prevent the rendered section count from oscillating
        if (!this.cameraChanged) {
            return NARROW_TO_WIDE;
        }

        // if only the render list is dirty but there's no graph update, do REGULAR first and potentially do FRUSTUM opportunistically
        if (!this.needsGraphUpdate) {
            return COMPROMISE;
        }

        // if both are dirty, the camera is moving and loading new sections, do WIDE first to ensure there's any correct result
        return WIDE_TO_NARROW;
    }

    private static final LongArrayList timings = new LongArrayList();

    private void updateFrustumTaskList(Viewport viewport) {
        // schedule generating a frustum task list if there's no frustum tree task running
        if (this.globalTaskTree != null) {
            var frustumTaskListPending = false;
            for (var task : this.pendingTasks) {
                if (task instanceof CullTask<?> cullTask && cullTask.getCullType() == CullType.FRUSTUM ||
                        task instanceof FrustumTaskCollectionTask) {
                    frustumTaskListPending = true;
                    break;
                }
            }
            if (!frustumTaskListPending) {
                var searchDistance = this.getSearchDistance();
                var task = new FrustumTaskCollectionTask(viewport, searchDistance, this.frame, this.sectionByPosition, this.globalTaskTree);
                task.submitTo(this.asyncCullExecutor);
                this.pendingTasks.add(task);
            }
        }
    }

    private void processRenderListUpdate(Viewport viewport) {
        // pick the narrowest valid tree. This tree is either up-to-date or the origin is out of the graph as otherwise sync bfs would have been triggered (in graph but moving rapidly)
        SectionTree bestTree = null;
        for (var type : NARROW_TO_WIDE) {
            var tree = this.cullResults.get(type);
            if (tree == null) {
                continue;
            }

            // pick the most recent and most valid tree
            float searchDistance = this.getSearchDistanceForCullType(type);
            if (!tree.isValidFor(viewport, searchDistance)) {
                continue;
            }
            if (bestTree == null || tree.getFrame() > bestTree.getFrame()) {
                bestTree = tree;
            }
        }

        // wait for pending tasks to maybe supply a valid tree if there's no current tree (first frames after initial load/reload)
        if (bestTree == null) {
            bestTree = this.unpackTaskResults(viewport);
        }

        // use out-of-graph fallback there's still no result because nothing was scheduled (missing origin section, empty world)
        if (bestTree == null) {
            var searchDistance = this.getSearchDistance();
            var visitor = new FallbackVisibleChunkCollector(viewport, searchDistance, this.sectionByPosition, this.regions, this.frame);

            this.renderableSectionTree.prepareForTraversal();
            this.renderableSectionTree.traverse(visitor, viewport, searchDistance);

            this.renderLists = visitor.createRenderLists(viewport);
            this.frustumTaskLists = visitor.getPendingTaskLists();
            this.globalTaskLists = null;
            this.renderTree = null;
        } else {
            var start = System.nanoTime();

            var visibleCollector = new VisibleChunkCollectorAsync(this.regions, this.frame);
            bestTree.traverse(visibleCollector, viewport, this.getSearchDistance());
            this.renderLists = visibleCollector.createRenderLists(viewport);

            var end = System.nanoTime();
            var time = end - start;
            timings.add(time);
            if (timings.size() >= 500) {
                var average = timings.longStream().average().orElse(0);
                System.out.println("Render list generation took " + (average) / 1000 + "µs over " + timings.size() + " samples");
                timings.clear();
            }

            this.renderTree = bestTree;
        }
    }

    public void markGraphDirty() {
        this.needsGraphUpdate = true;
    }

    public void notifyChangedCamera() {
        this.cameraChanged = true;
    }

    public boolean needsUpdate() {
        return this.needsGraphUpdate;
    }

    private float getSearchDistanceForCullType(CullType cullType) {
        if (cullType.isFogCulled) {
            return this.getSearchDistance();
        } else {
            return this.getRenderDistance();
        }
    }

    private float getSearchDistance() {
        float distance;

        if (SodiumClientMod.options().performance.useFogOcclusion) {
            distance = this.getEffectiveRenderDistance();
        } else {
            distance = this.getRenderDistance();
        }

        return distance;
    }

    private boolean shouldUseOcclusionCulling(Camera camera, boolean spectator) {
        final boolean useOcclusionCulling;
        BlockPos origin = camera.getBlockPosition();

        if (spectator && this.level.getBlockState(origin)
                .isSolidRender()) {
            useOcclusionCulling = false;
        } else {
            useOcclusionCulling = Minecraft.getInstance().smartCull;
        }
        return useOcclusionCulling;
    }

    public void beforeSectionUpdates() {
        this.renderableSectionTree.ensureCapacity(this.getRenderDistance());
    }

    public void onSectionAdded(int x, int y, int z) {
        long key = SectionPos.asLong(x, y, z);

        if (this.sectionByPosition.containsKey(key)) {
            return;
        }

        RenderRegion region = this.regions.createForChunk(x, y, z);

        RenderSection renderSection = new RenderSection(region, x, y, z);
        region.addSection(renderSection);

        this.sectionByPosition.put(key, renderSection);

        ChunkAccess chunk = this.level.getChunk(x, z);
        LevelChunkSection section = chunk.getSections()[this.level.getSectionIndexFromSectionY(y)];

        if (section.hasOnlyAir()) {
            this.updateSectionInfo(renderSection, BuiltSectionInfo.EMPTY);
        } else {
            this.renderableSectionTree.add(renderSection);
            renderSection.setPendingUpdate(ChunkUpdateType.INITIAL_BUILD, this.lastFrameAtTime);
        }

        this.connectNeighborNodes(renderSection);

        // force update to schedule build task
        this.markGraphDirty();
    }

    public void onSectionRemoved(int x, int y, int z) {
        long sectionPos = SectionPos.asLong(x, y, z);
        RenderSection section = this.sectionByPosition.remove(sectionPos);

        if (section == null) {
            return;
        }

        this.renderableSectionTree.remove(x, y, z);

        if (section.getTranslucentData() != null) {
            this.sortTriggering.removeSection(section.getTranslucentData(), sectionPos);
        }

        RenderRegion region = section.getRegion();

        if (region != null) {
            region.removeSection(section);
        }

        this.disconnectNeighborNodes(section);
        this.updateSectionInfo(section, null);

        section.delete();

        // force update to remove section from render lists
        this.markGraphDirty();
    }

    public void renderLayer(ChunkRenderMatrices matrices, TerrainRenderPass pass, double x, double y, double z) {
        RenderDevice device = RenderDevice.INSTANCE;
        CommandList commandList = device.createCommandList();

        this.chunkRenderer.render(matrices, commandList, this.renderLists, pass, new CameraTransform(x, y, z));

        commandList.flush();
    }

    public void tickVisibleRenders() {
        Iterator<ChunkRenderList> it = this.renderLists.iterator();

        while (it.hasNext()) {
            ChunkRenderList renderList = it.next();

            var region = renderList.getRegion();
            var iterator = renderList.sectionsWithSpritesIterator();

            if (iterator == null) {
                continue;
            }

            while (iterator.hasNext()) {
                var sprites = region.getAnimatedSprites(iterator.nextByteAsInt());

                if (sprites == null) {
                    continue;
                }

                for (TextureAtlasSprite sprite : sprites) {
                    SpriteUtil.markSpriteActive(sprite);
                }
            }
        }
    }

    // renderTree is not necessarily frustum-filtered but that is ok since the caller makes sure to eventually also perform a frustum test on the box being tested (see EntityRendererMixin)
    public boolean isBoxVisible(double x1, double y1, double z1, double x2, double y2, double z2) {
        return this.renderTree == null || this.renderTree.isBoxVisible(x1, y1, z1, x2, y2, z2);
    }

    public void uploadChunks() {
        var results = this.collectChunkBuildResults();

        if (results.isEmpty()) {
            return;
        }

        // only mark as needing a graph update if the uploads could have changed the graph
        // (sort results never change the graph)
        // generally there's no sort results without a camera movement, which would also trigger
        // a graph update, but it can sometimes happen because of async task execution
        if (this.processChunkBuildResults(results)) {
            this.markGraphDirty();
        }

        for (var result : results) {
            result.destroy();
        }
    }

    private boolean processChunkBuildResults(ArrayList<BuilderTaskOutput> results) {
        var filtered = filterChunkBuildResults(results);

        this.regions.uploadResults(RenderDevice.INSTANCE.createCommandList(), filtered);

        boolean touchedSectionInfo = false;
        for (var result : filtered) {
            TranslucentData oldData = result.render.getTranslucentData();
            if (result instanceof ChunkBuildOutput chunkBuildOutput) {
                touchedSectionInfo |= this.updateSectionInfo(result.render, chunkBuildOutput.info);

                var resultSize = chunkBuildOutput.getResultSize();
                result.render.setLastMeshResultSize(resultSize);
                this.meshTaskSizeEstimator.addBatchEntry(MeshResultSize.forSection(result.render, resultSize));

                if (chunkBuildOutput.translucentData != null) {
                    this.sortTriggering.integrateTranslucentData(oldData, chunkBuildOutput.translucentData, this.cameraPosition, this::scheduleSort);

                    // a rebuild always generates new translucent data which means applyTriggerChanges isn't necessary
                    result.render.setTranslucentData(chunkBuildOutput.translucentData);
                }
            } else if (result instanceof ChunkSortOutput sortOutput
                    && sortOutput.getDynamicSorter() != null
                    && result.render.getTranslucentData() instanceof DynamicTopoData data) {
                this.sortTriggering.applyTriggerChanges(data, sortOutput.getDynamicSorter(), result.render.getPosition(), this.cameraPosition);
            }

            var job = result.render.getTaskCancellationToken();

            // clear the cancellation token (thereby marking the section as not having an
            // active task) if this job is the most recent submitted job for this section
            if (job != null && result.submitTime >= result.render.getLastSubmittedFrame()) {
                result.render.setTaskCancellationToken(null);
            }

            result.render.setLastUploadFrame(result.submitTime);
        }

        this.meshTaskSizeEstimator.flushNewData();

        return touchedSectionInfo;
    }

    private boolean updateSectionInfo(RenderSection render, BuiltSectionInfo info) {
        if (info == null || (info.flags & RenderSectionFlags.MASK_NEEDS_RENDER) == 0) {
            this.renderableSectionTree.remove(render);
        } else {
            this.renderableSectionTree.add(render);
        }

        var infoChanged = render.setInfo(info);

        if (info == null || ArrayUtils.isEmpty(info.globalBlockEntities)) {
            return this.sectionsWithGlobalEntities.remove(render) || infoChanged;
        } else {
            return this.sectionsWithGlobalEntities.add(render) || infoChanged;
        }
    }

    private List<BuilderTaskOutput> filterChunkBuildResults(ArrayList<BuilderTaskOutput> outputs) {
        var map = new Reference2ReferenceLinkedOpenHashMap<RenderSection, BuilderTaskOutput>();

        for (var output : outputs) {
            // throw out outdated or duplicate outputs
            if (output.render.isDisposed() || output.render.getLastUploadFrame() > output.submitTime) {
                continue;
            }

            var render = output.render;
            var previous = map.get(render);

            if (previous == null || previous.submitTime < output.submitTime) {
                map.put(render, output);
            }
        }

        return new ArrayList<>(map.values());
    }

    private ArrayList<BuilderTaskOutput> collectChunkBuildResults() {
        ArrayList<BuilderTaskOutput> results = new ArrayList<>();

        ChunkJobResult<? extends BuilderTaskOutput> result;

        while ((result = this.buildResults.poll()) != null) {
            results.add(result.unwrap());
            var jobEffort = result.getJobEffort();
            if (jobEffort != null) {
                this.jobDurationEstimator.addBatchEntry(jobEffort);
            }
        }

        this.jobDurationEstimator.flushNewData();

        return results;
    }

    public void cleanupAndFlip() {
        this.sectionCache.cleanup();
        this.regions.update();
    }

    public void updateChunks(Viewport viewport, boolean updateImmediately) {
        this.thisFrameBlockingTasks = 0;
        this.nextFrameBlockingTasks = 0;
        this.deferredTasks = 0;

        var thisFrameBlockingCollector = this.lastBlockingCollector;
        this.lastBlockingCollector = null;
        if (thisFrameBlockingCollector == null) {
            thisFrameBlockingCollector = new ChunkJobCollector(this.buildResults::add);
        }

        if (updateImmediately) {
            // for a perfect frame where everything is finished use the last frame's blocking collector
            // and add all tasks to it so that they're waited on
            this.submitSectionTasks(thisFrameBlockingCollector, thisFrameBlockingCollector, thisFrameBlockingCollector, Long.MAX_VALUE, viewport);

            this.thisFrameBlockingTasks = thisFrameBlockingCollector.getSubmittedTaskCount();
            thisFrameBlockingCollector.awaitCompletion(this.builder);
        } else {
            var remainingDuration = this.builder.getTotalRemainingDuration(this.averageFrameDuration);
            var remainingUploadSize = this.regions.getStagingBuffer().getUploadSizeLimit(this.averageFrameDuration);

            var nextFrameBlockingCollector = new ChunkJobCollector(this.buildResults::add);
            var deferredCollector = new ChunkJobCollector(remainingDuration, this.buildResults::add);

            // if zero frame delay is allowed, submit important sorts with the current frame blocking collector.
            // otherwise submit with the collector that the next frame is blocking on.
            if (SodiumClientMod.options().performance.getSortBehavior().getDeferMode() == DeferMode.ZERO_FRAMES) {
                this.submitSectionTasks(thisFrameBlockingCollector, nextFrameBlockingCollector, deferredCollector, remainingUploadSize, viewport);
            } else {
                this.submitSectionTasks(nextFrameBlockingCollector, nextFrameBlockingCollector, deferredCollector, remainingUploadSize, viewport);
            }

            this.thisFrameBlockingTasks = thisFrameBlockingCollector.getSubmittedTaskCount();
            this.nextFrameBlockingTasks = nextFrameBlockingCollector.getSubmittedTaskCount();
            this.deferredTasks = deferredCollector.getSubmittedTaskCount();

            // wait on this frame's blocking collector which contains the important tasks from this frame
            // and semi-important tasks from the last frame
            thisFrameBlockingCollector.awaitCompletion(this.builder);

            // store the semi-important collector to wait on it in the next frame
            this.lastBlockingCollector = nextFrameBlockingCollector;
        }
    }

    private void submitSectionTasks(
            ChunkJobCollector importantCollector, ChunkJobCollector semiImportantCollector, ChunkJobCollector deferredCollector, long remainingUploadSize, Viewport viewport) {
        remainingUploadSize = submitImportantSectionTasks(importantCollector, remainingUploadSize, DeferMode.ZERO_FRAMES, viewport);
        remainingUploadSize = submitImportantSectionTasks(semiImportantCollector, remainingUploadSize, DeferMode.ONE_FRAME, viewport);
        submitDeferredSectionTasks(deferredCollector, remainingUploadSize);
    }

    private static final LongPriorityQueue EMPTY_TASK_QUEUE = new LongPriorityQueue() {
        @Override
        public void enqueue(long x) {
            throw new UnsupportedOperationException();
        }

        @Override
        public long dequeueLong() {
            throw new UnsupportedOperationException();
        }

        @Override
        public long firstLong() {
            throw new UnsupportedOperationException();
        }

        @Override
        public LongComparator comparator() {
            throw new UnsupportedOperationException();
        }

        @Override
        public int size() {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean isEmpty() {
            return true;
        }

        @Override
        public void clear() {
            throw new UnsupportedOperationException();
        }
    };

    private void submitDeferredSectionTasks(ChunkJobCollector collector, long remainingUploadSize) {
        LongPriorityQueue frustumQueue = this.frustumTaskLists;
        LongPriorityQueue globalQueue = this.globalTaskLists;
        float frustumPriorityBias = 0;
        float globalPriorityBias = 0;

        if (frustumQueue != null) {
            frustumPriorityBias = this.frustumTaskLists.getCollectorPriorityBias(this.lastFrameAtTime);
        } else {
            frustumQueue = EMPTY_TASK_QUEUE;
        }

        if (globalQueue != null) {
            globalPriorityBias = this.globalTaskLists.getCollectorPriorityBias(this.lastFrameAtTime);
        } else {
            globalQueue = EMPTY_TASK_QUEUE;
        }

        float frustumPriority = Float.POSITIVE_INFINITY;
        float globalPriority = Float.POSITIVE_INFINITY;
        long frustumItem = 0;
        long globalItem = 0;

        while ((!frustumQueue.isEmpty() || !globalQueue.isEmpty()) && collector.hasBudgetRemaining() && remainingUploadSize > 0) {
            // get the first item from the non-empty queues and see which one has higher priority.
            // if the priority is not infinity, then the item priority was fetched the last iteration and doesn't need updating.
            if (!frustumQueue.isEmpty() && Float.isInfinite(frustumPriority)) {
                frustumItem = frustumQueue.firstLong();
                frustumPriority = PendingTaskCollector.decodePriority(frustumItem) + frustumPriorityBias;
            }
            if (!globalQueue.isEmpty() && Float.isInfinite(globalPriority)) {
                globalItem = globalQueue.firstLong();
                globalPriority = PendingTaskCollector.decodePriority(globalItem) + globalPriorityBias;
            }

            // pick the task with the higher priority, decode the section, and schedule its task if it exists
            RenderSection section;
            if (frustumPriority < globalPriority) {
                frustumQueue.dequeueLong();
                frustumPriority = Float.POSITIVE_INFINITY;

                section = this.frustumTaskLists.decodeAndFetchSection(this.sectionByPosition, frustumItem);
            } else {
                globalQueue.dequeueLong();
                globalPriority = Float.POSITIVE_INFINITY;

                section = this.globalTaskLists.decodeAndFetchSection(this.sectionByPosition, globalItem);
            }

            if (section != null) {
                remainingUploadSize -= submitSectionTask(collector, section);
            }
        }
    }

    private long submitImportantSectionTasks(ChunkJobCollector collector, long remainingUploadSize, DeferMode deferMode, Viewport viewport) {
        var limitOnSize = deferMode != DeferMode.ZERO_FRAMES;
        var it =  this.importantTasks.get(deferMode).iterator();

        while (it.hasNext() && collector.hasBudgetRemaining() && (!limitOnSize || remainingUploadSize > 0)) {
            var section = it.next();
            var pendingUpdate = section.getPendingUpdate();
            if (pendingUpdate != null && pendingUpdate.getDeferMode() == deferMode) {
                if (this.renderTree == null || this.renderTree.isSectionVisible(viewport, section)) {
                    remainingUploadSize -= submitSectionTask(collector, section, pendingUpdate);
                } else {
                    // don't remove if simply not visible but still needs to be run
                    continue;
                }
            }
            it.remove();
        }

        return remainingUploadSize;
    }

    private long submitSectionTask(ChunkJobCollector collector, @NotNull RenderSection section) {
        // don't schedule tasks for sections that don't need it anymore,
        // since the pending update it cleared when a task is started, this includes
        // sections for which there's a currently running task.
        var type = section.getPendingUpdate();
        if (type == null) {
            return 0;
        }

        return submitSectionTask(collector, section, type);
    }

    private long submitSectionTask(ChunkJobCollector collector, @NotNull RenderSection section, ChunkUpdateType type) {
        if (section.isDisposed()) {
            return 0;
        }

        ChunkBuilderTask<? extends BuilderTaskOutput> task;
        if (type == ChunkUpdateType.SORT || type == ChunkUpdateType.IMPORTANT_SORT) {
            task = this.createSortTask(section, this.frame);

            if (task == null) {
                // when a sort task is null it means the render section has no dynamic data and
                // doesn't need to be sorted. Nothing needs to be done.
                return 0;
            }
        } else {
            task = this.createRebuildTask(section, this.frame);

            if (task == null) {
                // if the section is empty or doesn't exist submit this null-task to set the
                // built flag on the render section.
                // It's important to use a NoData instead of null translucency data here in
                // order for it to clear the old data from the translucency sorting system.
                // This doesn't apply to sorting tasks as that would result in the section being
                // marked as empty just because it was scheduled to be sorted and its dynamic
                // data has since been removed. In that case simply nothing is done as the
                // rebuild that must have happened in the meantime includes new non-dynamic
                // index data.
                var result = ChunkJobResult.successfully(new ChunkBuildOutput(
                        section, this.frame, NoData.forEmptySection(section.getPosition()),
                        BuiltSectionInfo.EMPTY, Collections.emptyMap()));
                this.buildResults.add(result);

                section.setTaskCancellationToken(null);
            }
        }

        var estimatedTaskSize = 0L;
        if (task != null) {
            var job = this.builder.scheduleTask(task, type.isImportant(), collector::onJobFinished);
            collector.addSubmittedJob(job);
            estimatedTaskSize = job.getEstimatedSize();

            section.setTaskCancellationToken(job);
        }

        section.setLastSubmittedFrame(this.frame);
        section.clearPendingUpdate();
        return estimatedTaskSize;
    }

    public @Nullable ChunkBuilderMeshingTask createRebuildTask(RenderSection render, int frame) {
        ChunkRenderContext context = LevelSlice.prepare(this.level, render.getPosition(), this.sectionCache);

        if (context == null) {
            return null;
        }

        var task = new ChunkBuilderMeshingTask(render, frame, this.cameraPosition, context);
        task.calculateEstimations(this.jobDurationEstimator, this.meshTaskSizeEstimator);
        return task;
    }

    public ChunkBuilderSortingTask createSortTask(RenderSection render, int frame) {
        var task = ChunkBuilderSortingTask.createTask(render, frame, this.cameraPosition);
        if (task != null) {
            task.calculateEstimations(this.jobDurationEstimator, this.meshTaskSizeEstimator);
        }
        return task;
    }

    public void processGFNIMovement(CameraMovement movement) {
        this.sortTriggering.triggerSections(this::scheduleSort, movement);
    }

    public ChunkBuilder getBuilder() {
        return this.builder;
    }

    public void destroy() {
        this.builder.shutdown(); // stop all the workers, and cancel any tasks

        this.asyncCullExecutor.shutdownNow();

        for (var result : this.collectChunkBuildResults()) {
            result.destroy(); // delete resources for any pending tasks (including those that were cancelled)
        }

        for (var section : this.sectionByPosition.values()) {
            section.delete();
        }

        this.sectionsWithGlobalEntities.clear();

        this.renderLists = SortedRenderLists.empty();

        try (CommandList commandList = RenderDevice.INSTANCE.createCommandList()) {
            this.regions.delete(commandList);
            this.chunkRenderer.delete(commandList);
        }
    }

    public int getTotalSections() {
        return this.sectionByPosition.size();
    }

    public int getVisibleChunkCount() {
        var sections = 0;
        var iterator = this.renderLists.iterator();

        while (iterator.hasNext()) {
            var renderList = iterator.next();
            sections += renderList.getSectionsWithGeometryCount();
        }

        return sections;
    }

    private ChunkUpdateType upgradePendingUpdate(RenderSection section, ChunkUpdateType type) {
        var current = section.getPendingUpdate();
        type = ChunkUpdateType.getPromotionUpdateType(current, type);

        // when the pending task type changes and it's important, add it to the list of important tasks
        if (type != null && current != type) {
            var deferMode = type.getDeferMode();
            if (deferMode != DeferMode.ALWAYS) {
                this.importantTasks.get(deferMode).add(section);
            }
        }

        section.setPendingUpdate(type, this.lastFrameAtTime);

        // if the section received a new task, mark in the task tree so an update can happen before a global cull task runs
        if (this.globalTaskTree != null && type != null && current == null) {
            this.globalTaskTree.markSectionTask(section);
            this.needsFrustumTaskListUpdate = true;
        }

        return type;
    }

    public void scheduleSort(long sectionPos, boolean isDirectTrigger) {
        RenderSection section = this.sectionByPosition.get(sectionPos);

        if (section != null) {
            var pendingUpdate = ChunkUpdateType.SORT;
            var priorityMode = SodiumClientMod.options().performance.getSortBehavior().getPriorityMode();
            if (priorityMode == PriorityMode.ALL
                    || priorityMode == PriorityMode.NEARBY && this.shouldPrioritizeTask(section, NEARBY_SORT_DISTANCE)) {
                pendingUpdate = ChunkUpdateType.IMPORTANT_SORT;
            }

            if (this.upgradePendingUpdate(section, pendingUpdate) != null) {
                section.prepareTrigger(isDirectTrigger);
            }
        }
    }

    public void scheduleRebuild(int x, int y, int z, boolean important) {
        RenderAsserts.validateCurrentThread();

        this.sectionCache.invalidate(x, y, z);

        RenderSection section = this.sectionByPosition.get(SectionPos.asLong(x, y, z));

        if (section != null && section.isBuilt()) {
            ChunkUpdateType pendingUpdate;

            if (allowImportantRebuilds() && (important || this.shouldPrioritizeTask(section, NEARBY_REBUILD_DISTANCE))) {
                pendingUpdate = ChunkUpdateType.IMPORTANT_REBUILD;
            } else {
                pendingUpdate = ChunkUpdateType.REBUILD;
            }

            this.upgradePendingUpdate(section, pendingUpdate);
        }
    }

    private boolean shouldPrioritizeTask(RenderSection section, float distance) {
        return this.cameraBlockPos != null && section.getSquaredDistance(this.cameraBlockPos) < distance;
    }

    private static boolean allowImportantRebuilds() {
        return !SodiumClientMod.options().performance.alwaysDeferChunkUpdates;
    }

    private float getEffectiveRenderDistance() {
        var alpha = RenderSystem.getShaderFog().alpha();
        var distance = RenderSystem.getShaderFog().end();

        var renderDistance = this.getRenderDistance();

        // The fog must be fully opaque in order to skip rendering of chunks behind it
        if (!Mth.equal(alpha, 1.0f)) {
            return renderDistance;
        }

        return Math.min(renderDistance, distance + 0.5f);
    }

    private float getRenderDistance() {
        return this.renderDistance * 16.0f;
    }

    private void connectNeighborNodes(RenderSection render) {
        for (int direction = 0; direction < GraphDirection.COUNT; direction++) {
            RenderSection adj = this.getRenderSection(render.getChunkX() + GraphDirection.x(direction),
                    render.getChunkY() + GraphDirection.y(direction),
                    render.getChunkZ() + GraphDirection.z(direction));

            if (adj != null) {
                adj.setAdjacentNode(GraphDirection.opposite(direction), render);
                render.setAdjacentNode(direction, adj);
            }
        }
    }

    private void disconnectNeighborNodes(RenderSection render) {
        for (int direction = 0; direction < GraphDirection.COUNT; direction++) {
            RenderSection adj = render.getAdjacent(direction);

            if (adj != null) {
                adj.setAdjacentNode(GraphDirection.opposite(direction), null);
                render.setAdjacentNode(direction, null);
            }
        }
    }

    private RenderSection getRenderSection(int x, int y, int z) {
        return this.sectionByPosition.get(SectionPos.asLong(x, y, z));
    }

    public Collection<String> getDebugStrings() {
        List<String> list = new ArrayList<>();

        int count = 0;

        long geometryDeviceUsed = 0;
        long geometryDeviceAllocated = 0;
        long indexDeviceUsed = 0;
        long indexDeviceAllocated = 0;

        for (var region : this.regions.getLoadedRegions()) {
            var resources = region.getResources();

            if (resources == null) {
                continue;
            }

            var geometryArena = resources.getGeometryArena();
            geometryDeviceUsed += geometryArena.getDeviceUsedMemory();
            geometryDeviceAllocated += geometryArena.getDeviceAllocatedMemory();

            var indexArena = resources.getIndexArena();
            indexDeviceUsed += indexArena.getDeviceUsedMemory();
            indexDeviceAllocated += indexArena.getDeviceAllocatedMemory();

            count++;
        }

        list.add(String.format("Pools: Geometry %d/%d MiB, Index %d/%d MiB (%d buffers)",
                MathUtil.toMib(geometryDeviceUsed), MathUtil.toMib(geometryDeviceAllocated),
                MathUtil.toMib(indexDeviceUsed), MathUtil.toMib(indexDeviceAllocated), count));
        list.add(String.format("Transfer Queue: %s", this.regions.getStagingBuffer().toString()));

        list.add(String.format("Chunk Builder: Schd=%02d | Busy=%02d (%04d%%) | Total=%02d",
                this.builder.getScheduledJobCount(), this.builder.getBusyThreadCount(), (int) (this.builder.getBusyFraction(this.lastFrameDuration) * 100), this.builder.getTotalThreadCount())
        );

        list.add(String.format("Tasks: N0=%03d | N1=%03d | Def=%03d, Recv=%03d",
                this.thisFrameBlockingTasks, this.nextFrameBlockingTasks, this.deferredTasks, this.buildResults.size())
        );

        this.sortTriggering.addDebugStrings(list);

        var taskSlots = new String[AsyncTaskType.VALUES.length];
        for (var task : this.pendingTasks) {
            var type = task.getTaskType();
            taskSlots[type.ordinal()] = type.abbreviation;
        }
        list.add("Tree Builds: " + Arrays
                .stream(taskSlots)
                .map(slot -> slot == null ? "_" : slot)
                .collect(Collectors.joining(" ")));

        return list;
    }

    public String getChunksDebugString() {
        // C: visible/total D: distance
        return String.format(
                "C: %d/%d (%s) D: %d",
                this.getVisibleChunkCount(),
                this.getTotalSections(),
                this.getCullTypeName(),
                this.renderDistance);
    }

    private String getCullTypeName() {
        CullType renderTreeCullType = null;
        for (var type : CullType.values()) {
            if (this.cullResults.get(type) == this.renderTree) {
                renderTreeCullType = type;
                break;
            }
        }
        var cullTypeName = "-";
        if (renderTreeCullType != null) {
            cullTypeName = renderTreeCullType.abbreviation;
        }
        return cullTypeName;
    }

    public @NotNull SortedRenderLists getRenderLists() {
        return this.renderLists;
    }

    public boolean isSectionBuilt(int x, int y, int z) {
        var section = this.getRenderSection(x, y, z);
        return section != null && section.isBuilt();
    }

    public void onChunkAdded(int x, int z) {
        for (int y = this.level.getMinSectionY(); y <= this.level.getMaxSectionY(); y++) {
            this.onSectionAdded(x, y, z);
        }
    }

    public void onChunkRemoved(int x, int z) {
        for (int y = this.level.getMinSectionY(); y <= this.level.getMaxSectionY(); y++) {
            this.onSectionRemoved(x, y, z);
        }
    }

    public Collection<RenderSection> getSectionsWithGlobalEntities() {
        return ReferenceSets.unmodifiable(this.sectionsWithGlobalEntities);
    }
}
