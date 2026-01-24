package net.caffeinemc.mods.sodium.instrumentation;

import net.caffeinemc.mods.sodium.client.render.SodiumWorldRenderer;
import net.caffeinemc.mods.sodium.client.render.chunk.RenderSectionManager;
import net.caffeinemc.mods.sodium.client.render.chunk.async.CullTask;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.CullType;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.OcclusionCuller;
import net.caffeinemc.mods.sodium.client.render.chunk.occlusion.RayOcclusionSectionTree;
import net.caffeinemc.mods.sodium.instrumentation.aspects.DirectMeasuredResult;
import net.caffeinemc.mods.sodium.instrumentation.aspects.MeasurementDurationResult;
import net.caffeinemc.mods.sodium.instrumentation.aspects.ValueListingResult;
import net.caffeinemc.mods.sodium.instrumentation.core.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import org.joml.Vector2f;
import org.joml.Vector3d;

import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

public class AsyncCullingMeasurement {
    public static final Context CONTEXT = new Context("async culling measurement");
    public static final MeasurementDurationResult MEASUREMENT_DURATION = new MeasurementDurationResult(
            CONTEXT,
            50_000_000
    );
    public static final ValueListingResult<Long> CULL_TIME = new ValueListingResult<>(
            CONTEXT,
            "mean cull time (ns)",
            CullTask.timings,
            10
    );
    public static final MeasuredAspect<Integer> RENDERED_SECTIONS = new DirectMeasuredResult<>(CONTEXT, "rendered sections",
            () -> SodiumWorldRenderer.instance().renderSectionManager.getVisibleChunkCount()
    );
    public static final ValueListingResult<Integer> TOTAL_SECTIONS = new ValueListingResult<>(CONTEXT, "total sections",
            RenderSectionManager.sectionCounts,
            0
    );

    public static final IteratingParameter<String> WORLD_SEED = new EnumerationParameter<>(
            CONTEXT,
            "world seed",
            // List.of("a", "b", "c")
            List.of(TestWorld.SEED)
    );

    // needs to be before camera rotation since we randomize rotation for warmup
//    public static final IteratingParameter<Vector3d> CAMERA_POSITION = new EnumerationParameter<>(
//            CONTEXT,
//            "camera position",
//            List.of(
//                    parsePosition("-1742.22 134.93 -3306.71")
//            )
//    ) {
//        @Override
//        public boolean isMeasurementAllowed(Vector3d value) {
//            return !Minecraft.getInstance().level.getBlockState(BlockPos.containing(value.x(), value.y(), value.z())).isSolidRender();
//        }
//    };

    public static final IteratingParameter<Integer> RENDER_DISTANCE = new EnumerationParameter<>(
            CONTEXT,
            "render distance",
            List.of(32, 20, 12)
//            List.of(32)
    );

    public record CameraState(double x, double y, double z, float yaw, float pitch) {
    }

    public static final IteratingParameter<CameraState> CAMERA_STATE = new EnumerationParameter<>(
            CONTEXT,
            "camera position",
            TestWorld.CAMERA_STATES.stream()
                    .map(
                            AsyncCullingMeasurement::parseCameraState
                    )
                    .flatMap(
                            state ->
                                    Stream.of(
                                            state,
                                            new CameraState(state.x, state.y, state.z, -state.yaw, state.pitch)
                                    )
                    )
                    .toList()
    ) {
        @Override
        public boolean isMeasurementAllowed(CameraState value) {
            return !Minecraft.getInstance().level.getBlockState(BlockPos.containing(Minecraft.getInstance().getCameraEntity().getEyePosition())).isSolidRender();
        }
    };

//    public static final IteratingParameter<Vector2f> CAMERA_ROTATION = new EnumerationParameter<>(
//            CONTEXT,
//            "camera rotation",
//            List.of(
//                    parseRotation("89.16 6.45"),
//                    parseRotation("87.05 47.08")
//            )
//    );

    private static final SubCombinationRandomizer RANDOMIZER1 = new SubCombinationRandomizer(CONTEXT, 5);

    public static final IteratingParameter<CullType> CULL_TYPE = new EnumerationParameter<>(
            CONTEXT,
            "cull type",
            // List.of(CullType.REGULAR, CullType.WIDE)
            List.of(CullType.REGULAR, CullType.FRUSTUM)
    );

    public enum RayCullMode {
        OFF,
        ON_SAFE,
        ON_AGGRESSIVE
    }

    public static final IteratingParameter<RayCullMode> RAY_CULL_MODE = new EnumerationParameter<>(
            CONTEXT,
            "ray cull mode",
            Arrays.asList(RayCullMode.values())
    );

    public static final IteratingParameter<Boolean> DIRECTIONAL_VIS_MODE = new EnumerationParameter<>(
            CONTEXT,
            "directional visibility",
            List.of(true, false)
    );

    public static final IteratingParameter<Boolean> ANGULAR_OCCLUSION_MODE = new EnumerationParameter<>(
            CONTEXT,
            "angular occlusion",
            List.of(true, false)
    );

    static {
        CULL_TYPE.setApplier(type -> RenderSectionManager.forceCullType = type);
        RAY_CULL_MODE.setApplier(mode -> RayOcclusionSectionTree.rayCullMode = mode);
        DIRECTIONAL_VIS_MODE.setApplier(enabled -> OcclusionCuller.directionalVis = enabled);
        ANGULAR_OCCLUSION_MODE.setApplier(enabled -> OcclusionCuller.angularOcclusion = enabled);
    }

    private static Vector3d parsePosition(String posStr) {
        String[] parts = posStr.split(" ");
        if (parts.length != 3) {
            throw new IllegalArgumentException("Invalid position string: " + posStr);
        }
        double x = Double.parseDouble(parts[0].trim());
        double y = Double.parseDouble(parts[1].trim());
        double z = Double.parseDouble(parts[2].trim());
        return new Vector3d(x, y, z);
    }

    private static Vector2f parseRotation(String rotStr) {
        String[] parts = rotStr.split(" ");
        if (parts.length != 2) {
            throw new IllegalArgumentException("Invalid rotation string: " + rotStr);
        }
        float yaw = Float.parseFloat(parts[0].trim());
        float pitch = Float.parseFloat(parts[1].trim());
        return new Vector2f(yaw, pitch);
    }

    private static CameraState parseCameraState(String stateStr) {
        String[] parts = stateStr.split(" ");
        if (parts.length != 5) {
            throw new IllegalArgumentException("Invalid camera state string: " + stateStr);
        }
        double x = Double.parseDouble(parts[0].trim());
        double y = Double.parseDouble(parts[1].trim());
        double z = Double.parseDouble(parts[2].trim());
        float yaw = Float.parseFloat(parts[3].trim());
        float pitch = Float.parseFloat(parts[4].trim());
        return new CameraState(x, y, z, yaw, pitch);
    }
}
